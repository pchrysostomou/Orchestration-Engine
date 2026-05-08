package com.workflowengine.worker

import io.lettuce.core.Consumer
import io.lettuce.core.RedisClient
import io.lettuce.core.XGroupCreateArgs
import io.lettuce.core.XReadArgs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

data class WorkerTask(
    val messageId: String,
    val runId: String,
    val stepName: String,
    val workflowId: String,
    val input: String
)

class RedisTaskQueue(redisUrl: String) : Closeable {

    private val client     = RedisClient.create(redisUrl)
    private val connection = client.connect()
    private val sync       = connection.sync()
    private val initialized = AtomicBoolean(false)

    private fun ensureGroup() {
        if (!initialized.compareAndSet(false, true)) return
        try {
            sync.xgroupCreate(
                XReadArgs.StreamOffset.from(STREAM_KEY, "0-0"),
                GROUP_NAME,
                XGroupCreateArgs.Builder.mkstream()
            )
        } catch (e: Exception) {
            if (e.message?.contains("BUSYGROUP") != true) throw e
        }
    }

    suspend fun enqueue(task: WorkerTask) = withContext(Dispatchers.IO) {
        ensureGroup()
        sync.xadd(
            STREAM_KEY,
            mapOf(
                "runId"      to task.runId,
                "stepName"   to task.stepName,
                "workflowId" to task.workflowId,
                "input"      to task.input
            )
        )
    }

    suspend fun dequeue(consumerId: String): WorkerTask? = withContext(Dispatchers.IO) {
        ensureGroup()
        val messages = sync.xreadgroup(
            Consumer.from(GROUP_NAME, consumerId),
            XReadArgs.Builder.count(1).block(Duration.ofMillis(500)),
            XReadArgs.StreamOffset.lastConsumed(STREAM_KEY)
        )
        messages.firstOrNull()?.let { msg ->
            WorkerTask(
                messageId  = msg.id.toString(),
                runId      = msg.body["runId"]      ?: "",
                stepName   = msg.body["stepName"]   ?: "",
                workflowId = msg.body["workflowId"] ?: "",
                input      = msg.body["input"]      ?: "{}"
            )
        }
    }

    suspend fun ack(task: WorkerTask) = withContext(Dispatchers.IO) {
        sync.xack(STREAM_KEY, GROUP_NAME, task.messageId)
    }

    suspend fun nack(task: WorkerTask, error: String) = withContext(Dispatchers.IO) {
        // Move to dead-letter stream and acknowledge from main stream
        sync.xadd(
            "$STREAM_KEY:dead",
            mapOf(
                "originalId" to task.messageId,
                "runId"      to task.runId,
                "stepName"   to task.stepName,
                "error"      to error
            )
        )
        sync.xack(STREAM_KEY, GROUP_NAME, task.messageId)
    }

    override fun close() {
        connection.close()
        client.shutdown()
    }

    companion object {
        const val STREAM_KEY = "workflow:tasks"
        const val GROUP_NAME = "workers"
    }
}
