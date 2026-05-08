package com.workflowengine.worker

import kotlinx.serialization.json.JsonElement
import java.util.concurrent.ConcurrentHashMap

typealias StepHandler = suspend (input: JsonElement) -> JsonElement

object HandlerRegistry {
    private val handlers = ConcurrentHashMap<String, StepHandler>()

    fun register(stepName: String, handler: StepHandler) {
        handlers[stepName] = handler
    }

    fun get(stepName: String): StepHandler =
        handlers[stepName]
            ?: throw IllegalArgumentException("No handler registered for step: '$stepName'")

    fun has(stepName: String): Boolean = handlers.containsKey(stepName)
}
