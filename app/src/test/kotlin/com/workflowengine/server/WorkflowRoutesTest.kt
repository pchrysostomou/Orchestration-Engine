package com.workflowengine.server

import com.workflowengine.core.workflow
import com.workflowengine.runtime.RunStatus
import com.workflowengine.runtime.WorkflowRegistry
import com.workflowengine.runtime.WorkflowRun
import com.workflowengine.runtime.WorkflowScheduler
import com.workflowengine.runtime.db.WorkflowStateStore
import com.workflowengine.server.routes.configureWorkflowRoutes
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.verify
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorkflowRoutesTest {

    private fun makeRun(id: String = "run-1", wfId: String = "test") = WorkflowRun(
        id = id, workflowId = wfId, status = RunStatus.RUNNING,
        input = null, createdAt = LocalDateTime.now(), updatedAt = LocalDateTime.now()
    )

    private fun makeRegistry(vararg ids: String): WorkflowRegistry {
        val registry = WorkflowRegistry()
        ids.forEach { id -> registry.register(workflow(id) { step("s1") { _ -> } }) }
        return registry
    }

    @Test
    fun `GET api workflows returns all registered workflows`() = testApplication {
        val registry  = makeRegistry("wf-alpha", "wf-beta")
        val scheduler = mockk<WorkflowScheduler>(relaxed = true)
        val store     = mockk<WorkflowStateStore>(relaxed = true)

        application {
            install(ContentNegotiation) { json() }
            configureWorkflowRoutes(scheduler, store, registry)
        }

        val response = client.get("/api/workflows")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("wf-alpha"), "Expected wf-alpha in response")
        assertTrue(body.contains("wf-beta"),  "Expected wf-beta in response")
    }

    @Test
    fun `POST trigger returns 202 and the new run id`() = testApplication {
        val registry  = makeRegistry("order-processing")
        val scheduler = mockk<WorkflowScheduler>()
        val store     = mockk<WorkflowStateStore>(relaxed = true)
        coEvery { scheduler.trigger("order-processing", any()) } returns
            makeRun(id = "run-abc", wfId = "order-processing")

        application {
            install(ContentNegotiation) { json() }
            configureWorkflowRoutes(scheduler, store, registry)
        }

        val response = client.post("/api/workflows/order-processing/trigger") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
        assertTrue(response.bodyAsText().contains("run-abc"), "Expected run id in response body")
    }

    @Test
    fun `POST trigger unknown workflow returns 404`() = testApplication {
        val registry  = WorkflowRegistry()
        val scheduler = mockk<WorkflowScheduler>(relaxed = true)
        val store     = mockk<WorkflowStateStore>(relaxed = true)

        application {
            install(ContentNegotiation) { json() }
            configureWorkflowRoutes(scheduler, store, registry)
        }

        val response = client.post("/api/workflows/ghost-wf/trigger") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.NotFound, response.status)
        assertTrue(response.bodyAsText().contains("ghost-wf"), "Expected workflow id in error body")
    }

    @Test
    fun `GET runs {runId} returns run detail with steps`() = testApplication {
        val registry  = WorkflowRegistry()
        val scheduler = mockk<WorkflowScheduler>(relaxed = true)
        val store     = mockk<WorkflowStateStore>(relaxed = true)
        coEvery { store.getRun("run-99") } returns makeRun(id = "run-99", wfId = "data-pipeline")
        coEvery { store.getStepsForRun("run-99") } returns emptyList()

        application {
            install(ContentNegotiation) { json() }
            configureWorkflowRoutes(scheduler, store, registry)
        }

        val response = client.get("/api/runs/run-99")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("run-99"),       "Expected run id in response")
        assertTrue(body.contains("data-pipeline"), "Expected workflow id in response")
    }

    @Test
    fun `GET runs {runId} returns 404 when run does not exist`() = testApplication {
        val registry  = WorkflowRegistry()
        val scheduler = mockk<WorkflowScheduler>(relaxed = true)
        val store     = mockk<WorkflowStateStore>(relaxed = true)
        coEvery { store.getRun("no-such-run") } returns null

        application {
            install(ContentNegotiation) { json() }
            configureWorkflowRoutes(scheduler, store, registry)
        }

        val response = client.get("/api/runs/no-such-run")
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `DELETE runs {runId} cancels the run and returns 204`() = testApplication {
        val registry  = WorkflowRegistry()
        val scheduler = mockk<WorkflowScheduler>(relaxed = true)
        val store     = mockk<WorkflowStateStore>(relaxed = true)

        application {
            install(ContentNegotiation) { json() }
            configureWorkflowRoutes(scheduler, store, registry)
        }

        val response = client.delete("/api/runs/run-77")
        assertEquals(HttpStatusCode.NoContent, response.status)
        verify(exactly = 1) { scheduler.cancel("run-77") }
    }
}
