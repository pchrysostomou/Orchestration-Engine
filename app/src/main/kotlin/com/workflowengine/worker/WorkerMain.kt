package com.workflowengine.worker

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.slf4j.LoggerFactory
import java.util.UUID

object WorkerMain {

    private val log = LoggerFactory.getLogger(WorkerMain::class.java)

    fun start() = runBlocking {
        val redisUrl  = System.getenv("REDIS_URL") ?: "redis://localhost:6379"
        val workerId  = "worker-${UUID.randomUUID().toString().take(8)}"
        val queue     = RedisTaskQueue(redisUrl)

        registerDemoHandlers()

        log.info("Worker {} started, listening on {}", workerId, RedisTaskQueue.STREAM_KEY)

        while (isActive) {
            val task = withContext(Dispatchers.IO) { queue.dequeue(workerId) }

            if (task == null) {
                delay(50)
                continue
            }

            log.info("Worker {} picked up step '{}' for run {}", workerId, task.stepName, task.runId)

            try {
                val input = Json.parseToJsonElement(task.input)
                val handler = HandlerRegistry.get(task.stepName)
                val result  = handler(input)
                queue.ack(task)
                log.info("Worker {} completed step '{}': {}", workerId, task.stepName, result)
            } catch (e: Exception) {
                log.error("Worker {} failed step '{}': {}", workerId, task.stepName, e.message)
                queue.nack(task, e.message ?: "Unknown error")
            }
        }

        queue.close()
        log.info("Worker {} stopped", workerId)
    }

    private fun registerDemoHandlers() {
        HandlerRegistry.register("validate-order") { input ->
            delay(200)
            JsonObject(mapOf("valid" to JsonPrimitive(true), "input" to input))
        }
        HandlerRegistry.register("charge-payment") { _ ->
            delay(300)
            JsonObject(mapOf("transactionId" to JsonPrimitive("txn-${UUID.randomUUID().toString().take(8)}")))
        }
        HandlerRegistry.register("send-to-warehouse") { _ ->
            delay(400)
            JsonObject(mapOf("dispatchId" to JsonPrimitive("disp-demo")))
        }
        HandlerRegistry.register("notify-customer") { _ ->
            delay(250)
            JsonObject(mapOf("sent" to JsonPrimitive(true)))
        }
        HandlerRegistry.register("ingest") { _ ->
            delay(150)
            JsonObject(mapOf("records" to JsonPrimitive(42)))
        }
        HandlerRegistry.register("transform-branch-a") { _ ->
            delay(300)
            JsonObject(mapOf("status" to JsonPrimitive("transformed")))
        }
        HandlerRegistry.register("validate-branch-a") { _ ->
            delay(200)
            JsonObject(mapOf("valid" to JsonPrimitive(true)))
        }
        HandlerRegistry.register("transform-branch-b") { _ ->
            delay(350)
            JsonObject(mapOf("status" to JsonPrimitive("transformed")))
        }
        HandlerRegistry.register("validate-branch-b") { _ ->
            delay(150)
            JsonObject(mapOf("valid" to JsonPrimitive(true)))
        }
        HandlerRegistry.register("merge-results") { _ ->
            delay(100)
            JsonObject(mapOf("merged" to JsonPrimitive(true)))
        }
    }
}
