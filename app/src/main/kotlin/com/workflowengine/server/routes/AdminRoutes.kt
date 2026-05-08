package com.workflowengine.server.routes

import com.workflowengine.runtime.CronParser
import com.workflowengine.runtime.WorkflowRegistry
import com.workflowengine.runtime.db.ApiKeyStore
import com.workflowengine.runtime.db.WorkflowScheduleStore
import com.workflowengine.server.auth.AUTH_SCHEME
import com.workflowengine.server.dto.ApiKeyDto
import com.workflowengine.server.dto.CreateKeyRequest
import com.workflowengine.server.dto.CreateKeyResponse
import com.workflowengine.server.dto.CreateScheduleRequest
import com.workflowengine.server.dto.ErrorResponse
import com.workflowengine.server.dto.ScheduleDto
import com.workflowengine.server.dto.WorkerDto
import com.workflowengine.worker.WorkerHeartbeat
import io.lettuce.core.RedisClient
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing

fun Application.configureAdminRoutes(
    apiKeyStore: ApiKeyStore,
    scheduleStore: WorkflowScheduleStore,
    registry: WorkflowRegistry,
    redisClient: RedisClient? = null
) {
    routing {
        route("/api/admin") {

            // ── API key management ─────────────────────────────────────────────

            post("/keys") {
                val req = call.receive<CreateKeyRequest>()
                val (raw, record) = apiKeyStore.create(req.name)
                call.respond(
                    HttpStatusCode.Created,
                    CreateKeyResponse(record.id, record.name, raw, record.createdAt.toString())
                )
            }

            authenticate(AUTH_SCHEME) {

                get("/keys") {
                    val keys = apiKeyStore.list().map {
                        ApiKeyDto(it.id, it.name, it.createdAt.toString(), it.lastUsedAt?.toString())
                    }
                    call.respond(keys)
                }

                delete("/keys/{id}") {
                    apiKeyStore.delete(call.parameters["id"]!!)
                    call.respond(HttpStatusCode.NoContent)
                }

                // ── Schedules ──────────────────────────────────────────────────

                get("/schedules") {
                    call.respond(scheduleStore.list().map { it.toDto() })
                }

                post("/schedules") {
                    val req = call.receive<CreateScheduleRequest>()
                    if (!registry.contains(req.workflowId)) {
                        return@post call.respond(
                            HttpStatusCode.NotFound,
                            ErrorResponse("Workflow not found: ${req.workflowId}")
                        )
                    }
                    runCatching { CronParser.nextFireTime(req.cron) }.onFailure {
                        return@post call.respond(
                            HttpStatusCode.BadRequest,
                            ErrorResponse("Invalid cron expression: ${it.message}")
                        )
                    }
                    val nextRun = CronParser.nextFireTime(req.cron)
                    val schedule = scheduleStore.create(req.workflowId, req.cron, nextRun)
                    call.respond(HttpStatusCode.Created, schedule.toDto())
                }

                put("/schedules/{id}/enable") {
                    scheduleStore.setEnabled(call.parameters["id"]!!, true)
                    call.respond(HttpStatusCode.NoContent)
                }

                put("/schedules/{id}/disable") {
                    scheduleStore.setEnabled(call.parameters["id"]!!, false)
                    call.respond(HttpStatusCode.NoContent)
                }

                delete("/schedules/{id}") {
                    scheduleStore.delete(call.parameters["id"]!!)
                    call.respond(HttpStatusCode.NoContent)
                }

                // ── Workers ────────────────────────────────────────────────────

                get("/workers") {
                    if (redisClient == null) {
                        call.respond(emptyList<WorkerDto>())
                        return@get
                    }
                    val workers = WorkerHeartbeat.listActive(redisClient).map { w ->
                        WorkerDto(
                            id         = w["id"] ?: "unknown",
                            host       = w["host"] ?: "unknown",
                            startedAt  = w["startedAt"] ?: "",
                            activeJobs = 0
                        )
                    }
                    call.respond(workers)
                }
            }
        }
    }
}

private fun com.workflowengine.runtime.WorkflowSchedule.toDto() = ScheduleDto(
    id             = id,
    workflowId     = workflowId,
    cronExpression = cronExpression,
    enabled        = enabled,
    lastRunAt      = lastRunAt?.toString(),
    nextRunAt      = nextRunAt.toString()
)
