package com.workflowengine.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class WorkflowDefinitionTest {

    @Test
    fun `sequential workflow has steps in order`() {
        val handler: suspend (WorkflowContext) -> Any? = { _ -> }

        val definition = WorkflowDefinition(
            id = "order-processing",
            steps = listOf(
                StepDefinition.Task("validate-order", handler),
                StepDefinition.Task("charge-payment", handler, RetryPolicy.exponential(3, 2.seconds)),
                StepDefinition.Task("send-confirmation", handler)
            ),
            onFailure = FailureStrategy.FAIL_FAST
        )

        assertEquals("order-processing", definition.id)
        assertEquals(3, definition.steps.size)
        assertEquals(FailureStrategy.FAIL_FAST, definition.onFailure)

        val first = definition.steps[0]
        assertIs<StepDefinition.Task>(first)
        assertEquals("validate-order", first.name)
        assertEquals(1, first.retry.maxAttempts)

        val second = definition.steps[1]
        assertIs<StepDefinition.Task>(second)
        assertEquals("charge-payment", second.name)
        assertEquals(3, second.retry.maxAttempts)
        assertIs<BackoffStrategy.Exponential>(second.retry.backoff)
    }

    @Test
    fun `parallel step contains child steps`() {
        val handler: suspend (WorkflowContext) -> Any? = { _ -> }

        val definition = WorkflowDefinition(
            id = "notify-workflow",
            steps = listOf(
                StepDefinition.Parallel(
                    listOf(
                        StepDefinition.Task("send-email", handler),
                        StepDefinition.Task("send-sms", handler),
                    )
                )
            )
        )

        assertEquals(1, definition.steps.size)
        val parallel = definition.steps[0]
        assertIs<StepDefinition.Parallel>(parallel)
        assertEquals(2, parallel.steps.size)

        val children = parallel.steps.filterIsInstance<StepDefinition.Task>().map { it.name }
        assertEquals(listOf("send-email", "send-sms"), children)
    }

    @Test
    fun `nested sequential inside parallel`() {
        val handler: suspend (WorkflowContext) -> Any? = { _ -> }

        val definition = WorkflowDefinition(
            id = "complex-workflow",
            steps = listOf(
                StepDefinition.Task("start", handler),
                StepDefinition.Parallel(
                    listOf(
                        StepDefinition.Sequential(
                            listOf(
                                StepDefinition.Task("branch-a-step-1", handler),
                                StepDefinition.Task("branch-a-step-2", handler),
                            )
                        ),
                        StepDefinition.Task("branch-b", handler),
                    )
                ),
                StepDefinition.Task("finish", handler)
            ),
            onFailure = FailureStrategy.COMPENSATE
        )

        assertEquals(3, definition.steps.size)
        assertEquals(FailureStrategy.COMPENSATE, definition.onFailure)

        val parallel = definition.steps[1]
        assertIs<StepDefinition.Parallel>(parallel)
        assertEquals(2, parallel.steps.size)

        val sequential = parallel.steps[0]
        assertIs<StepDefinition.Sequential>(sequential)
        assertEquals(2, sequential.steps.size)
    }

    @Test
    fun `retry policy none has maxAttempts 1 and zero delay`() {
        val policy = RetryPolicy.none()
        assertEquals(1, policy.maxAttempts)
        assertIs<BackoffStrategy.Fixed>(policy.backoff)
        assertEquals(0.seconds, (policy.backoff as BackoffStrategy.Fixed).delay)
    }

    @Test
    fun `exponential backoff delay grows with attempt`() {
        val backoff = BackoffStrategy.Exponential(1.seconds)
        assertTrue(backoff.delayFor(1) > backoff.delayFor(0))
        assertTrue(backoff.delayFor(2) > backoff.delayFor(1))
    }
}
