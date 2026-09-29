package com.example.ai

import com.example.BuildConfig
import com.example.ai.backend.AiBackendClient
import com.example.ai.backend.AiBackendService
import com.example.ai.backend.BackendGeneratedDietPlanDto
import com.example.ai.backend.BackendGeneratedWorkoutPlanDto
import com.example.ai.backend.BackendExecutionMode
import com.example.ai.backend.BackendPublicError
import com.example.ai.backend.BackendResult
import com.example.ai.backend.CoachRequestDto
import com.example.ai.backend.CoachConversationContextDto
import com.example.ai.backend.CoachRemoteClient
import com.example.ai.backend.CoachResponseDto
import com.example.ai.backend.HealthResponseDto
import com.example.ai.knowledge.ExercisePackRecord
import com.example.ai.knowledge.KnowledgePackRegistry
import com.example.ai.knowledge.LocalCoachContextStore
import com.example.security.BuildWeekRuntimeConfig
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class AiRuntimeMode {
    LOCAL,
    BACKEND_MOCK,
    BACKEND_REMOTE
}

enum class AiCoachExecutionState {
    LOCAL_SUCCESS,
    BACKEND_MOCK_SUCCESS,
    REMOTE_SUCCESS,
    LOCAL_FALLBACK,
    TIMEOUT,
    RATE_LIMITED,
    BACKEND_UNAVAILABLE,
    INVALID_RESPONSE,
    VALIDATION_FAILURE,
    UNKNOWN_ERROR
}

data class AiCoachOutcome(
    val response: AiCoachResponse?,
    val state: AiCoachExecutionState,
    val backendFailureState: AiCoachExecutionState? = null,
    val backendError: BackendPublicError? = null,
    val retryAfterSeconds: Long? = null,
    val message: String? = null
)

interface AiWorkoutProvider {
    suspend fun generate(request: AiWorkoutRequest): Result<GeneratedRoutine>
}

object AiRuntimeConfig {
    val mode: AiRuntimeMode
        get() {
            if (!BuildWeekRuntimeConfig.aiProxyEnabled) return AiRuntimeMode.LOCAL
            return runCatching { AiRuntimeMode.valueOf(BuildConfig.AI_RUNTIME_MODE) }
                .getOrDefault(AiRuntimeMode.LOCAL)
        }
}

/** Provider-neutral Coach boundary. Remote execution remains gated by reviewed build config. */
class AiRepository(
    private val mode: AiRuntimeMode = AiRuntimeConfig.mode,
    private val backendService: AiBackendService? = null,
    private val coachClient: CoachRemoteClient? = null,
    private val localCoach: AiCoachRepository = LocalAiCoachProvider,
    private val localWorkout: AiWorkoutProvider = LocalAiWorkoutProvider,
    private val backendExecutionAllowed: Boolean = BuildWeekRuntimeConfig.aiProxyEnabled,
    private val exerciseCatalogProvider: () -> List<ExercisePackRecord> =
        KnowledgePackRegistry::verifiedExercises,
    private val clock: () -> Long = System::currentTimeMillis,
    private val workoutGenerationDispatcher: CoroutineDispatcher = Dispatchers.Default
) {
    suspend fun checkBackendHealth(): BackendResult<HealthResponseDto> =
        if (mode == AiRuntimeMode.LOCAL || !backendExecutionAllowed) {
            BackendResult.BackendUnavailable("The secure backend is disabled in this build.")
        } else {
            backend().health()
        }

    suspend fun askCoach(request: AiCoachRequest): Result<AiCoachResponse> {
        val outcome = askCoachWithStatus(request)
        return outcome.response?.let { Result.success(it) }
            ?: Result.failure(IllegalStateException(outcome.message ?: "The Coach request failed."))
    }

    suspend fun askCoachWithStatus(request: AiCoachRequest): AiCoachOutcome {
        if (mode == AiRuntimeMode.LOCAL || !backendExecutionAllowed) {
            return localOutcome(request, fallback = mode != AiRuntimeMode.LOCAL)
        }

        val context = LocalCoachContextStore.merge(request.coachContext())
        val backendResult = remoteCoach().submit(request.toDto(context))
        if (backendResult is BackendResult.Success) {
            val acceptedMode = when (mode) {
                AiRuntimeMode.LOCAL -> false
                AiRuntimeMode.BACKEND_MOCK -> backendResult.mode == BackendExecutionMode.MOCK
                AiRuntimeMode.BACKEND_REMOTE -> true
            }
            if (acceptedMode) {
                val mapped = mapCoachResponse(
                    dto = backendResult.value,
                    envelopeMode = backendResult.mode,
                    requestId = backendResult.requestId,
                    context = context
                )
                if (mapped != null) {
                    return AiCoachOutcome(
                        response = mapped,
                        state = if (backendResult.mode == BackendExecutionMode.REMOTE) {
                            AiCoachExecutionState.REMOTE_SUCCESS
                        } else {
                            AiCoachExecutionState.BACKEND_MOCK_SUCCESS
                        }
                    )
                }
            }
            return localOutcome(
                request,
                fallback = true,
                failureState = AiCoachExecutionState.INVALID_RESPONSE
            )
        }

        val failureState = when (backendResult) {
            is BackendResult.RateLimited -> AiCoachExecutionState.RATE_LIMITED
            is BackendResult.Timeout -> AiCoachExecutionState.TIMEOUT
            is BackendResult.BackendUnavailable -> AiCoachExecutionState.BACKEND_UNAVAILABLE
            is BackendResult.InvalidResponse -> AiCoachExecutionState.INVALID_RESPONSE
            is BackendResult.ValidationError,
            is BackendResult.PayloadTooLarge -> AiCoachExecutionState.VALIDATION_FAILURE
            is BackendResult.PublicError -> backendResult.code.toExecutionState()
            else -> AiCoachExecutionState.UNKNOWN_ERROR
        }
        val publicError = when (backendResult) {
            is BackendResult.PublicError -> backendResult.code
            is BackendResult.RateLimited -> BackendPublicError.RATE_LIMITED
            is BackendResult.Timeout -> BackendPublicError.REQUEST_TIMEOUT
            is BackendResult.BackendUnavailable -> BackendPublicError.REMOTE_UNAVAILABLE
            is BackendResult.InvalidResponse -> BackendPublicError.INVALID_RESPONSE
            is BackendResult.ValidationError -> BackendPublicError.VALIDATION_ERROR
            is BackendResult.PayloadTooLarge -> BackendPublicError.PAYLOAD_TOO_LARGE
            else -> BackendPublicError.REMOTE_UNAVAILABLE
        }
        return localOutcome(
            request = request,
            fallback = true,
            failureState = failureState,
            backendError = publicError,
            retryAfterSeconds = when (backendResult) {
                is BackendResult.RateLimited -> backendResult.retryAfterSeconds
                is BackendResult.PublicError -> backendResult.retryAfterSeconds
                else -> null
            }
        )
    }

    suspend fun generateWorkout(request: AiWorkoutRequest): Result<GeneratedRoutine> =
        withContext(workoutGenerationDispatcher) {
            localWorkout.generate(request)
        }

    private suspend fun localOutcome(
        request: AiCoachRequest,
        fallback: Boolean,
        failureState: AiCoachExecutionState? = null,
        backendError: BackendPublicError? = null,
        retryAfterSeconds: Long? = null
    ): AiCoachOutcome {
        return localCoach.ask(request.question, request.coachContext()).fold(
            onSuccess = { response ->
                AiCoachOutcome(
                    response = response.copy(
                        metadata = response.metadata.copy(
                            sourceType = if (fallback) "LOCAL_FALLBACK" else response.metadata.sourceType,
                            fallbackUsed = fallback
                        )
                    ),
                    state = if (fallback) AiCoachExecutionState.LOCAL_FALLBACK else {
                        AiCoachExecutionState.LOCAL_SUCCESS
                    },
                    backendFailureState = failureState,
                    backendError = backendError,
                    retryAfterSeconds = retryAfterSeconds,
                    message = failureState?.friendlyMessage(retryAfterSeconds)
                )
            },
            onFailure = {
                AiCoachOutcome(
                    response = null,
                    state = failureState ?: AiCoachExecutionState.UNKNOWN_ERROR,
                    backendFailureState = failureState,
                    backendError = backendError,
                    retryAfterSeconds = retryAfterSeconds,
                    message = failureState?.friendlyMessage(retryAfterSeconds)
                        ?: "The local Coach could not prepare a response. Please try again."
                )
            }
        )
    }

    private fun mapCoachResponse(
        dto: CoachResponseDto,
        envelopeMode: BackendExecutionMode,
        requestId: String,
        context: com.example.ai.knowledge.CoachContext
    ): AiCoachResponse? {
        val source = when (envelopeMode) {
            BackendExecutionMode.MOCK -> "BACKEND_MOCK"
            BackendExecutionMode.REMOTE -> "REMOTE"
        }
        val compatibleSource = when (envelopeMode) {
            BackendExecutionMode.MOCK -> "BACKEND_MOCK"
            BackendExecutionMode.REMOTE -> LEGACY_REMOTE_SOURCE
        }
        if (dto.sourceType != null && dto.sourceType != compatibleSource) return null
        if (dto.requestId != null && dto.requestId != requestId) return null
        val hasStructuredPlan = dto.generatedWorkoutPlan != null || dto.generatedDietPlan != null
        if (dto.validationStatus != null && dto.validationStatus != "VALIDATED") return null
        if (hasStructuredPlan && dto.validationStatus != "VALIDATED") return null
        if (envelopeMode != BackendExecutionMode.REMOTE && hasStructuredPlan) return null
        if (dto.generatedWorkoutPlan != null && dto.generatedDietPlan != null) return null

        val workoutPlan = dto.generatedWorkoutPlan?.let {
            mapWorkoutPlan(it, context, dto.profileContextUsed)
        } ?: if (dto.generatedWorkoutPlan != null) return null else null
        val dietPlan = dto.generatedDietPlan?.let {
            mapDietPlan(it, dto.profileContextUsed)
        } ?: if (dto.generatedDietPlan != null) return null else null

        return AiCoachResponse(
            summary = dto.summary,
            recommendedAction = dto.recommendedAction,
            nutritionNote = dto.nutritionNote,
            workoutNote = dto.workoutNote,
            safetyDisclaimer = dto.safetyDisclaimer,
            metadata = AiCoachResponseMetadata(
                sourceType = source,
                knowledgeRecordIds = dto.knowledgeRecordIds.take(MAX_KNOWLEDGE_IDS),
                fallbackUsed = dto.fallbackUsed,
                profileContextUsed = dto.profileContextUsed,
                recentWorkoutContextUsed = dto.recentWorkoutContextUsed,
                intent = dto.detectedIntent.toIntent(),
                confidence = dto.confidence ?: 0.0,
                requestId = requestId,
                backendMode = when (envelopeMode) {
                    BackendExecutionMode.MOCK -> "mock"
                    BackendExecutionMode.REMOTE -> "remote"
                },
                warnings = dto.warnings.take(MAX_WARNINGS),
                escalationRequired = dto.escalationRequired,
                detectedGoal = dto.detectedGoal,
                validationStatus = dto.validationStatus,
                requestedWorkoutDays = dto.generatedWorkoutPlan?.requestedDays,
                generatedWorkoutDays = dto.generatedWorkoutPlan?.generatedDays?.size,
                calorieTargetSource = dto.generatedDietPlan?.calorieTargetSource,
                macroTargetSource = dto.generatedDietPlan?.macroTargetSource
            ),
            workoutPlan = workoutPlan,
            dietPlan = dietPlan
        )
    }

    private fun mapWorkoutPlan(
        dto: BackendGeneratedWorkoutPlanDto,
        context: com.example.ai.knowledge.CoachContext,
        profileContextUsed: Boolean
    ): GeneratedWorkoutPlan? {
        val catalogue = exerciseCatalogProvider().associateBy(ExercisePackRecord::id)
        if (dto.requestedDays !in 2..6 || dto.generatedDays.size != dto.requestedDays) return null
        if (dto.generatedDays.any { it.exercises.isEmpty() || it.exercises.size > 10 }) return null
        if (dto.generatedDays.map { it.dayName.lowercase() }.distinct().size != dto.generatedDays.size) {
            return null
        }
        val days = dto.generatedDays.map { day ->
            GeneratedWorkoutPlanDay(
                dayName = day.dayName,
                focus = day.focus,
                exercises = day.exercises.map { exercise ->
                    val known = catalogue[exercise.exerciseId] ?: return null
                    if (exercise.name != known.name ||
                        exercise.role.lowercase() !in WORKOUT_EXERCISE_ROLES
                    ) return null
                    if (!known.supports(context.equipment)) return null
                    GeneratedWorkoutPlanExercise(
                        exerciseId = exercise.exerciseId,
                        name = exercise.name,
                        movementPattern = known.movementPattern,
                        sets = exercise.sets,
                        repsOrDuration = exercise.repsOrDuration,
                        restSeconds = exercise.restSeconds
                    )
                }
            )
        }
        if (dto.generatedDays.any { day ->
                day.exercises.all { it.role.equals("warmup", ignoreCase = true) }
            }
        ) return null
        return GeneratedWorkoutPlan(
            planId = dto.planId,
            title = dto.title,
            goal = dto.goal,
            experienceLevel = context.experience.orEmpty(),
            days = days,
            progressionGuidance = dto.progressionGuidance,
            recoveryGuidance = "",
            safetyNote = dto.safetyNote,
            createdAt = clock(),
            sourceType = GeneratedPlanSource.REMOTE,
            profileContextUsed = profileContextUsed
        ).takeIf(GeneratedWorkoutPlan::isValid)
    }

    private fun mapDietPlan(
        dto: BackendGeneratedDietPlanDto,
        profileContextUsed: Boolean
    ): GeneratedDietPlan? {
        if (dto.calorieTarget != null && dto.calorieTarget !in 1_200..6_000) return null
        if (dto.calorieTargetSource !in TARGET_SOURCES || dto.macroTargetSource !in TARGET_SOURCES) {
            return null
        }
        if (dto.meals.size !in 2..6 || dto.meals.any { it.foods.isEmpty() || it.foods.size > 6 }) {
            return null
        }
        if (dto.meals.flatMap { it.foods }.any { food ->
                val nutrition = listOf(
                    food.calories, food.proteinGrams, food.carbsGrams, food.fatGrams
                )
                food.nutritionSource !in NUTRITION_SOURCES ||
                    (food.nutritionSource == "unavailable" && nutrition.any { it != null }) ||
                    (food.foodRecordId == null && (
                        food.nutritionSource != "unavailable" || nutrition.any { it != null }
                    )) ||
                    (food.foodRecordId?.isBlank() == true)
            }
        ) {
            return null
        }
        val meals = dto.meals.map { meal ->
            val primary = meal.foods.first()
            GeneratedDietPlanMeal(
                label = meal.name,
                foodRecordId = primary.foodRecordId.orEmpty(),
                foodName = primary.name,
                storedServing = primary.portion,
                portionMultiplier = 1.0,
                estimatedCalories = primary.calories?.roundToInt(),
                estimatedProteinGrams = primary.proteinGrams,
                estimatedCarbsGrams = primary.carbsGrams,
                estimatedFatGrams = primary.fatGrams,
                alternatives = dto.alternatives.map {
                    GeneratedDietPlanAlternative(foodRecordId = "", foodName = it)
                },
                portionDescription = primary.portion,
                additionalFoods = meal.foods.drop(1).map { food ->
                    GeneratedDietPlanComponent(
                        foodRecordId = food.foodRecordId.orEmpty(),
                        foodName = food.name,
                        portionDescription = food.portion,
                        portionMultiplier = 1.0,
                        estimatedCalories = food.calories?.roundToInt(),
                        estimatedProteinGrams = food.proteinGrams,
                        estimatedCarbsGrams = food.carbsGrams,
                        estimatedFatGrams = food.fatGrams
                    )
                }
            )
        }
        val plan = GeneratedDietPlan(
            planId = dto.planId,
            title = dto.title,
            goal = dto.goal,
            calorieTarget = dto.calorieTarget,
            proteinTargetGrams = dto.macroTargets?.proteinGrams,
            carbsTargetGrams = dto.macroTargets?.carbsGrams,
            fatTargetGrams = dto.macroTargets?.fatGrams,
            days = listOf(GeneratedDietPlanDay("Plan", meals)),
            hydrationReminder = "",
            disclaimer = dto.disclaimer,
            createdAt = clock(),
            sourceType = GeneratedPlanSource.REMOTE,
            profileContextUsed = profileContextUsed
        )
        return plan.takeIf(GeneratedDietPlan::isValid)
    }

    private fun AiCoachRequest.toDto(context: com.example.ai.knowledge.CoachContext) = CoachRequestDto(
        question = question.trim().take(MAX_QUESTION_CHARS),
        goal = context.goal?.take(MAX_SHORT_CONTEXT_CHARS),
        experience = context.experience?.take(MAX_SHORT_CONTEXT_CHARS),
        equipment = context.equipment.map(String::trim).filter(String::isNotBlank).take(MAX_EQUIPMENT),
        recentWorkoutSummary = context.recentWorkoutSummary?.take(MAX_RECENT_SUMMARY_CHARS),
        calorieTarget = context.calorieTarget,
        proteinTargetGrams = context.proteinTargetGrams,
        carbsTargetGrams = context.carbsTargetGrams,
        fatTargetGrams = context.fatTargetGrams,
        dietaryPreference = context.dietaryPreference?.take(MAX_SHORT_CONTEXT_CHARS),
        mealsPerDay = context.mealsPerDay?.takeIf { it in 2..6 },
        workoutDays = context.workoutDays?.takeIf { it in 2..6 },
        limitations = context.limitations
            .map(String::trim)
            .filter(String::isNotBlank)
            .take(MAX_LIMITATIONS)
            .map { it.take(MAX_LIMITATION_CHARS) },
        conversationContext = this.conversationContext.toBackendContext()
    )

    private fun List<AiCoachConversationTurn>.toBackendContext(): List<CoachConversationContextDto> {
        var remaining = MAX_CONVERSATION_CONTEXT_AGGREGATE
        val reversed = mutableListOf<CoachConversationContextDto>()
        takeLast(MAX_CONVERSATION_CONTEXT_MESSAGES).asReversed().forEach { turn ->
            if (remaining <= 0) return@forEach
            val text = turn.text.take(MAX_CONVERSATION_CONTEXT_TEXT).take(remaining).trim()
            if (text.isNotEmpty()) {
                reversed += CoachConversationContextDto(
                    role = turn.role.name.lowercase(),
                    text = text
                )
                remaining -= text.length
            }
        }
        return reversed.asReversed()
    }

    private fun String?.toIntent(): AiCoachIntent = this?.let { value ->
        AiCoachIntent.entries.firstOrNull { it.name == value.uppercase() }
    } ?: AiCoachIntent.GENERAL_COACHING

    private fun ExercisePackRecord.supports(availableEquipment: Set<String>): Boolean {
        if (availableEquipment.isEmpty() || equipment.isEmpty()) return true
        val available = availableEquipment.map { it.normalizedEquipment() }.toSet()
        val required = equipment.map { it.normalizedEquipment() }
            .filterNot { it in setOf("bodyweight", "none") }
        return required.isEmpty() || required.any(available::contains)
    }

    private fun String.normalizedEquipment(): String = lowercase()
        .replace(" ", "")
        .removeSuffix("s")

    private fun AiCoachExecutionState.friendlyMessage(retryAfterSeconds: Long?): String = when (this) {
        AiCoachExecutionState.TIMEOUT -> "The secure backend timed out. A local response was used."
        AiCoachExecutionState.RATE_LIMITED -> retryAfterSeconds?.let {
            "The secure backend is busy. A local response was used; retry in about $it seconds."
        } ?: "The secure backend is busy. A local response was used."
        AiCoachExecutionState.BACKEND_UNAVAILABLE -> "The secure backend is unavailable. A local response was used."
        AiCoachExecutionState.INVALID_RESPONSE,
        AiCoachExecutionState.VALIDATION_FAILURE -> "The backend response was not safe to use. A local response was used."
        else -> "A local response was used."
    }

    private fun backend(): AiBackendService = backendService ?: AiBackendClient.service

    private fun remoteCoach(): CoachRemoteClient = coachClient
        ?: (backendService as? CoachRemoteClient)
        ?: CoachRemoteClient { BackendResult.PublicError(BackendPublicError.REMOTE_UNAVAILABLE) }

    private fun BackendPublicError.toExecutionState(): AiCoachExecutionState = when (this) {
        BackendPublicError.RATE_LIMITED,
        BackendPublicError.PROVIDER_RATE_LIMITED,
        BackendPublicError.PROVIDER_BUSY,
        BackendPublicError.REMOTE_ACCOUNT_QUOTA_EXHAUSTED -> AiCoachExecutionState.RATE_LIMITED
        BackendPublicError.REQUEST_TIMEOUT,
        BackendPublicError.PROVIDER_TIMEOUT -> AiCoachExecutionState.TIMEOUT
        BackendPublicError.INVALID_RESPONSE,
        BackendPublicError.PROVIDER_INVALID_RESPONSE -> AiCoachExecutionState.INVALID_RESPONSE
        BackendPublicError.VALIDATION_ERROR,
        BackendPublicError.PAYLOAD_TOO_LARGE,
        BackendPublicError.IDEMPOTENCY_REQUIRED,
        BackendPublicError.IDEMPOTENCY_INVALID -> AiCoachExecutionState.VALIDATION_FAILURE
        BackendPublicError.AUTH_REQUIRED,
        BackendPublicError.INVALID_SESSION,
        BackendPublicError.AUTH_VERIFICATION_UNAVAILABLE,
        BackendPublicError.EMAIL_VERIFICATION_REQUIRED,
        BackendPublicError.REMOTE_AI_CONSENT_REQUIRED,
        BackendPublicError.CONSENT_NOTICE_VERSION_MISMATCH,
        BackendPublicError.CONSENT_AUTHORITY_UNAVAILABLE,
        BackendPublicError.REMOTE_CAPABILITY_UNAVAILABLE,
        BackendPublicError.REMOTE_CAPABILITY_NOT_LAUNCHED,
        BackendPublicError.QUOTA_POLICY_UNAVAILABLE,
        BackendPublicError.REMOTE_GLOBAL_BUDGET_UNAVAILABLE,
        BackendPublicError.IDEMPOTENCY_CONFLICT,
        BackendPublicError.REMOTE_REQUEST_IN_PROGRESS,
        BackendPublicError.REMOTE_REQUEST_COMPLETED,
        BackendPublicError.REMOTE_ADMISSION_UNAVAILABLE,
        BackendPublicError.PROVIDER_UNAVAILABLE,
        BackendPublicError.COMMERCIAL_AUTHORITY_UNAVAILABLE,
        BackendPublicError.INTERNAL_ERROR,
        BackendPublicError.REMOTE_UNAVAILABLE -> AiCoachExecutionState.BACKEND_UNAVAILABLE
    }

    private companion object {
        const val LEGACY_REMOTE_SOURCE = "FIREWORKS"
        const val MAX_QUESTION_CHARS = 1_000
        const val MAX_SHORT_CONTEXT_CHARS = 80
        const val MAX_RECENT_SUMMARY_CHARS = 500
        const val MAX_CONVERSATION_CONTEXT_MESSAGES = 6
        const val MAX_CONVERSATION_CONTEXT_TEXT = 1_000
        const val MAX_CONVERSATION_CONTEXT_AGGREGATE = 4_000
        const val MAX_EQUIPMENT = 12
        const val MAX_LIMITATIONS = 10
        const val MAX_LIMITATION_CHARS = 120
        const val MAX_WARNINGS = 10
        const val MAX_KNOWLEDGE_IDS = 30
        val TARGET_SOURCES = setOf("profile", "user_request", "unavailable")
        val NUTRITION_SOURCES = setOf("existing_record", "estimated", "unavailable")
        val WORKOUT_EXERCISE_ROLES = setOf("primary", "accessory", "warmup")
    }
}
