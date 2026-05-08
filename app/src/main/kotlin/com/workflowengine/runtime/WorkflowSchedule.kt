package com.workflowengine.runtime

import java.time.LocalDateTime

data class WorkflowSchedule(
    val id: String,
    val workflowId: String,
    val cronExpression: String,
    val enabled: Boolean,
    val lastRunAt: LocalDateTime?,
    val nextRunAt: LocalDateTime,
    val createdAt: LocalDateTime
)
