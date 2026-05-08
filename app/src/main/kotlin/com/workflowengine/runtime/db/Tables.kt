package com.workflowengine.runtime.db

import com.workflowengine.runtime.RunStatus
import com.workflowengine.runtime.StepStatus
import com.workflowengine.runtime.TimerStatus
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.datetime

object WorkflowRuns : Table("workflow_runs") {
    val id         = varchar("id", 36)
    val workflowId = varchar("workflow_id", 100)
    val status     = enumerationByName("status", 20, RunStatus::class)
    val input      = text("input").nullable()
    val error      = text("error").nullable()
    val createdAt  = datetime("created_at")
    val updatedAt  = datetime("updated_at")
    override val primaryKey = PrimaryKey(id)
}

object StepRuns : Table("step_runs") {
    val id        = varchar("id", 36)
    val runId     = varchar("run_id", 36).references(WorkflowRuns.id)
    val stepName  = varchar("step_name", 200)
    val status    = enumerationByName("status", 20, StepStatus::class)
    val result    = text("result").nullable()
    val error     = text("error").nullable()
    val startedAt = datetime("started_at").nullable()
    val endedAt   = datetime("ended_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

object WorkflowTimers : Table("workflow_timers") {
    val id       = varchar("id", 36)
    val runId    = varchar("run_id", 36).references(WorkflowRuns.id)
    val stepName = varchar("step_name", 200)
    val resumeAt = datetime("resume_at")
    val status   = enumerationByName("status", 20, TimerStatus::class)
    override val primaryKey = PrimaryKey(id)
}

object WorkflowSchedules : Table("workflow_schedules") {
    val id             = varchar("id", 36)
    val workflowId     = varchar("workflow_id", 100)
    val cronExpression = varchar("cron_expression", 100)
    val enabled        = bool("enabled").default(true)
    val lastRunAt      = datetime("last_run_at").nullable()
    val nextRunAt      = datetime("next_run_at")
    val createdAt      = datetime("created_at")
    override val primaryKey = PrimaryKey(id)
}

object ApiKeys : Table("api_keys") {
    val id          = varchar("id", 36)
    val name        = varchar("name", 100)
    val keyHash     = varchar("key_hash", 64)
    val createdAt   = datetime("created_at")
    val lastUsedAt  = datetime("last_used_at").nullable()
    override val primaryKey = PrimaryKey(id)
}
