package com.workflowengine.server.routes

import com.workflowengine.runtime.WorkflowRegistry
import com.workflowengine.runtime.WorkflowScheduler
import com.workflowengine.runtime.db.WorkflowStateStore
import com.workflowengine.server.dto.ErrorResponse
import com.workflowengine.server.dto.TriggerRequest
import com.workflowengine.server.dto.toDetailDto
import com.workflowengine.server.dto.toDto
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.json.JsonNull

fun Application.configureWorkflowRoutes(
    scheduler: WorkflowScheduler,
    stateStore: WorkflowStateStore,
    registry: WorkflowRegistry
) {
    routing {
        route("/api") {

            get("/workflows") {
                call.respond(registry.list().map { it.toDto() })
            }

            route("/workflows/{id}") {
                post("/trigger") {
                    val workflowId = call.parameters["id"]!!
                    if (!registry.contains(workflowId)) {
                        return@post call.respond(
                            HttpStatusCode.NotFound,
                            ErrorResponse("Workflow not found: $workflowId")
                        )
                    }
                    val request = runCatching { call.receive<TriggerRequest>() }
                        .getOrDefault(TriggerRequest())
                    val input = request.input.takeIf { it != JsonNull }
                    val run = scheduler.trigger(workflowId, input)
                    call.respond(HttpStatusCode.Accepted, run.toDto())
                }

                get("/runs") {
                    val workflowId = call.parameters["id"]!!
                    val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 0
                    val runs = stateStore.listRuns(workflowId, page)
                    call.respond(runs.map { it.toDto() })
                }
            }

            get("/runs") {
                val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 0
                call.respond(stateStore.listAllRuns(page).map { it.toDto() })
            }

            route("/runs/{runId}") {
                get {
                    val runId = call.parameters["runId"]!!
                    val run = stateStore.getRun(runId)
                        ?: return@get call.respond(
                            HttpStatusCode.NotFound,
                            ErrorResponse("Run not found: $runId")
                        )
                    val steps = stateStore.getStepsForRun(runId)
                    call.respond(run.toDetailDto(steps))
                }

                delete {
                    val runId = call.parameters["runId"]!!
                    scheduler.cancel(runId)
                    call.respond(HttpStatusCode.NoContent)
                }
            }
        }
    }
}
