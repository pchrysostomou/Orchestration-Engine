package com.workflowengine.server.routes

import com.workflowengine.runtime.EventBus
import com.workflowengine.runtime.WorkflowEvent
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.routing.routing
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.Json

private val json = Json { classDiscriminator = "type" }

fun Application.configureWebSocketRoutes(eventBus: EventBus) {
    routing {
        webSocket("/ws/runs/{runId}") {
            val runId = call.parameters["runId"]!!

            eventBus.subscribe(runId).collect { event ->
                send(Frame.Text(json.encodeToString(WorkflowEvent.serializer(), event)))

                // Close the connection once the run reaches a terminal state
                if (event is WorkflowEvent.RunCompleted ||
                    event is WorkflowEvent.RunFailed ||
                    event is WorkflowEvent.RunCancelled
                ) {
                    close()
                    return@collect
                }
            }
        }
    }
}
