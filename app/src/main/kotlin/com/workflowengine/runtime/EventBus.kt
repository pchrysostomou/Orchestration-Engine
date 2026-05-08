package com.workflowengine.runtime

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed class WorkflowEvent {
    abstract val runId: String

    @Serializable @SerialName("step_started")
    data class StepStarted(override val runId: String, val stepName: String) : WorkflowEvent()

    @Serializable @SerialName("step_completed")
    data class StepCompleted(override val runId: String, val stepName: String) : WorkflowEvent()

    @Serializable @SerialName("step_failed")
    data class StepFailed(override val runId: String, val stepName: String, val error: String) : WorkflowEvent()

    @Serializable @SerialName("run_started")
    data class RunStarted(override val runId: String, val workflowId: String) : WorkflowEvent()

    @Serializable @SerialName("run_completed")
    data class RunCompleted(override val runId: String) : WorkflowEvent()

    @Serializable @SerialName("run_failed")
    data class RunFailed(override val runId: String, val error: String) : WorkflowEvent()

    @Serializable @SerialName("run_cancelled")
    data class RunCancelled(override val runId: String) : WorkflowEvent()
}

class EventBus {
    private val _events = MutableSharedFlow<WorkflowEvent>(extraBufferCapacity = 256)
    val events: SharedFlow<WorkflowEvent> = _events.asSharedFlow()

    suspend fun emit(event: WorkflowEvent) = _events.emit(event)

    fun subscribe(runId: String): Flow<WorkflowEvent> = events.filter { it.runId == runId }
}
