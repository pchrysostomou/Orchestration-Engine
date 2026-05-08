package com.workflowengine.runtime

import java.time.LocalDateTime

data class WorkflowRun(
    val id: String,
    val workflowId: String,
    val status: RunStatus,
    val input: String?,
    val error: String? = null,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime
)

data class StepRun(
    val id: String,
    val runId: String,
    val stepName: String,
    val status: StepStatus,
    val result: String? = null,
    val error: String? = null,
    val startedAt: LocalDateTime? = null,
    val endedAt: LocalDateTime? = null
)

enum class RunStatus  { RUNNING, COMPLETED, FAILED, CANCELLED }
enum class StepStatus { RUNNING, COMPLETED, FAILED }
