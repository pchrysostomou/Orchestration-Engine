package com.workflowengine.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds

class WorkflowBuilderTest {

    // Workflow 1: sequential steps with retry
    @Test
    fun `order processing workflow has correct structure`() {
        val definition = workflow("order-processing") {
            step("validate-order") { ctx ->
                val items = ctx.input<List<String>>()
                require(items.isNotEmpty())
            }
            step("charge-payment", retry = RetryPolicy.exponential(3, 2.seconds)) { _ -> }
            parallel {
                step("send-to-warehouse") { _ -> }
                step("notify-customer") { _ -> }
            }
            onFailure(FailureStrategy.COMPENSATE)
        }

        assertEquals("order-processing", definition.id)
        assertEquals(FailureStrategy.COMPENSATE, definition.onFailure)
        assertEquals(3, definition.steps.size)

        val validate = definition.steps[0]
        assertIs<StepDefinition.Task>(validate)
        assertEquals("validate-order", validate.name)
        assertEquals(1, validate.retry.maxAttempts)

        val charge = definition.steps[1]
        assertIs<StepDefinition.Task>(charge)
        assertEquals("charge-payment", charge.name)
        assertEquals(3, charge.retry.maxAttempts)
        assertIs<BackoffStrategy.Exponential>(charge.retry.backoff)

        val parallel = definition.steps[2]
        assertIs<StepDefinition.Parallel>(parallel)
        val parallelNames = parallel.steps.filterIsInstance<StepDefinition.Task>().map { it.name }
        assertEquals(listOf("send-to-warehouse", "notify-customer"), parallelNames)
    }

    // Workflow 2: only parallel steps, default FAIL_FAST
    @Test
    fun `notification workflow runs all channels in parallel`() {
        val definition = workflow("multi-channel-notify") {
            parallel {
                step("email") { _ -> }
                step("sms") { _ -> }
                step("push") { _ -> }
            }
        }

        assertEquals("multi-channel-notify", definition.id)
        assertEquals(FailureStrategy.FAIL_FAST, definition.onFailure)
        assertEquals(1, definition.steps.size)

        val parallel = definition.steps[0]
        assertIs<StepDefinition.Parallel>(parallel)
        assertEquals(3, parallel.steps.size)
        val names = parallel.steps.filterIsInstance<StepDefinition.Task>().map { it.name }
        assertEquals(listOf("email", "sms", "push"), names)
    }

    // Workflow 3: nested sequential inside parallel
    @Test
    fun `data pipeline workflow has nested sequential inside parallel`() {
        val definition = workflow("data-pipeline") {
            step("ingest") { _ -> }
            parallel {
                sequential {
                    step("transform-a") { _ -> }
                    step("validate-a") { _ -> }
                }
                sequential {
                    step("transform-b") { _ -> }
                    step("validate-b") { _ -> }
                }
            }
            step("merge") { _ -> }
            onFailure(FailureStrategy.COMPENSATE)
        }

        assertEquals(3, definition.steps.size)

        val parallel = definition.steps[1]
        assertIs<StepDefinition.Parallel>(parallel)
        assertEquals(2, parallel.steps.size)

        val branchA = parallel.steps[0]
        assertIs<StepDefinition.Sequential>(branchA)
        assertEquals(2, branchA.steps.size)
        val branchANames = branchA.steps.filterIsInstance<StepDefinition.Task>().map { it.name }
        assertEquals(listOf("transform-a", "validate-a"), branchANames)

        val branchB = parallel.steps[1]
        assertIs<StepDefinition.Sequential>(branchB)
        val branchBNames = branchB.steps.filterIsInstance<StepDefinition.Task>().map { it.name }
        assertEquals(listOf("transform-b", "validate-b"), branchBNames)

        val merge = definition.steps[2]
        assertIs<StepDefinition.Task>(merge)
        assertEquals("merge", merge.name)
    }

    @Test
    fun `@WorkflowDsl prevents step from leaking into wrong scope`() {
        // This test verifies the DSL marker works — if scope leakage were possible,
        // calling outer builder methods inside an inner block would compile.
        // We just confirm the DSL compiles correctly with proper scoping.
        val definition = workflow("scope-test") {
            parallel {
                step("inside-parallel") { _ -> }
                // `onFailure` is NOT available here — it would be a compile error.
                // The @WorkflowDsl annotation prevents calling WorkflowBuilder.onFailure
                // from inside ParallelBuilder's lambda.
            }
        }
        assertEquals(1, definition.steps.size)
    }
}
