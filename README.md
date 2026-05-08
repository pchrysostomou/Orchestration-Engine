# Workflow Orchestration Engine

A production-ready workflow orchestration engine built with **Kotlin**, **Ktor**, and **Coroutines** — a self-contained mini-Temporal/Airflow you can run with a single command.

## Features

| Feature | Description |
|---------|-------------|
| **DSL** | Type-safe Kotlin DSL to define workflows as code |
| **Parallel execution** | Steps run concurrently via Kotlin Coroutines |
| **Conditional branching** | `branch { onTrue {} onFalse {} }` based on runtime context |
| **Retry + backoff** | Exponential backoff per step |
| **Step timeouts** | Cancel a step if it exceeds a duration |
| **Durable timers** | `waitFor(24.hours)` — persisted to DB, survives restarts |
| **Scheduled workflows** | Cron-style schedules (`* * * * *`) backed by PostgreSQL |
| **Cancellation** | Cancel any running workflow mid-flight |
| **Compensation** | `onFailure(COMPENSATE)` for rollback logic |
| **API key auth** | Bearer token authentication, SHA-256 hashed keys |
| **Metrics** | Prometheus endpoint at `/metrics` via Micrometer |
| **WebSockets** | Live step-by-step progress at `ws://localhost:8080/ws/runs/{id}` |
| **Worker queue** | Redis Streams task queue with consumer groups |
| **Multi-node workers** | Redis heartbeat + worker discovery API |
| **Dashboard** | Dark-theme web UI with real-time run tracking |

## Tech Stack

- **Runtime**: Kotlin 2.1 + Coroutines 1.8
- **HTTP server**: Ktor 2.3 (Netty)
- **Database**: PostgreSQL 16 + Exposed ORM + HikariCP
- **Queue**: Redis 7 Streams (Lettuce client)
- **Metrics**: Micrometer + Prometheus
- **Build**: Gradle 8 with version catalog
- **Tests**: JUnit 5 + MockK — 37 tests

---

## Quick Start

### Prerequisites

- **Docker Desktop** (for PostgreSQL + Redis)
- **JDK 21** ([download](https://adoptium.net/))

### 1. Clone the repo

```bash
git clone https://github.com/<your-username>/orchestration-engine.git
cd orchestration-engine
```

### 2. Start infrastructure

```bash
docker compose up db redis -d
```

This starts:
- PostgreSQL on port **5433**
- Redis on port **6380**

> Tables are created automatically on first run.

### 3. Run the server

```bash
./run-local.sh
```

Server starts at **http://localhost:8080**

On first start, an API key is printed to the console:
```
╔══════════════════════════════════════════════════╗
║  Default API key created — save it, shown once!  ║
║  Key: wfe_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx        ║
╚══════════════════════════════════════════════════╝
```

**Save this key** — you need it for all API calls.

---

## Alternative: Run everything with Docker

```bash
docker compose up --build
```

This starts PostgreSQL + Redis + Server + Worker + Adminer (DB UI at http://localhost:8081).

---

## API Reference

All `/api/*` endpoints (except `POST /api/admin/keys`) require:
```
Authorization: Bearer wfe_your_key_here
```

### Workflows

```bash
# List registered workflows
GET /api/workflows

# Trigger a workflow run
POST /api/workflows/{id}/trigger
Content-Type: application/json
{}

# List runs for a workflow
GET /api/workflows/{id}/runs

# All runs (paginated)
GET /api/runs?page=0

# Run detail with step breakdown
GET /api/runs/{runId}

# Cancel a running workflow
DELETE /api/runs/{runId}
```

### Admin — API Keys

```bash
# Create a key (no auth required)
POST /api/admin/keys
{"name": "my-key"}

# List all keys
GET /api/admin/keys

# Revoke a key
DELETE /api/admin/keys/{id}
```

### Admin — Schedules

```bash
# Create a cron schedule
POST /api/admin/schedules
{"workflowId": "health-check", "cron": "*/5 * * * *"}

# List schedules
GET /api/admin/schedules

# Enable / disable
PUT /api/admin/schedules/{id}/enable
PUT /api/admin/schedules/{id}/disable

# Delete
DELETE /api/admin/schedules/{id}
```

### Admin — Workers

```bash
GET /api/admin/workers
```

### Metrics

```bash
GET /metrics   # Prometheus text format
```

### WebSocket — Live run events

```
ws://localhost:8080/ws/runs/{runId}
```

Emits JSON events: `RunStarted`, `StepStarted`, `StepCompleted`, `StepFailed`, `RunCompleted`, `RunFailed`, `RunCancelled`.

---

## Workflow DSL

```kotlin
workflow("order-processing") {

    // Simple step
    step("validate-order") { ctx ->
        ctx.set("orderId", "ORD-123")
    }

    // Step with retry and timeout
    step(
        name    = "charge-payment",
        retry   = RetryPolicy.exponential(maxAttempts = 3, base = 500.milliseconds),
        timeout = 10.seconds
    ) { ctx ->
        paymentService.charge(ctx.get("orderId"))
    }

    // Parallel steps
    parallel {
        step("send-to-warehouse") { ctx -> warehouse.notify() }
        step("notify-customer")   { ctx -> email.send() }
    }

    // Conditional branching
    branch({ ctx -> ctx.get<Boolean>("inStock") }) {
        onTrue  { step("fulfill")      { ctx -> fulfill() } }
        onFalse { step("notify-oos")   { ctx -> notifyOOS() } }
    }

    // Durable timer (survives server restarts)
    waitFor(24.hours, name = "wait-for-payment")

    // Nested sequential inside parallel
    parallel {
        sequential {
            step("transform-a") { ctx -> }
            step("validate-a")  { ctx -> }
        }
        sequential {
            step("transform-b") { ctx -> }
            step("validate-b")  { ctx -> }
        }
    }

    // Compensation on failure
    onFailure(FailureStrategy.COMPENSATE)
}
```

---

## Cron Expression Format

5-field Unix cron: `minute hour day month weekday`

| Expression | Meaning |
|------------|---------|
| `* * * * *` | Every minute |
| `*/5 * * * *` | Every 5 minutes |
| `0 9 * * *` | Daily at 09:00 |
| `0 0 * * 1` | Every Monday at midnight |
| `0 */6 * * *` | Every 6 hours |

---

## Running Tests

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home ./gradlew test
```

37 tests across 6 suites — no database required (MockK for all I/O).

---

## Project Structure

```
app/src/main/kotlin/com/workflowengine/
├── core/
│   ├── WorkflowDefinition.kt   # DSL sealed class hierarchy
│   ├── WorkflowBuilder.kt      # Kotlin DSL builders
│   ├── WorkflowContext.kt      # Per-run key-value store
│   └── WorkflowValidator.kt    # Pre-execution validation
├── runtime/
│   ├── WorkflowExecutor.kt     # Coroutine-based execution engine
│   ├── EventBus.kt             # SharedFlow event bus
│   ├── WorkflowRegistry.kt     # In-memory workflow registry
│   ├── WorkflowScheduler.kt    # Trigger/cancel facade
│   ├── CronScheduler.kt        # Background cron runner
│   ├── CronParser.kt           # 5-field cron parser (no deps)
│   ├── DurableTimerService.kt  # Timer persistence + recovery
│   ├── MetricsRegistry.kt      # Micrometer counters/timers
│   └── db/
│       ├── Tables.kt           # Exposed ORM schema
│       ├── WorkflowStateStore.kt
│       ├── WorkflowTimerStore.kt
│       ├── WorkflowScheduleStore.kt
│       └── ApiKeyStore.kt
├── server/
│   ├── Application.kt          # Ktor module + wiring
│   ├── auth/ApiKeyAuth.kt      # Bearer auth plugin
│   └── routes/
│       ├── WorkflowRoutes.kt
│       ├── AdminRoutes.kt
│       └── WebSocketRoutes.kt
└── worker/
    ├── WorkerMain.kt           # Standalone worker process
    ├── RedisTaskQueue.kt       # Redis Streams consumer
    ├── HandlerRegistry.kt      # Step handler registry
    └── WorkerHeartbeat.kt      # Redis heartbeat
```

---

## Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/workflows` | PostgreSQL JDBC URL |
| `DATABASE_USER` | `workflow_user` | DB username |
| `DATABASE_PASSWORD` | `secret` | DB password |
| `REDIS_URL` | `redis://localhost:6380` | Redis URL |

---

## License

MIT
