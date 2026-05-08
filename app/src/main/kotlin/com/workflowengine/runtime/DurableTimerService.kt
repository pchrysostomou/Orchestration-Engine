package com.workflowengine.runtime

import com.workflowengine.runtime.db.WorkflowTimerStore
import org.slf4j.LoggerFactory
import java.time.LocalDateTime

class DurableTimerService(private val timerStore: WorkflowTimerStore) {
    private val log = LoggerFactory.getLogger(DurableTimerService::class.java)

    suspend fun getOrCreate(runId: String, stepName: String, durationMs: Long): LocalDateTime {
        val existing = timerStore.getTimer(runId, stepName)
        if (existing != null) return existing.resumeAt
        val resumeAt = LocalDateTime.now().plusNanos(durationMs * 1_000_000L)
        timerStore.createTimer(runId, stepName, resumeAt)
        return resumeAt
    }

    suspend fun markFired(runId: String, stepName: String) =
        timerStore.markFired(runId, stepName)

    /** On startup: re-trigger any RUNNING workflows whose timers are overdue. */
    suspend fun recoverPendingTimers(executor: WorkflowExecutor, registry: WorkflowRegistry, stateStore: com.workflowengine.runtime.db.WorkflowStateStore) {
        val pending = timerStore.getPendingTimers()
            .filter { !it.resumeAt.isAfter(LocalDateTime.now()) }
        if (pending.isEmpty()) return
        log.info("Recovering {} overdue timer(s) from previous run", pending.size)
        pending.forEach { timer ->
            val run = stateStore.getRun(timer.runId) ?: return@forEach
            if (run.status != RunStatus.RUNNING) return@forEach
            if (!registry.contains(run.workflowId)) return@forEach
            log.info("Resuming run {} (timer '{}' has fired)", timer.runId, timer.stepName)
            executor.resume(registry.get(run.workflowId), run)
        }
    }
}
