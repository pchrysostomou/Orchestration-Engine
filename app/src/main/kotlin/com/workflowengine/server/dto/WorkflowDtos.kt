package com.workflowengine.server.dto

import com.workflowengine.core.WorkflowDefinition
import com.workflowengine.runtime.StepRun
import com.workflowengine.runtime.WorkflowRun
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

@Serializable
data class WorkflowDto(
    val id: String,
    val stepCount: Int,
    val onFailure: String
)

@Serializable
data class WorkflowRunDto(
    val id: String,
    val workflowId: String,
    val status: String,
    val createdAt: String,
    val updatedAt: String
)

@Serializable
data class WorkflowRunDetailDto(
    val id: String,
    val workflowId: String,
    val status: String,
    val input: String?,
    val error: String?,
    val steps: List<StepRunDto>,
    val createdAt: String,
    val updatedAt: String
)

@Serializable
data class StepRunDto(
    val stepName: String,
    val status: String,
    val result: String?,
    val error: String?,
    val startedAt: String?,
    val endedAt: String?
)

@Serializable
data class TriggerRequest(val input: JsonElement = JsonNull)

@Serializable
data class ErrorResponse(val error: String)

fun WorkflowDefinition.toDto() = WorkflowDto(
    id        = id,
    stepCount = steps.size,
    onFailure = onFailure.name
)

fun WorkflowRun.toDto() = WorkflowRunDto(
    id         = id,
    workflowId = workflowId,
    status     = status.name,
    createdAt  = createdAt.toString(),
    updatedAt  = updatedAt.toString()
)

fun WorkflowRun.toDetailDto(steps: List<StepRun>) = WorkflowRunDetailDto(
    id         = id,
    workflowId = workflowId,
    status     = status.name,
    input      = input,
    error      = error,
    steps      = steps.map { it.toDto() },
    createdAt  = createdAt.toString(),
    updatedAt  = updatedAt.toString()
)

fun StepRun.toDto() = StepRunDto(
    stepName  = stepName,
    status    = status.name,
    result    = result,
    error     = error,
    startedAt = startedAt?.toString(),
    endedAt   = endedAt?.toString()
)
