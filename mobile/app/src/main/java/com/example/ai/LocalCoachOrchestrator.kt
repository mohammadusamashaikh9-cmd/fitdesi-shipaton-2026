package com.example.ai

import com.example.ai.knowledge.CoachContext
import com.example.ai.knowledge.FitnessKnowledgeRetriever
import com.example.ai.knowledge.KnowledgeDomain
import com.example.ai.knowledge.KnowledgeEntry
import com.example.ai.knowledge.KnowledgePackRegistry
import com.example.ai.knowledge.KnowledgeQuery
import com.example.ai.knowledge.KnowledgeSourceType
import com.example.ai.knowledge.ExercisePackRecord
import com.example.ai.knowledge.ProjectFoodRecord

private val EXPLICIT_EXERCISE_TERMS: Set<String> =
    setOf("clean", "snatch", "jerk", "burpee", "jump", "explosive", "olympic")
private val FOOD_QUERY_NOISE_TERMS: Set<String> =
    setOf("pakistani", "desi", "food", "foods", "suggest")

internal class LocalCoachOrchestrator(
    private val workoutGenerator: LocalWorkoutPlanGenerator = LocalWorkoutPlanGenerator(),
    private val dietGenerator: LocalPakistaniDietPlanGenerator = LocalPakistaniDietPlanGenerator(),
    private val exerciseProvider: () -> List<ExercisePackRecord> =
        KnowledgePackRegistry::verifiedExercises,
    private val foodProvider: () -> List<ProjectFoodRecord> =
        KnowledgePackRegistry::localFoods
) {
    fun respond(
        question: String,
        context: CoachContext,
        retriever: FitnessKnowledgeRetriever = KnowledgePackRegistry.retriever()
    ): AiCoachResponse {
        val intent = LocalCoachIntentClassifier.classify(question)
        if (intent == AiCoachIntent.MEDICAL_ESCALATION) return medicalEscalation(question)

        return when (intent) {
            AiCoachIntent.WORKOUT_PLAN -> workoutPlan(question, context)
            AiCoachIntent.PAKISTANI_DIET_PLAN -> dietPlan(question, context)
            AiCoachIntent.FOOD_QUESTION -> foodAnswer(question, context, retriever)
            AiCoachIntent.EXERCISE_QUESTION -> exerciseAnswer(question, context, retriever)
            AiCoachIntent.PROGRESS_REVIEW -> progressAnswer(context, retriever)
            AiCoachIntent.YOGA_PLAN -> yogaAnswer(question, context, retriever)
            AiCoachIntent.GENERAL_COACHING -> generalAnswer(question, context, retriever)
            AiCoachIntent.UNSUPPORTED -> unsupportedAnswer()
            AiCoachIntent.MEDICAL_ESCALATION -> medicalEscalation(question)
        }
    }

    private fun workoutPlan(question: String, context: CoachContext): AiCoachResponse {
        val promptEquipment = equipmentFromQuestion(question)
        val promptGoal = goalFromQuestion(question)
        val promptExperience = experienceFromQuestion(question)
        val effectiveContext = context.copy(
            goal = promptGoal ?: context.goal,
            experience = promptExperience ?: context.experience,
            equipment = promptEquipment.ifEmpty { context.equipment }
        )
        val frequency = workoutFrequency(question, effectiveContext, workoutSplit(question))
        val plan = workoutGenerator.generate(
            LocalWorkoutPlanInput(
                goal = effectiveContext.goal,
                experience = effectiveContext.experience,
                requestedDays = frequency.programmedDays,
                availableTrainingDays = effectiveContext.workoutDays ?: frequency.programmedDays,
                explicitFrequencyRequest = frequency.explicit,
                equipment = effectiveContext.equipment,
                recentWorkoutSummary = effectiveContext.recentWorkoutSummary,
                limitations = effectiveContext.limitations,
                requestedDurationMinutes = requestedDuration(question),
                explicitlyRequestedTerms = requestedExerciseTerms(question),
                splitPreference = frequency.splitPreference,
                frequencyAdjustmentNote = frequency.adjustmentNote,
                exercises = exerciseProvider()
            )
        )
        val recordIds = plan.days.flatMap { day -> day.exercises.map(LocalWorkoutExercise::id) }.distinct()
        val generatedPlan = plan.toGeneratedWorkoutPlan(
            goal = effectiveContext.goal,
            experience = effectiveContext.experience,
            profileContextUsed = context.hasProfileContext()
        )
        val workoutTemplateValid = WorkoutPlanValidator.isValidGenerated(generatedPlan.days)
        val workoutValidationStatus = if (generatedPlan.isValid() && workoutTemplateValid) {
            PlanValidationStatus.VALIDATED
        } else {
            PlanValidationStatus.NEEDS_ADJUSTMENT
        }
        return AiCoachResponse(
            summary = plan.weeklySummary,
            recommendedAction =
                "Follow the sessions in order, log completed sets in Track Workout, and review recovery before progressing.",
            nutritionNote =
                "Support the plan with regular balanced meals, adequate protein foods, and hydration. Exact needs depend on your stored profile targets.",
            workoutNote = plan.asMultilineText(),
            safetyDisclaimer = plan.safetyNote,
            metadata = metadata(
                intent = AiCoachIntent.WORKOUT_PLAN,
                ids = recordIds,
                context = context,
                fallback = plan.fallbackUsed,
                confidence = if (recordIds.isEmpty()) 0.45 else 0.95,
                sourceType = generatedPlan.sourceType.name,
                validationStatus = workoutValidationStatus.name
            ),
            workoutPlan = generatedPlan.takeIf { it.isValid() && workoutTemplateValid }
        )
    }

    private fun dietPlan(question: String, context: CoachContext): AiCoachResponse {
        val promptGoal = goalFromQuestion(question)
        val effectiveContext = context.copy(goal = promptGoal ?: context.goal)
        val plan = dietGenerator.generate(
            LocalDietPlanInput(
                goal = effectiveContext.goal,
                calorieTarget = effectiveContext.calorieTarget,
                proteinTargetGrams = effectiveContext.proteinTargetGrams,
                carbsTargetGrams = effectiveContext.carbsTargetGrams,
                fatTargetGrams = effectiveContext.fatTargetGrams,
                dietaryPreference = effectiveContext.dietaryPreference,
                requestedMealCount = requestedMeals(question, effectiveContext),
                limitations = effectiveContext.limitations,
                dislikedFoods = extractDislikedFoods(effectiveContext.limitations),
                foods = foodProvider()
            )
        )
        val recordIds = plan.meals.flatMap { meal ->
            listOf(meal.primary.id) +
                meal.accompaniments.map { it.food.id } +
                meal.alternatives.map { it.food.id }
        }.distinct()
        val generatedPlan = plan.toGeneratedDietPlan(
            goal = effectiveContext.goal,
            calorieTarget = effectiveContext.calorieTarget,
            proteinTargetGrams = effectiveContext.proteinTargetGrams,
            carbsTargetGrams = effectiveContext.carbsTargetGrams,
            fatTargetGrams = effectiveContext.fatTargetGrams,
            profileContextUsed = context.hasProfileContext()
        )
        val requiresProfileInput = generatedPlan.validationStatus == PlanValidationStatus.NEEDS_PROFILE_INPUT
        return AiCoachResponse(
            summary = plan.targetSummary,
            recommendedAction = if (requiresProfileInput) {
                dietPlanClarification(effectiveContext)
            } else {
                plan.asMultilineText()
            },
            nutritionNote = plan.variationDisclaimer,
            workoutNote =
                "Place a comfortable meal one to three hours before training and use a normal balanced meal afterward.",
            safetyDisclaimer =
                "General nutrition education only. Seek a registered dietitian or clinician for disease, pregnancy, eating-disorder concerns, allergies requiring treatment, or therapeutic diets.",
            metadata = metadata(
                intent = AiCoachIntent.PAKISTANI_DIET_PLAN,
                ids = recordIds.map { "local-food-$it" },
                context = context,
                fallback = plan.fallbackUsed,
                confidence = if (recordIds.isEmpty()) 0.4 else 0.94,
                sourceType = generatedPlan.sourceType.name,
                validationStatus = generatedPlan.validationStatus.name
            ),
            dietPlan = generatedPlan.takeIf(GeneratedDietPlan::isSavable)
        )
    }

    private fun dietPlanClarification(context: CoachContext): String = when {
        context.dietaryPreference.isNullOrBlank() ->
            "What diet preference should I use? Add it in Profile, then ask again."
        context.calorieTarget == null ->
            "What daily calorie target should I use? Add it in Profile, then ask again."
        context.proteinTargetGrams == null || context.carbsTargetGrams == null || context.fatTargetGrams == null ->
            "What macro targets should I use? Complete them in Profile, then ask again."
        else -> "What meal count should I use? Complete your profile, then ask again."
    }

    private fun foodAnswer(
        question: String,
        context: CoachContext,
        retriever: FitnessKnowledgeRetriever
    ): AiCoachResponse {
        val matches = retriever.retrieve(KnowledgeQuery(question, context, GENERAL_LIMIT))
            .filter { it.entry.domain == KnowledgeDomain.PAKISTANI_FOOD }
            .take(GENERAL_LIMIT)
        val proteinRequest = "protein" in question.lowercase()
        val normalizedQuestion = normalizeFoodText(question)
        val directFoods = foodProvider()
            .asSequence()
            .filter { it.id.isNotBlank() && it.name.isNotBlank() }
            .map { food -> food to foodRelevance(food, normalizedQuestion, proteinRequest) }
            .filter { (_, score) -> score > 0.0 || normalizedQuestion.isBlank() }
            .sortedWith(compareByDescending<Pair<ProjectFoodRecord, Double>> { it.second }
                .thenBy { it.first.id })
            .take(GENERAL_LIMIT)
            .map { (food, _) -> food }
            .toList()
        val foods = (
            directFoods.map(ProjectFoodRecord::name) +
                matches.flatMap { it.entry.foodNames }
            ).distinct().take(GENERAL_LIMIT)
        val notes = if (directFoods.isNotEmpty()) {
            directFoods.take(5).joinToString("\n") { food ->
                if (food.isNutritionReviewed) {
                    "- ${food.name}: ${food.servingSize}, about ${food.calories} kcal and " +
                        "${food.proteinGrams} g protein."
                } else {
                    "- ${food.name}: searchable local nutrition reference; serving basis is under review, so do not log its source values as a serving."
                }
            }
        } else {
            matches.take(5).joinToString("\n") { "- ${it.entry.title}: ${it.entry.content}" }
        }
        val directIds = directFoods.map { "local-food-${it.id}" }
        return AiCoachResponse(
            summary = if (foods.isEmpty()) {
                "No matching local Pakistani food record was found."
            } else {
                "Matching local Pakistani food options: ${foods.joinToString()}."
            },
            recommendedAction =
                "Compare portions and recipe assumptions, then choose foods that fit your goal and dietary needs.",
            nutritionNote = notes.ifBlank {
                "Use familiar protein foods and balanced portions; home recipes vary."
            },
            workoutNote = "Food choices support training, but they do not replace a repeatable workout and recovery plan.",
            safetyDisclaimer = STANDARD_SAFETY,
            metadata = metadata(
                intent = AiCoachIntent.FOOD_QUESTION,
                ids = directIds + matches.map { it.entry.id },
                context = context,
                fallback = directFoods.isEmpty() && matches.isEmpty(),
                confidence = if (directFoods.isEmpty() && matches.isEmpty()) 0.35 else 0.9,
                sourceType = if (directFoods.any { !it.isNutritionReviewed }) {
                    "LOCAL_MERGED_REVIEW_REQUIRED"
                } else if (directFoods.isNotEmpty()) {
                    "LEGACY_LOCAL_UNKNOWN"
                } else {
                    sourceType(matches.map { it.entry })
                }
            )
        )
    }

    private fun foodRelevance(
        food: ProjectFoodRecord,
        normalizedQuestion: String,
        proteinRequest: Boolean
    ): Double {
        val searchable = (listOf(food.name, food.category) + food.aliases)
            .joinToString(" ")
            .let(::normalizeFoodText)
        val queryTerms = normalizedQuestion.split(" ")
            .filter { it.length >= 2 && it !in FOOD_QUERY_NOISE_TERMS }
            .toSet()
        val searchableTerms = searchable.split(" ").toSet()
        var score = queryTerms.count(searchableTerms::contains) * 10.0
        if (normalizedQuestion.isNotBlank() && normalizedQuestion in searchable) score += 30.0
        if (proteinRequest && food.isNutritionReviewed) score += food.proteinGrams.coerceAtMost(50.0)
        return score
    }

    private fun normalizeFoodText(value: String): String = value.lowercase()
        .replace(Regex("\\bdaal\\b"), "dal")
        .replace(Regex("\\bchanay\\b"), "chana")
        .replace(Regex("\\bcholay\\b"), "chole")
        .replace(Regex("\\bbhallay\\b"), "bhalla")
        .replace(Regex("\\bjaman\\b"), "jamun")
        .replace(Regex("\\bkhorma\\b"), "khurma")
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()

    private fun exerciseAnswer(
        question: String,
        context: CoachContext,
        retriever: FitnessKnowledgeRetriever
    ): AiCoachResponse {
        val matches = retriever.retrieve(KnowledgeQuery(question, context, GENERAL_LIMIT))
            .filter { it.entry.domain == KnowledgeDomain.EXERCISE }
            .take(GENERAL_LIMIT)
        val primary = matches.firstOrNull()?.entry
        val guidance = matches.take(5).joinToString("\n") { match ->
            buildString {
                append("- ${match.entry.title}: ${match.entry.content}")
                if (match.entry.regressions.isNotEmpty()) {
                    append("\n  Regressions: ${match.entry.regressions.joinToString()}")
                }
                if (match.entry.progressions.isNotEmpty()) {
                    append("\n  Progressions: ${match.entry.progressions.joinToString()}")
                }
            }
        }
        return AiCoachResponse(
            summary = primary?.let { "Local exercise guidance for ${it.title}." }
                ?: "No matching verified exercise guidance was found.",
            recommendedAction = guidance.ifBlank {
                "Use a comfortable movement variation and progress only while technique remains controlled."
            },
            nutritionNote = "Support training with regular balanced meals and hydration.",
            workoutNote =
                "Use the listed regression when control is limited, and progress only after completing the current variation comfortably.",
            safetyDisclaimer = STANDARD_SAFETY,
            metadata = metadata(
                intent = AiCoachIntent.EXERCISE_QUESTION,
                ids = matches.map { it.entry.id },
                context = context,
                fallback = matches.isEmpty(),
                confidence = if (matches.isEmpty()) 0.35 else 0.9,
                sourceType = sourceType(matches.map { it.entry })
            )
        )
    }

    private fun progressAnswer(
        context: CoachContext,
        retriever: FitnessKnowledgeRetriever
    ): AiCoachResponse {
        val recent = context.recentWorkoutSummary
        val matches = retriever.retrieve(
            KnowledgeQuery("progress recovery recent workout", context, GENERAL_LIMIT)
        )
        return AiCoachResponse(
            summary = recent?.let { "Recent local workout context: $it" }
                ?: "No recent workout summary is available yet.",
            recommendedAction =
                if (recent.isNullOrBlank()) "Complete and save workouts to enable a grounded progress review."
                else "Compare completed sets, volume, duration, technique, and recovery across several sessions before changing the plan.",
            nutritionNote = "Keep food intake and hydration consistent when comparing training performance.",
            workoutNote =
                "Progress one variable at a time - repetitions, load, sets, or duration - and reduce demand when recovery or technique declines.",
            safetyDisclaimer = STANDARD_SAFETY,
            metadata = metadata(
                intent = AiCoachIntent.PROGRESS_REVIEW,
                ids = matches.map { it.entry.id },
                context = context,
                fallback = recent.isNullOrBlank(),
                confidence = if (recent.isNullOrBlank()) 0.45 else 0.85
            )
        )
    }

    private fun yogaAnswer(
        question: String,
        context: CoachContext,
        retriever: FitnessKnowledgeRetriever
    ): AiCoachResponse {
        val matches = retriever.retrieve(KnowledgeQuery("$question yoga mobility", context, GENERAL_LIMIT))
            .filter { it.entry.keywords.any { keyword -> keyword.equals("yoga", true) } }
        val options = matches.take(5).joinToString("\n") { "- ${it.entry.title}: ${it.entry.content}" }
        return AiCoachResponse(
            summary = "These local yoga and mobility options should stay comfortable and controlled.",
            recommendedAction = options.ifBlank {
                "No verified yoga or mobility options are available beyond the general fallback."
            },
            nutritionNote = "Hydrate normally; special supplements are not required for these gentle movements.",
            workoutNote = "Move slowly, breathe normally, and avoid forcing end range.",
            safetyDisclaimer = STANDARD_SAFETY,
            metadata = metadata(
                intent = AiCoachIntent.YOGA_PLAN,
                ids = matches.map { it.entry.id },
                context = context,
                fallback = matches.isEmpty(),
                confidence = if (matches.isEmpty()) 0.4 else 0.8
            )
        )
    }

    private fun generalAnswer(
        question: String,
        context: CoachContext,
        retriever: FitnessKnowledgeRetriever
    ): AiCoachResponse {
        val matches = retriever.retrieve(KnowledgeQuery(question, context, GENERAL_LIMIT))
            .filter { it.entry.domain != KnowledgeDomain.MEDICAL_ESCALATION }
            .take(GENERAL_LIMIT)
        val guidance = matches.take(5).joinToString("\n") { "- ${it.entry.title}: ${it.entry.content}" }
        return AiCoachResponse(
            summary = "Grounded local coaching based on available FitDesi knowledge and profile context.",
            recommendedAction = guidance.ifBlank {
                "Choose one clear fitness goal and one repeatable action for this week."
            },
            nutritionNote =
                matches.firstOrNull { it.entry.domain in NUTRITION_DOMAINS }?.entry?.content
                    ?: "Use balanced meals and realistic portions; mixed-dish values are estimates.",
            workoutNote =
                matches.firstOrNull { it.entry.domain in WORKOUT_DOMAINS }?.entry?.content
                    ?: "Use controlled technique, progress gradually, and allow recovery.",
            safetyDisclaimer = STANDARD_SAFETY,
            metadata = metadata(
                intent = AiCoachIntent.GENERAL_COACHING,
                ids = matches.map { it.entry.id },
                context = context,
                fallback = matches.isEmpty(),
                confidence = if (matches.isEmpty()) 0.35 else 0.75,
                sourceType = sourceType(matches.map { it.entry })
            )
        )
    }

    private fun medicalEscalation(question: String): AiCoachResponse {
        val urgent = LocalCoachIntentClassifier.matchedRedFlags(question)
        return AiCoachResponse(
            summary = "Your message contains urgent symptom language: ${urgent.joinToString()}.",
            recommendedAction =
                "Stop exercise and seek urgent medical assessment. Contact local emergency services for severe, sudden, or life-threatening symptoms.",
            nutritionNote = "Do not use food, supplements, or hydration changes as a substitute for urgent assessment.",
            workoutNote = "Do not continue training until the urgent symptoms have been assessed.",
            safetyDisclaimer = "This is an escalation notice, not a diagnosis or treatment plan.",
            metadata = AiCoachResponseMetadata(
                sourceType = "LOCAL_SAFETY_RULES",
                knowledgeRecordIds = listOf("fd-medical-urgent-symptoms"),
                intent = AiCoachIntent.MEDICAL_ESCALATION,
                confidence = 1.0
            )
        )
    }

    private fun unsupportedAnswer() = AiCoachResponse(
        summary = "This offline Coach currently supports fitness, workouts, Pakistani foods, progress, and yoga or mobility questions.",
        recommendedAction = "Rephrase the request around one supported fitness topic.",
        nutritionNote = "No nutrition recommendation was generated.",
        workoutNote = "No workout recommendation was generated.",
        safetyDisclaimer = STANDARD_SAFETY,
        metadata = AiCoachResponseMetadata(
            sourceType = "LOCAL_FALLBACK",
            fallbackUsed = true,
            intent = AiCoachIntent.UNSUPPORTED,
            confidence = 0.2
        )
    )

    private fun metadata(
        intent: AiCoachIntent,
        ids: List<String>,
        context: CoachContext,
        fallback: Boolean,
        confidence: Double,
        sourceType: String = "VERIFIED_LOCAL_KNOWLEDGE",
        validationStatus: String? = null
    ) = AiCoachResponseMetadata(
        sourceType = sourceType,
        knowledgeRecordIds = ids.distinct().take(GENERAL_LIMIT),
        fallbackUsed = fallback,
        profileContextUsed = listOf(
            context.goal,
            context.experience,
            context.dietaryPreference
        ).any { !it.isNullOrBlank() } || context.equipment.isNotEmpty() ||
            context.calorieTarget != null || context.proteinTargetGrams != null ||
            context.carbsTargetGrams != null || context.fatTargetGrams != null ||
            context.mealsPerDay != null || context.workoutDays != null ||
            context.limitations.isNotEmpty(),
        recentWorkoutContextUsed = !context.recentWorkoutSummary.isNullOrBlank(),
        intent = intent,
        confidence = confidence.coerceIn(0.0, 1.0),
        validationStatus = validationStatus
    )

    private fun sourceType(entries: List<KnowledgeEntry>): String {
        val sources = entries.map(KnowledgeEntry::sourceType).toSet()
        return when {
            KnowledgeSourceType.LEGACY_LOCAL in sources && sources.size > 1 ->
                "MERGED_VERIFIED_AND_LEGACY_LOCAL"
            sources == setOf(KnowledgeSourceType.LEGACY_LOCAL) -> "LEGACY_LOCAL_UNKNOWN"
            else -> "VERIFIED_LOCAL_KNOWLEDGE"
        }
    }

    private fun workoutFrequency(
        question: String,
        context: CoachContext,
        requestedSplit: WorkoutSplitPreference
    ): WorkoutFrequencySelection {
        val normalized = question.lowercase()
        val words = mapOf(
            "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6
        )
        val explicitDays = words.entries.firstOrNull { (word, _) ->
            Regex("\\b$word(?:[- ]day| days?)\\b").containsMatchIn(normalized)
        }?.value ?: Regex("\\b([2-6])(?:[- ]day| days?)\\b").find(normalized)
            ?.groupValues?.getOrNull(1)?.toIntOrNull()
        val available = context.workoutDays?.coerceIn(2, 7) ?: DEFAULT_WORKOUT_DAYS
        val experience = context.experience.orEmpty().lowercase()
        val goal = context.goal.orEmpty().lowercase()
        val baseDays = explicitDays ?: when {
            "beginner" in experience -> 3
            ("muscle" in goal || "gain" in goal) && "advanced" in experience -> 5
            "muscle" in goal || "gain" in goal -> 4
            "advanced" in experience -> 5
            else -> 4
        }
        val allowedByProfile = context.workoutDays?.let { baseDays.coerceAtMost(available) } ?: baseDays
        val selection = fitFrequencyToSplit(
            requestedDays = allowedByProfile.coerceIn(2, 6),
            availableDays = if (context.workoutDays == null) 6 else available,
            requestedSplit = requestedSplit
        )
        val availabilityNote = if (explicitDays != null && allowedByProfile != explicitDays) {
            "The requested $explicitDays sessions exceed the $available available training days, so the plan uses ${selection.days}."
        } else {
            null
        }
        return WorkoutFrequencySelection(
            programmedDays = selection.days,
            explicit = explicitDays != null || requestedSplit != WorkoutSplitPreference.AUTO,
            splitPreference = selection.split,
            adjustmentNote = listOfNotNull(availabilityNote, selection.note)
                .joinToString(" ")
                .takeIf(String::isNotBlank)
        )
    }

    private fun fitFrequencyToSplit(
        requestedDays: Int,
        availableDays: Int,
        requestedSplit: WorkoutSplitPreference
    ): SplitFrequencySelection = when (requestedSplit) {
        WorkoutSplitPreference.PUSH_PULL_LEGS -> when {
            availableDays >= 6 && requestedDays >= 5 -> SplitFrequencySelection(6, requestedSplit, null)
            availableDays >= 3 -> SplitFrequencySelection(
                3,
                requestedSplit,
                if (requestedDays == 3) null else
                    "Push Pull Legs was adjusted to three sessions so each split day fits with recovery."
            )
            else -> SplitFrequencySelection(
                availableDays.coerceIn(2, 4),
                WorkoutSplitPreference.FULL_BODY,
                "Push Pull Legs does not fit the available days, so a full-body schedule is used."
            )
        }
        WorkoutSplitPreference.UPPER_LOWER -> {
            val days = when {
                availableDays >= 6 && requestedDays >= 5 -> 6
                availableDays >= 4 && requestedDays >= 3 -> 4
                else -> 2
            }
            SplitFrequencySelection(
                days,
                requestedSplit,
                if (days == requestedDays) null else "Upper Lower was adjusted to $days sessions to fit the available days."
            )
        }
        WorkoutSplitPreference.FULL_BODY -> {
            val days = requestedDays.coerceAtMost(availableDays).coerceIn(2, 3)
            SplitFrequencySelection(
                days,
                requestedSplit,
                if (days == requestedDays) null else "Full-body training was adjusted to $days sessions for recovery."
            )
        }
        WorkoutSplitPreference.AUTO -> SplitFrequencySelection(
            requestedDays.coerceAtMost(availableDays).coerceIn(2, 6),
            requestedSplit,
            null
        )
    }

    private fun requestedMeals(question: String, context: CoachContext): Int {
        val normalized = question.lowercase()
        val words = mapOf(
            "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6
        )
        words.entries.firstOrNull { (word, _) ->
            Regex("\\b$word meals?\\b").containsMatchIn(normalized)
        }?.let { return it.value }
        Regex("\\b([2-6]) meals?\\b").find(normalized)
            ?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        return context.mealsPerDay ?: DEFAULT_MEALS
    }

    private fun extractDislikedFoods(limitations: Set<String>): Set<String> =
        limitations.mapNotNull { limitation ->
            val prefix = "disliked foods:"
            limitation.trim().takeIf { it.lowercase().startsWith(prefix) }
                ?.substringAfter(':')
                ?.trim()
                ?.takeIf(String::isNotBlank)
        }.flatMap { value ->
            value.split(',', ';').map(String::trim).filter(String::isNotBlank)
        }.take(MAX_DISLIKED_FOODS).toSet()

    private fun equipmentFromQuestion(question: String): Set<String> {
        val normalized = question.lowercase()
        return buildSet {
            if ("home workout" in normalized || "workout at home" in normalized || "home routine" in normalized) {
                add("Bodyweight")
            }
            if ("dumbbell" in normalized) add("Dumbbells")
            if ("barbell" in normalized) add("Barbell")
            if ("bodyweight" in normalized || "body weight" in normalized) add("Bodyweight")
            if ("band" in normalized) add("Resistance Bands")
            if ("kettlebell" in normalized) add("Kettlebells")
            if ("cable" in normalized) add("Cables")
            if ("machine" in normalized) add("Machine")
            if ("bench" in normalized) add("Bench")
            if ("stability ball" in normalized || "exercise ball" in normalized) add("Stability Ball")
        }
    }

    private fun requestedDuration(question: String): Int? {
        val normalized = question.lowercase()
        return Regex("\\b(20|30|40|45|50|60|75|90)\\s*(?:minute|min)\\b")
            .find(normalized)
            ?.groupValues?.getOrNull(1)
            ?.toIntOrNull()
    }

    private fun requestedExerciseTerms(question: String): Set<String> {
        val normalized = question.lowercase()
        return EXPLICIT_EXERCISE_TERMS.filter { term: String ->
            normalized.contains(term)
        }.toSet()
    }

    private fun workoutSplit(question: String): WorkoutSplitPreference {
        val normalized = normalizeIntentText(question)
        return when {
            "push pull legs" in normalized || Regex("\\bppl\\b").containsMatchIn(normalized) ->
                WorkoutSplitPreference.PUSH_PULL_LEGS
            "upper lower" in normalized -> WorkoutSplitPreference.UPPER_LOWER
            "full body" in normalized -> WorkoutSplitPreference.FULL_BODY
            else -> WorkoutSplitPreference.AUTO
        }
    }

    private fun goalFromQuestion(question: String): String? {
        val normalized = normalizeIntentText(question)
        return when {
            "muscle gain" in normalized || "gain muscle" in normalized || "build muscle" in normalized -> "Gain Muscle"
            "fat loss" in normalized || "weight loss" in normalized || "lose weight" in normalized -> "Fat Loss"
            "strength" in normalized || "get stronger" in normalized -> "Strength"
            "maintenance" in normalized || "maintain weight" in normalized -> "Maintain"
            else -> null
        }
    }

    private fun experienceFromQuestion(question: String): String? {
        val normalized = normalizeIntentText(question)
        return when {
            Regex("\\bbeginner\\b").containsMatchIn(normalized) -> "Beginner"
            Regex("\\bintermediate\\b").containsMatchIn(normalized) -> "Intermediate"
            Regex("\\badvanced\\b").containsMatchIn(normalized) -> "Advanced"
            else -> null
        }
    }

    companion object {
        private const val GENERAL_LIMIT = 10
        private const val DEFAULT_WORKOUT_DAYS = 3
        private const val DEFAULT_MEALS = 3
        private const val MAX_DISLIKED_FOODS = 10
        private val NUTRITION_DOMAINS = setOf(KnowledgeDomain.NUTRITION, KnowledgeDomain.PAKISTANI_FOOD)
        private val WORKOUT_DOMAINS = setOf(KnowledgeDomain.WORKOUT, KnowledgeDomain.EXERCISE)
        private const val STANDARD_SAFETY =
            "General fitness education only, not medical diagnosis or treatment. Seek qualified care for injury, disease, severe pain, or other medical concerns."
    }

    private data class WorkoutFrequencySelection(
        val programmedDays: Int,
        val explicit: Boolean,
        val splitPreference: WorkoutSplitPreference,
        val adjustmentNote: String?
    )

    private data class SplitFrequencySelection(
        val days: Int,
        val split: WorkoutSplitPreference,
        val note: String?
    )
}

private fun LocalWorkoutPlan.toGeneratedWorkoutPlan(
    goal: String?,
    experience: String?,
    profileContextUsed: Boolean
): GeneratedWorkoutPlan {
    val canonicalContent = buildString {
        append(title)
        days.forEach { day ->
            append('|').append(day.dayName).append('|').append(day.focus)
            day.exercises.forEach { exercise ->
                append('|').append(exercise.id)
                    .append('|').append(exercise.sets)
                    .append('|').append(exercise.repsOrDuration)
                    .append('|').append(exercise.restSeconds)
            }
        }
    }
    return GeneratedWorkoutPlan(
        planId = generatedPlanId("workout", canonicalContent),
        title = title,
        goal = goal?.takeIf(String::isNotBlank) ?: "General fitness",
        experienceLevel = experience?.takeIf(String::isNotBlank) ?: "Not set",
        days = days.map { day ->
            GeneratedWorkoutPlanDay(
                dayName = day.dayName,
                focus = day.focus,
                exercises = day.exercises.map { exercise ->
                    GeneratedWorkoutPlanExercise(
                        exerciseId = exercise.id,
                        name = exercise.name,
                        movementPattern = exercise.movementPattern.name,
                        sets = exercise.sets,
                        repsOrDuration = exercise.repsOrDuration,
                        restSeconds = exercise.restSeconds
                    )
                }
            )
        },
        progressionGuidance = progressionGuidance,
        recoveryGuidance = recoveryGuidance,
        safetyNote = safetyNote,
        createdAt = System.currentTimeMillis(),
        sourceType = if (fallbackUsed) {
            GeneratedPlanSource.LOCAL_FALLBACK
        } else {
            GeneratedPlanSource.LOCAL_KNOWLEDGE
        },
        profileContextUsed = profileContextUsed
    )
}

private fun LocalDietPlan.toGeneratedDietPlan(
    goal: String?,
    calorieTarget: Int?,
    proteinTargetGrams: Double?,
    carbsTargetGrams: Double?,
    fatTargetGrams: Double?,
    profileContextUsed: Boolean
): GeneratedDietPlan {
    val canonicalContent = buildString {
        append(goal.orEmpty()).append('|').append(calorieTarget)
        meals.forEach { meal ->
            append('|').append(meal.label)
                .append('|').append(meal.primary.id)
                .append('|').append(meal.portionMultiplier)
            meal.accompaniments.forEach { component ->
                append('|').append(component.food.id).append('|').append(component.portionMultiplier)
            }
        }
    }
    val normalizedGoal = goal?.takeIf(String::isNotBlank) ?: "General balanced eating"
    return GeneratedDietPlan(
        planId = generatedPlanId("diet", canonicalContent),
        title = "FitDesi Pakistani $normalizedGoal Plan",
        goal = normalizedGoal,
        calorieTarget = calorieTarget,
        proteinTargetGrams = proteinTargetGrams,
        carbsTargetGrams = carbsTargetGrams,
        fatTargetGrams = fatTargetGrams,
        days = listOf(
            GeneratedDietPlanDay(
                dayName = "Daily plan",
                meals = meals.map { meal ->
                    GeneratedDietPlanMeal(
                        label = meal.label,
                        foodRecordId = meal.primary.id,
                        foodName = meal.primary.name,
                        storedServing = meal.primary.servingSize,
                        portionMultiplier = meal.portionMultiplier,
                        estimatedCalories = meal.estimatedCalories,
                        estimatedProteinGrams = meal.estimatedProteinGrams,
                        estimatedCarbsGrams = meal.estimatedCarbsGrams,
                        estimatedFatGrams = meal.estimatedFatGrams,
                        portionDescription = meal.portionDescription,
                        additionalFoods = meal.accompaniments.map { component ->
                            GeneratedDietPlanComponent(
                                foodRecordId = component.food.id,
                                foodName = component.food.name,
                                portionDescription = component.portionDescription,
                                portionMultiplier = component.portionMultiplier,
                                estimatedCalories = component.nutrition.calories,
                                estimatedProteinGrams = component.nutrition.protein,
                                estimatedCarbsGrams = component.nutrition.carbs,
                                estimatedFatGrams = component.nutrition.fat
                            )
                        },
                        alternatives = meal.alternatives.map { alternative ->
                            GeneratedDietPlanAlternative(
                                foodRecordId = alternative.food.id,
                                foodName = alternative.food.name,
                                portionDescription = alternative.portionDescription,
                                portionMultiplier = alternative.portionMultiplier,
                                estimatedCalories = alternative.estimatedCalories,
                                estimatedProteinGrams = alternative.estimatedProteinGrams,
                                estimatedCarbsGrams = alternative.estimatedCarbsGrams,
                                estimatedFatGrams = alternative.estimatedFatGrams,
                                dietaryCompatibilityStatus = alternative.dietaryCompatibilityStatus
                            )
                        }
                    )
                }
            )
        ),
        hydrationReminder = hydrationReminder,
        disclaimer = variationDisclaimer,
        createdAt = System.currentTimeMillis(),
        sourceType = if (fallbackUsed) GeneratedPlanSource.LOCAL_FALLBACK else GeneratedPlanSource.LOCAL_KNOWLEDGE,
        profileContextUsed = profileContextUsed,
        validationStatus = validationStatus,
        validationFailures = validationFailures
    )
}

private fun CoachContext.hasProfileContext(): Boolean =
    !goal.isNullOrBlank() ||
        !experience.isNullOrBlank() ||
        equipment.isNotEmpty() ||
        calorieTarget != null ||
        proteinTargetGrams != null ||
        carbsTargetGrams != null ||
        fatTargetGrams != null ||
        !dietaryPreference.isNullOrBlank() ||
        mealsPerDay != null ||
        !activityLevel.isNullOrBlank() ||
        workoutDays != null ||
        limitations.isNotEmpty()

private fun normalizeIntentText(value: String): String =
    value.lowercase()
        .replace(Regex("[^a-z0-9]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

internal object LocalCoachIntentClassifier {
    private val redFlags = listOf(
        "chest pain",
        "fainting",
        "feel faint",
        "felt faint",
        "severe breathing difficulty",
        "difficulty breathing",
        "injury",
        "acute pain",
        "sudden severe pain",
        "severe sudden pain",
        "loss of consciousness",
        "lost consciousness",
        "emergency symptoms"
    )

    fun matchedRedFlags(question: String): List<String> {
        val normalized = normalizeIntentText(question)
        return redFlags.filter(normalized::contains)
    }

    fun classify(question: String): AiCoachIntent {
        val text = normalizeIntentText(question)
        if (matchedRedFlags(text).isNotEmpty()) return AiCoachIntent.MEDICAL_ESCALATION
        return when {
            isYogaRequest(text) -> AiCoachIntent.YOGA_PLAN
            isExerciseSpecificProgressionRequest(text) -> AiCoachIntent.EXERCISE_QUESTION
            isWorkoutProgressReviewRequest(text) -> AiCoachIntent.PROGRESS_REVIEW
            isDietPlanRequest(text) -> AiCoachIntent.PAKISTANI_DIET_PLAN
            !isInformationalRequest(text) && (
                isWorkoutPlanRequest(text) ||
                    (EQUIPMENT_TERMS.any(text::contains) &&
                        ("substitution" in text || "substitutions" in text || "substitute" in text)) ||
                    Regex("\\b(?:[2-6]|two|three|four|five|six)(?:[- ]day| days?)?\\s+workout\\b")
                        .containsMatchIn(text) ||
                    (("workout" in text || "training" in text || "routine" in text) && (
                        EQUIPMENT_TERMS.any(text::contains) ||
                            "according to my profile" in text ||
                            "based on my profile" in text ||
                            "substitution" in text ||
                            EXPLICIT_EXERCISE_TERMS.any { term: String ->
                                text.contains(term)
                            }
                        ))
                ) ->
                AiCoachIntent.WORKOUT_PLAN
            FOOD_LOOKUP_TERMS.any(text::contains) -> AiCoachIntent.FOOD_QUESTION
            "food" in text || "protein" in text || "diet" in text || "meal" in text ->
                AiCoachIntent.FOOD_QUESTION
            EXERCISE_QUESTION_TERMS.any(text::contains) ->
                AiCoachIntent.EXERCISE_QUESTION
            text.isBlank() -> AiCoachIntent.UNSUPPORTED
            GENERAL_FITNESS_TERMS.any(text::contains) -> AiCoachIntent.GENERAL_COACHING
            else -> AiCoachIntent.UNSUPPORTED
        }
    }

    private fun isExerciseSpecificProgressionRequest(text: String): Boolean =
        EXERCISE_PROGRESSION_TERMS.any(text::contains) &&
            EXERCISE_QUESTION_TERMS.any(text::contains)

    private fun isYogaRequest(text: String): Boolean =
        text.containsIntentPhrase("yoga") ||
            text.containsIntentPhrase("mobility plan") ||
            text.containsIntentPhrase("stretching plan")

    private fun isWorkoutProgressReviewRequest(text: String): Boolean {
        if (DIET_TOPICS.any { text.containsIntentPhrase(it) }) return false
        val hasWorkoutSubject = WORKOUT_DISCUSSION_TERMS.any { text.containsIntentPhrase(it) }
        return "how am i doing" in text ||
            (hasWorkoutSubject && (
                REVIEW_PATTERN.containsMatchIn(text) ||
                    "missed" in text ||
                    "too easy" in text ||
                    "too hard" in text ||
                    Regex("\\bprogress\\b").containsMatchIn(text)
                ))
    }

    private fun isDietPlanRequest(text: String): Boolean {
        if (isExistingPlanDiscussion(text) ||
            isInformationalRequest(text) ||
            FOOD_LOOKUP_TERMS.any(text::contains)
        ) return false
        if (ROMAN_URDU_DIET_PLAN_TERMS.any { text.containsIntentPhrase(it) } ||
            text.containsIntentPhrase("pakistani diet for me")
        ) return true
        val hasPlanObject = DIET_PLAN_TERMS.any { text.containsIntentPhrase(it) } ||
            DIET_PLAN_OBJECTS.any { text.containsIntentPhrase(it) }
        val hasDietTopic = DIET_TOPICS.any { text.containsIntentPhrase(it) }
        val hasStrongCreationLanguage = hasStrongPlanCreationLanguage(text)
        val hasWeakCreationLanguage = hasWeakPlanCreationLanguage(text)
        return (hasStrongCreationLanguage && (hasPlanObject || hasDietTopic)) ||
            (hasWeakCreationLanguage && hasPlanObject) ||
            (PROFILE_PLAN_TERMS.any { text.containsIntentPhrase(it) } && (hasPlanObject || hasDietTopic))
    }

    private fun isWorkoutPlanRequest(text: String): Boolean {
        if (isExistingPlanDiscussion(text) || isInformationalRequest(text)) return false
        if (ROMAN_URDU_WORKOUT_PLAN_TERMS.any { text.containsIntentPhrase(it) }) return true
        val hasPlanObject = WORKOUT_PLAN_TERMS.any { text.containsIntentPhrase(it) } ||
            WORKOUT_PLAN_OBJECTS.any { text.containsIntentPhrase(it) }
        val hasWorkoutTopic = WORKOUT_TOPICS.any { text.containsIntentPhrase(it) }
        val hasStrongCreationLanguage = hasStrongPlanCreationLanguage(text)
        val hasWeakCreationLanguage = hasWeakPlanCreationLanguage(text)
        val hasFrequency = WORKOUT_FREQUENCY_PATTERN.containsMatchIn(text)
        val hasSpecificWorkoutObject = SPECIFIC_WORKOUT_PLAN_OBJECTS.any { text.containsIntentPhrase(it) }
        return EXPLICIT_WORKOUT_REQUEST_TERMS.any { text.containsIntentPhrase(it) } ||
            (hasStrongCreationLanguage && (
                hasWorkoutTopic ||
                    hasPlanObject ||
                    hasSpecificWorkoutObject
                )) ||
            (hasWeakCreationLanguage && (hasPlanObject || (hasFrequency && hasWorkoutTopic))) ||
            (hasWorkoutTopic && PROFILE_PLAN_TERMS.any { text.containsIntentPhrase(it) })
    }

    private fun hasStrongPlanCreationLanguage(text: String): Boolean =
        PLAN_CREATION_WORD_PATTERN.containsMatchIn(text) ||
            STRONG_PLAN_CREATION_PHRASES.any { text.containsIntentPhrase(it) } ||
            PLAN_VERB_REQUEST_PATTERN.containsMatchIn(text)

    private fun hasWeakPlanCreationLanguage(text: String): Boolean =
        WEAK_PLAN_CREATION_PHRASES.any { text.containsIntentPhrase(it) }

    private fun isInformationalRequest(text: String): Boolean =
        INFORMATIONAL_REQUEST_PHRASES.any { text.containsIntentPhrase(it) }

    private fun String.containsIntentPhrase(phrase: String): Boolean =
        " $this ".contains(" $phrase ")

    private fun isExistingPlanDiscussion(text: String): Boolean {
        val referencesExisting = EXISTING_PLAN_REFERENCES.any { reference ->
            Regex("\\b$reference\\b").containsMatchIn(text)
        }
        return REVIEW_PATTERN.containsMatchIn(text) ||
            (referencesExisting && (
                "missed" in text ||
                "too easy" in text ||
                "too hard" in text ||
                "is my" in text && PLAN_QUALITY_TERMS.any { text.containsIntentPhrase(it) }
                ))
    }

    private val GENERAL_FITNESS_TERMS = listOf(
        "fitness",
        "muscle",
        "fat loss",
        "weight loss",
        "strength",
        "endurance",
        "training",
        "recovery",
        "calorie",
        "nutrition",
        "active",
        "health"
    )

    private val EXERCISE_PROGRESSION_TERMS = listOf(
        "progress",
        "progression",
        "regress",
        "regression"
    )

    private val EXERCISE_QUESTION_TERMS = listOf(
        "exercise",
        "squat",
        "bench",
        "deadlift",
        "push up",
        "pushup"
    )

    private val EQUIPMENT_TERMS = listOf(
        "dumbbell",
        "barbell",
        "bodyweight",
        "body weight",
        "resistance band",
        "kettlebell",
        "cable",
        "machine"
    )

    private val DIET_PLAN_TERMS = listOf(
        "diet plan",
        "meal plan",
        "eating plan",
        "weekly diet",
        "daily meal schedule",
        "weekly meal schedule",
        "meals according to my profile",
        "meals based on my profile",
        "eggitarian plan",
        "eggetarian plan",
        "calorie target plan",
        "pakistani diet",
        "daily meals",
        "daily meal plan",
        "gain-muscle diet",
        "gain muscle diet",
        "fat loss diet"
    )

    private val ROMAN_URDU_DIET_PLAN_TERMS = listOf(
        "profile ke mutabiq diet",
        "diet plan banao",
        "meal plan banao",
        "khane ka plan",
        "mere liye diet",
        "diet bana do"
    )

    private val WORKOUT_PLAN_TERMS = listOf(
        "workout plan",
        "training plan",
        "workout routine",
        "training routine",
        "gym plan",
        "exercise plan",
        "routine for me",
        "weekly workout schedule",
        "push pull legs",
        "ppl plan",
        "upper lower plan",
        "full body plan",
        "home workout plan",
        "workout according to my profile",
        "workout based on my profile"
    )

    private val ROMAN_URDU_WORKOUT_PLAN_TERMS = listOf(
        "workout plan banao",
        "training plan banao",
        "workout routine banao",
        "mere profile ke mutabiq workout",
        "gym plan bana do"
    )

    private val PLAN_CREATION_WORD_PATTERN =
        Regex("\\b(?:create|build|make|generate|design|prepare|banao)\\b")

    private val STRONG_PLAN_CREATION_PHRASES = listOf(
        "give me",
        "set up",
        "put together",
        "bana do"
    )

    private val WEAK_PLAN_CREATION_PHRASES = listOf("i need", "i want")

    private val INFORMATIONAL_REQUEST_PHRASES = listOf(
        "what is",
        "what are",
        "how does",
        "explain",
        "tell me about",
        "information about",
        "advice on",
        "advice about",
        "tips for"
    )

    private val PLAN_VERB_REQUEST_PATTERN = Regex(
        "\\b(?:plan (?:my|me|a|an|this|the|workouts?|training|meals?|diet|food|menu)|" +
            "(?:can|could|would|will) you plan)\\b"
    )

    private val WORKOUT_FREQUENCY_PATTERN =
        Regex("\\b(?:[2-6]|two|three|four|five|six)(?:[- ]day| days?)\\b")
    private val REVIEW_PATTERN = Regex("\\breview\\b")

    private val DIET_PLAN_OBJECTS = listOf(
        "diet plan",
        "meal plan",
        "eating plan",
        "weekly diet",
        "daily meals",
        "meal schedule",
        "nutrition plan",
        "eating program",
        "food plan",
        "daily menu",
        "weekly meals",
        "weekly eating schedule",
        "khane ka plan"
    )

    private val DIET_TOPICS = listOf(
        "diet",
        "meal",
        "meals",
        "nutrition",
        "eating",
        "menu",
        "khana",
        "khane"
    )

    private val FOOD_LOOKUP_TERMS = listOf(
        "calories in",
        "calorie in",
        "protein in",
        "nutrition of",
        "nutrients in",
        "how much protein",
        "how many calories",
        "which pakistani foods",
        "is nihari healthy",
        "food alternatives"
    )

    private val WORKOUT_PLAN_OBJECTS = listOf(
        "workout plan",
        "training plan",
        "exercise plan",
        "workout routine",
        "training routine",
        "routine for me",
        "workout schedule",
        "program",
        "programme",
        "routine",
        "split",
        "schedule",
        "weekly plan",
        "home program",
        "gym program",
        "push pull legs",
        "ppl plan",
        "upper lower plan",
        "full body plan"
    )

    private val WORKOUT_TOPICS = listOf(
        "workout",
        "workouts",
        "training",
        "lifting",
        "exercise",
        "routine",
        "gym",
        "strength",
        "muscle building"
    )
    private val WORKOUT_DISCUSSION_TERMS = listOf("workout", "workouts", "training", "routine", "plan", "session", "sessions")
    private val EXPLICIT_WORKOUT_REQUEST_TERMS = listOf(
        "routine for me"
    )
    private val SPECIFIC_WORKOUT_PLAN_OBJECTS = listOf("home program", "home programme")
    private val EXISTING_PLAN_REFERENCES = listOf("my", "current", "existing")
    private val PLAN_QUALITY_TERMS = listOf("good", "okay", "ok", "safe", "effective")
    private val PROFILE_PLAN_TERMS = listOf("according to my profile", "based on my profile", "for my profile")
}
