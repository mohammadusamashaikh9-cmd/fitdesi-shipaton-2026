package com.example.ai.conversation

import com.example.ai.AiCoachResponse
import com.example.ai.AiCoachConversationRole
import com.example.ai.AiCoachConversationTurn
import com.example.ai.GeneratedDietPlan
import com.example.ai.GeneratedWorkoutPlan

object AiCoachConversationContext {
    const val MAX_MESSAGES = 6
    const val MAX_MESSAGE_CHARACTERS = 1_000
    const val MAX_AGGREGATE_CHARACTERS = 4_000

    fun build(messages: List<AiCoachMessage>): List<AiCoachConversationTurn> {
        val recent = messages.takeLast(MAX_MESSAGES)
        var remaining = MAX_AGGREGATE_CHARACTERS
        val reversed = mutableListOf<AiCoachConversationTurn>()
        for (message in recent.asReversed()) {
            if (remaining <= 0) break
            val text = message.contextText()
                .take(MAX_MESSAGE_CHARACTERS)
                .take(remaining)
                .trim()
            if (text.isNotEmpty()) {
                reversed += AiCoachConversationTurn(
                    role = if (message.role == AiCoachMessageRole.USER) {
                        AiCoachConversationRole.USER
                    } else {
                        AiCoachConversationRole.ASSISTANT
                    },
                    text = text
                )
                remaining -= text.length
            }
        }
        return reversed.asReversed()
    }

    private fun AiCoachMessage.contextText(): String = when (role) {
        AiCoachMessageRole.USER -> text
        AiCoachMessageRole.COACH -> response?.toCompactContext() ?: text
    }

    private fun AiCoachResponse.toCompactContext(): String = buildList {
        add(summary)
        if (recommendedAction.isNotBlank()) add("Next action: $recommendedAction")
        workoutPlan?.takeIf { it.isValid() }?.let { add(it.toCompactContext()) }
        dietPlan?.takeIf { it.isValid() }?.let { add(it.toCompactContext()) }
    }.joinToString("\n")

    private fun GeneratedWorkoutPlan.toCompactContext(): String = buildString {
        append("Workout plan: ").append(title).append("; goal: ").append(goal)
        days.forEach { day ->
            append("\n").append(day.dayName).append(" — ").append(day.focus).append(": ")
            append(day.exercises.joinToString("; ") { exercise ->
                "${exercise.name} (${exercise.exerciseId}), ${exercise.sets} x ${exercise.repsOrDuration}, ${exercise.restSeconds}s rest"
            })
        }
    }

    private fun GeneratedDietPlan.toCompactContext(): String = buildString {
        append("Diet plan: ").append(title).append("; goal: ").append(goal)
        calorieTarget?.let { append("; target: ").append(it).append(" kcal") }
        days.forEach { day ->
            day.meals.forEach { meal ->
                append("\n").append(meal.label).append(": ")
                    .append(meal.foodName)
                meal.foodRecordId.takeIf(String::isNotBlank)?.let {
                    append(" (").append(it).append(")")
                }
                append(", ").append(meal.portionDescription.ifBlank { meal.storedServing })
                meal.additionalFoods.forEach { food ->
                    append("; ").append(food.foodName)
                    food.foodRecordId.takeIf(String::isNotBlank)?.let {
                        append(" (").append(it).append(")")
                    }
                    append(", ").append(food.portionDescription)
                }
            }
        }
    }
}
