package com.workflowengine.runtime

import kotlinx.serialization.json.JsonElement

class WorkflowScheduler(
    private val executor: WorkflowExecutor,
    private val registry: WorkflowRegistry
) {
    suspend fun trigger(workflowId: String, input: JsonElement?): WorkflowRun {
        val definition = registry.get(workflowId)
        return executor.execute(definition, input)
    }

    fun cancel(runId: String) = executor.cancel(runId)
}
