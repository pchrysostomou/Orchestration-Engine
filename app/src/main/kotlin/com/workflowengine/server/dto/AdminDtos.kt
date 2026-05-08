package com.workflowengine.server.dto

import kotlinx.serialization.Serializable

@Serializable data class CreateKeyRequest(val name: String)
@Serializable data class CreateKeyResponse(val id: String, val name: String, val key: String, val createdAt: String)
@Serializable data class ApiKeyDto(val id: String, val name: String, val createdAt: String, val lastUsedAt: String?)
@Serializable data class WorkerDto(val id: String, val host: String, val startedAt: String, val activeJobs: Int)

@Serializable data class CreateScheduleRequest(val workflowId: String, val cron: String)
@Serializable data class ScheduleDto(
    val id: String,
    val workflowId: String,
    val cronExpression: String,
    val enabled: Boolean,
    val lastRunAt: String?,
    val nextRunAt: String
)
