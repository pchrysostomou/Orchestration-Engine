package com.workflowengine.runtime

import com.workflowengine.core.RetryPolicy
import com.workflowengine.core.workflow
import com.workflowengine.runtime.db.WorkflowStateStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.measureTime
import kotlin.time.Duration.Companion.milliseconds

class WorkflowExecutorTest {

    // ── Test helpers ──────────────────────────────────────────────────────────

    private fun makeStore(runId: String = UUID.randomUUID().toString(), wfId: String = "test"): WorkflowStateStore {
        // relaxed = true auto-stubs all suspend Unit functions so we only override what we need
        val store = mockk<WorkflowStateStore>(relaxed = true)
        coEvery { store.createRun(any(), any()) } returns WorkflowRun(
            id = runId, workflowId = wfId, status = RunStatus.RUNNING,
            input = null, createdAt = LocalDateTime.now(), updatedAt = LocalDateTime.now()
        )
        coEvery { store.getStepsForRun(any()) } returns emptyList()
        return store
    }

    private fun makeExecutor(store: WorkflowStateStore, eventBus: EventBus): WorkflowExecutor {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        return WorkflowExecutor(store, eventBus, scope)
    }

    private suspend fun awaitTerminal(runId: String, eventBus: EventBus): WorkflowEvent =
        withTimeout(5_000) {
            eventBus.events.first { event ->
                event.runId == runId && (
                    event is WorkflowEvent.RunCompleted ||
                    event is WorkflowEvent.RunFailed   ||
                    event is WorkflowEvent.RunCancelled
                )
            }
        }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    fun `sequential steps execute in order`() = runBlocking {
        val order = mutableListOf<String>()
        val eventBus = EventBus()
        val store    = makeStore()
        val executor = makeExecutor(store, eventBus)

        val def = workflow("order-test") {
            step("first")  { _ -> order += "first" }
            step("second") { _ -> order += "second" }
            step("third")  { _ -> order += "third" }
        }

        val run = executor.execute(def, null)
        awaitTerminal(run.id, eventBus)

        assertEquals(listOf("first", "second", "third"), order)
        coVerify(exactly = 1) { store.markCompleted(run.id) }
    }

    @Test
    fun `parallel steps run concurrently — faster than sequential`() = runBlocking {
        val eventBus = EventBus()
        val store    = makeStore()
        val executor = makeExecutor(store, eventBus)

        // 3 steps each sleeping 200ms — sequential would take 600ms, parallel ~200ms
        val def = workflow("parallel-timing") {
            parallel {
                step("a") { _ -> delay(200) }
                step("b") { _ -> delay(200) }
                step("c") { _ -> delay(200) }
            }
        }

        val elapsed = measureTime {
            val run = executor.execute(def, null)
            awaitTerminal(run.id, eventBus)
        }

        assertTrue(
            elapsed < 500.milliseconds,
            "Parallel steps should finish in ~200ms, but took ${elapsed.inWholeMilliseconds}ms"
        )
    }

    @Test
    fun `retry policy retries on transient failure`() = runBlocking {
        val attempts = AtomicInteger(0)
        val eventBus = EventBus()
        val store    = makeStore()
        val executor = makeExecutor(store, eventBus)

        val def = workflow("retry-test") {
            // maxAttempts = 3, backoff = 10ms so test stays fast
            step("flaky", retry = RetryPolicy.exponential(3, 10.milliseconds)) { _ ->
                if (attempts.incrementAndGet() < 3) throw RuntimeException("transient error")
            }
        }

        val run = executor.execute(def, null)
        val terminal = awaitTerminal(run.id, eventBus)

        assertEquals(3, attempts.get(), "Expected exactly 3 attempts")
        assertIs<WorkflowEvent.RunCompleted>(terminal)
        coVerify(exactly = 1) { store.markCompleted(run.id) }
    }

    @Test
    fun `step failure after all retries marks run as failed`() = runBlocking {
        val eventBus = EventBus()
        val store    = makeStore()
        val executor = makeExecutor(store, eventBus)

        val def = workflow("fail-test") {
            step("always-fails", retry = RetryPolicy.exponential(2, 10.milliseconds)) { _ ->
                throw RuntimeException("permanent failure")
            }
        }

        val run = executor.execute(def, null)
        val terminal = awaitTerminal(run.id, eventBus)

        assertIs<WorkflowEvent.RunFailed>(terminal)
        assertEquals("permanent failure", (terminal as WorkflowEvent.RunFailed).error)
        coVerify(exactly = 1) { store.markFailed(run.id, "permanent failure") }
    }

    @Test
    fun `context passes output from one step to the next`() = runBlocking {
        val eventBus = EventBus()
        val store    = makeStore()
        val executor = makeExecutor(store, eventBus)
        var seenValue: Any? = null

        val def = workflow("context-test") {
            step("produce") { ctx ->
                ctx.set("payload", "hello-from-step-1")
                "hello-from-step-1"
            }
            step("consume") { ctx ->
                seenValue = ctx.get<String>("payload")
            }
        }

        val run = executor.execute(def, null)
        awaitTerminal(run.id, eventBus)

        assertEquals("hello-from-step-1", seenValue)
    }

    @Test
    fun `cancelled run emits RunCancelled and stops execution`() = runBlocking {
        val eventBus = EventBus()
        val store    = makeStore()
        val executor = makeExecutor(store, eventBus)
        val secondStepStarted = AtomicInteger(0)

        val def = workflow("cancel-test") {
            step("long-step") { _ -> delay(2_000) }
            step("should-not-run") { _ -> secondStepStarted.incrementAndGet() }
        }

        val run = executor.execute(def, null)
        delay(50)              // give the first step time to start
        executor.cancel(run.id)

        val terminal = awaitTerminal(run.id, eventBus)

        assertIs<WorkflowEvent.RunCancelled>(terminal)
        assertEquals(0, secondStepStarted.get(), "Second step should not have run")
        coVerify(exactly = 1) { store.markCancelled(run.id) }
    }

    @Test
    fun `timed-out step marks run as failed`() = runBlocking {
        val eventBus = EventBus()
        val store    = makeStore()
        val executor = makeExecutor(store, eventBus)

        val def = workflow("timeout-test") {
            step("slow-step", timeout = 100.milliseconds) { _ -> delay(5_000) }
        }

        val run      = executor.execute(def, null)
        val terminal = awaitTerminal(run.id, eventBus)

        assertIs<WorkflowEvent.RunFailed>(terminal)
        assertTrue(
            (terminal as WorkflowEvent.RunFailed).error.contains("timed out"),
            "Expected timeout message, got: ${terminal.error}"
        )
        coVerify(exactly = 1) { store.markFailed(run.id, any()) }
    }

    @Test
    fun `step events are emitted in the correct sequence`() = runBlocking {
        val eventBus = EventBus()
        val store    = makeStore()
        val executor = makeExecutor(store, eventBus)

        val def = workflow("events-test") {
            step("alpha") { _ -> }
            step("beta")  { _ -> }
        }

        // 2 steps: RunStarted + 2×StepStarted + 2×StepCompleted + RunCompleted = 6 events
        // Subscribe before executing so we don't miss early events
        val eventsJob = async {
            withTimeout(5_000) { eventBus.events.take(6).toList() }
        }
        delay(20) // let the subscription activate before the executor fires

        val run     = executor.execute(def, null)
        val all     = eventsJob.await()
        val events  = all.filter { it.runId == run.id }
        val types   = events.map { it::class.simpleName }

        assertTrue(types.contains("StepStarted"),   "Expected StepStarted")
        assertTrue(types.contains("StepCompleted"), "Expected StepCompleted")
        assertTrue(
            types.indexOf("StepStarted") < types.indexOf("StepCompleted"),
            "StepStarted must come before StepCompleted"
        )
        assertEquals("RunCompleted", types.last(), "Last event must be RunCompleted")
    }
}
