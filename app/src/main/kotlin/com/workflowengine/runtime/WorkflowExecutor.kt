package com.workflowengine.runtime

import com.workflowengine.core.BackoffStrategy
import com.workflowengine.core.FailureStrategy
import com.workflowengine.core.StepDefinition
import com.workflowengine.core.WorkflowContext
import com.workflowengine.core.WorkflowDefinition
import com.workflowengine.runtime.db.WorkflowStateStore
import com.workflowengine.runtime.db.WorkflowTimerStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap

class WorkflowExecutor(
    private val stateStore: WorkflowStateStore,
    private val eventBus: EventBus,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
    private val timerStore: WorkflowTimerStore? = null
) {
    private val log = LoggerFactory.getLogger(WorkflowExecutor::class.java)
    private val activeJobs = ConcurrentHashMap<String, Job>()

    suspend fun execute(definition: WorkflowDefinition, input: Any?): WorkflowRun {
        val run = stateStore.createRun(definition.id, input?.toString())
        MetricsRegistry.runsStarted.increment()
        launchRun(definition, run, input)
        return run
    }

    /** Resume a previously-started run, skipping steps already marked COMPLETED in the DB. */
    suspend fun resume(definition: WorkflowDefinition, run: WorkflowRun) {
        if (activeJobs.containsKey(run.id)) return  // already running
        launchRun(definition, run, null, resuming = true)
    }

    private fun launchRun(
        definition: WorkflowDefinition,
        run: WorkflowRun,
        input: Any?,
        resuming: Boolean = false
    ) {
        val ctx = WorkflowContext(run.id, definition.id)
        if (input != null) ctx.set("__input", input)

        val job = scope.launch {
            eventBus.emit(WorkflowEvent.RunStarted(run.id, definition.id))
            try {
                executeStep(StepDefinition.Sequential(definition.steps), ctx, run, resuming)
                stateStore.markCompleted(run.id)
                MetricsRegistry.runsCompleted.increment()
                eventBus.emit(WorkflowEvent.RunCompleted(run.id))
                log.info("Run {} completed", run.id)
            } catch (e: CancellationException) {
                stateStore.markCancelled(run.id)
                MetricsRegistry.runsCancelled.increment()
                eventBus.emit(WorkflowEvent.RunCancelled(run.id))
                log.info("Run {} cancelled", run.id)
            } catch (e: Exception) {
                stateStore.markFailed(run.id, e.message)
                MetricsRegistry.runsFailed.increment()
                eventBus.emit(WorkflowEvent.RunFailed(run.id, e.message ?: "Unknown error"))
                log.error("Run {} failed: {}", run.id, e.message)
                if (definition.onFailure == FailureStrategy.COMPENSATE) {
                    runCompensation(definition, ctx, run)
                }
            } finally {
                activeJobs.remove(run.id)
            }
        }
        activeJobs[run.id] = job
    }

    fun cancel(runId: String) {
        activeJobs[runId]?.cancel(CancellationException("Cancelled by request"))
    }

    private suspend fun executeStep(
        step: StepDefinition,
        ctx: WorkflowContext,
        run: WorkflowRun,
        resuming: Boolean = false
    ) {
        when (step) {
            is StepDefinition.Sequential ->
                step.steps.forEach { executeStep(it, ctx, run, resuming) }

            is StepDefinition.Parallel -> coroutineScope {
                step.steps.map { child -> async { executeStep(child, ctx, run, resuming) } }.awaitAll()
            }

            is StepDefinition.Branch -> {
                val branch = if (step.predicate(ctx)) step.onTrue else step.onFalse
                if (branch.isNotEmpty()) executeStep(StepDefinition.Sequential(branch), ctx, run, resuming)
            }

            is StepDefinition.Timer -> executeTimer(step, ctx, run, resuming)

            is StepDefinition.Task  -> executeTask(step, ctx, run, resuming)
        }
    }

    private suspend fun executeTimer(
        step: StepDefinition.Timer,
        ctx: WorkflowContext,
        run: WorkflowRun,
        resuming: Boolean
    ) {
        if (resuming) {
            // Check if this timer already fired (DB has it as FIRED or resumeAt is in the past)
            val existing = timerStore?.getTimer(run.id, step.name)
            if (existing?.status == TimerStatus.FIRED) {
                log.debug("Timer '{}' already fired for run {}, skipping", step.name, run.id)
                return
            }
            if (existing != null && !existing.resumeAt.isAfter(LocalDateTime.now())) {
                timerStore?.markFired(run.id, step.name)
                log.debug("Timer '{}' overdue, resuming run {} immediately", step.name, run.id)
                return
            }
        }

        val durationMs = step.duration.inWholeMilliseconds
        val resumeAt = timerStore?.let {
            val existing = it.getTimer(run.id, step.name)
            existing?.resumeAt ?: run {
                it.createTimer(run.id, step.name, LocalDateTime.now().plusNanos(durationMs * 1_000_000L))
                LocalDateTime.now().plusNanos(durationMs * 1_000_000L)
            }
        } ?: LocalDateTime.now().plusNanos(durationMs * 1_000_000L)

        val remaining = Duration.between(LocalDateTime.now(), resumeAt).toMillis().coerceAtLeast(0)
        log.info("Run {} waiting {} ms for timer '{}'", run.id, remaining, step.name)
        if (remaining > 0) delay(remaining)

        timerStore?.markFired(run.id, step.name)
        log.info("Timer '{}' fired for run {}", step.name, run.id)
    }

    private suspend fun executeTask(
        task: StepDefinition.Task,
        ctx: WorkflowContext,
        run: WorkflowRun,
        resuming: Boolean
    ) {
        // On resume: skip steps that already completed
        if (resuming) {
            val existing = stateStore.getStepForRun(run.id, task.name)
            if (existing?.status == StepStatus.COMPLETED) {
                existing.result?.let { ctx.set(task.name, it) }
                log.debug("Step '{}' skipped (already completed)", task.name)
                return
            }
        }

        stateStore.markStepRunning(run.id, task.name)
        eventBus.emit(WorkflowEvent.StepStarted(run.id, task.name))
        log.debug("Step '{}' started (run {})", task.name, run.id)

        val stepTimeout = task.timeout
        var lastError: Exception? = null
        val startMs = System.currentTimeMillis()

        for (attempt in 0 until task.retry.maxAttempts) {
            try {
                val result = if (stepTimeout != null) {
                    withTimeout(stepTimeout) { task.handler(ctx) }
                } else {
                    task.handler(ctx)
                }
                if (result != null) ctx.set(task.name, result)
                val elapsed = System.currentTimeMillis() - startMs
                stateStore.markStepCompleted(run.id, task.name, result?.toString())
                MetricsRegistry.recordStep(run.workflowId, task.name, "success", elapsed)
                eventBus.emit(WorkflowEvent.StepCompleted(run.id, task.name))
                log.debug("Step '{}' completed (run {})", task.name, run.id)
                return
            } catch (e: TimeoutCancellationException) {
                lastError = RuntimeException("Step '${task.name}' timed out after $stepTimeout")
                log.warn("Step '{}' attempt {}/{} timed out", task.name, attempt + 1, task.retry.maxAttempts)
                if (attempt < task.retry.maxAttempts - 1) backoff(task.retry.backoff, attempt)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                log.warn("Step '{}' attempt {}/{} failed: {}", task.name, attempt + 1, task.retry.maxAttempts, e.message)
                if (attempt < task.retry.maxAttempts - 1) backoff(task.retry.backoff, attempt)
            }
        }

        val elapsed = System.currentTimeMillis() - startMs
        MetricsRegistry.recordStep(run.workflowId, task.name, "failure", elapsed)
        stateStore.markStepFailed(run.id, task.name, lastError!!.message)
        eventBus.emit(WorkflowEvent.StepFailed(run.id, task.name, lastError.message ?: "Unknown error"))
        throw lastError
    }

    private suspend fun backoff(strategy: BackoffStrategy, attempt: Int) {
        val ms = strategy.delayFor(attempt).inWholeMilliseconds
        if (ms > 0) delay(ms)
    }

    private suspend fun runCompensation(
        definition: WorkflowDefinition,
        ctx: WorkflowContext,
        run: WorkflowRun
    ) {
        val completed = stateStore.getStepsForRun(run.id)
            .filter { it.status == StepStatus.COMPLETED }
            .map { it.stepName }
            .toSet()
        log.info("Compensating {} completed steps for run {}: {}", completed.size, run.id, completed)
    }
}
