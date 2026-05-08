package com.workflowengine.runtime

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class EventBusTest {

    @Test
    fun `emitted event is received by subscriber`() = runBlocking {
        val bus = EventBus()
        val received = async {
            withTimeout(1_000) { bus.events.take(1).toList() }
        }

        delay(10)
        bus.emit(WorkflowEvent.RunCompleted("run-1"))

        val events = received.await()
        assertEquals(1, events.size)
        assertIs<WorkflowEvent.RunCompleted>(events[0])
        assertEquals("run-1", events[0].runId)
    }

    @Test
    fun `subscribe filters by runId`() = runBlocking {
        val bus = EventBus()
        val forRun1 = mutableListOf<WorkflowEvent>()

        val collector = launch {
            withTimeout(1_000) {
                bus.subscribe("run-1").take(2).toList(forRun1)
            }
        }

        delay(10)
        bus.emit(WorkflowEvent.StepStarted("run-2", "step-a"))  // different runId — should be filtered
        bus.emit(WorkflowEvent.StepStarted("run-1", "step-a"))  // matches
        bus.emit(WorkflowEvent.StepCompleted("run-1", "step-a")) // matches

        collector.join()

        assertEquals(2, forRun1.size)
        assertTrue(forRun1.all { it.runId == "run-1" })
    }

    @Test
    fun `multiple subscribers all receive the same events`() = runBlocking {
        val bus = EventBus()
        val received1 = mutableListOf<WorkflowEvent>()
        val received2 = mutableListOf<WorkflowEvent>()

        val job1 = launch {
            withTimeout(1_000) { bus.events.take(3).toList(received1) }
        }
        val job2 = launch {
            withTimeout(1_000) { bus.events.take(3).toList(received2) }
        }

        delay(20)
        bus.emit(WorkflowEvent.RunStarted("run-1", "my-workflow"))
        bus.emit(WorkflowEvent.StepStarted("run-1", "step-a"))
        bus.emit(WorkflowEvent.StepCompleted("run-1", "step-a"))

        job1.join()
        job2.join()

        assertEquals(3, received1.size)
        assertEquals(3, received2.size)
        assertEquals(received1.map { it::class }, received2.map { it::class })
    }

    @Test
    fun `step lifecycle events have correct fields`() = runBlocking {
        val bus = EventBus()

        val events = async {
            withTimeout(1_000) { bus.events.take(3).toList() }
        }

        delay(10)
        bus.emit(WorkflowEvent.StepStarted("run-42", "validate"))
        bus.emit(WorkflowEvent.StepCompleted("run-42", "validate"))
        bus.emit(WorkflowEvent.StepFailed("run-42", "charge", "timeout"))

        val list = events.await()

        assertIs<WorkflowEvent.StepStarted>(list[0])
        assertEquals("validate", (list[0] as WorkflowEvent.StepStarted).stepName)

        assertIs<WorkflowEvent.StepCompleted>(list[1])

        assertIs<WorkflowEvent.StepFailed>(list[2])
        assertEquals("timeout", (list[2] as WorkflowEvent.StepFailed).error)
    }
}
