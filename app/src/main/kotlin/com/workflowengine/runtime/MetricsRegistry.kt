package com.workflowengine.runtime

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Timer
import io.micrometer.prometheus.PrometheusConfig
import io.micrometer.prometheus.PrometheusMeterRegistry
import java.util.concurrent.TimeUnit

object MetricsRegistry {
    val prometheus = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)

    val runsStarted   = counter("workflow.runs.started",    "Total workflow runs started")
    val runsCompleted = counter("workflow.runs.completed",  "Total workflow runs completed successfully")
    val runsFailed    = counter("workflow.runs.failed",     "Total workflow runs that failed")
    val runsCancelled = counter("workflow.runs.cancelled",  "Total workflow runs cancelled")

    fun stepTimer(workflowId: String, stepName: String, status: String): Timer =
        Timer.builder("workflow.step.duration")
            .description("Step execution duration")
            .tags("workflow", workflowId, "step", stepName, "status", status)
            .register(prometheus)

    fun recordStep(workflowId: String, stepName: String, status: String, elapsedMs: Long) {
        stepTimer(workflowId, stepName, status).record(elapsedMs, TimeUnit.MILLISECONDS)
    }

    private fun counter(name: String, description: String): Counter =
        Counter.builder(name).description(description).register(prometheus)
}
