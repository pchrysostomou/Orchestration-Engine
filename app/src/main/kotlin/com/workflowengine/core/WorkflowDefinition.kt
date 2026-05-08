package com.workflowengine.core

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

data class WorkflowDefinition(
    val id: String,
    val steps: List<StepDefinition>,
    val onFailure: FailureStrategy = FailureStrategy.FAIL_FAST
)

sealed class StepDefinition {
    data class Sequential(val steps: List<StepDefinition>) : StepDefinition()
    data class Parallel(val steps: List<StepDefinition>) : StepDefinition()
    data class Task(
        val name: String,
        val handler: suspend (WorkflowContext) -> Any?,
        val retry: RetryPolicy = RetryPolicy.none(),
        val timeout: Duration? = null
    ) : StepDefinition()
    data class Branch(
        val predicate: suspend (WorkflowContext) -> Boolean,
        val onTrue: List<StepDefinition> = emptyList(),
        val onFalse: List<StepDefinition> = emptyList()
    ) : StepDefinition()
    data class Timer(val name: String, val duration: Duration) : StepDefinition()
}

data class RetryPolicy(
    val maxAttempts: Int,
    val backoff: BackoffStrategy
) {
    companion object {
        fun none() = RetryPolicy(1, BackoffStrategy.Fixed(0.seconds))
        fun exponential(maxAttempts: Int, base: Duration) =
            RetryPolicy(maxAttempts, BackoffStrategy.Exponential(base))
    }
}

sealed class BackoffStrategy {
    data class Fixed(val delay: Duration) : BackoffStrategy()
    data class Exponential(val base: Duration) : BackoffStrategy()

    fun delayFor(attempt: Int): Duration = when (this) {
        is Fixed       -> delay
        is Exponential -> base * Math.pow(2.0, attempt.toDouble())
    }
}

enum class FailureStrategy { FAIL_FAST, COMPENSATE }
