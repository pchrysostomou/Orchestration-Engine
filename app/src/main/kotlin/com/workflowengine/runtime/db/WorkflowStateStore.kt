package com.workflowengine.runtime.db

import com.workflowengine.runtime.RunStatus
import com.workflowengine.runtime.StepRun
import com.workflowengine.runtime.StepStatus
import com.workflowengine.runtime.WorkflowRun
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update
import java.time.LocalDateTime
import java.util.UUID

class WorkflowStateStore {

    private suspend fun <T> db(block: () -> T): T =
        newSuspendedTransaction(Dispatchers.IO) { block() }

    suspend fun createRun(workflowId: String, input: String?): WorkflowRun {
        val now = LocalDateTime.now()
        val run = WorkflowRun(
            id         = UUID.randomUUID().toString(),
            workflowId = workflowId,
            status     = RunStatus.RUNNING,
            input      = input,
            createdAt  = now,
            updatedAt  = now
        )
        db {
            WorkflowRuns.insert {
                it[id]             = run.id
                it[WorkflowRuns.workflowId] = run.workflowId
                it[status]         = run.status
                it[WorkflowRuns.input]      = run.input
                it[createdAt]      = run.createdAt
                it[updatedAt]      = run.updatedAt
            }
        }
        return run
    }

    suspend fun markCompleted(runId: String) = db {
        WorkflowRuns.update({ WorkflowRuns.id eq runId }) {
            it[status]    = RunStatus.COMPLETED
            it[updatedAt] = LocalDateTime.now()
        }
    }

    suspend fun markFailed(runId: String, error: String?) = db {
        WorkflowRuns.update({ WorkflowRuns.id eq runId }) {
            it[status]             = RunStatus.FAILED
            it[WorkflowRuns.error] = error
            it[updatedAt]          = LocalDateTime.now()
        }
    }

    suspend fun markCancelled(runId: String) = db {
        WorkflowRuns.update({ WorkflowRuns.id eq runId }) {
            it[status]    = RunStatus.CANCELLED
            it[updatedAt] = LocalDateTime.now()
        }
    }

    suspend fun markStepRunning(runId: String, stepName: String) = db {
        val exists = StepRuns
            .selectAll().where { (StepRuns.runId eq runId) and (StepRuns.stepName eq stepName) }
            .count() > 0
        if (exists) {
            StepRuns.update({ (StepRuns.runId eq runId) and (StepRuns.stepName eq stepName) }) {
                it[status]    = StepStatus.RUNNING
                it[startedAt] = LocalDateTime.now()
            }
        } else {
            StepRuns.insert {
                it[id]              = UUID.randomUUID().toString()
                it[StepRuns.runId]  = runId
                it[StepRuns.stepName] = stepName
                it[status]          = StepStatus.RUNNING
                it[startedAt]       = LocalDateTime.now()
            }
        }
    }

    suspend fun markStepCompleted(runId: String, stepName: String, result: String?) = db {
        StepRuns.update({ (StepRuns.runId eq runId) and (StepRuns.stepName eq stepName) }) {
            it[status]          = StepStatus.COMPLETED
            it[StepRuns.result] = result
            it[endedAt]         = LocalDateTime.now()
        }
    }

    suspend fun markStepFailed(runId: String, stepName: String, error: String?) = db {
        StepRuns.update({ (StepRuns.runId eq runId) and (StepRuns.stepName eq stepName) }) {
            it[status]         = StepStatus.FAILED
            it[StepRuns.error] = error
            it[endedAt]        = LocalDateTime.now()
        }
    }

    suspend fun getRun(runId: String): WorkflowRun? = db {
        WorkflowRuns
            .selectAll().where { WorkflowRuns.id eq runId }
            .firstOrNull()
            ?.toWorkflowRun()
    }

    suspend fun getStepForRun(runId: String, stepName: String): StepRun? = db {
        StepRuns
            .selectAll().where { (StepRuns.runId eq runId) and (StepRuns.stepName eq stepName) }
            .firstOrNull()
            ?.toStepRun()
    }

    suspend fun getStepsForRun(runId: String): List<StepRun> = db {
        StepRuns
            .selectAll().where { StepRuns.runId eq runId }
            .orderBy(StepRuns.startedAt, SortOrder.ASC)
            .map { it.toStepRun() }
    }

    suspend fun listRuns(workflowId: String, page: Int = 0, pageSize: Int = 20): List<WorkflowRun> = db {
        WorkflowRuns
            .selectAll().where { WorkflowRuns.workflowId eq workflowId }
            .orderBy(WorkflowRuns.createdAt, SortOrder.DESC)
            .limit(pageSize, offset = (page * pageSize).toLong())
            .map { it.toWorkflowRun() }
    }

    suspend fun listAllRuns(page: Int = 0, pageSize: Int = 50): List<WorkflowRun> = db {
        WorkflowRuns
            .selectAll()
            .orderBy(WorkflowRuns.createdAt, SortOrder.DESC)
            .limit(pageSize, offset = (page * pageSize).toLong())
            .map { it.toWorkflowRun() }
    }

    private fun ResultRow.toWorkflowRun() = WorkflowRun(
        id         = this[WorkflowRuns.id],
        workflowId = this[WorkflowRuns.workflowId],
        status     = this[WorkflowRuns.status],
        input      = this[WorkflowRuns.input],
        error      = this[WorkflowRuns.error],
        createdAt  = this[WorkflowRuns.createdAt],
        updatedAt  = this[WorkflowRuns.updatedAt]
    )

    private fun ResultRow.toStepRun() = StepRun(
        id        = this[StepRuns.id],
        runId     = this[StepRuns.runId],
        stepName  = this[StepRuns.stepName],
        status    = this[StepRuns.status],
        result    = this[StepRuns.result],
        error     = this[StepRuns.error],
        startedAt = this[StepRuns.startedAt],
        endedAt   = this[StepRuns.endedAt]
    )
}
