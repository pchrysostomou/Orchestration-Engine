package com.workflowengine.core

import kotlin.time.Duration

@DslMarker
annotation class WorkflowDsl

@WorkflowDsl
class WorkflowBuilder(val id: String) {
    private val steps = mutableListOf<StepDefinition>()
    private var failureStrategy: FailureStrategy = FailureStrategy.FAIL_FAST
    private var timerCount = 0

    fun step(
        name: String,
        retry: RetryPolicy = RetryPolicy.none(),
        timeout: Duration? = null,
        block: suspend (WorkflowContext) -> Any?
    ) {
        steps += StepDefinition.Task(name, block, retry, timeout)
    }

    fun parallel(block: ParallelBuilder.() -> Unit) {
        steps += StepDefinition.Parallel(ParallelBuilder().apply(block).build())
    }

    fun sequential(block: SequentialBuilder.() -> Unit) {
        steps += StepDefinition.Sequential(SequentialBuilder().apply(block).build())
    }

    fun branch(predicate: suspend (WorkflowContext) -> Boolean, block: BranchBuilder.() -> Unit) {
        steps += BranchBuilder(predicate).apply(block).build()
    }

    fun waitFor(duration: Duration, name: String = "timer-${++timerCount}") {
        steps += StepDefinition.Timer(name, duration)
    }

    fun onFailure(strategy: FailureStrategy) {
        failureStrategy = strategy
    }

    fun build() = WorkflowDefinition(id, steps.toList(), failureStrategy)
}

@WorkflowDsl
class ParallelBuilder {
    private val steps = mutableListOf<StepDefinition>()

    fun step(
        name: String,
        retry: RetryPolicy = RetryPolicy.none(),
        timeout: Duration? = null,
        block: suspend (WorkflowContext) -> Any?
    ) {
        steps += StepDefinition.Task(name, block, retry, timeout)
    }

    fun sequential(block: SequentialBuilder.() -> Unit) {
        steps += StepDefinition.Sequential(SequentialBuilder().apply(block).build())
    }

    fun build(): List<StepDefinition> = steps.toList()
}

@WorkflowDsl
class SequentialBuilder {
    private val steps = mutableListOf<StepDefinition>()
    private var timerCount = 0

    fun step(
        name: String,
        retry: RetryPolicy = RetryPolicy.none(),
        timeout: Duration? = null,
        block: suspend (WorkflowContext) -> Any?
    ) {
        steps += StepDefinition.Task(name, block, retry, timeout)
    }

    fun branch(predicate: suspend (WorkflowContext) -> Boolean, block: BranchBuilder.() -> Unit) {
        steps += BranchBuilder(predicate).apply(block).build()
    }

    fun waitFor(duration: Duration, name: String = "timer-${++timerCount}") {
        steps += StepDefinition.Timer(name, duration)
    }

    fun build(): List<StepDefinition> = steps.toList()
}

@WorkflowDsl
class BranchBuilder(private val predicate: suspend (WorkflowContext) -> Boolean) {
    private var onTrue: List<StepDefinition> = emptyList()
    private var onFalse: List<StepDefinition> = emptyList()

    fun onTrue(block: SequentialBuilder.() -> Unit) {
        onTrue = SequentialBuilder().apply(block).build()
    }

    fun onFalse(block: SequentialBuilder.() -> Unit) {
        onFalse = SequentialBuilder().apply(block).build()
    }

    fun build() = StepDefinition.Branch(predicate, onTrue, onFalse)
}

fun workflow(id: String, block: WorkflowBuilder.() -> Unit): WorkflowDefinition =
    WorkflowBuilder(id).apply(block).build()
