package com.example.ai

import com.example.ai.knowledge.CoachContext
import com.example.ai.knowledge.ExercisePackRecord
import com.example.ai.knowledge.FitnessKnowledgeCatalog
import com.example.ai.knowledge.FitnessKnowledgeRetriever
import com.example.ai.knowledge.LicenseStatus
import com.example.ai.knowledge.KnowledgeDomain
import com.example.ai.knowledge.KnowledgeEntry
import com.example.ai.knowledge.KnowledgeSourceType
import com.example.ai.knowledge.ProjectFoodRecord
import com.example.ai.knowledge.FoodDietaryClassification
import com.example.ai.knowledge.ProjectKnowledgeAdapters
import com.example.ai.knowledge.RedistributionStatus
import com.example.ai.knowledge.RuntimeLicenseStatus
import com.example.ai.knowledge.SourceMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalCoachOrchestratorTest {
    private val exercises = exerciseFixtures()
    private val foods = foodFixtures()
    private val retriever = FitnessKnowledgeRetriever.from(
        FitnessKnowledgeCatalog.entries + ProjectKnowledgeAdapters.foodEntries(foods)
    )
    private val orchestrator = LocalCoachOrchestrator(
        exerciseProvider = { exercises },
        foodProvider = { foods }
    )

    @Test
    fun `yoga and mobility suggestion uses bounded reviewed local content`() {
        fun yogaEntry(id: String, title: String, targets: Set<String>) = KnowledgeEntry(
            id = id,
            domain = KnowledgeDomain.EXERCISE,
            title = title,
            content = "Move within a comfortable range. Safety: stop for sharp pain or dizziness.",
            keywords = targets + setOf("yoga", "mobility"),
            exerciseIds = setOf(id),
            priority = 18,
            sourceType = KnowledgeSourceType.VERIFIED_PACK,
            licenseStatus = RuntimeLicenseStatus.VERIFIED,
        )
        val yogaRetriever = FitnessKnowledgeRetriever.from(
            listOf(
                yogaEntry("fd-yoga-cat-cow", "Cat-Cow Mobility", setOf("spine")),
                yogaEntry("fd-yoga-bound-angle", "Bound Angle Pose", setOf("hips")),
                yogaEntry("fd-yoga-supine-twist", "Supine Twist", setOf("hips", "twist")),
                yogaEntry("fd-yoga-thread-the-needle", "Thread the Needle", setOf("shoulders", "upper back")),
                yogaEntry("fd-yoga-childs-pose", "Child's Pose", setOf("hips", "back")),
                yogaEntry("fd-yoga-sphinx-pose", "Sphinx Pose", setOf("spine")),
            ),
        )

        val response = orchestrator.respond(
            "Show me beginner yoga and mobility options.",
            CoachContext(),
            yogaRetriever,
        )

        assertEquals(AiCoachIntent.YOGA_PLAN, response.metadata.intent)
        assertTrue(response.metadata.knowledgeRecordIds.isNotEmpty())
        assertTrue(response.metadata.knowledgeRecordIds.all { it.startsWith("fd-yoga-") })
        assertTrue(response.recommendedAction.contains("comfortable", ignoreCase = true))
        assertTrue(response.summary.contains("options", ignoreCase = true))
        assertTrue(response.recommendedAction.lineSequence().count { it.startsWith("- ") } <= 5)
        assertFalse(response.summary.contains("sequence", ignoreCase = true))
        assertFalse(response.recommendedAction.contains("sequence", ignoreCase = true))
        assertFalse(response.nutritionNote.contains("session", ignoreCase = true))
        assertFalse(response.metadata.fallbackUsed)
        assertNull(response.workoutPlan)
        assertNull(response.dietPlan)
        assertFalse(response.hasSavableGeneratedPlan())
    }

    @Test
    fun `safety escalation outranks yoga intent`() {
        val response = orchestrator.respond(
            "I feel faint with chest pain while trying yoga mobility.",
            CoachContext(),
            retriever,
        )

        assertEquals(AiCoachIntent.MEDICAL_ESCALATION, response.metadata.intent)
        assertNull(response.workoutPlan)
        assertNull(response.dietPlan)
        assertFalse(response.hasSavableGeneratedPlan())
    }

    @Test
    fun `Pakistani diet request produces a meal plan instead of escalation`() {
        val response = orchestrator.respond(
            "Build Pakistani food Diet plan for me.",
            CoachContext(
                goal = "Gain Muscle",
                calorieTarget = 2400,
                proteinTargetGrams = 140.0,
                carbsTargetGrams = 300.0,
                fatTargetGrams = 70.0,
                dietaryPreference = "No preference",
                mealsPerDay = 4
            ),
            retriever
        )

        assertEquals(AiCoachIntent.PAKISTANI_DIET_PLAN, response.metadata.intent)
        assertTrue(response.recommendedAction.contains("Breakfast:"))
        assertTrue(response.recommendedAction.contains("Lunch:"))
        assertTrue(response.recommendedAction.contains("Dinner:"))
        assertTrue(response.recommendedAction.contains("kcal"))
        assertFalse(response.recommendedAction.contains("urgent medical", ignoreCase = true))
        assertEquals("LOCAL_KNOWLEDGE", response.metadata.sourceType)
        assertEquals("VALIDATED", response.metadata.validationStatus)
        assertNotNull(response.dietPlan)
        assertEquals(response.dietPlan!!.sourceType.name, response.metadata.sourceType)
        assertEquals(response.dietPlan!!.validationStatus.name, response.metadata.validationStatus)
        assertTrue(response.hasSavableGeneratedPlan())
        assertNull(response.workoutPlan)
    }

    @Test
    fun `generic and Roman Urdu diet plan prompts route to plan generation`() {
        listOf(
            "Generate a diet plan for me according to my profile",
            "Create a meal plan based on my profile",
            "Make me a gain-muscle diet",
            "Build my daily meal plan",
            "Mere profile ke mutabiq diet plan banao",
            "Make my Eggitarian plan",
            "Create a calorie-target plan",
            "Pakistani diet for me",
            "Khane ka plan bana do"
        ).forEach { prompt ->
            assertEquals(AiCoachIntent.PAKISTANI_DIET_PLAN, LocalCoachIntentClassifier.classify(prompt))
        }
        assertEquals(AiCoachIntent.FOOD_QUESTION, LocalCoachIntentClassifier.classify("Calories in biryani"))
        assertEquals(AiCoachIntent.FOOD_QUESTION, LocalCoachIntentClassifier.classify("How much protein is in daal?"))
        assertEquals(AiCoachIntent.FOOD_QUESTION, LocalCoachIntentClassifier.classify("Show food alternatives for roti"))
    }

    @Test
    fun `workout progress and safety intent variants route without overriding food lookup`() {
        listOf(
            "Build a training plan according to my profile",
            "Give me a six-day workout plan",
            "I need dumbbell equipment substitutions",
            "Mere profile ke mutabiq workout plan banao"
        ).forEach { prompt ->
            assertEquals(AiCoachIntent.WORKOUT_PLAN, LocalCoachIntentClassifier.classify(prompt))
        }
        listOf("Review my missed workouts", "My plan is too easy", "My sessions are too hard").forEach { prompt ->
            assertEquals(AiCoachIntent.PROGRESS_REVIEW, LocalCoachIntentClassifier.classify(prompt))
        }
        assertEquals(AiCoachIntent.MEDICAL_ESCALATION, LocalCoachIntentClassifier.classify("I have chest pain and feel faint"))
        assertEquals(AiCoachIntent.MEDICAL_ESCALATION, LocalCoachIntentClassifier.classify("I have an injury with acute pain"))
        assertEquals(AiCoachIntent.FOOD_QUESTION, LocalCoachIntentClassifier.classify("Calories in biryani"))
    }

    @Test
    fun `exercise specific progression outranks generic progress review wording`() {
        assertEquals(
            AiCoachIntent.EXERCISE_QUESTION,
            LocalCoachIntentClassifier.classify("How should I progress my squat?")
        )
    }

    @Test
    fun `reasonable workout plan prompts produce valid savable structured plans`() {
        val context = completePlanContext()
        listOf(
            "Create a workout plan for muscle gain",
            "Create a workout plan according to my profile",
            "Create a Push Pull Legs workout plan",
            "Give me a PPL plan",
            "Build me an upper lower plan",
            "Make me a full body plan",
            "Design a weekly workout schedule for strength",
            "Make me an upper/lower training plan",
            "Can you make me a 4 day lifting program?",
            "Put together a three day gym program for me",
            "Plan my workouts for this week",
            "Set up a 4 day training schedule",
            "Give me a strength program",
            "Build me a muscle building routine",
            "I want a 3 day split",
            "I want a workout plan",
            "Create a program using dumbbells",
            "Prepare a home training schedule",
            "Can you design my weekly training?",
            "Make a workout for me",
            "Create my gym routine",
            "Plan a workout based on my profile",
            "Give me a weekly exercise schedule",
            "I need a four day strength routine",
            "Can you make me a 4 day lifting programme?",
            "Prepare a home program for me",
            "Build me a weekly training plan"
        ).forEach { prompt ->
            val response = orchestrator.respond(prompt, context, retriever)

            assertEquals(prompt, AiCoachIntent.WORKOUT_PLAN, response.metadata.intent)
            assertNotNull(prompt, response.workoutPlan)
            assertTrue(prompt, response.workoutPlan!!.isValid())
            assertTrue(prompt, response.hasSavableGeneratedPlan())
            assertNull(prompt, response.dietPlan)
        }
    }

    @Test
    fun `identical local workout generation produces the same stable plan ID`() {
        val prompt = "Can you make me a 4 day lifting program?"

        val first = requireNotNull(orchestrator.respond(prompt, completePlanContext(), retriever).workoutPlan)
        val second = requireNotNull(orchestrator.respond(prompt, completePlanContext(), retriever).workoutPlan)

        assertEquals(first.copy(createdAt = 0L), second.copy(createdAt = 0L))
        assertEquals(first.planId, second.planId)
    }

    @Test
    fun `same visible title with different canonical workout content has different plan IDs`() {
        val dumbbellPlan = requireNotNull(
            orchestrator.respond(
                "Set up a 4 day training schedule using dumbbells",
                completePlanContext(),
                retriever
            ).workoutPlan
        )
        val homePlan = requireNotNull(
            orchestrator.respond(
                "Prepare a 4 day bodyweight training schedule",
                completePlanContext(),
                retriever
            ).workoutPlan
        )

        assertEquals(dumbbellPlan.title, homePlan.title)
        assertTrue(dumbbellPlan.days != homePlan.days)
        assertTrue(dumbbellPlan.planId != homePlan.planId)
    }

    @Test
    fun `push pull legs request uses a compatible structured split`() {
        val response = orchestrator.respond(
            "Create a workout plan for muscle gain and it should be Push Pull Legs",
            completePlanContext(),
            retriever
        )

        val plan = requireNotNull(response.workoutPlan)
        assertEquals(listOf("Push", "Pull", "Legs & Core"), plan.days.map { it.focus })
        assertTrue(plan.isValid())
        assertTrue(response.summary.contains("Split: Push Pull Legs"))
    }

    @Test
    fun `push and pull days remain valid when compatible isolation is unavailable`() {
        assertTrue(
            WorkoutPlanValidator.isDayValid(
                "Push",
                listOf(MovementPattern.HORIZONTAL_PUSH, MovementPattern.VERTICAL_PUSH)
            )
        )
        assertTrue(
            WorkoutPlanValidator.isDayValid(
                "Pull",
                listOf(MovementPattern.HORIZONTAL_PULL, MovementPattern.VERTICAL_PULL)
            )
        )
    }

    @Test
    fun `home workout routine uses bodyweight compatible exercises`() {
        val response = orchestrator.respond(
            "Make me a home workout routine",
            completePlanContext(),
            retriever
        )

        val plan = requireNotNull(response.workoutPlan)
        assertTrue(plan.days.flatMap { it.exercises }.all { "body" in it.exerciseId })
        assertTrue(plan.isValid())
    }

    @Test
    fun `exercise technique questions remain non plan guidance`() {
        listOf(
            "How do I squat?",
            "What is progressive overload?",
            "What muscles does bench press train?"
        ).forEach { prompt ->
            val response = orchestrator.respond(prompt, completePlanContext(), retriever)
            assertTrue(prompt, response.metadata.intent != AiCoachIntent.WORKOUT_PLAN)
            assertNull(prompt, response.workoutPlan)
        }
    }

    @Test
    fun `reasonable diet plan prompts produce structured savable plans`() {
        val context = completePlanContext()
        listOf(
            "Create a Pakistani diet plan for fat loss",
            "Make me a meal plan according to my profile",
            "Create a high-protein Pakistani meal plan",
            "Give me a weekly diet plan",
            "I want a fat-loss diet",
            "I need a meal plan",
            "I need a nutrition plan",
            "Plan my meals for this week",
            "Make me a nutrition plan",
            "Put together a meal schedule",
            "Create an eating program for me",
            "Prepare my daily menu",
            "Give me a Pakistani meal schedule",
            "Set up my meals according to my profile",
            "Make my food plan",
            "Build a weekly eating schedule",
            "Plan my diet based on my profile"
        ).forEach { prompt ->
            val response = orchestrator.respond(prompt, context, retriever)

            assertEquals(prompt, AiCoachIntent.PAKISTANI_DIET_PLAN, response.metadata.intent)
            assertNotNull(prompt, response.dietPlan)
            assertTrue(prompt, response.dietPlan!!.isSavable())
            assertTrue(prompt, response.hasSavableGeneratedPlan())
            assertNull(prompt, response.workoutPlan)
        }
    }

    @Test
    fun `information review safety and Yoga prompts never create structured plans`() {
        listOf(
            "How do I squat?" to AiCoachIntent.EXERCISE_QUESTION,
            "What muscles does bench press train?" to AiCoachIntent.EXERCISE_QUESTION,
            "How much protein is in biryani?" to AiCoachIntent.FOOD_QUESTION,
            "Calories in nihari" to AiCoachIntent.FOOD_QUESTION,
            "Which Pakistani foods are high in protein?" to AiCoachIntent.FOOD_QUESTION,
            "Review my workout" to AiCoachIntent.PROGRESS_REVIEW,
            "Review my workout plan" to AiCoachIntent.PROGRESS_REVIEW,
            "My workout plan is too hard" to AiCoachIntent.PROGRESS_REVIEW,
            "My workout plan is too easy" to AiCoachIntent.PROGRESS_REVIEW,
            "I missed my workout" to AiCoachIntent.PROGRESS_REVIEW,
            "How should I progress my squat?" to AiCoachIntent.EXERCISE_QUESTION,
            "Is my diet plan good?" to AiCoachIntent.FOOD_QUESTION,
            "Review my diet" to AiCoachIntent.FOOD_QUESTION,
            "Review my diet plan" to AiCoachIntent.FOOD_QUESTION,
            "Create a workout plan despite chest pain" to AiCoachIntent.MEDICAL_ESCALATION,
            "Make me a yoga workout plan" to AiCoachIntent.YOGA_PLAN,
            "Create a mobility plan" to AiCoachIntent.YOGA_PLAN
        ).forEach { (prompt, expectedIntent) ->
            val response = orchestrator.respond(prompt, completePlanContext(), retriever)

            assertEquals(prompt, expectedIntent, response.metadata.intent)
            assertNull(prompt, response.workoutPlan)
            assertNull(prompt, response.dietPlan)
            assertFalse(prompt, response.hasSavableGeneratedPlan())
        }
    }

    @Test
    fun `informational plan wording never creates a structured plan`() {
        listOf(
            "I want information about strength training",
            "I need advice on nutrition",
            "I want tips for muscle building",
            "I need information about workout routines",
            "I need information about a 4 day workout",
            "Tell me about dumbbell workouts",
            "Tell me about Push Pull Legs",
            "Explain an upper lower plan",
            "What is Push Pull Legs?",
            "What is an upper lower plan?",
            "Explain a full body plan",
            "What is a full body plan?",
            "How does a PPL split work?"
        ).forEach { prompt ->
            val response = orchestrator.respond(prompt, completePlanContext(), retriever)

            assertNull(prompt, response.workoutPlan)
            assertNull(prompt, response.dietPlan)
            assertFalse(prompt, response.hasSavableGeneratedPlan())
        }
    }

    @Test
    fun `Pakistani food information remains non plan guidance`() {
        listOf(
            "How much protein is in chicken biryani?",
            "Which Pakistani foods are high in protein?",
            "Is nihari healthy?"
        ).forEach { prompt ->
            val response = orchestrator.respond(prompt, completePlanContext(), retriever)
            assertEquals(prompt, AiCoachIntent.FOOD_QUESTION, response.metadata.intent)
            assertNull(prompt, response.dietPlan)
            assertFalse(prompt, response.hasSavableGeneratedPlan())
        }
    }

    @Test
    fun `medical escalation never exposes generated plan saving`() {
        val response = orchestrator.respond(
            "I have chest pain and feel faint during my workout",
            completePlanContext(),
            retriever
        )

        assertEquals(AiCoachIntent.MEDICAL_ESCALATION, response.metadata.intent)
        assertNull(response.workoutPlan)
        assertNull(response.dietPlan)
        assertFalse(response.hasSavableGeneratedPlan())
    }

    @Test
    fun `diet request with genuinely missing profile input asks one concise clarification and exposes no save plan`() {
        val response = orchestrator.respond("Create a meal plan", CoachContext(), retriever)

        assertEquals(AiCoachIntent.PAKISTANI_DIET_PLAN, response.metadata.intent)
        assertEquals("NEEDS_PROFILE_INPUT", response.metadata.validationStatus)
        assertTrue(response.recommendedAction.startsWith("What diet preference"))
        assertNull(response.dietPlan)
        assertFalse(response.hasSavableGeneratedPlan())
    }

    @Test
    fun `four day request produces four distinct scheduled days`() {
        val response = orchestrator.respond(
            "Give me a four-day workout plan.",
            CoachContext(equipment = setOf("Dumbbells")),
            retriever
        )

        assertEquals(AiCoachIntent.WORKOUT_PLAN, response.metadata.intent)
        listOf("Monday -", "Tuesday -", "Thursday -", "Saturday -").forEach {
            assertTrue(response.workoutNote.contains(it))
        }
        assertEquals(4, Regex("(?m)^(Monday|Tuesday|Thursday|Saturday) -").findAll(response.workoutNote).count())
        assertNotNull(response.workoutPlan)
        assertNull(response.dietPlan)
    }

    @Test
    fun `four day dumbbell muscle plan balances major movement patterns`() {
        val plan = LocalWorkoutPlanGenerator().generate(
            LocalWorkoutPlanInput(
                goal = "Gain Muscle",
                experience = "Beginner",
                requestedDays = 4,
                equipment = setOf("Dumbbells"),
                recentWorkoutSummary = null,
                limitations = emptySet(),
                exercises = exercises
            )
        )
        val patterns = plan.days.flatMap { day -> day.exercises.map(LocalWorkoutExercise::movementPattern) }.toSet()

        assertTrue(MovementPattern.HORIZONTAL_PUSH in patterns)
        assertTrue(MovementPattern.HORIZONTAL_PULL in patterns)
        assertTrue(MovementPattern.SQUAT in patterns)
        assertTrue(MovementPattern.HINGE in patterns)
        assertTrue(plan.days.flatMap(LocalWorkoutDay::exercises).all { "Barbell" !in it.name })
    }

    @Test
    fun `ordinary upper day does not stack shoulder movements`() {
        val plan = LocalWorkoutPlanGenerator().generate(
            LocalWorkoutPlanInput(
                goal = "Gain Muscle",
                experience = "Beginner",
                requestedDays = 4,
                equipment = setOf("Dumbbells"),
                recentWorkoutSummary = null,
                limitations = emptySet(),
                exercises = exercises
            )
        )

        plan.days.filter { it.focus == "Upper Body" }.forEach { day ->
            val shoulderCount = day.exercises.count {
                it.movementPattern == MovementPattern.VERTICAL_PUSH ||
                    it.movementPattern == MovementPattern.SHOULDER_ISOLATION
            }
            assertTrue(shoulderCount <= 1)
            assertTrue(day.exercises.any { it.movementPattern == MovementPattern.HORIZONTAL_PUSH })
            assertTrue(day.exercises.any { it.movementPattern == MovementPattern.HORIZONTAL_PULL })
        }
    }

    @Test
    fun `beginner plan excludes cleans and burpees by default`() {
        val plan = LocalWorkoutPlanGenerator().generate(
            LocalWorkoutPlanInput(
                goal = "Gain Muscle",
                experience = "Beginner",
                requestedDays = 4,
                equipment = setOf("Dumbbells"),
                recentWorkoutSummary = null,
                limitations = emptySet(),
                exercises = exercises
            )
        )
        val names = plan.days.flatMap(LocalWorkoutDay::exercises).map { it.name.lowercase() }

        assertTrue(names.none { "clean" in it })
        assertTrue(names.none { "burpee" in it })
        assertTrue(plan.days.all { it.exercises.size <= 4 })
        assertTrue(
            plan.days.flatMap(LocalWorkoutDay::exercises)
                .none { it.movementPattern == MovementPattern.EXPLOSIVE_OR_TECHNICAL }
        )

        val requestedTechnical = LocalWorkoutPlanGenerator().generate(
            LocalWorkoutPlanInput(
                goal = "Gain Muscle",
                experience = "Beginner",
                requestedDays = 2,
                equipment = setOf("Dumbbells"),
                recentWorkoutSummary = null,
                limitations = emptySet(),
                explicitlyRequestedTerms = setOf("clean"),
                exercises = exercises
            )
        )
        val firstTechnicalDay = requestedTechnical.days.first()
        val firstTechnicalDayPatterns = firstTechnicalDay.exercises
            .map(LocalWorkoutExercise::movementPattern)

        assertTrue(
            requestedTechnical.days.flatMap(LocalWorkoutDay::exercises)
                .any { "clean" in it.name.lowercase() }
        )
        assertTrue(
            requestedTechnical.days.flatMap(LocalWorkoutDay::exercises)
                .none { "burpee" in it.name.lowercase() }
        )
        assertTrue(firstTechnicalDay.exercises.size <= 5)
        assertEquals(
            1,
            firstTechnicalDayPatterns.count { it == MovementPattern.EXPLOSIVE_OR_TECHNICAL }
        )
        assertTrue(
            firstTechnicalDayPatterns.any {
                it == MovementPattern.SQUAT ||
                    it == MovementPattern.HINGE ||
                    it == MovementPattern.LUNGE
            }
        )
        assertTrue(
            firstTechnicalDayPatterns.any {
                it == MovementPattern.HORIZONTAL_PUSH || it == MovementPattern.VERTICAL_PUSH
            }
        )
        assertTrue(
            firstTechnicalDayPatterns.any {
                it == MovementPattern.HORIZONTAL_PULL || it == MovementPattern.VERTICAL_PULL
            }
        )
        assertTrue(
            firstTechnicalDayPatterns.any {
                it == MovementPattern.CORE || it == MovementPattern.CARRY
            }
        )
        assertTrue(WorkoutPlanValidator.isValid(requestedTechnical.days))
        assertTrue(
            requestedTechnical.days.flatMap(LocalWorkoutDay::exercises)
                .groupingBy(LocalWorkoutExercise::id)
                .eachCount()
                .values
                .all { it <= 2 }
        )
        assertTrue(
            requestedTechnical.days.drop(1).all { day ->
                day.exercises.size <= 4 &&
                    day.exercises.none {
                        it.movementPattern == MovementPattern.EXPLOSIVE_OR_TECHNICAL
                    }
            }
        )
    }

    @Test
    fun `profile workout request uses profile and recent workout context`() {
        val response = orchestrator.respond(
            "Build a workout plan according to my profile.",
            CoachContext(
                goal = "Get Stronger",
                experience = "Beginner",
                equipment = setOf("Dumbbells"),
                recentWorkoutSummary = "Completed lower-body training yesterday",
                workoutDays = 3
            ),
            retriever
        )

        assertEquals(AiCoachIntent.WORKOUT_PLAN, response.metadata.intent)
        assertTrue(response.metadata.profileContextUsed)
        assertTrue(response.metadata.recentWorkoutContextUsed)
        assertTrue(response.summary.contains("Get Stronger"))
        assertTrue(response.summary.contains("Beginner"))
        assertTrue(response.workoutNote.contains("3 sets"))
    }

    @Test
    fun `dumbbell only request excludes barbell exercises`() {
        val response = orchestrator.respond(
            "Give me a dumbbell-only workout.",
            CoachContext(),
            retriever
        )

        assertEquals(AiCoachIntent.WORKOUT_PLAN, response.metadata.intent)
        assertTrue(response.workoutNote.contains("Dumbbell"))
        listOf("Barbell", "Cable", "Machine", "Bench Press").forEach { incompatible ->
            assertFalse(response.workoutNote.contains(incompatible))
        }
    }

    @Test
    fun `Pakistani protein query uses bounded legacy food records`() {
        val response = orchestrator.respond(
            "Suggest Pakistani protein foods.",
            CoachContext(goal = "Gain Muscle"),
            retriever
        )

        assertEquals(AiCoachIntent.FOOD_QUESTION, response.metadata.intent)
        assertTrue(response.nutritionNote.contains("Grilled Chicken"))
        assertTrue(response.nutritionNote.contains("Daal Masoor"))
        assertTrue(response.metadata.knowledgeRecordIds.size <= 10)
        assertEquals("LEGACY_LOCAL_UNKNOWN", response.metadata.sourceType)
    }

    @Test
    fun `accepted imported food is retrievable without presenting source values as a serving`() {
        val imported = ProjectFoodRecord(
            id = "fd-food-nourish-f5e3f352",
            name = "Chicken Tikka",
            category = "Protein & Main Dishes",
            servingSize = "Serving basis under review",
            calories = 148,
            proteinGrams = 18.0,
            carbsGrams = 4.0,
            fatGrams = 6.0,
            aliases = listOf("tikka chicken"),
            nutritionBasis = "SOURCE_VALUE_BASIS_UNCLEAR_LIKELY_PER_100G",
            reviewStatus = "NEEDS_FITDESI_NUTRITION_REVIEW",
            isNutritionReviewed = false,
            runtimeSource = "IMPORTED_REVIEW_REQUIRED"
        )
        val mergedFoods = foods + imported
        val mergedRetriever = FitnessKnowledgeRetriever.from(
            FitnessKnowledgeCatalog.entries + ProjectKnowledgeAdapters.foodEntries(mergedFoods)
        )
        val mergedOrchestrator = LocalCoachOrchestrator(
            exerciseProvider = { exercises },
            foodProvider = { mergedFoods }
        )

        val response = mergedOrchestrator.respond(
            "Tell me about Chicken Tikka food.",
            CoachContext(),
            mergedRetriever
        )

        assertEquals(AiCoachIntent.FOOD_QUESTION, response.metadata.intent)
        assertTrue(response.nutritionNote.contains("Chicken Tikka"))
        assertTrue(response.nutritionNote.contains("serving basis is under review"))
        assertFalse(response.nutritionNote.contains("148 kcal"))
        assertEquals("LOCAL_MERGED_REVIEW_REQUIRED", response.metadata.sourceType)
    }

    @Test
    fun `squat question returns progression and regression guidance`() {
        val response = orchestrator.respond(
            "How should I progress my squat?",
            CoachContext(),
            retriever
        )

        assertEquals(AiCoachIntent.EXERCISE_QUESTION, response.metadata.intent)
        assertTrue(response.recommendedAction.contains("Regressions:"))
        assertTrue(response.recommendedAction.contains("Progressions:"))
    }

    @Test
    fun `genuine red flags trigger urgent escalation`() {
        val response = orchestrator.respond(
            "I have chest pain and feel faint.",
            CoachContext(),
            retriever
        )

        assertEquals(AiCoachIntent.MEDICAL_ESCALATION, response.metadata.intent)
        assertTrue(response.recommendedAction.contains("urgent", ignoreCase = true))
        assertNull(response.workoutPlan)
        assertNull(response.dietPlan)
    }

    @Test
    fun `fat loss diet request uses plan clarification without escalation`() {
        val response = orchestrator.respond(
            "I want a fat-loss diet.",
            CoachContext(goal = "Lose Fat"),
            retriever
        )

        assertEquals(AiCoachIntent.PAKISTANI_DIET_PLAN, response.metadata.intent)
        assertNull(response.dietPlan)
        assertFalse(response.hasSavableGeneratedPlan())
        assertFalse(response.recommendedAction.contains("urgent medical", ignoreCase = true))
    }

    @Test
    fun `generic coaching answer has no savable plan`() {
        val response = orchestrator.respond(
            "How can I improve my recovery?",
            CoachContext(),
            retriever
        )

        assertEquals(AiCoachIntent.GENERAL_COACHING, response.metadata.intent)
        assertNull(response.workoutPlan)
        assertNull(response.dietPlan)
    }

    @Test
    fun `fat loss Pakistani plan respects stored target`() {
        val response = orchestrator.respond(
            "Build a fat-loss Pakistani diet plan.",
            CoachContext(
                goal = "Lose Fat",
                calorieTarget = 1600,
                proteinTargetGrams = 120.0,
                carbsTargetGrams = 180.0,
                fatTargetGrams = 44.0,
                dietaryPreference = "No preference",
                mealsPerDay = 4
            ),
            retriever
        )

        assertEquals(AiCoachIntent.PAKISTANI_DIET_PLAN, response.metadata.intent)
        assertTrue(response.summary.contains("1600 kcal"))
        val listedTotal = Regex("meal estimate: about (\\d+) kcal")
            .find(response.summary)?.groupValues?.get(1)?.toInt()
        assertTrue(listedTotal != null && listedTotal in 1200..1600)
        assertFalse(response.recommendedAction.contains("urgent medical", ignoreCase = true))
    }

    @Test
    fun `muscle gain Pakistani plan prioritizes protein sources`() {
        val plan = LocalPakistaniDietPlanGenerator().generate(
            LocalDietPlanInput(
                goal = "Gain Muscle",
                calorieTarget = 2400,
                proteinTargetGrams = 140.0,
                carbsTargetGrams = 300.0,
                fatTargetGrams = 70.0,
                dietaryPreference = "No preference",
                requestedMealCount = 3,
                limitations = emptySet(),
                foods = foods
            )
        )

        assertTrue(plan.meals.all { (it.estimatedProteinGrams ?: 0.0) >= 10.0 })
        assertTrue(plan.meals.any { "Chicken" in it.primary.name || "Fish" in it.primary.name || "Daal" in it.primary.name })
    }

    @Test
    fun `allergy restricted food is excluded`() {
        val allergyFoods = listOf(
            ProjectFoodRecord("allergy", "Peanut Chaat", "Snack", "1 bowl", 300, 14.0, 22.0, 18.0)
        ) + foods
        val plan = LocalPakistaniDietPlanGenerator().generate(
            LocalDietPlanInput(
                goal = "Lose Fat",
                calorieTarget = 1800,
                proteinTargetGrams = null,
                carbsTargetGrams = null,
                fatTargetGrams = null,
                dietaryPreference = null,
                requestedMealCount = 4,
                limitations = setOf("Food allergies: peanut"),
                dislikedFoods = setOf("Daal Masoor"),
                foods = allergyFoods
            )
        )

        assertTrue(plan.meals.none { "Peanut" in it.primary.name })
        assertTrue(plan.meals.flatMap(LocalDietMeal::accompaniments).none { "Peanut" in it.food.name })
        assertTrue(plan.meals.flatMap(LocalDietMeal::alternatives).none { "Peanut" in it.food.name })
        assertTrue(plan.meals.none { "Daal Masoor" in it.primary.name })
    }

    @Test
    fun `missing nutrition is labelled incomplete`() {
        val plan = LocalPakistaniDietPlanGenerator().generate(
            LocalDietPlanInput(
                goal = "General fitness",
                calorieTarget = 1800,
                proteinTargetGrams = null,
                carbsTargetGrams = null,
                fatTargetGrams = null,
                dietaryPreference = null,
                requestedMealCount = 3,
                limitations = emptySet(),
                foods = listOf(
                    ProjectFoodRecord(
                        "missing",
                        "Home Recipe",
                        "Local",
                        "1 household serving",
                        -1,
                        -1.0,
                        -1.0,
                        -1.0
                    )
                )
            )
        )

        assertTrue(plan.targetSummary.contains("incomplete", ignoreCase = true))
        assertTrue(plan.asMultilineText().contains("incomplete", ignoreCase = true))
    }

    @Test
    fun `diet plan does not target unclear imported nutrition as a reviewed serving`() {
        val unreviewed = ProjectFoodRecord(
            id = "fd-food-nourish-unreviewed",
            name = "Unreviewed Dense Dish",
            category = "Protein & Main Dishes",
            servingSize = "Serving basis under review",
            calories = 100,
            proteinGrams = 50.0,
            carbsGrams = 1.0,
            fatGrams = 1.0,
            nutritionBasis = "SOURCE_VALUE_BASIS_UNCLEAR_LIKELY_PER_100G",
            reviewStatus = "NEEDS_FITDESI_NUTRITION_REVIEW",
            isNutritionReviewed = false,
            runtimeSource = "IMPORTED_REVIEW_REQUIRED"
        )
        val plan = LocalPakistaniDietPlanGenerator().generate(
            LocalDietPlanInput(
                goal = "Gain Muscle",
                calorieTarget = 2200,
                proteinTargetGrams = 130.0,
                carbsTargetGrams = null,
                fatTargetGrams = null,
                dietaryPreference = null,
                requestedMealCount = 3,
                limitations = emptySet(),
                foods = foods + unreviewed
            )
        )

        assertTrue(plan.meals.none { it.primary.id == unreviewed.id })
        assertTrue(plan.meals.flatMap(LocalDietMeal::accompaniments).none { it.food.id == unreviewed.id })
        assertTrue(plan.meals.flatMap(LocalDietMeal::alternatives).none { it.food.id == unreviewed.id })
    }

    @Test
    fun `Eggitarian plan excludes meat fish and unsafe alternatives`() {
        val plan = LocalPakistaniDietPlanGenerator().generate(
            LocalDietPlanInput(
                goal = "Gain Muscle",
                calorieTarget = 2555,
                proteinTargetGrams = 100.0,
                carbsTargetGrams = 379.0,
                fatTargetGrams = 71.0,
                dietaryPreference = "Eggetarian",
                requestedMealCount = 4,
                limitations = emptySet(),
                foods = foods + ProjectFoodRecord(
                    "unknown-restrictive",
                    "Unknown Diet Dish",
                    "Local",
                    "1 serving",
                    500,
                    50.0,
                    20.0,
                    20.0,
                    dietaryClassification = FoodDietaryClassification.UNKNOWN
                )
            )
        )
        val selected = plan.meals.flatMap { meal ->
            listOf(meal.primary) +
                meal.accompaniments.map { it.food } +
                meal.alternatives.map { it.food }
        }

        assertEquals(4, plan.meals.size)
        assertTrue(selected.isNotEmpty())
        assertTrue(selected.all {
            it.dietaryClassification in setOf(
                FoodDietaryClassification.VEGAN,
                FoodDietaryClassification.VEGETARIAN,
                FoodDietaryClassification.EGG
            )
        })
        assertTrue(selected.none { food ->
            listOf("chicken", "beef", "mutton", "fish", "prawn", "seafood", "nihari")
                .any { it in food.name.lowercase() }
        })
        assertTrue(selected.none { it.id == "unknown-restrictive" })
        assertFalse(plan.validationStatus == PlanValidationStatus.NEEDS_PROFILE_INPUT)
        assertTrue(plan.meals.all { it.portionMultiplier <= 2.0 })
        assertTrue(plan.meals.flatMap(LocalDietMeal::alternatives).all {
            it.dietaryCompatibilityStatus == "COMPATIBLE" &&
                it.portionDescription.isNotBlank() &&
                it.estimatedCalories != null
        })
    }

    @Test
    fun `calorie validation rejects large shortfall and accepts target tolerance`() {
        val limitedFoods = (1..4).map { index ->
            ProjectFoodRecord(
                id = "limited-$index",
                name = "Vegetarian Bowl $index",
                category = "Vegetable Dishes",
                servingSize = "1 bowl",
                calories = 150,
                proteinGrams = 10.0,
                carbsGrams = 20.0,
                fatGrams = 4.0,
                dietaryClassification = FoodDietaryClassification.VEGETARIAN
            )
        }
        val shortPlan = LocalPakistaniDietPlanGenerator().generate(
            LocalDietPlanInput("Gain Muscle", 2555, 100.0, 379.0, 71.0, "Eggitarian", 4, emptySet(), foods = limitedFoods)
        )
        val targetPlan = LocalPakistaniDietPlanGenerator().generate(
            LocalDietPlanInput("Gain Muscle", 1500, null, null, null, "Eggitarian", 4, emptySet(), foods = limitedFoods)
        )

        assertEquals(PlanValidationStatus.NEEDS_ADJUSTMENT, shortPlan.validationStatus)
        assertTrue(shortPlan.estimatedTotalCalories!! < 2555 * 0.9)
        assertEquals(PlanValidationStatus.NEEDS_PROFILE_INPUT, targetPlan.validationStatus)
    }

    @Test
    fun `calories and every macro validate independently`() {
        val failures = validateDietNutritionTargets(
            actualCalories = 2456.0,
            targetCalories = 2555.0,
            actualProtein = 147.0,
            targetProtein = 100.0,
            actualCarbs = 308.0,
            targetCarbs = 379.0,
            actualFat = 78.0,
            targetFat = 71.0
        )

        assertFalse(failures.any { it.startsWith("calories") })
        assertTrue(failures.any { it == "protein 147 vs target 100" })
        assertFalse(failures.any { it.startsWith("carbohydrate") })
        assertFalse(failures.any { it.startsWith("fat") })
    }

    @Test
    fun `missing dietary preference cannot produce validated personalization`() {
        val plan = LocalPakistaniDietPlanGenerator().generate(
            LocalDietPlanInput(
                goal = "Gain Muscle",
                calorieTarget = 2400,
                proteinTargetGrams = 120.0,
                carbsTargetGrams = 330.0,
                fatTargetGrams = 67.0,
                dietaryPreference = null,
                requestedMealCount = 4,
                limitations = emptySet(),
                foods = foods
            )
        )

        assertEquals(PlanValidationStatus.NEEDS_PROFILE_INPUT, plan.validationStatus)
    }

    @Test
    fun `practical serving labels use natural singular plural and totals`() {
        val food = ProjectFoodRecord(
            id = "piece",
            name = "Roti",
            category = "Breads",
            servingSize = "1 piece (60g)",
            calories = 120,
            proteinGrams = 4.0,
            carbsGrams = 24.0,
            fatGrams = 2.0
        )

        assertEquals("1 piece, 60 g total", practicalServingDescription(food, 1.0))
        assertEquals("2 pieces, 120 g total", practicalServingDescription(food, 2.0))
    }

    @Test
    fun `six day strength plan enforces movement templates and excludes mobility records`() {
        val badRecords = listOf(
            exercise("balance", "Balance Board", "dumbbell", "upper legs", "quadriceps", "upper legs"),
            exercise("toe-touch", "Basic Toe Touch", "dumbbell", "upper legs", "glutes", "upper legs"),
            exercise("yoga", "Butterfly Yoga Pose", "dumbbell", "upper legs", "quadriceps", "upper legs"),
            exercise("stretch", "Back Pec Stretch", "dumbbell", "back", "upper back", "back")
        )
        val plan = LocalWorkoutPlanGenerator().generate(
            LocalWorkoutPlanInput(
                goal = "Gain Muscle",
                experience = "Intermediate",
                requestedDays = 6,
                equipment = setOf("Dumbbells"),
                recentWorkoutSummary = null,
                limitations = emptySet(),
                exercises = badRecords + exercises
            )
        )
        val names = plan.days.flatMap(LocalWorkoutDay::exercises).map { it.name.lowercase() }

        assertTrue(WorkoutPlanValidator.isValid(plan.days))
        assertTrue(names.none { "stretch" in it || "yoga" in it || "balance" in it || "toe touch" in it })
        assertEquals(6, plan.days.size)
        assertTrue(
            plan.days.flatMap(LocalWorkoutDay::exercises)
                .groupingBy(LocalWorkoutExercise::id)
                .eachCount()
                .values
                .all { it <= 2 }
        )
    }

    @Test
    fun `three day dumbbell full body split preserves required coverage within reuse limit`() {
        val plan = LocalWorkoutPlanGenerator().generate(
            LocalWorkoutPlanInput(
                goal = "Gain Muscle",
                experience = "Intermediate",
                requestedDays = 3,
                equipment = setOf("Dumbbells"),
                recentWorkoutSummary = null,
                limitations = emptySet(),
                splitPreference = WorkoutSplitPreference.FULL_BODY,
                exercises = exercises
            )
        )

        assertEquals(3, plan.days.size)
        assertTrue(WorkoutPlanValidator.isValid(plan.days))
        plan.days.forEach { day ->
            val patterns = day.exercises.map(LocalWorkoutExercise::movementPattern)
            assertTrue(
                "${day.focus} must contain lower-body, push, pull, and core/carry coverage",
                patterns.any {
                    it == MovementPattern.SQUAT ||
                        it == MovementPattern.HINGE ||
                        it == MovementPattern.LUNGE
                } && patterns.any {
                    it == MovementPattern.HORIZONTAL_PUSH || it == MovementPattern.VERTICAL_PUSH
                } && patterns.any {
                    it == MovementPattern.HORIZONTAL_PULL || it == MovementPattern.VERTICAL_PULL
                } && patterns.any {
                    it == MovementPattern.CORE || it == MovementPattern.CARRY
                }
            )
        }
        assertTrue(
            plan.days.flatMap(LocalWorkoutDay::exercises)
                .groupingBy(LocalWorkoutExercise::id)
                .eachCount()
                .values
                .all { it <= 2 }
        )
        assertTrue(
            plan.days.flatMap(LocalWorkoutDay::exercises).any {
                it.id == "db-carry" && it.movementPattern == MovementPattern.CARRY
            }
        )
    }

    @Test
    fun `full body reserves trunk coverage before unused optional slots fill day three`() {
        val pressureExercises = listOf(
            exercise("pressure-hpush-1", "Dumbbell Floor Press One", "dumbbell", "chest", "pectorals", "chest"),
            exercise("pressure-hpush-2", "Dumbbell Floor Press Two", "dumbbell", "chest", "pectorals", "chest"),
            exercise("pressure-vpush-1", "Dumbbell Shoulder Press One", "dumbbell", "shoulders", "delts", "shoulders"),
            exercise("pressure-vpush-2", "Dumbbell Shoulder Press Two", "dumbbell", "shoulders", "delts", "shoulders"),
            exercise("pressure-hpull-1", "Dumbbell Bent Over Row One", "dumbbell", "back", "upper back", "back"),
            exercise("pressure-hpull-2", "Dumbbell Bent Over Row Two", "dumbbell", "back", "upper back", "back"),
            exercise("pressure-hpull-3", "Dumbbell Bent Over Row Three", "dumbbell", "back", "upper back", "back"),
            exercise("pressure-squat-1", "Dumbbell Goblet Squat One", "dumbbell", "upper legs", "quads", "upper legs"),
            exercise("pressure-squat-2", "Dumbbell Front Squat Two", "dumbbell", "upper legs", "quads", "upper legs"),
            exercise("pressure-hinge-1", "Dumbbell Deadlift One", "dumbbell", "upper legs", "hamstrings", "upper legs"),
            exercise("pressure-hinge-2", "Dumbbell Romanian Deadlift Two", "dumbbell", "upper legs", "hamstrings", "upper legs"),
            exercise("pressure-lunge-1", "Dumbbell Reverse Lunge One", "dumbbell", "upper legs", "glutes", "upper legs"),
            exercise("pressure-lunge-2", "Dumbbell Walking Lunge Two", "dumbbell", "upper legs", "glutes", "upper legs"),
            exercise("pressure-core", "Dumbbell Dead Bug", "dumbbell", "waist", "abs", "waist"),
            exercise("pressure-carry", "Dumbbell Suitcase Carry", "dumbbell", "waist", "abs", "waist")
        )
        val plan = LocalWorkoutPlanGenerator().generate(
            LocalWorkoutPlanInput(
                goal = "Gain Muscle",
                experience = "Intermediate",
                requestedDays = 3,
                equipment = setOf("Dumbbells"),
                recentWorkoutSummary = null,
                limitations = emptySet(),
                splitPreference = WorkoutSplitPreference.FULL_BODY,
                exercises = pressureExercises
            )
        )

        assertEquals(3, plan.days.size)
        val firstTwoExerciseIds = plan.days.take(2).flatMap(LocalWorkoutDay::exercises)
            .map(LocalWorkoutExercise::id)
        assertEquals(1, firstTwoExerciseIds.count { it == "pressure-core" })
        assertEquals(1, firstTwoExerciseIds.count { it == "pressure-carry" })
        val dayThree = plan.days[2]
        val dayThreePatterns = dayThree.exercises.map(LocalWorkoutExercise::movementPattern)
        assertTrue(dayThreePatterns.any { it in setOf(MovementPattern.SQUAT, MovementPattern.HINGE, MovementPattern.LUNGE) })
        assertTrue(dayThreePatterns.any { it in setOf(MovementPattern.HORIZONTAL_PUSH, MovementPattern.VERTICAL_PUSH) })
        assertTrue(dayThreePatterns.any { it in setOf(MovementPattern.HORIZONTAL_PULL, MovementPattern.VERTICAL_PULL) })
        assertTrue(dayThreePatterns.any { it == MovementPattern.CORE || it == MovementPattern.CARRY })
        assertEquals(1, dayThreePatterns.count { it == MovementPattern.CORE || it == MovementPattern.CARRY })
        assertFalse(
            dayThree.exercises.first {
                it.movementPattern in setOf(MovementPattern.SQUAT, MovementPattern.HINGE, MovementPattern.LUNGE)
            }.id in firstTwoExerciseIds
        )
        assertFalse(
            dayThree.exercises.first {
                it.movementPattern in setOf(MovementPattern.HORIZONTAL_PUSH, MovementPattern.VERTICAL_PUSH)
            }.id in firstTwoExerciseIds
        )
        assertFalse(
            dayThree.exercises.first {
                it.movementPattern in setOf(MovementPattern.HORIZONTAL_PULL, MovementPattern.VERTICAL_PULL)
            }.id in firstTwoExerciseIds
        )
        assertTrue(
            dayThree.exercises.first {
                it.movementPattern == MovementPattern.CORE || it.movementPattern == MovementPattern.CARRY
            }.id in firstTwoExerciseIds
        )
        assertTrue(dayThree.exercises.size <= 5)
        assertTrue(
            plan.days.flatMap(LocalWorkoutDay::exercises)
                .groupingBy(LocalWorkoutExercise::id)
                .eachCount()
                .values
                .all { it <= 2 }
        )
        assertTrue(WorkoutPlanValidator.isValid(plan.days))
    }

    @Test
    fun `generic intermediate muscle plan treats seven days as availability`() {
        val response = orchestrator.respond(
            "Build a workout plan according to my profile.",
            CoachContext(
                goal = "Gain Muscle",
                experience = "Intermediate, 6-12 months",
                equipment = setOf("Dumbbells"),
                workoutDays = 7
            ),
            retriever
        )

        assertEquals(4, response.workoutPlan?.days?.size)
        assertTrue(response.summary.contains("Available training days: 7"))
        assertTrue(response.summary.contains("Programmed sessions: 4"))
        assertTrue(response.summary.contains("Recovery days: 3"))
    }

    @Test
    fun `explicit safe six day request keeps six sessions`() {
        val response = orchestrator.respond(
            "Give me a six-day workout plan.",
            CoachContext(
                goal = "Gain Muscle",
                experience = "Intermediate",
                equipment = setOf("Dumbbells"),
                workoutDays = 7
            ),
            retriever
        )

        assertEquals(6, response.workoutPlan?.days?.size)
        assertTrue(response.summary.contains("requested 6-session frequency"))
    }

    @Test
    fun `compound and core prescriptions are exercise specific`() {
        val deadlift = ExercisePrescriptionPolicy.forExercise(
            MovementPattern.HINGE,
            "Gain Muscle",
            "Intermediate"
        )
        val sitUp = ExercisePrescriptionPolicy.forExercise(
            MovementPattern.CORE,
            "Gain Muscle",
            "Intermediate"
        )

        assertEquals(4, deadlift.sets)
        assertEquals("6-10 reps", deadlift.reps)
        assertEquals(150, deadlift.restSeconds)
        assertEquals(3, sitUp.sets)
        assertTrue(sitUp.reps.contains("controlled reps"))
        assertEquals(60, sitUp.restSeconds)
    }

    @Test
    fun `descriptive profile experience labels preserve canonical prescriptions`() {
        listOf(
            "Beginner" to "Beginner <6 months",
            "Intermediate" to "Intermediate, 6-12 months",
            "Advanced" to "Advanced >1 year"
        ).forEach { (canonical, descriptive) ->
            assertEquals(
                ExercisePrescriptionPolicy.forExercise(MovementPattern.HINGE, "Gain Muscle", canonical),
                ExercisePrescriptionPolicy.forExercise(MovementPattern.HINGE, "Gain Muscle", descriptive)
            )
        }
    }

    @Test
    fun `pullover to press cannot satisfy a pull slot`() {
        val classified = ExerciseMovementClassifier.classify(
            exercise("hybrid", "Dumbbell Pullover to Press", "dumbbell", "back", "lats", "back")
        )
        assertEquals(MovementPattern.HORIZONTAL_PUSH, classified.pattern)
    }

    @Test
    fun `empty catalogues return safe bounded fallback`() {
        val emptyOrchestrator = LocalCoachOrchestrator(
            exerciseProvider = { emptyList() },
            foodProvider = { emptyList() }
        )
        val response = emptyOrchestrator.respond(
            "Give me a four-day workout plan.",
            CoachContext(),
            FitnessKnowledgeRetriever.from(emptyList())
        )

        assertEquals(AiCoachIntent.WORKOUT_PLAN, response.metadata.intent)
        assertTrue(response.metadata.fallbackUsed)
        assertTrue(response.workoutNote.contains("unavailable", ignoreCase = true))
        assertTrue(response.safetyDisclaimer.contains("General fitness guidance"))
    }

    @Test
    fun `malformed records are ignored without crashing`() {
        val malformedExercise = exercises.first().copy(id = "", name = "")
        val malformedFood = foods.first().copy(id = "", name = "", calories = -1)
        val safeOrchestrator = LocalCoachOrchestrator(
            exerciseProvider = { listOf(malformedExercise) + exercises.take(8) },
            foodProvider = { listOf(malformedFood) + foods.take(8) }
        )

        val workout = safeOrchestrator.respond(
            "Give me a two-day workout plan.",
            CoachContext(equipment = setOf("Dumbbells")),
            retriever
        )
        val diet = safeOrchestrator.respond(
            "Build Pakistani food diet plan for me.",
            CoachContext(),
            retriever
        )

        assertFalse(workout.metadata.knowledgeRecordIds.contains(""))
        assertFalse(diet.metadata.knowledgeRecordIds.any { it == "legacy-food-" })
    }

    private fun exerciseFixtures(): List<ExercisePackRecord> {
        val dumbbells = listOf(
            exercise("db-floor-press", "Dumbbell Floor Press", "dumbbell", "chest", "pectorals", "chest"),
            exercise("db-push-up", "Deep Push-Up with Dumbbells", "dumbbell", "chest", "pectorals", "chest"),
            exercise("db-row", "Dumbbell Bent Over Row", "dumbbell", "back", "upper back", "back"),
            exercise("db-one-row", "Dumbbell One Arm Row", "dumbbell", "back", "lats", "back"),
            exercise("db-shoulder", "Dumbbell Shoulder Press", "dumbbell", "shoulders", "delts", "shoulders"),
            exercise("db-lateral", "Dumbbell Lateral Raise", "dumbbell", "shoulders", "delts", "shoulders"),
            exercise("db-curl", "Dumbbell Biceps Curl", "dumbbell", "upper arms", "biceps", "upper arms"),
            exercise("db-triceps", "Dumbbell Triceps Extension", "dumbbell", "upper arms", "triceps", "upper arms"),
            exercise("db-goblet", "Dumbbell Goblet Squat", "dumbbell", "upper legs", "quads", "upper legs"),
            exercise("db-squat", "Dumbbell Front Squat", "dumbbell", "upper legs", "quads", "upper legs"),
            exercise("db-rdl", "Dumbbell Romanian Deadlift", "dumbbell", "upper legs", "hamstrings", "upper legs"),
            exercise("db-deadlift", "Dumbbell Deadlift", "dumbbell", "upper legs", "glutes", "upper legs"),
            exercise("db-lunge", "Dumbbell Reverse Lunge", "dumbbell", "upper legs", "glutes", "upper legs"),
            exercise("db-split", "Dumbbell Split Squat", "dumbbell", "upper legs", "quads", "upper legs"),
            exercise("db-core", "Dumbbell Dead Bug", "dumbbell", "waist", "abs", "waist"),
            exercise("db-carry", "Dumbbell Suitcase Carry", "dumbbell", "waist", "abs", "waist"),
            exercise("db-clean", "Dumbbell Clean", "dumbbell", "upper legs", "glutes", "upper legs"),
            exercise("db-burpee", "Dumbbell Burpee", "dumbbell", "cardio", "cardiovascular system", "cardio")
        )
        val bodyweight = listOf(
            exercise("body-push-up", "Bodyweight Push-Up", "bodyweight", "chest", "pectorals", "chest"),
            exercise("body-close-push-up", "Bodyweight Close Grip Push-Up", "bodyweight", "chest", "pectorals", "chest"),
            exercise("body-inverted-row", "Bodyweight Inverted Row", "bodyweight", "back", "upper back", "back"),
            exercise("body-reverse-row", "Bodyweight Reverse Row", "bodyweight", "back", "lats", "back"),
            exercise("body-squat", "Bodyweight Squat", "bodyweight", "upper legs", "quads", "upper legs"),
            exercise("body-sumo-squat", "Bodyweight Sumo Squat", "bodyweight", "upper legs", "quads", "upper legs"),
            exercise("body-bridge", "Bodyweight Glute Bridge", "bodyweight", "upper legs", "glutes", "upper legs"),
            exercise("body-hip-thrust", "Bodyweight Hip Thrust", "bodyweight", "upper legs", "glutes", "upper legs"),
            exercise("body-lunge", "Bodyweight Reverse Lunge", "bodyweight", "upper legs", "glutes", "upper legs"),
            exercise("body-walking-lunge", "Bodyweight Walking Lunge", "bodyweight", "upper legs", "quads", "upper legs"),
            exercise("body-plank", "Bodyweight Plank", "bodyweight", "waist", "abs", "waist"),
            exercise("body-dead-bug", "Bodyweight Dead Bug", "bodyweight", "waist", "abs", "waist")
        )
        val barbells = (1..4).map { index ->
            exercise(
                id = "verified-barbell-$index",
                name = "Barbell Exercise $index",
                equipment = "barbell",
                bodyPart = if (index % 2 == 0) "upper legs" else "chest",
                target = if (index % 2 == 0) "quadriceps" else "pectorals",
                pattern = if (index % 2 == 0) "squat" else "push"
            )
        }
        val incompatible = listOf(
            exercise("db-bench", "Dumbbell Bench Press", "dumbbell", "chest", "pectorals", "chest"),
            exercise("cable-row", "Cable Seated Row", "cable", "back", "upper back", "back"),
            exercise("machine-press", "Machine Chest Press", "machine", "chest", "pectorals", "chest")
        )
        return dumbbells + bodyweight + barbells + incompatible
    }

    private fun completePlanContext() = CoachContext(
        goal = "Gain Muscle",
        experience = "Intermediate",
        equipment = setOf("Dumbbells"),
        calorieTarget = 2400,
        proteinTargetGrams = 140.0,
        carbsTargetGrams = 300.0,
        fatTargetGrams = 70.0,
        dietaryPreference = "No preference",
        mealsPerDay = 4,
        workoutDays = 7
    )

    private fun exercise(
        id: String,
        name: String,
        equipment: String,
        bodyPart: String,
        target: String,
        pattern: String
    ) = ExercisePackRecord(
        id = id,
        name = name,
        aliases = emptyList(),
        movementPattern = pattern,
        category = bodyPart,
        bodyPart = bodyPart,
        target = target,
        muscleGroup = target,
        secondaryMuscles = emptyList(),
        bodyTargets = listOf(target),
        equipment = listOf(equipment),
        experienceLevels = listOf("beginner", "intermediate"),
        goals = listOf("strength", "muscle", "fat_loss"),
        instructions = "Use controlled technique.",
        safetyNote = "Stop if the movement causes pain.",
        source = verifiedSource(id)
    )

    private fun foodFixtures(): List<ProjectFoodRecord> {
        val named = listOf(
            ProjectFoodRecord("1", "Grilled Chicken", "Protein", "1 plate", 320, 45.0, 8.0, 10.0, dietaryClassification = FoodDietaryClassification.MEAT),
            ProjectFoodRecord("2", "Daal Masoor", "Legumes", "1 bowl", 230, 16.0, 35.0, 4.0, dietaryClassification = FoodDietaryClassification.VEGETARIAN),
            ProjectFoodRecord("3", "Anda Paratha", "Breakfast", "1 serving", 380, 15.0, 42.0, 17.0, dietaryClassification = FoodDietaryClassification.EGG),
            ProjectFoodRecord("4", "Chana Chaat", "Snack", "1 bowl", 260, 12.0, 40.0, 6.0, dietaryClassification = FoodDietaryClassification.VEGETARIAN),
            ProjectFoodRecord("5", "Fish Curry", "Protein", "1 bowl", 300, 32.0, 12.0, 14.0, dietaryClassification = FoodDietaryClassification.MEAT),
            ProjectFoodRecord("6", "Mixed Sabzi", "Vegetables", "1 bowl", 180, 6.0, 24.0, 7.0, dietaryClassification = FoodDietaryClassification.VEGETARIAN)
        )
        return named + (7..74).map { index ->
            ProjectFoodRecord(
                id = index.toString(),
                name = "Pakistani Food $index",
                category = "Local",
                servingSize = "1 serving",
                calories = 150 + index,
                proteinGrams = (index % 12 + 3).toDouble(),
                carbsGrams = (index % 30 + 15).toDouble(),
                fatGrams = (index % 8 + 2).toDouble(),
                dietaryClassification = FoodDietaryClassification.VEGETARIAN
            )
        }
    }

    private fun verifiedSource(id: String) = SourceMetadata(
        sourceName = "FitDesi test fixture",
        sourceRepository = "local-test",
        sourceRecordId = id,
        licenseIdentifier = "MIT",
        licenseScope = "Test metadata",
        retrievedAt = "2026-07-16",
        modifiedByFitDesi = false,
        redistributionStatus = RedistributionStatus.VERIFIED,
        licenseStatus = LicenseStatus.VERIFIED,
        attribution = "FitDesi test fixture"
    )
}
