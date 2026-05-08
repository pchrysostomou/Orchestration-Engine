package com.workflowengine.server

import com.workflowengine.core.FailureStrategy
import com.workflowengine.core.RetryPolicy
import com.workflowengine.core.workflow
import com.workflowengine.runtime.CronScheduler
import com.workflowengine.runtime.DurableTimerService
import com.workflowengine.runtime.EventBus
import com.workflowengine.runtime.MetricsRegistry
import com.workflowengine.runtime.WorkflowExecutor
import com.workflowengine.runtime.WorkflowRegistry
import com.workflowengine.runtime.WorkflowScheduler
import com.workflowengine.runtime.db.ApiKeyStore
import com.workflowengine.runtime.db.StepRuns
import com.workflowengine.runtime.db.WorkflowRuns
import com.workflowengine.runtime.db.WorkflowScheduleStore
import com.workflowengine.runtime.db.WorkflowSchedules
import com.workflowengine.runtime.db.WorkflowStateStore
import com.workflowengine.runtime.db.WorkflowTimerStore
import com.workflowengine.runtime.db.WorkflowTimers
import com.workflowengine.runtime.db.ApiKeys
import com.workflowengine.server.auth.configureAuth
import com.workflowengine.server.routes.configureAdminRoutes
import com.workflowengine.server.routes.configureWebSocketRoutes
import com.workflowengine.server.routes.configureWorkflowRoutes
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.http.content.staticResources
import io.ktor.server.metrics.micrometer.MicrometerMetrics
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import io.lettuce.core.RedisClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

private val log = LoggerFactory.getLogger("Application")

fun Application.module() {
    val stateStore    = WorkflowStateStore()
    val timerStore    = WorkflowTimerStore()
    val scheduleStore = WorkflowScheduleStore()
    val apiKeyStore   = ApiKeyStore()
    val eventBus      = EventBus()
    val wfRegistry    = WorkflowRegistry()

    val redisUrl    = System.getenv("REDIS_URL") ?: "redis://localhost:6380"
    val redisClient = RedisClient.create(redisUrl)

    val executor  = WorkflowExecutor(stateStore, eventBus, timerStore = timerStore)
    val scheduler = WorkflowScheduler(executor, wfRegistry)
    val cronSched = CronScheduler(executor, wfRegistry, scheduleStore)
    val timerSvc  = DurableTimerService(timerStore)

    configureDatabase()

    install(ContentNegotiation) {
        json(Json {
            prettyPrint = true
            isLenient   = true
            classDiscriminator = "type"
        })
    }

    install(WebSockets) {
        pingPeriod = 15.seconds.toJavaDuration()
        timeout    = 60.seconds.toJavaDuration()
    }

    install(MicrometerMetrics) {
        registry = MetricsRegistry.prometheus
    }

    configureAuth(apiKeyStore)

    // Auto-create a default API key if none exist
    runBlocking {
        if (apiKeyStore.count() == 0L) {
            val (raw, _) = apiKeyStore.create("default")
            log.warn("╔══════════════════════════════════════════════════╗")
            log.warn("║  Default API key created — save it, shown once!  ║")
            log.warn("║  Key: {}  ║", raw)
            log.warn("╚══════════════════════════════════════════════════╝")
        }
    }

    registerSampleWorkflows(wfRegistry)

    configureWorkflowRoutes(scheduler, stateStore, wfRegistry)
    configureAdminRoutes(apiKeyStore, scheduleStore, wfRegistry, redisClient)
    configureWebSocketRoutes(eventBus)

    routing {
        staticResources("/", "static")

        get("/metrics") {
            call.respondText(MetricsRegistry.prometheus.scrape())
        }
    }

    // Start background services
    runBlocking {
        timerSvc.recoverPendingTimers(executor, wfRegistry, stateStore)
    }
    cronSched.start()
}

private fun Application.configureDatabase() {
    val url      = System.getenv("DATABASE_URL")      ?: "jdbc:postgresql://localhost:5432/workflows"
    val user     = System.getenv("DATABASE_USER")     ?: "workflow_user"
    val password = System.getenv("DATABASE_PASSWORD") ?: "secret"

    val ds = HikariDataSource(HikariConfig().apply {
        jdbcUrl         = url
        username        = user
        this.password   = password
        maximumPoolSize = 10
        isAutoCommit    = false
        transactionIsolation = "TRANSACTION_REPEATABLE_READ"
        validate()
    })

    Database.connect(ds)

    transaction {
        SchemaUtils.createMissingTablesAndColumns(
            WorkflowRuns, StepRuns,
            WorkflowTimers, WorkflowSchedules, ApiKeys
        )
    }

    log.info("Database connected: {}", url)
}

private fun registerSampleWorkflows(wfRegistry: WorkflowRegistry) {
    wfRegistry.register(workflow("order-processing") {
        step("validate-order") { ctx ->
            delay(200)
            log.info("[{}] Order validated", ctx.runId)
            JsonObject(mapOf("valid" to JsonPrimitive(true)))
        }
        step("charge-payment", retry = RetryPolicy.exponential(3, 500.milliseconds)) { ctx ->
            delay(300)
            log.info("[{}] Payment charged", ctx.runId)
            JsonObject(mapOf("transactionId" to JsonPrimitive("txn-demo-123")))
        }
        parallel {
            step("send-to-warehouse") { ctx ->
                delay(400)
                log.info("[{}] Sent to warehouse", ctx.runId)
            }
            step("notify-customer") { ctx ->
                delay(250)
                log.info("[{}] Customer notified", ctx.runId)
            }
        }
        onFailure(FailureStrategy.COMPENSATE)
    })

    wfRegistry.register(workflow("data-pipeline") {
        step("ingest") { ctx ->
            delay(150)
            log.info("[{}] Data ingested", ctx.runId)
            JsonObject(mapOf("records" to JsonPrimitive(3)))
        }
        parallel {
            sequential {
                step("transform-branch-a") { ctx ->
                    delay(300)
                    log.info("[{}] Branch A transform done", ctx.runId)
                }
                step("validate-branch-a") { ctx ->
                    delay(200)
                    log.info("[{}] Branch A validated", ctx.runId)
                }
            }
            sequential {
                step("transform-branch-b") { ctx ->
                    delay(350)
                    log.info("[{}] Branch B transform done", ctx.runId)
                }
                step("validate-branch-b") { ctx ->
                    delay(150)
                    log.info("[{}] Branch B validated", ctx.runId)
                }
            }
        }
        step("merge-results") { ctx ->
            delay(100)
            log.info("[{}] Results merged", ctx.runId)
        }
    })

    wfRegistry.register(workflow("health-check") {
        step("ping-db") { ctx ->
            delay(50)
            log.info("[{}] DB ping OK", ctx.runId)
        }
        step("ping-cache") { ctx ->
            delay(30)
            log.info("[{}] Cache ping OK", ctx.runId)
        }
    })

    // Demo: conditional branching
    wfRegistry.register(workflow("conditional-demo") {
        step("check-stock") { ctx ->
            ctx.set("inStock", true)
            JsonObject(mapOf("inStock" to JsonPrimitive(true)))
        }
        branch({ ctx -> ctx.get<Boolean>("inStock") }) {
            onTrue {
                step("fulfill-order") { ctx ->
                    delay(200)
                    log.info("[{}] Order fulfilled", ctx.runId)
                }
            }
            onFalse {
                step("notify-out-of-stock") { ctx ->
                    delay(100)
                    log.info("[{}] Notified out of stock", ctx.runId)
                }
            }
        }
    })
}
