package com.workflowengine.runtime.db

import com.workflowengine.runtime.WorkflowSchedule
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update
import java.time.LocalDateTime
import java.util.UUID

class WorkflowScheduleStore {

    suspend fun create(workflowId: String, cron: String, nextRunAt: LocalDateTime): WorkflowSchedule =
        newSuspendedTransaction(Dispatchers.IO) {
            val id = UUID.randomUUID().toString()
            WorkflowSchedules.insert {
                it[WorkflowSchedules.id]             = id
                it[WorkflowSchedules.workflowId]     = workflowId
                it[WorkflowSchedules.cronExpression] = cron
                it[WorkflowSchedules.enabled]        = true
                it[WorkflowSchedules.lastRunAt]      = null
                it[WorkflowSchedules.nextRunAt]      = nextRunAt
                it[WorkflowSchedules.createdAt]      = LocalDateTime.now()
            }
            WorkflowSchedule(id, workflowId, cron, true, null, nextRunAt, LocalDateTime.now())
        }

    suspend fun list(): List<WorkflowSchedule> =
        newSuspendedTransaction(Dispatchers.IO) {
            WorkflowSchedules.selectAll().map { it.toSchedule() }
        }

    suspend fun getDueSchedules(): List<WorkflowSchedule> =
        newSuspendedTransaction(Dispatchers.IO) {
            WorkflowSchedules.selectAll()
                .where { WorkflowSchedules.enabled eq true }
                .map { it.toSchedule() }
                .filter { !it.nextRunAt.isAfter(LocalDateTime.now()) }
        }

    suspend fun markTriggered(id: String, nextRunAt: LocalDateTime) =
        newSuspendedTransaction(Dispatchers.IO) {
            WorkflowSchedules.update({ WorkflowSchedules.id eq id }) {
                it[WorkflowSchedules.lastRunAt]  = LocalDateTime.now()
                it[WorkflowSchedules.nextRunAt]  = nextRunAt
            }
        }

    suspend fun setEnabled(id: String, enabled: Boolean) =
        newSuspendedTransaction(Dispatchers.IO) {
            WorkflowSchedules.update({ WorkflowSchedules.id eq id }) {
                it[WorkflowSchedules.enabled] = enabled
            }
        }

    suspend fun delete(id: String) =
        newSuspendedTransaction(Dispatchers.IO) {
            WorkflowSchedules.deleteWhere { WorkflowSchedules.id eq id }
        }

    private fun org.jetbrains.exposed.sql.ResultRow.toSchedule() = WorkflowSchedule(
        id             = this[WorkflowSchedules.id],
        workflowId     = this[WorkflowSchedules.workflowId],
        cronExpression = this[WorkflowSchedules.cronExpression],
        enabled        = this[WorkflowSchedules.enabled],
        lastRunAt      = this[WorkflowSchedules.lastRunAt],
        nextRunAt      = this[WorkflowSchedules.nextRunAt],
        createdAt      = this[WorkflowSchedules.createdAt]
    )
}
