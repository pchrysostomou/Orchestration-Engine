package com.workflowengine.runtime

import com.workflowengine.runtime.db.WorkflowScheduleStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

class CronScheduler(
    private val executor: WorkflowExecutor,
    private val registry: WorkflowRegistry,
    private val scheduleStore: WorkflowScheduleStore,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
) {
    private val log = LoggerFactory.getLogger(CronScheduler::class.java)

    fun start() {
        scope.launch {
            log.info("CronScheduler started")
            while (true) {
                checkDue()
                delay(30_000) // poll every 30 seconds
            }
        }
    }

    private suspend fun checkDue() {
        runCatching {
            scheduleStore.getDueSchedules().forEach { schedule ->
                if (!registry.contains(schedule.workflowId)) {
                    log.warn("Scheduled workflow '{}' not found in registry, skipping", schedule.workflowId)
                    return@forEach
                }
                log.info("Triggering scheduled run of '{}'", schedule.workflowId)
                executor.execute(registry.get(schedule.workflowId), null)
                val next = CronParser.nextFireTime(schedule.cronExpression)
                scheduleStore.markTriggered(schedule.id, next)
            }
        }.onFailure { log.error("CronScheduler error: {}", it.message, it) }
    }
}
