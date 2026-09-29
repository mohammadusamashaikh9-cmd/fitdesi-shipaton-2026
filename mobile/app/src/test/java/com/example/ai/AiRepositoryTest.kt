package com.example.ai

import com.example.ai.backend.AiBackendService
import com.example.ai.backend.BackendResult
import com.example.ai.backend.BackendExecutionMode
import com.example.ai.backend.BackendPublicError
import com.example.ai.backend.BackendSessionResult
import com.example.ai.backend.CoachRequestDto
import com.example.ai.backend.CoachConversationContextDto
import com.example.ai.backend.CoachResponseDto
import com.example.ai.backend.CoachRemoteClient
import com.example.ai.backend.BackendGeneratedWorkoutPlanDto
import com.example.ai.backend.BackendGeneratedWorkoutDayDto
import com.example.ai.backend.BackendGeneratedWorkoutExerciseDto
import com.example.ai.backend.BackendGeneratedDietPlanDto
import com.example.ai.backend.BackendGeneratedFoodDto
import com.example.ai.backend.BackendGeneratedMealDto
import com.example.ai.backend.BackendMacroTargetsDto
import com.example.ai.knowledge.ExercisePackRecord
import com.example.ai.knowledge.CoachContext
import com.example.ai.knowledge.LocalCoachContextStore
import com.example.ai.knowledge.LicenseStatus
import com.example.ai.knowledge.RedistributionStatus
import com.example.ai.knowledge.SourceMetadata
import com.example.ai.backend.DietPlanRequestDto
import com.example.ai.backend.DietPlanResponseDto
import com.example.ai.backend.FoodAnalyzeRequestDto
import com.example.ai.backend.FoodAnalyzeResponseDto
import com.example.ai.backend.HealthResponseDto
import com.example.ai.backend.ProgressReviewRequestDto
import com.example.ai.backend.ProgressReviewResponseDto
import com.example.ai.backend.WorkoutPlanRequestDto
import com.example.ai.backend.WorkoutPlanResponseDto
import com.example.ai.backend.YogaPlanRequestDto
import com.example.ai.backend.YogaPlanResponseDto
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AiRepositoryTest {
    private val workoutRequest = AiWorkoutRequest(
        generatorType = "Weekly Routine",
        gender = "Male",
        age = 30,
        level = "Intermediate",
        goal = "Gain Muscle",
        daysCount = 3,
        selectedDays = listOf("Mon", "Wed", "Fri"),
        split = "Full Body",
        enforceRecovery = true,
        equipment = listOf("Bodyweight")
    )

    @Test
    fun localMode_routesBothFlagshipFeaturesToOfflineProviders() = runTest {
        val backend = FakeBackendService()
        val repository = AiRepository(
            mode = AiRuntimeMode.LOCAL,
            backendService = backend,
            localWorkout = deterministicWorkoutProvider()
        )

        val coach = repository.askCoach(AiCoachRequest("Help me build muscle safely")).getOrThrow()
        val workout = repository.generateWorkout(workoutRequest).getOrThrow()

        assertTrue(coach.summary.isNotBlank())
        assertEquals(3, workout.days.size)
        assertTrue(workout.safetyNote.isNotBlank())
        assertEquals(0, backend.coachCalls)
    }

    @Test
    fun backendMockMode_mapsSuccessfulCoachResponse() = runTest {
        val backend = FakeBackendService(
            coachResult = BackendResult.Success(
                CoachResponseDto("Backend", "Action", "Nutrition", "Workout", "Safety"),
                requestId = "request-id"
            )
        )
        val repository = AiRepository(
            mode = AiRuntimeMode.BACKEND_MOCK,
            backendExecutionAllowed = true,
            backendService = backend
        )

        val response = repository.askCoach(AiCoachRequest("Question")).getOrThrow()
        assertEquals("Backend", response.summary)
        assertEquals("BACKEND_MOCK", response.metadata.sourceType)
        assertEquals("request-id", response.metadata.requestId)
    }

    @Test
    fun backendRemote_mapsTruthfulFireworksResponseAndStructuredWorkout() = runTest {
        val plan = BackendGeneratedWorkoutPlanDto(
            planId = "remote-plan",
            title = "Four day plan",
            goal = "Build muscle",
            requestedDays = 2,
            generatedDays = listOf(
                workoutDay("Monday", "Push", "exercise-1"),
                workoutDay("Thursday", "Pull", "exercise-2")
            ),
            progressionGuidance = "Add reps before load.",
            safetyNote = "Stop for severe pain."
        )
        val backend = FakeBackendService(
            coachResult = BackendResult.Success(
                CoachResponseDto(
                    "Remote", "Action", "Nutrition", "Workout", "Safety",
                    sourceType = "FIREWORKS",
                    confidence = 0.85,
                    validationStatus = "VALIDATED",
                    generatedWorkoutPlan = plan
                ),
                requestId = "fireworks-request",
                mode = BackendExecutionMode.REMOTE
            )
        )
        val repository = AiRepository(
            mode = AiRuntimeMode.BACKEND_REMOTE,
            backendExecutionAllowed = true,
            backendService = backend,
            exerciseCatalogProvider = { listOf(exercise("exercise-1"), exercise("exercise-2")) },
            clock = { 100L }
        )

        val outcome = repository.askCoachWithStatus(
            AiCoachRequest("Plan", experience = "Beginner")
        )

        assertEquals(AiCoachExecutionState.REMOTE_SUCCESS, outcome.state)
        assertEquals("REMOTE", outcome.response?.metadata?.sourceType)
        assertEquals("remote", outcome.response?.metadata?.backendMode)
        assertEquals(listOf("exercise-1"), outcome.response?.workoutPlan?.days?.first()?.exercises?.map { it.exerciseId })
        assertEquals(2, outcome.response?.metadata?.requestedWorkoutDays)
        assertEquals(GeneratedPlanSource.REMOTE, outcome.response?.workoutPlan?.sourceType)
    }

    @Test
    fun backendMock_rejectsFireworksModeAndUsesLocalFallback() = runTest {
        val backend = FakeBackendService(
            coachResult = BackendResult.Success(
                CoachResponseDto("Remote", "Action", "Nutrition", "Workout", "Safety"),
                "request-id",
                BackendExecutionMode.REMOTE
            )
        )
        val local = fixedLocalCoach()
        val repository = AiRepository(
            mode = AiRuntimeMode.BACKEND_MOCK,
            backendExecutionAllowed = true,
            backendService = backend,
            localCoach = local
        )

        val outcome = repository.askCoachWithStatus(AiCoachRequest("Question"))

        assertEquals(AiCoachExecutionState.LOCAL_FALLBACK, outcome.state)
        assertEquals("LOCAL_FALLBACK", outcome.response?.metadata?.sourceType)
    }

    @Test
    fun backendRemote_mapsFireworksDietPlanToSavableDomainModel() = runTest {
        val diet = BackendGeneratedDietPlanDto(
            planId = "diet-1",
            title = "Pakistani plan",
            goal = "Fat loss",
            calorieTarget = 1_900,
            calorieTargetSource = "profile",
            macroTargets = BackendMacroTargetsDto(130.0, 210.0, 55.0),
            macroTargetSource = "profile",
            meals = listOf(
                meal("Breakfast", "food-1", "Chana").copy(
                    foods = listOf(
                        meal("Breakfast", "food-1", "Chana").foods.single(),
                        BackendGeneratedFoodDto(
                            "food-4", "Dahi", "1 cup", 120.0, 8.0, 12.0, 4.0,
                            "existing_record"
                        )
                    )
                ),
                meal("Lunch", "food-2", "Chicken karahi"),
                meal("Dinner", "food-3", "Daal")
            ),
            alternatives = listOf("Swap daal varieties"),
            disclaimer = "Nutrition values are estimates."
        )
        val backend = FakeBackendService(
            BackendResult.Success(
                CoachResponseDto(
                    "Diet", "Action", "Nutrition", "Workout", "Safety",
                    sourceType = "FIREWORKS",
                    validationStatus = "VALIDATED",
                    generatedDietPlan = diet,
                    profileContextUsed = true
                ),
                "diet-request",
                BackendExecutionMode.REMOTE
            )
        )
        val repository = AiRepository(
            mode = AiRuntimeMode.BACKEND_REMOTE,
            backendExecutionAllowed = true,
            backendService = backend,
            clock = { 200L }
        )

        val plan = repository.askCoach(AiCoachRequest("Diet plan")).getOrThrow().dietPlan

        assertEquals("diet-1", plan?.planId)
        assertEquals("profile", repository.askCoach(AiCoachRequest("Diet plan")).getOrThrow()
            .metadata.calorieTargetSource)
        assertEquals("food-1", plan?.days?.first()?.meals?.first()?.foodRecordId)
        assertEquals("food-4", plan?.days?.first()?.meals?.first()?.additionalFoods?.single()?.foodRecordId)
        assertTrue(plan?.isValid() == true)
    }

    @Test
    fun backendRemote_dietMealCountUsesTwoToSixProductBoundary() = runTest {
        for (mealCount in listOf(2, 6, 1, 7)) {
            val repository = AiRepository(
                mode = AiRuntimeMode.BACKEND_REMOTE,
                backendExecutionAllowed = true,
                backendService = FakeBackendService(
                    BackendResult.Success(
                        CoachResponseDto(
                            "Diet", "Action", "Nutrition", "Workout", "Safety",
                            sourceType = "FIREWORKS",
                            validationStatus = "VALIDATED",
                            generatedDietPlan = dietPlan(mealCount)
                        ),
                        "diet-$mealCount",
                        BackendExecutionMode.REMOTE
                    )
                ),
                localCoach = fixedLocalCoach(),
                clock = { 200L }
            )

            val outcome = repository.askCoachWithStatus(AiCoachRequest("Diet plan"))

            assertEquals(
                if (mealCount in 2..6) AiCoachExecutionState.REMOTE_SUCCESS else AiCoachExecutionState.LOCAL_FALLBACK,
                outcome.state
            )
        }
    }

    @Test
    fun backendRemote_preservesUnavailableNutritionWithoutSyntheticCatalogueId() = runTest {
        val repository = AiRepository(
            mode = AiRuntimeMode.BACKEND_REMOTE,
            backendExecutionAllowed = true,
            backendService = FakeBackendService(
                BackendResult.Success(
                    CoachResponseDto(
                        "Diet", "Action", "Nutrition", "Workout", "Safety",
                        sourceType = "FIREWORKS",
                        validationStatus = "VALIDATED",
                        generatedDietPlan = dietPlan(2, unavailableNutrition = true)
                    ),
                    "diet-unavailable",
                    BackendExecutionMode.REMOTE
                )
            ),
            clock = { 200L }
        )

        val outcome = repository.askCoachWithStatus(AiCoachRequest("Diet plan"))
        val meals = outcome.response?.dietPlan?.days?.single()?.meals.orEmpty()

        assertEquals(AiCoachExecutionState.REMOTE_SUCCESS, outcome.state)
        assertTrue(meals.all { it.foodRecordId.isEmpty() })
        assertTrue(meals.none { it.foodRecordId.startsWith("unmatched:") })
        assertTrue(meals.all { it.estimatedCalories == null })
        assertTrue(outcome.response?.dietPlan?.isValid() == true)
    }

    @Test
    fun backendRemote_rejectsStructuredPlanWithoutValidatedMarker() = runTest {
        val unmarkedPlan = BackendGeneratedWorkoutPlanDto(
            planId = "unmarked-plan",
            title = "Two day plan",
            goal = "General fitness",
            requestedDays = 2,
            generatedDays = listOf(
                workoutDay("Monday", "Full body", "exercise-1"),
                workoutDay("Thursday", "Full body", "exercise-1")
            ),
            progressionGuidance = "Progress gradually.",
            safetyNote = "Stop for severe pain."
        )
        val repository = AiRepository(
            mode = AiRuntimeMode.BACKEND_REMOTE,
            backendExecutionAllowed = true,
            backendService = FakeBackendService(
                BackendResult.Success(
                    CoachResponseDto(
                        "Remote", "Action", "Nutrition", "Workout", "Safety",
                        sourceType = "FIREWORKS",
                        generatedWorkoutPlan = unmarkedPlan
                    ),
                    "request-id",
                    BackendExecutionMode.REMOTE
                )
            ),
            localCoach = fixedLocalCoach(),
            exerciseCatalogProvider = { listOf(exercise("exercise-1")) }
        )

        assertEquals(
            AiCoachExecutionState.LOCAL_FALLBACK,
            repository.askCoachWithStatus(AiCoachRequest("Plan")).state
        )
    }

    @Test
    fun remoteModeDisabled_doesNotCallBackend() = runTest {
        val backend = FakeBackendService()
        val repository = AiRepository(
            mode = AiRuntimeMode.BACKEND_REMOTE,
            backendExecutionAllowed = false,
            backendService = backend,
            localCoach = fixedLocalCoach()
        )

        val outcome = repository.askCoachWithStatus(AiCoachRequest("Question"))

        assertEquals(AiCoachExecutionState.LOCAL_FALLBACK, outcome.state)
        assertEquals(0, backend.coachCalls)
    }

    @Test
    fun boundedContext_andExistingProfileFieldsReachTheRemoteDto() = runTest {
        val backend = FakeBackendService(
            coachResult = BackendResult.Success(
                CoachResponseDto("Mock", "Action", "Nutrition", "Workout", "Safety"),
                "request-id"
            )
        )
        val repository = AiRepository(
            mode = AiRuntimeMode.BACKEND_MOCK,
            backendExecutionAllowed = true,
            backendService = backend
        )

        repository.askCoach(
            AiCoachRequest(
                question = "Q".repeat(2_000),
                goal = "Build muscle",
                equipment = (1..20).map { "Equipment $it" }.toSet(),
                recentWorkoutSummary = "R".repeat(900),
                dietaryPreference = "Vegetarian",
                mealsPerDay = 5,
                workoutDays = 4,
                limitations = setOf("Knee discomfort", "No overhead pressing"),
                conversationContext = listOf(
                    AiCoachConversationTurn(AiCoachConversationRole.USER, "Prior question"),
                    AiCoachConversationTurn(AiCoachConversationRole.ASSISTANT, "Prior answer")
                )
            )
        )

        assertEquals(1_000, backend.lastCoachRequest?.question?.length)
        assertEquals(12, backend.lastCoachRequest?.equipment?.size)
        assertEquals(500, backend.lastCoachRequest?.recentWorkoutSummary?.length)
        assertEquals("Vegetarian", backend.lastCoachRequest?.dietaryPreference)
        assertEquals(5, backend.lastCoachRequest?.mealsPerDay)
        assertEquals(4, backend.lastCoachRequest?.workoutDays)
        assertEquals(
            setOf("Knee discomfort", "No overhead pressing"),
            backend.lastCoachRequest?.limitations?.toSet()
        )
        assertEquals(
            listOf("user", "assistant"),
            backend.lastCoachRequest?.conversationContext?.map(CoachConversationContextDto::role)
        )
    }

    @Test
    fun absentProfileFieldsRemainAbsentInTheRemoteDto() = runTest {
        LocalCoachContextStore.update(CoachContext())
        val backend = FakeBackendService(
            coachResult = BackendResult.Success(
                CoachResponseDto("Mock", "Action", "Nutrition", "Workout", "Safety"),
                "request-id"
            )
        )
        val repository = AiRepository(
            mode = AiRuntimeMode.BACKEND_MOCK,
            backendExecutionAllowed = true,
            backendService = backend
        )

        repository.askCoach(AiCoachRequest("Question"))

        assertEquals(null, backend.lastCoachRequest?.dietaryPreference)
        assertEquals(null, backend.lastCoachRequest?.mealsPerDay)
        assertEquals(null, backend.lastCoachRequest?.workoutDays)
        assertTrue(backend.lastCoachRequest?.limitations.orEmpty().isEmpty())
    }

    @Test
    fun conversationContext_isBoundedAgainAtTheAndroidTransportBoundary() = runTest {
        val backend = FakeBackendService(
            coachResult = BackendResult.Success(
                CoachResponseDto("Mock", "Action", "Nutrition", "Workout", "Safety"),
                "request-id"
            )
        )
        val repository = AiRepository(
            mode = AiRuntimeMode.BACKEND_MOCK,
            backendExecutionAllowed = true,
            backendService = backend
        )
        val context = List(8) { index ->
            AiCoachConversationTurn(
                role = if (index % 2 == 0) AiCoachConversationRole.USER else AiCoachConversationRole.ASSISTANT,
                text = "x".repeat(1_500)
            )
        }

        repository.askCoach(AiCoachRequest("Current question", conversationContext = context))

        val sent = backend.lastCoachRequest?.conversationContext.orEmpty()
        assertTrue(sent.size <= 6)
        assertTrue(sent.all { it.text.length <= 1_000 })
        assertTrue(sent.sumOf { it.text.length } <= 4_000)
    }

    @Test
    fun unknownExerciseId_usesLocalFallback() = runTest {
        val invalidPlan = BackendGeneratedWorkoutPlanDto(
            "bad", "Bad plan", "Fitness", 2,
            listOf(
                workoutDay("Monday", "Full body", "exercise-1"),
                workoutDay("Thursday", "Full body", "unknown")
            ),
            "Progress", "Safety"
        )
        val backend = FakeBackendService(
            BackendResult.Success(
                CoachResponseDto(
                    "Remote", "Action", "Nutrition", "Workout", "Safety",
                    sourceType = "FIREWORKS",
                    validationStatus = "VALIDATED",
                    generatedWorkoutPlan = invalidPlan
                ),
                "request-id",
                BackendExecutionMode.REMOTE
            )
        )
        val repository = AiRepository(
            mode = AiRuntimeMode.BACKEND_REMOTE,
            backendExecutionAllowed = true,
            backendService = backend,
            localCoach = fixedLocalCoach(),
            exerciseCatalogProvider = { listOf(exercise("exercise-1")) }
        )

        assertEquals(
            AiCoachExecutionState.LOCAL_FALLBACK,
            repository.askCoachWithStatus(AiCoachRequest("Plan")).state
        )
    }

    @Test
    fun workoutDayCountMismatch_usesLocalFallback() = runTest {
        val invalidPlan = BackendGeneratedWorkoutPlanDto(
            "bad-count", "Bad count", "Fitness", 3,
            listOf(
                workoutDay("Monday", "Full body", "exercise-1"),
                workoutDay("Thursday", "Full body", "exercise-1")
            ),
            "Progress", "Safety"
        )
        val backend = FakeBackendService(
            BackendResult.Success(
                CoachResponseDto(
                    "Remote", "Action", "Nutrition", "Workout", "Safety",
                    sourceType = "FIREWORKS",
                    validationStatus = "VALIDATED",
                    generatedWorkoutPlan = invalidPlan
                ),
                "request-id",
                BackendExecutionMode.REMOTE
            )
        )
        val repository = AiRepository(
            mode = AiRuntimeMode.BACKEND_REMOTE,
            backendExecutionAllowed = true,
            backendService = backend,
            localCoach = fixedLocalCoach(),
            exerciseCatalogProvider = { listOf(exercise("exercise-1")) }
        )

        assertEquals(
            AiCoachExecutionState.LOCAL_FALLBACK,
            repository.askCoachWithStatus(AiCoachRequest("Plan")).state
        )
    }

    @Test
    fun timeoutFallback_retainsFailureReasonWithoutExposingBackendDetails() = runTest {
        val repository = AiRepository(
            mode = AiRuntimeMode.BACKEND_REMOTE,
            backendExecutionAllowed = true,
            backendService = FakeBackendService(BackendResult.Timeout("raw provider timeout")),
            localCoach = fixedLocalCoach()
        )

        val outcome = repository.askCoachWithStatus(AiCoachRequest("Question"))

        assertEquals(AiCoachExecutionState.LOCAL_FALLBACK, outcome.state)
        assertEquals(AiCoachExecutionState.TIMEOUT, outcome.backendFailureState)
        assertTrue(outcome.message.orEmpty().contains("local response"))
        assertTrue(!outcome.message.orEmpty().contains("raw provider"))
    }

    @Test
    fun rateLimitFallback_preservesRetryAfter() = runTest {
        val repository = AiRepository(
            mode = AiRuntimeMode.BACKEND_REMOTE,
            backendExecutionAllowed = true,
            backendService = FakeBackendService(
                BackendResult.RateLimited("raw", 30L, "request-id")
            ),
            localCoach = fixedLocalCoach()
        )

        val outcome = repository.askCoachWithStatus(AiCoachRequest("Question"))

        assertEquals(AiCoachExecutionState.RATE_LIMITED, outcome.backendFailureState)
        assertEquals(30L, outcome.retryAfterSeconds)
    }

    @Test
    fun boundedPublicErrorRemainsTypedWithoutBackendMessageExposure() = runTest {
        val repository = AiRepository(
            mode = AiRuntimeMode.BACKEND_REMOTE,
            backendExecutionAllowed = true,
            backendService = FakeBackendService(
                BackendResult.PublicError(BackendPublicError.REMOTE_AI_CONSENT_REQUIRED)
            ),
            localCoach = fixedLocalCoach()
        )

        val outcome = repository.askCoachWithStatus(AiCoachRequest("Question"))

        assertEquals(BackendPublicError.REMOTE_AI_CONSENT_REQUIRED, outcome.backendError)
        assertEquals(AiCoachExecutionState.BACKEND_UNAVAILABLE, outcome.backendFailureState)
        assertTrue(outcome.message.orEmpty().contains("local response"))
    }

    @Test
    fun backendFailure_activatesDeterministicLocalCoachFallback() = runTest {
        val backend = FakeBackendService(
            coachResult = BackendResult.BackendUnavailable("Unavailable")
        )
        val local = object : AiCoachRepository {
            var calls = 0

            override suspend fun ask(question: String): Result<AiCoachResponse> {
                calls += 1
                return Result.success(
                    AiCoachResponse("Local fallback", "Action", "Nutrition", "Workout", "Safety")
                )
            }
        }
        val repository = AiRepository(
            mode = AiRuntimeMode.BACKEND_MOCK,
            backendExecutionAllowed = true,
            backendService = backend,
            localCoach = local
        )

        assertEquals("Local fallback", repository.askCoach(AiCoachRequest("Question")).getOrThrow().summary)
        assertEquals(1, local.calls)
    }

    @Test
    fun workoutGenerator_remainsLocalDuringBackendClientPhase() = runTest {
        val backend = FakeBackendService()
        val repository = AiRepository(
            mode = AiRuntimeMode.BACKEND_MOCK,
            backendExecutionAllowed = true,
            backendService = backend,
            localWorkout = deterministicWorkoutProvider()
        )

        val workout = repository.generateWorkout(workoutRequest).getOrThrow()

        assertEquals(3, workout.days.size)
        assertEquals(0, backend.workoutCalls)
    }

    @Test
    fun workoutGeneration_runsOnConfiguredBackgroundDispatcherAndPreservesResult() = runTest {
        val expected = GeneratedRoutine(
            name = "Dispatcher test workout",
            description = "Repository result passthrough",
            splitType = workoutRequest.split,
            frequency = "3 days/week",
            days = emptyList(),
            safetyNote = "General fitness guidance only."
        )
        val dispatcher = Executors.newSingleThreadExecutor { task ->
            Thread(task, "fitdesi-workout-generation-test").apply { isDaemon = true }
        }.asCoroutineDispatcher()

        try {
            var executionThreadName: String? = null
            val repository = AiRepository(
                mode = AiRuntimeMode.LOCAL,
                localWorkout = object : AiWorkoutProvider {
                    override suspend fun generate(request: AiWorkoutRequest): Result<GeneratedRoutine> {
                        executionThreadName = Thread.currentThread().name
                        return Result.success(expected)
                    }
                },
                workoutGenerationDispatcher = dispatcher
            )

            val result = repository.generateWorkout(workoutRequest)

            assertEquals("fitdesi-workout-generation-test", executionThreadName)
            assertEquals(expected, result.getOrThrow())
        } finally {
            dispatcher.close()
        }
    }

    @Test
    fun workoutGeneration_preservesProviderFailureAcrossDispatcherBoundary() = runTest {
        val expectedFailure = IllegalStateException("deterministic generation failed")
        val dispatcher = Executors.newSingleThreadExecutor { task ->
            Thread(task, "fitdesi-workout-failure-test").apply { isDaemon = true }
        }.asCoroutineDispatcher()

        try {
            val repository = AiRepository(
                mode = AiRuntimeMode.LOCAL,
                localWorkout = object : AiWorkoutProvider {
                    override suspend fun generate(request: AiWorkoutRequest): Result<GeneratedRoutine> =
                        Result.failure(expectedFailure)
                },
                workoutGenerationDispatcher = dispatcher
            )

            val result = repository.generateWorkout(workoutRequest)

            assertSame(expectedFailure, result.exceptionOrNull())
        } finally {
            dispatcher.close()
        }
    }

    @Test
    fun workoutGeneration_addsNoArtificialDelay() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repository = AiRepository(
            mode = AiRuntimeMode.LOCAL,
            localWorkout = deterministicWorkoutProvider(),
            workoutGenerationDispatcher = dispatcher
        )

        val result = async { repository.generateWorkout(workoutRequest) }
        advanceUntilIdle()

        assertTrue(result.await().isSuccess)
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test
    fun uninjectedWorkoutProvider_failsClosed() = runTest {
        val result = AiRepository(mode = AiRuntimeMode.LOCAL).generateWorkout(workoutRequest)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("has not been injected"))
    }

    private fun deterministicWorkoutProvider(): AiWorkoutProvider = object : AiWorkoutProvider {
        override suspend fun generate(request: AiWorkoutRequest): Result<GeneratedRoutine> = Result.success(
            GeneratedRoutine(
                name = "Deterministic test workout",
                description = "Explicit test provider",
                splitType = request.split,
                frequency = "${request.daysCount} days/week",
                days = request.selectedDays.take(request.daysCount).map { day ->
                    GeneratedDay(day, "Full Body", "Test day", emptyList())
                },
                safetyNote = "General fitness guidance only."
            )
        )
    }

    private class FakeBackendService(
        private val coachResult: BackendResult<CoachResponseDto> =
            BackendResult.BackendUnavailable("Not configured")
    ) : AiBackendService, CoachRemoteClient {
        override suspend fun authSession(idToken: String): BackendSessionResult =
            BackendSessionResult.BackendUnavailable

        var workoutCalls = 0
        var coachCalls = 0
        var lastCoachRequest: CoachRequestDto? = null

        override suspend fun health(): BackendResult<HealthResponseDto> =
            BackendResult.BackendUnavailable("Not configured")

        override suspend fun submit(request: CoachRequestDto): BackendResult<CoachResponseDto> {
            coachCalls += 1
            lastCoachRequest = request
            return coachResult
        }

        override suspend fun coach(
            idToken: String,
            idempotencyKey: String,
            request: CoachRequestDto
        ): BackendResult<CoachResponseDto> = submit(request)

        override suspend fun workoutPlan(
            request: WorkoutPlanRequestDto
        ): BackendResult<WorkoutPlanResponseDto> {
            workoutCalls += 1
            return BackendResult.BackendUnavailable("Not integrated")
        }

        override suspend fun foodAnalyze(
            request: FoodAnalyzeRequestDto
        ): BackendResult<FoodAnalyzeResponseDto> =
            BackendResult.BackendUnavailable("Not integrated")

        override suspend fun dietPlan(
            request: DietPlanRequestDto
        ): BackendResult<DietPlanResponseDto> =
            BackendResult.BackendUnavailable("Not integrated")

        override suspend fun yogaPlan(
            request: YogaPlanRequestDto
        ): BackendResult<YogaPlanResponseDto> =
            BackendResult.BackendUnavailable("Not integrated")

        override suspend fun progressReview(
            request: ProgressReviewRequestDto
        ): BackendResult<ProgressReviewResponseDto> =
            BackendResult.BackendUnavailable("Not integrated")
    }

    private fun fixedLocalCoach() = object : AiCoachRepository {
        override suspend fun ask(question: String): Result<AiCoachResponse> = Result.success(
            AiCoachResponse("Local", "Action", "Nutrition", "Workout", "Safety")
        )
    }

    private fun workoutDay(day: String, focus: String, id: String) = BackendGeneratedWorkoutDayDto(
        dayName = day,
        focus = focus,
        exercises = listOf(
            BackendGeneratedWorkoutExerciseDto(id, "Exercise $id", 3, "8-12", 90, "primary")
        )
    )

    private fun meal(label: String, id: String, name: String) = BackendGeneratedMealDto(
        name = label,
        foods = listOf(
            BackendGeneratedFoodDto(
                id, name, "1 serving", 450.0, 25.0, 45.0, 15.0, "existing_record"
            )
        )
    )

    private fun dietPlan(
        mealCount: Int,
        unavailableNutrition: Boolean = false
    ) = BackendGeneratedDietPlanDto(
        planId = "diet-$mealCount",
        title = "Bounded diet plan",
        goal = "Balanced nutrition",
        calorieTarget = null,
        calorieTargetSource = "unavailable",
        macroTargets = null,
        macroTargetSource = "unavailable",
        meals = List(mealCount) { index ->
            BackendGeneratedMealDto(
                name = "Meal ${index + 1}",
                foods = listOf(
                    BackendGeneratedFoodDto(
                        foodRecordId = if (unavailableNutrition) null else "food-$index",
                        name = "Food $index",
                        portion = "1 serving",
                        calories = if (unavailableNutrition) null else 400.0,
                        proteinGrams = if (unavailableNutrition) null else 20.0,
                        carbsGrams = if (unavailableNutrition) null else 50.0,
                        fatGrams = if (unavailableNutrition) null else 12.0,
                        nutritionSource = if (unavailableNutrition) "unavailable" else "existing_record"
                    )
                )
            )
        },
        alternatives = emptyList(),
        disclaimer = "General nutrition guidance only."
    )

    private fun exercise(id: String) = ExercisePackRecord(
        id = id,
        name = "Exercise $id",
        aliases = emptyList(),
        movementPattern = "horizontal_push",
        category = "strength",
        bodyPart = "upper body",
        target = "chest",
        muscleGroup = "chest",
        secondaryMuscles = emptyList(),
        bodyTargets = listOf("chest"),
        equipment = listOf("dumbbell"),
        experienceLevels = listOf("beginner"),
        goals = listOf("muscle gain"),
        instructions = "Use controlled technique.",
        safetyNote = "Stop for severe pain.",
        source = SourceMetadata(
            "FitDesi test", "local", id, "MIT", "test", "2026-07-18",
            true, RedistributionStatus.VERIFIED, LicenseStatus.VERIFIED, "Test fixture"
        )
    )
}
