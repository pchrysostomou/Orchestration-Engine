package com.workflowengine.runtime

import java.time.LocalDateTime

data class WorkflowTimer(
    val id: String,
    val runId: String,
    val stepName: String,
    val resumeAt: LocalDateTime,
    val status: TimerStatus
)

enum class TimerStatus { PENDING, FIRED, CANCELLED }
