package com.workflowengine.core

data class ValidationError(val message: String)

class WorkflowValidator {

    fun validate(definition: WorkflowDefinition): List<ValidationError> {
        val errors = mutableListOf<ValidationError>()
        val names = mutableSetOf<String>()

        fun check(step: StepDefinition) {
            when (step) {
                is StepDefinition.Task -> {
                    if (step.name.isBlank())
                        errors += ValidationError("Step name cannot be blank")
                    if (!names.add(step.name))
                        errors += ValidationError("Duplicate step name: '${step.name}'")
                    if (step.retry.maxAttempts < 1)
                        errors += ValidationError("maxAttempts must be >= 1 for step '${step.name}'")
                }
                is StepDefinition.Sequential -> {
                    if (step.steps.isEmpty())
                        errors += ValidationError("Sequential block cannot be empty")
                    step.steps.forEach(::check)
                }
                is StepDefinition.Parallel -> {
                    if (step.steps.isEmpty())
                        errors += ValidationError("Parallel block cannot be empty")
                    step.steps.forEach(::check)
                }
                is StepDefinition.Branch -> {
                    if (step.onTrue.isEmpty() && step.onFalse.isEmpty())
                        errors += ValidationError("Branch must define at least one of onTrue or onFalse")
                    step.onTrue.forEach(::check)
                    step.onFalse.forEach(::check)
                }
                is StepDefinition.Timer -> {
                    if (step.name.isBlank())
                        errors += ValidationError("Timer step name cannot be blank")
                    if (!names.add(step.name))
                        errors += ValidationError("Duplicate step name: '${step.name}'")
                    if (step.duration.isNegative())
                        errors += ValidationError("Timer duration must be positive for step '${step.name}'")
                }
            }
        }

        if (definition.id.isBlank())
            errors += ValidationError("Workflow id cannot be blank")
        if (definition.steps.isEmpty())
            errors += ValidationError("Workflow must have at least one step")
        definition.steps.forEach(::check)
        return errors
    }

    fun validateOrThrow(definition: WorkflowDefinition) {
        val errors = validate(definition)
        if (errors.isNotEmpty())
            throw IllegalArgumentException(
                "Invalid workflow '${definition.id}': ${errors.joinToString("; ") { it.message }}"
            )
    }
}
