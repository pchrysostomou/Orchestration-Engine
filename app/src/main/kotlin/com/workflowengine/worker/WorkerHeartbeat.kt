package com.workflowengine.worker

import io.lettuce.core.RedisClient
import io.lettuce.core.api.async.RedisAsyncCommands
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.time.LocalDateTime
import java.util.UUID

class WorkerHeartbeat(
    private val redisClient: RedisClient,
    private val workerId: String = UUID.randomUUID().toString(),
    private val host: String = java.net.InetAddress.getLocalHost().hostName,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    private val log = LoggerFactory.getLogger(WorkerHeartbeat::class.java)
    private val conn by lazy { redisClient.connect() }
    private val cmds: RedisAsyncCommands<String, String> get() = conn.async()
    private val key = "workers:$workerId"
    val startedAt: String = LocalDateTime.now().toString()

    fun start() {
        scope.launch {
            log.info("Worker {} starting heartbeat", workerId)
            while (true) {
                runCatching { beat() }
                    .onFailure { log.warn("Heartbeat error: {}", it.message) }
                delay(15_000)
            }
        }
    }

    private fun beat() {
        val payload = buildJsonObject {
            put("id",        workerId)
            put("host",      host)
            put("startedAt", startedAt)
        }.toString()
        cmds.setex(key, 45, payload)  // TTL = 45s; heartbeat every 15s → 3 misses before expiry
    }

    fun stop() {
        runCatching { cmds.del(key) }
        conn.close()
    }

    companion object {
        fun listActive(redisClient: RedisClient): List<Map<String, String>> {
            val conn = redisClient.connect()
            return try {
                val keys = conn.sync().keys("workers:*")
                keys.mapNotNull { key ->
                    conn.sync().get(key)?.let { json ->
                        runCatching {
                            val obj = Json.parseToJsonElement(json)
                                .let { it as? kotlinx.serialization.json.JsonObject } ?: return@mapNotNull null
                            obj.entries.associate { (k, v) -> k to v.toString().trim('"') }
                        }.getOrNull()
                    }
                }
            } finally {
                conn.close()
            }
        }
    }
}
