package com.workflowengine.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorkflowValidatorTest {

    private val validator = WorkflowValidator()
    private val noOp: suspend (WorkflowContext) -> Any? = { _ -> }

    @Test
    fun `valid workflow passes validation`() {
        val definition = workflow("order-processing") {
            step("validate") { _ -> }
            step("charge") { _ -> }
        }
        val errors = validator.validate(definition)
        assertTrue(errors.isEmpty(), "Expected no errors but got: $errors")
    }

    @Test
    fun `blank workflow id is an error`() {
        val definition = WorkflowDefinition(
            id = "  ",
            steps = listOf(StepDefinition.Task("step-1", noOp))
        )
        val errors = validator.validate(definition)
        assertTrue(errors.any { it.message.contains("id") })
    }

    @Test
    fun `workflow with no steps is an error`() {
        val definition = WorkflowDefinition(id = "empty", steps = emptyList())
        val errors = validator.validate(definition)
        assertTrue(errors.any { it.message.contains("at least one step") })
    }

    @Test
    fun `duplicate step names are detected`() {
        val definition = WorkflowDefinition(
            id = "dup-test",
            steps = listOf(
                StepDefinition.Task("step-a", noOp),
                StepDefinition.Task("step-a", noOp),
            )
        )
        val errors = validator.validate(definition)
        assertEquals(1, errors.count { it.message.contains("Duplicate") })
    }

    @Test
    fun `blank step name is an error`() {
        val definition = WorkflowDefinition(
            id = "blank-step",
            steps = listOf(StepDefinition.Task("  ", noOp))
        )
        val errors = validator.validate(definition)
        assertTrue(errors.any { it.message.contains("blank") })
    }

    @Test
    fun `empty parallel block is an error`() {
        val definition = WorkflowDefinition(
            id = "empty-parallel",
            steps = listOf(StepDefinition.Parallel(emptyList()))
        )
        val errors = validator.validate(definition)
        assertTrue(errors.any { it.message.contains("Parallel block cannot be empty") })
    }

    @Test
    fun `empty sequential block is an error`() {
        val definition = WorkflowDefinition(
            id = "empty-sequential",
            steps = listOf(StepDefinition.Sequential(emptyList()))
        )
        val errors = validator.validate(definition)
        assertTrue(errors.any { it.message.contains("Sequential block cannot be empty") })
    }

    @Test
    fun `duplicate names across nested steps are detected`() {
        val definition = workflow("nested-dup") {
            step("shared-name") { _ -> }
            parallel {
                step("shared-name") { _ -> }
            }
        }
        val errors = validator.validate(definition)
        assertEquals(1, errors.count { it.message.contains("Duplicate step name: 'shared-name'") })
    }

    @Test
    fun `validateOrThrow throws on invalid workflow`() {
        val definition = WorkflowDefinition(id = "", steps = emptyList())
        try {
            validator.validateOrThrow(definition)
            error("Expected exception")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.isNotBlank())
        }
    }
}
