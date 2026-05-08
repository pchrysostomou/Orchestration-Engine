package com.workflowengine.runtime

import com.workflowengine.core.WorkflowDefinition
import java.util.concurrent.ConcurrentHashMap

class WorkflowRegistry {
    private val definitions = ConcurrentHashMap<String, WorkflowDefinition>()

    fun register(definition: WorkflowDefinition) {
        definitions[definition.id] = definition
    }

    fun get(id: String): WorkflowDefinition =
        definitions[id] ?: throw NoSuchElementException("Unknown workflow: $id")

    fun contains(id: String): Boolean = definitions.containsKey(id)

    fun list(): List<WorkflowDefinition> = definitions.values.toList()
}
