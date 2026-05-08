package com.workflowengine.runtime.db

import com.workflowengine.runtime.TimerStatus
import com.workflowengine.runtime.WorkflowTimer
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update
import java.time.LocalDateTime
import java.util.UUID

class WorkflowTimerStore {

    suspend fun createTimer(runId: String, stepName: String, resumeAt: LocalDateTime): WorkflowTimer =
        newSuspendedTransaction(Dispatchers.IO) {
            val id = UUID.randomUUID().toString()
            WorkflowTimers.insert {
                it[WorkflowTimers.id]       = id
                it[WorkflowTimers.runId]    = runId
                it[WorkflowTimers.stepName] = stepName
                it[WorkflowTimers.resumeAt] = resumeAt
                it[WorkflowTimers.status]   = TimerStatus.PENDING
            }
            WorkflowTimer(id, runId, stepName, resumeAt, TimerStatus.PENDING)
        }

    suspend fun getTimer(runId: String, stepName: String): WorkflowTimer? =
        newSuspendedTransaction(Dispatchers.IO) {
            WorkflowTimers.selectAll()
                .where { (WorkflowTimers.runId eq runId) and (WorkflowTimers.stepName eq stepName) }
                .firstOrNull()
                ?.let {
                    WorkflowTimer(
                        id       = it[WorkflowTimers.id],
                        runId    = it[WorkflowTimers.runId],
                        stepName = it[WorkflowTimers.stepName],
                        resumeAt = it[WorkflowTimers.resumeAt],
                        status   = it[WorkflowTimers.status]
                    )
                }
        }

    suspend fun markFired(runId: String, stepName: String) =
        newSuspendedTransaction(Dispatchers.IO) {
            WorkflowTimers.update({
                (WorkflowTimers.runId eq runId) and (WorkflowTimers.stepName eq stepName)
            }) { it[status] = TimerStatus.FIRED }
        }

    suspend fun getPendingTimers(): List<WorkflowTimer> =
        newSuspendedTransaction(Dispatchers.IO) {
            WorkflowTimers.selectAll()
                .where { WorkflowTimers.status eq TimerStatus.PENDING }
                .map {
                    WorkflowTimer(
                        id       = it[WorkflowTimers.id],
                        runId    = it[WorkflowTimers.runId],
                        stepName = it[WorkflowTimers.stepName],
                        resumeAt = it[WorkflowTimers.resumeAt],
                        status   = it[WorkflowTimers.status]
                    )
                }
        }
}
