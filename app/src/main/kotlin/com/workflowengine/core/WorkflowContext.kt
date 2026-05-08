package com.workflowengine.core

class WorkflowContext(
    val runId: String,
    val workflowId: String,
    @PublishedApi internal val store: MutableMap<String, Any?> = java.util.concurrent.ConcurrentHashMap()
) {
    inline fun <reified T> input(): T = store["__input"] as T
    inline fun <reified T> get(key: String = T::class.simpleName!!): T = store[key] as T
    fun set(key: String, value: Any?) { store[key] = value }
    fun complete(result: Any?) { store["__result"] = result }
}
