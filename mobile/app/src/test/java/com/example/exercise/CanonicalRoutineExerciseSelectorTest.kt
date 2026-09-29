package com.example.exercise

import com.example.exercise.programming.ExercisePrescriptionMode
import com.example.exercise.programming.ExerciseProgrammingMetadata
import com.example.exercise.programming.ExerciseProgrammingRegion
import com.example.exercise.programming.ExerciseProgrammingReviewStatus
import com.example.exercise.programming.ExerciseProgrammingRole
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalRoutineExerciseSelectorTest {
    private val selector = CanonicalRoutineExerciseSelector(FakeCatalogue())

    @Test
    fun `bodyweight and unanchored bands reject pull-dependent configurations explicitly`() {
        val cases = listOf(
            request(equipment = setOf("Bodyweight"), split = "Pull", dayFocus = "Pull"),
            request(equipment = setOf("Bodyweight"), split = "Push / Pull / Legs", dayFocus = "Push"),
            request(equipment = setOf("Bodyweight"), split = "Upper / Lower", dayFocus = "Upper Body"),
            request(equipment = setOf("Bodyweight"), split = "Full Body", dayFocus = "Full Body"),
            request(equipment = setOf("Resistance bands"), split = "Pull", dayFocus = "Pull")
        )

        cases.forEach { request ->
            val result = selector.selectDayResult(richCatalogue(), request)

            assertTrue(request.toString(), result is CanonicalRoutineSelectionResult.Unsupported)
            assertEquals(
                PULL_CAPABILITY_UNAVAILABLE_MESSAGE,
                (result as CanonicalRoutineSelectionResult.Unsupported).message
            )
        }
    }

    @Test
    fun `dumbbells and full gym retain supported pull capability`() {
        listOf(setOf("Dumbbells"), setOf("Full gym")).forEach { equipment ->
            val pullCatalogue = (1..4).map { ordinal ->
                exercise(
                    id = "dumbbell-pull-$ordinal",
                    name = "Dumbbell pull $ordinal",
                    bodyPart = "back",
                    primaryMuscle = if (ordinal == 4) "biceps" else "lats",
                    equipment = "dumbbell"
                )
            }
            val result = selector.selectDayResult(
                pullCatalogue,
                request(equipment = equipment, split = "Pull", dayFocus = "Pull")
            )

            assertTrue(equipment.toString(), result is CanonicalRoutineSelectionResult.Supported)
            assertEquals(4, (result as CanonicalRoutineSelectionResult.Supported).exercises.size)
        }
    }

    @Test
    fun `supported capability with insufficient main coverage returns coverage failure`() {
        val result = selector.selectDayResult(
            richCatalogue().filter { it.id.value == "lower-1" },
            request(equipment = setOf("Bodyweight"), split = "Legs", dayFocus = "Legs")
        )

        assertEquals(
            CanonicalRoutineSelectionResult.CoverageFailure(ROUTINE_COVERAGE_UNAVAILABLE_MESSAGE),
            result
        )
    }

    @Test
    fun `beginner hard constraints reject advanced and unsupported phone QA evidence`() {
        val cases = listOf(
            RejectedPhoneCandidate(
                exercise("3294", "archer push up", "chest", "pectorals", instructions = "Extend one arm straight out to the side and repeat."),
                "Push"
            ),
            RejectedPhoneCandidate(
                exercise("0251", "chest dip", "chest", "pectorals", instructions = "Position yourself on parallel bars and repeat."),
                "Push"
            ),
            RejectedPhoneCandidate(
                exercise("1430", "chest dip (on dip-pull-up cage)", "chest", "pectorals", instructions = "Grip the dip bars and repeat."),
                "Push"
            ),
            RejectedPhoneCandidate(
                exercise("3297", "back lever", "back", "lats", instructions = "Hang from a pull-up bar and repeat."),
                "Pull"
            ),
            RejectedPhoneCandidate(
                exercise("0139", "biceps narrow pull-ups", "back", "biceps", instructions = "Hang from a pull-up bar and repeat."),
                "Pull"
            ),
            RejectedPhoneCandidate(
                exercise("1326", "chin-up", "back", "lats", instructions = "Hang from a pull-up bar and repeat."),
                "Pull"
            ),
            RejectedPhoneCandidate(
                exercise("0140", "biceps pull-up", "back", "biceps", instructions = "Hang from a pull-up bar and repeat."),
                "Pull"
            ),
            RejectedPhoneCandidate(
                exercise("0130", "bench hip extension", "upper legs", "glutes", instructions = "Sit on a bench and repeat."),
                "Lower Body"
            ),
            RejectedPhoneCandidate(
                exercise("1374", "box jump down with one leg stabilization", "lower legs", "calves", instructions = "Jump onto the box and stabilize on one leg."),
                "Lower Body"
            ),
            RejectedPhoneCandidate(
                exercise("1274", "deep push up", "chest", "pectorals", equipment = "dumbbell", instructions = "Start in a high plank and repeat."),
                "Push"
            ),
            RejectedPhoneCandidate(
                exercise("2137", "dumbbell arnold press", "shoulders", "delts", equipment = "dumbbell", instructions = "Sit on a bench with back support and repeat with dumbbells."),
                "Push"
            ),
            RejectedPhoneCandidate(
                exercise("0304", "dumbbell decline shrug v. 2", "back", "traps", equipment = "dumbbell", instructions = "Lie on a decline bench with dumbbells and repeat."),
                "Pull"
            ),
            RejectedPhoneCandidate(
                exercise("0295", "dumbbell clean", "upper legs", "glutes", equipment = "dumbbell", instructions = "Explosively jump and catch the dumbbells at shoulder height."),
                "Lower Body"
            )
        )

        cases.forEach { case ->
            val safeCandidates = (1..4).map { ordinal ->
                val bodyPart = when (case.focus) {
                    "Push" -> "chest"
                    "Pull" -> "back"
                    else -> "upper legs"
                }
                val muscle = when (case.focus) {
                    "Push" -> listOf("pectorals", "delts", "triceps", "chest")[ordinal - 1]
                    "Pull" -> listOf("lats", "upper back", "biceps", "forearms")[ordinal - 1]
                    else -> listOf("quadriceps", "glutes", "hamstrings", "calves")[ordinal - 1]
                }
                exercise(
                    id = "safe-${case.candidate.id.value}-$ordinal",
                    name = "Z Dumbbell ${case.focus} $ordinal",
                    bodyPart = bodyPart,
                    primaryMuscle = muscle,
                    equipment = "dumbbell"
                )
            }
            val selected = selector.selectDay(
                listOf(case.candidate) + safeCandidates,
                request(
                    equipment = setOf("Dumbbells"),
                    level = "Beginner",
                    split = case.focus,
                    dayFocus = case.focus
                )
            )

            assertEquals(case.candidate.id.value, 4, selected.size)
            assertTrue(case.candidate.id.value, selected.none { it.id == case.candidate.id })
        }
    }

    @Test
    fun `all supported equipment choices return compatible canonical records`() {
        val equipment = listOf(
            setOf("Bodyweight"),
            setOf("Dumbbells"),
            setOf("Barbell and rack"),
            setOf("Resistance bands"),
            setOf("Full gym")
        )

        equipment.forEach { choice ->
            val selected = selector.selectDay(
                richCatalogue() + exercise("unsupported", "A unsupported", "upper legs", "glutes", "kettlebell"),
                request(equipment = choice)
            )

            assertEquals(choice.toString(), 4, selected.size)
            assertEquals(choice.toString(), 4, selected.map { it.id.value }.distinct().size)
            assertTrue(choice.toString(), selected.none { it.id.value == "unsupported" })
        }
    }

    @Test
    fun `dumbbells plus resistance bands unions capabilities and excludes incompatible records`() {
        val catalogue = listOf(
            exercise("dumbbell-lower", "Dumbbell lower", "upper legs", "glutes", "dumbbell"),
            exercise("band-push", "Band push", "chest", "pectorals", "band"),
            exercise("bodyweight-pull", "Bodyweight pull", "back", "lats", "body weight"),
            exercise("dumbbell-core", "Dumbbell core", "waist", "abs", "dumbbell"),
            exercise("incompatible", "Kettlebell core", "waist", "abs", "kettlebell")
        )

        val selected = selector.selectDay(
            catalogue,
            request(equipment = setOf("Dumbbells", "Resistance bands"))
        )

        assertEquals(
            listOf("dumbbell-lower", "band-push", "bodyweight-pull", "dumbbell-core"),
            selected.map { it.id.value }
        )
        assertTrue(selected.none { it.id.value == "incompatible" })
    }

    @Test
    fun `AI generator equipment labels expand through shared canonical capabilities`() {
        assertTrue(CanonicalEquipmentCapabilities.isCompatible(listOf("cable"), setOf("Cables")))
        assertTrue(CanonicalEquipmentCapabilities.isCompatible(listOf("leverage machine"), setOf("Machine")))
        assertTrue(CanonicalEquipmentCapabilities.isCompatible(listOf("body weight"), setOf("Dumbbells")))
        assertTrue(!CanonicalEquipmentCapabilities.isCompatible(listOf("barbell"), setOf("Bodyweight")))
        assertTrue(!CanonicalEquipmentCapabilities.isCompatible(listOf("kettlebell"), setOf("Kettlebells")))
        assertTrue(!CanonicalEquipmentCapabilities.isCompatible(listOf("body weight"), setOf("Kettlebells")))
        assertTrue(!CanonicalEquipmentCapabilities.isCompatible(listOf("pull-up bar"), setOf("Pull-up Bar")))
    }

    @Test
    fun `all supported split day focuses return four exercises in requested region order`() {
        val cases = listOf(
            Triple("Full Body", "Full Body", listOf("lower-1", "push-1", "pull-1", "core-1")),
            Triple("Upper / Lower", "Upper Body", listOf("push-1", "pull-1", "push-2", "pull-2")),
            Triple("Upper / Lower", "Lower Body", listOf("lower-1", "lower-2", "lower-3", "lower-4")),
            Triple("Push / Pull / Legs", "Push", listOf("push-1", "push-2", "push-3", "push-4")),
            Triple("Push / Pull / Legs", "Pull", listOf("pull-1", "pull-2", "pull-3", "pull-4")),
            Triple("Push / Pull / Legs", "Legs", listOf("lower-1", "lower-2", "lower-3", "lower-4")),
            Triple("Body Part Split", "Chest & Triceps", listOf("push-1", "push-2", "push-3", "push-4")),
            Triple("Body Part Split", "Back & Biceps", listOf("pull-1", "pull-2", "pull-3", "pull-4")),
            Triple("Body Part Split", "Shoulders & Core", listOf("push-1", "pull-1", "core-1", "core-2")),
            Triple("Body Part Split", "Conditioning", listOf("conditioning", "lower-1", "push-1", "core-1"))
        )

        cases.forEach { (split, focus, expectedIds) ->
            val selected = selector.selectDay(
                richCatalogue(),
                request(equipment = setOf("Full gym"), split = split, dayFocus = focus)
            )

            assertEquals("$split - $focus", 4, selected.size)
            assertEquals("$split - $focus", 4, selected.map { it.id.value }.distinct().size)
            assertEquals("$split - $focus", expectedIds, selected.map { it.id.value })
        }
    }

    @Test
    fun `goal and experience metadata rank and filter compatible records`() {
        val catalogue = listOf(
            exercise("goal-match", "A goal match", "chest", "pectorals", goals = listOf("get stronger")),
            exercise("goal-empty", "D goal empty", "chest", "pectorals"),
            exercise("goal-other", "C goal other", "chest", "pectorals", goals = listOf("build muscle")),
            exercise("beginner", "C beginner", "chest", "pectorals", levels = listOf("beginner")),
            exercise("intermediate", "B intermediate", "chest", "pectorals", levels = listOf("intermediate")),
            exercise("advanced", "A advanced", "chest", "pectorals", levels = listOf("advanced"))
        )

        val beginner = selector.selectDay(
            catalogue,
            request(goal = "Strength", level = "Beginner", dayFocus = "Push")
        )
        val advanced = selector.selectDay(
            catalogue,
            request(goal = "Strength", level = "Advanced", dayFocus = "Push")
        )
        val intermediate = selector.selectDay(
            catalogue,
            request(goal = "Strength", level = "Intermediate", dayFocus = "Push")
        )

        assertEquals(
            listOf("beginner", "goal-match", "goal-empty", "goal-other"),
            beginner.map { it.id.value }
        )
        assertTrue(beginner.none { it.id.value == "advanced" })
        assertTrue(intermediate.any { it.id.value == "intermediate" })
        assertTrue(intermediate.none { it.id.value == "advanced" })
        assertTrue(advanced.any { it.id.value == "advanced" })
    }

    @Test
    fun `all supported goals prioritize matching catalogue metadata`() {
        val cases = mapOf(
            "Strength" to "get stronger",
            "Muscle Gain" to "build muscle",
            "Fat Loss" to "general fitness",
            "Endurance" to "general fitness",
            "General Fitness" to "general fitness"
        )

        cases.forEach { (goal, metadata) ->
            val catalogue = listOf(
                exercise("match", "Z matching goal", "chest", "pectorals", goals = listOf(metadata)),
                exercise("blank-1", "A blank goal", "chest", "pectorals"),
                exercise("blank-2", "B blank goal", "chest", "pectorals"),
                exercise("push-four", "C fourth push", "chest", "pectorals")
            )

            val selected = selector.selectDay(catalogue, request(goal = goal, dayFocus = "Push"))

            assertEquals(goal, "match", selected.first().id.value)
        }
    }

    @Test
    fun `day focus filters out unrelated regions`() {
        val legs = selector.selectDay(
            richCatalogue(),
            request(equipment = setOf("Full gym"), split = "Push / Pull / Legs", dayFocus = "Legs")
        )

        assertEquals(listOf("lower-1", "lower-2", "lower-3", "lower-4"), legs.map { it.id.value })
    }

    @Test
    fun `identical inputs are deterministic and duplicate IDs are removed`() {
        val duplicated = richCatalogue() + richCatalogue().first()
        val request = request(equipment = setOf("Full gym"))

        val first = selector.selectDay(duplicated, request)
        val second = selector.selectDay(duplicated.reversed(), request)

        assertEquals(first.map { it.id.value }, second.map { it.id.value })
        assertEquals(first.size, first.map { it.id.value }.distinct().size)
    }

    @Test
    fun `opaque leading-zero and duplicate-name IDs remain exact and distinct`() {
        val catalogue = listOf(
            exercise("fd-exercise-chair-squat", "Chair Squat", "upper legs", "glutes"),
            exercise("0257", "Canonical Push", "chest", "pectorals"),
            exercise("0088", "Duplicate Name", "back", "lats"),
            exercise("1371", "Duplicate Name", "waist", "abs")
        )

        val selected = selector.selectDay(catalogue, request())

        assertEquals(
            listOf("fd-exercise-chair-squat", "0257", "0088", "1371"),
            selected.map { it.id.value }
        )
        assertEquals(2, selected.count { it.name == "Duplicate Name" })
    }

    @Test
    fun `reviewed phase-only exercises including exact 1512 cannot become normal main work`() {
        val phaseOnly = listOf(
            exercise("1512", "all fours squad stretch", "upper legs", "quadriceps"),
            exercise("mobility", "A mobility candidate", "upper legs", "quadriceps"),
            exercise("dynamic", "A dynamic candidate", "upper legs", "quadriceps"),
            exercise("activation", "A activation candidate", "upper legs", "quadriceps")
        )
        val records = listOf(
            programming("1512", listOf(ExerciseProgrammingRole.STATIC_COOLDOWN)),
            programming("mobility", listOf(ExerciseProgrammingRole.MOBILITY)),
            programming("dynamic", listOf(ExerciseProgrammingRole.DYNAMIC_PREPARATION)),
            programming("activation", listOf(ExerciseProgrammingRole.ACTIVATION))
        )

        val selected = selector.selectDay(
            phaseOnly + richCatalogue(),
            request(equipment = setOf("Full gym"), dayFocus = "Legs"),
            records
        )

        assertEquals(4, selected.size)
        assertTrue(selected.none { it.id.value in phaseOnly.map { candidate -> candidate.id.value } })
        assertTrue(selected.none { it.id.value == "1512" })
    }

    @Test
    fun `never-auto-select wins while main strength remains eligible even when auto approval is false`() {
        val never = exercise("never", "A Never Candidate", "upper legs", "quadriceps")
        val protected0286 = exercise("0286", "A Reviewed Strength Candidate", "upper legs", "quadriceps")
        val records = listOf(
            programming(
                "never",
                listOf(
                    ExerciseProgrammingRole.MAIN_STRENGTH,
                    ExerciseProgrammingRole.NEVER_AUTO_SELECT
                )
            ),
            programming(
                "0286",
                listOf(ExerciseProgrammingRole.MAIN_STRENGTH),
                autoSelectApproved = false,
                beginnerSuitable = false
            )
        )

        val selected = selector.selectDay(
            listOf(never, protected0286) + richCatalogue(),
            request(equipment = setOf("Full gym"), dayFocus = "Legs"),
            records
        )

        assertTrue(selected.none { it.id.value == "never" })
        assertTrue(selected.any { it.id.value == "0286" })
    }

    @Test
    fun `reviewed beginner suitability restricts only Beginner main selection`() {
        val reviewed = exercise(
            "reviewed-strength",
            "A Reviewed Strength Candidate",
            "upper legs",
            "quadriceps"
        )
        val catalogue = listOf(reviewed) + richCatalogue()

        val beginnerUnsuitable = selector.selectDay(
            catalogue,
            request(equipment = setOf("Full gym"), level = "Beginner", dayFocus = "Legs"),
            listOf(
                programming(
                    reviewed.id.value,
                    listOf(ExerciseProgrammingRole.MAIN_STRENGTH),
                    beginnerSuitable = false
                )
            )
        )
        val beginnerSuitable = selector.selectDay(
            catalogue,
            request(equipment = setOf("Full gym"), level = "Beginner", dayFocus = "Legs"),
            listOf(
                programming(
                    reviewed.id.value,
                    listOf(ExerciseProgrammingRole.MAIN_STRENGTH),
                    beginnerSuitable = true
                )
            )
        )
        val intermediate = selector.selectDay(
            catalogue,
            request(equipment = setOf("Full gym"), level = "Intermediate", dayFocus = "Legs"),
            listOf(
                programming(
                    reviewed.id.value,
                    listOf(ExerciseProgrammingRole.MAIN_STRENGTH),
                    beginnerSuitable = false
                )
            )
        )
        val advanced = selector.selectDay(
            catalogue,
            request(equipment = setOf("Full gym"), level = "Advanced", dayFocus = "Legs"),
            listOf(
                programming(
                    reviewed.id.value,
                    listOf(ExerciseProgrammingRole.MAIN_STRENGTH),
                    beginnerSuitable = false
                )
            )
        )

        assertTrue(beginnerUnsuitable.none { it.id.value == reviewed.id.value })
        assertTrue(beginnerSuitable.any { it.id.value == reviewed.id.value })
        assertTrue(intermediate.any { it.id.value == reviewed.id.value })
        assertTrue(advanced.any { it.id.value == reviewed.id.value })
    }

    @Test
    fun `unreviewed Beginner fallback remains eligible and returns four mains`() {
        val unreviewed = exercise(
            "unreviewed-beginner",
            "A Unreviewed Beginner Candidate",
            "upper legs",
            "quadriceps",
            levels = listOf("beginner")
        )

        val selected = selector.selectDay(
            listOf(unreviewed) + richCatalogue(),
            request(equipment = setOf("Full gym"), level = "Beginner", dayFocus = "Legs"),
            programmingRecords = emptyList()
        )

        assertEquals(4, selected.size)
        assertTrue(selected.any { it.id.value == "unreviewed-beginner" })
    }

    @Test
    fun `reviewed quality tier wins before unreviewed fallback regardless of source order`() {
        val reviewed = listOf(
            exercise("reviewed-lower", "Z Reviewed Lower", "upper legs", "glutes"),
            exercise("reviewed-push", "Z Reviewed Push", "chest", "pectorals"),
            exercise("reviewed-pull", "Z Reviewed Pull", "back", "lats"),
            exercise("reviewed-core", "Z Reviewed Core", "waist", "abs")
        )
        val unreviewed = listOf(
            exercise("unreviewed-lower", "A Unreviewed Lower", "upper legs", "glutes"),
            exercise("unreviewed-push", "A Unreviewed Push", "chest", "pectorals"),
            exercise("unreviewed-pull", "A Unreviewed Pull", "back", "lats"),
            exercise("unreviewed-core", "A Unreviewed Core", "waist", "abs")
        )
        val records = reviewed.map { candidate ->
            programming(candidate.id.value, listOf(ExerciseProgrammingRole.MAIN_STRENGTH))
        }

        val forward = selector.selectDay(reviewed + unreviewed, request(), records)
        val reversed = selector.selectDay((reviewed + unreviewed).reversed(), request(), records.reversed())

        assertEquals(
            listOf("reviewed-lower", "reviewed-push", "reviewed-pull", "reviewed-core"),
            forward.map { it.id.value }
        )
        assertEquals(forward.map { it.id.value }, reversed.map { it.id.value })
    }

    @Test
    fun `explicit selected equipment outranks compatible bodyweight fallback`() {
        val dumbbell = listOf(
            exercise("db-lower", "Z Dumbbell Lower", "upper legs", "glutes", "dumbbell"),
            exercise("db-push", "Z Dumbbell Push", "chest", "pectorals", "dumbbell"),
            exercise("db-pull", "Z Dumbbell Pull", "back", "lats", "dumbbell"),
            exercise("db-core", "Z Dumbbell Core", "waist", "abs", "dumbbell")
        )
        val bodyweight = listOf(
            exercise("bw-lower", "A Bodyweight Lower", "upper legs", "glutes"),
            exercise("bw-push", "A Bodyweight Push", "chest", "pectorals"),
            exercise("bw-pull", "A Bodyweight Pull", "back", "lats"),
            exercise("bw-core", "A Bodyweight Core", "waist", "abs")
        )

        val selected = selector.selectDay(
            dumbbell + bodyweight,
            request(equipment = setOf("Dumbbells"))
        )

        assertEquals(listOf("db-lower", "db-push", "db-pull", "db-core"), selected.map { it.id.value })
    }

    @Test
    fun `duplicate reviewed family is suppressed when distinct push families exist`() {
        val exercises = listOf(
            exercise("dip-a", "A Dip", "chest", "triceps"),
            exercise("dip-b", "B Dip", "chest", "triceps"),
            exercise("press", "C Press", "chest", "pectorals"),
            exercise("shoulder", "D Shoulder Press", "shoulders", "delts"),
            exercise("extension", "E Triceps Extension", "upper arms", "triceps")
        )
        val records = listOf(
            programming("dip-a", listOf(ExerciseProgrammingRole.MAIN_STRENGTH), duplicateFamilyKey = "dip"),
            programming("dip-b", listOf(ExerciseProgrammingRole.MAIN_STRENGTH), duplicateFamilyKey = "dip"),
            programming("press", listOf(ExerciseProgrammingRole.MAIN_STRENGTH), duplicateFamilyKey = "press"),
            programming("shoulder", listOf(ExerciseProgrammingRole.MAIN_STRENGTH), duplicateFamilyKey = "shoulder"),
            programming("extension", listOf(ExerciseProgrammingRole.MAIN_STRENGTH), duplicateFamilyKey = "extension")
        )

        val selected = selector.selectDay(exercises, request(dayFocus = "Push"), records)

        assertEquals(4, selected.size)
        assertEquals(1, selected.count { it.id.value in setOf("dip-a", "dip-b") })
    }

    @Test
    fun `focused Push and Pull exhaust direct work before core fallback`() {
        val push = (1..4).map { index ->
            exercise("push-$index", "Push $index", "chest", "pectorals")
        }
        val pull = (1..4).map { index ->
            exercise("pull-$index", "Pull $index", "back", "lats")
        }
        val core = exercise("core-first", "A Core Filler", "waist", "abs")

        val pushSelected = selector.selectDay(listOf(core) + push, request(dayFocus = "Push"))
        val pullSelected = selector.selectDay(listOf(core) + pull, request(dayFocus = "Pull"))

        assertEquals(push.map { it.id.value }, pushSelected.map { it.id.value })
        assertEquals(pull.map { it.id.value }, pullSelected.map { it.id.value })
        assertTrue(pushSelected.none { it.id.value == "core-first" })
        assertTrue(pullSelected.none { it.id.value == "core-first" })
    }

    @Test
    fun `primary canonical focus wins over secondary muscle facets`() {
        val shoulderRaise = exercise("shoulder", "A Shoulder Raise", "shoulders", "delts")
            .copy(secondaryMuscles = listOf("upper back"))
        val pull = (1..4).map { index ->
            exercise("pull-$index", "Pull $index", "back", "lats")
        }
        val deadlift = exercise("deadlift", "A Deadlift", "upper legs", "glutes")
            .copy(secondaryMuscles = listOf("lower back"))
        val lower = (1..3).map { index ->
            exercise("lower-$index", "Lower $index", "upper legs", "quadriceps")
        }

        val pullSelected = selector.selectDay(listOf(shoulderRaise) + pull, request(dayFocus = "Pull"))
        val lowerSelected = selector.selectDay(listOf(deadlift) + lower, request(dayFocus = "Legs"))

        assertEquals(pull.map { it.id.value }, pullSelected.map { it.id.value })
        assertEquals(listOf("lower-1", "deadlift", "lower-2", "lower-3"), lowerSelected.map { it.id.value })
    }

    @Test
    fun `phone QA records follow reviewed roles suitability and equipment correction`() {
        val exercises = listOf(
            exercise("0020", "balance board", "upper legs", "quadriceps"),
            exercise("3212", "basic toe touch (male)", "upper legs", "glutes"),
            exercise("3214", "arms apart circular toe touch (male)", "upper legs", "glutes"),
            exercise("1473", "backward jump", "upper legs", "quadriceps"),
            exercise("3293", "archer pull up", "back", "lats"),
            exercise("1770", "biceps leg concentration curl", "upper arms", "biceps"),
            exercise("lower-1", "Lower 1", "upper legs", "glutes"),
            exercise("lower-2", "Lower 2", "upper legs", "hamstrings"),
            exercise("lower-3", "Lower 3", "upper legs", "calves"),
            exercise("lower-4", "Lower 4", "upper legs", "quadriceps"),
            exercise("pull-1", "Pull 1", "back", "lats"),
            exercise("pull-2", "Pull 2", "back", "upper back"),
            exercise("pull-3", "Pull 3", "upper arms", "biceps"),
            exercise("pull-4", "Pull 4", "lower arms", "forearms")
        )
        val records = listOf(
            programming("0020", listOf(ExerciseProgrammingRole.ACTIVATION), autoSelectApproved = false),
            programming("3212", listOf(ExerciseProgrammingRole.MOBILITY)),
            programming("3214", listOf(ExerciseProgrammingRole.DYNAMIC_PREPARATION), beginnerSuitable = false),
            programming("1473", listOf(ExerciseProgrammingRole.CONDITIONING), beginnerSuitable = false),
            programming("3293", listOf(ExerciseProgrammingRole.MAIN_STRENGTH), beginnerSuitable = false),
            programming(
                "1770",
                listOf(ExerciseProgrammingRole.MAIN_STRENGTH),
                equipment = listOf("dumbbell")
            )
        )

        val legsResult = selector.selectDayResult(
            exercises,
            request(equipment = setOf("Bodyweight"), level = "Beginner", split = "Legs", dayFocus = "Legs"),
            records
        )
        val pullResult = selector.selectDayResult(
            exercises,
            request(equipment = setOf("Dumbbells"), level = "Beginner", split = "Pull", dayFocus = "Pull"),
            records
        )
        assertTrue(legsResult is CanonicalRoutineSelectionResult.Supported)
        assertTrue(pullResult is CanonicalRoutineSelectionResult.Supported)
        val legs = (legsResult as CanonicalRoutineSelectionResult.Supported).exercises
        val pull = (pullResult as CanonicalRoutineSelectionResult.Supported).exercises

        assertEquals(setOf("lower-1", "lower-2", "lower-3", "lower-4"), legs.map { it.id.value }.toSet())
        assertEquals(setOf("pull-1", "pull-2", "pull-3", "pull-4"), pull.map { it.id.value }.toSet())
        assertTrue(legs.none { it.id.value in setOf("0020", "3212", "3214", "1473") })
        assertTrue(pull.none { it.id.value == "3293" || it.id.value == "1770" })
    }

    @Test
    fun `conditioning-only role is eligible only for explicit conditioning focus`() {
        val conditioning = exercise(
            "conditioning-only",
            "A Conditioning Candidate",
            "cardio",
            "cardiovascular system"
        )
        val records = listOf(
            programming("conditioning-only", listOf(ExerciseProgrammingRole.CONDITIONING))
        )
        val catalogue = conditioning.let { listOf(it) + richCatalogue() }

        val normal = selector.selectDay(
            catalogue,
            request(equipment = setOf("Full gym"), dayFocus = "Full Body"),
            records
        )
        val conditioningDay = selector.selectDay(
            catalogue,
            request(equipment = setOf("Full gym"), dayFocus = "Conditioning"),
            records
        )

        assertTrue(normal.none { it.id.value == "conditioning-only" })
        assertTrue(conditioningDay.any { it.id.value == "conditioning-only" })
    }

    @Test
    fun `unreviewed whole-word stretch and mobility names or aliases are rejected conservatively`() {
        val rejectedByName = exercise("stretch-name", "A stretch movement", "upper legs", "quadriceps")
        val rejectedByAlias = exercise("mobility-alias", "A controlled movement", "upper legs", "quadriceps")
            .copy(aliases = listOf("hip mobility"))
        val allowedNearMatch = exercise("stretching", "A stretching movement", "upper legs", "quadriceps")

        val selected = selector.selectDay(
            listOf(rejectedByName, rejectedByAlias, allowedNearMatch) + richCatalogue(),
            request(equipment = setOf("Full gym"), dayFocus = "Legs")
        )

        assertTrue(selected.none { it.id.value == "stretch-name" })
        assertTrue(selected.none { it.id.value == "mobility-alias" })
        assertTrue(selected.any { it.id.value == "stretching" })
    }

    @Test
    fun `reviewed support equipment conflicts remain excluded when programming is unavailable`() {
        val supportConflicts = SUPPORT_EQUIPMENT_CONFLICT_IDS.mapIndexed { index, exerciseId ->
            when (index % 4) {
                0 -> exercise(exerciseId, "A Support Conflict $exerciseId", "upper legs", "quadriceps")
                1 -> exercise(exerciseId, "A Support Conflict $exerciseId", "chest", "pectorals")
                2 -> exercise(exerciseId, "A Support Conflict $exerciseId", "back", "lats")
                else -> exercise(exerciseId, "A Support Conflict $exerciseId", "waist", "abs")
            }
        }

        val selected = selector.selectDay(
            supportConflicts + richCatalogue(),
            request(equipment = setOf("Full gym")),
            programmingRecords = emptyList()
        )

        assertEquals(4, selected.size)
        assertTrue(selected.none { it.id.value in SUPPORT_EQUIPMENT_CONFLICT_IDS })
    }

    @Test
    fun `reviewed main metadata cannot bypass exact support equipment exclusions`() {
        val supportConflicts = listOf(
            exercise("1373", "bodyweight standing calf raise", "lower legs", "calves"),
            exercise("3165", "bodyweight standing row with towel", "back", "upper back")
        )
        val records = supportConflicts.map { conflict ->
            programming(conflict.id.value, listOf(ExerciseProgrammingRole.MAIN_STRENGTH))
        }

        val selected = selector.selectDay(
            supportConflicts + richCatalogue(),
            request(equipment = setOf("Dumbbells")),
            records
        )

        assertTrue(selected.none { it.id.value in setOf("1373", "3165") })
    }

    @Test
    fun `repository failures and insufficient catalogues fail safely`() = runTest {
        val failedSelector = CanonicalRoutineExerciseSelector(
            FakeCatalogue(ExerciseCatalogueState.Error("raw repository detail"))
        )

        assertEquals(
            CanonicalRoutineCatalogueResult.Error(),
            failedSelector.loadCatalogue()
        )
        assertTrue(selector.selectDay(richCatalogue().take(3), request()).isEmpty())
        assertTrue(selector.selectDay(richCatalogue(), request(limit = 5)).isEmpty())
    }

    @Test
    fun `repository ready state exposes the canonical snapshot`() = runTest {
        val catalogue = richCatalogue()
        val result = CanonicalRoutineExerciseSelector(
            FakeCatalogue(ExerciseCatalogueState.Ready(catalogue))
        ).loadCatalogue()

        assertEquals(CanonicalRoutineCatalogueResult.Ready(catalogue), result)
    }

    @Test
    fun `B3B upstream leading-zero IDs remain opaque in Routine Builder catalogue state`() = runTest {
        val catalogue = listOf(
            exercise("0643", "overhead triceps stretch", "upper arms", "triceps"),
            exercise("1576", "leg up hamstring stretch", "upper legs", "hamstrings")
        )
        val result = CanonicalRoutineExerciseSelector(
            FakeCatalogue(ExerciseCatalogueState.Ready(catalogue))
        ).loadCatalogue() as CanonicalRoutineCatalogueResult.Ready

        assertEquals(listOf("0643", "1576"), result.exercises.map { it.id.value })
    }

    private fun request(
        equipment: Set<String> = setOf("Bodyweight"),
        goal: String = "General Fitness",
        level: String = "Intermediate",
        split: String = "Full Body",
        dayFocus: String = "Full Body",
        limit: Int = 4
    ) = CanonicalRoutineSelectionRequest(equipment, goal, level, split, dayFocus, limit)

    private fun richCatalogue(): List<Exercise> = buildList {
        listOf("body weight", "dumbbell", "barbell", "band", "cable").forEachIndexed { index, equipment ->
            val suffix = index + 1
            add(exercise("lower-$suffix", "Lower $suffix", "upper legs", "glutes", equipment))
            add(exercise("push-$suffix", "Push $suffix", "chest", "pectorals", equipment))
            add(exercise("pull-$suffix", "Pull $suffix", "back", "lats", equipment))
            add(exercise("core-$suffix", "Core $suffix", "waist", "abs", equipment))
        }
        add(exercise("conditioning", "Conditioning", "cardio", "cardiovascular system", "body weight"))
    }

    private data class RejectedPhoneCandidate(
        val candidate: Exercise,
        val focus: String
    )

    private companion object {
        val SUPPORT_EQUIPMENT_CONFLICT_IDS = setOf(
            "0129", "0137", "0291", "0305", "0974", "0980", "0988", "0993", "1008", "1013",
            "1254", "1399", "1770", "3019", "0279", "0284", "1000", "1277", "1373", "1649",
            "1650", "2403", "3165"
        )
    }

    private fun exercise(
        id: String,
        name: String,
        bodyPart: String,
        primaryMuscle: String,
        equipment: String = "body weight",
        goals: List<String> = emptyList(),
        levels: List<String> = emptyList(),
        instructions: String = "Use $equipment and repeat this normal main movement for the desired repetitions."
    ) = Exercise(
        id = ExerciseId(id),
        name = name,
        aliases = emptyList(),
        category = if (bodyPart == "cardio") "cardio" else "strength",
        movementPattern = bodyPart,
        bodyPart = bodyPart,
        bodyTargets = listOf(bodyPart),
        primaryMuscles = listOf(primaryMuscle),
        secondaryMuscles = emptyList(),
        equipment = listOf(equipment),
        goals = goals,
        experienceLevels = levels,
        instructions = instructions,
        safetyNote = ""
    )

    private fun programming(
        id: String,
        roles: List<ExerciseProgrammingRole>,
        autoSelectApproved: Boolean = true,
        beginnerSuitable: Boolean = true,
        equipment: List<String> = listOf("body weight"),
        duplicateFamilyKey: String? = null
    ) = ExerciseProgrammingMetadata(
        exerciseId = id,
        roles = roles,
        programmingRegions = listOf(ExerciseProgrammingRegion.QUADRICEPS),
        joints = emptyList(),
        movementPatterns = emptyList(),
        prescriptionMode = ExercisePrescriptionMode.REPETITIONS,
        defaultRepetitions = 8,
        defaultDurationSeconds = null,
        freeTextPrescription = null,
        perSide = false,
        equipmentOverride = emptyList(),
        equipment = equipment,
        duplicateFamilyKey = duplicateFamilyKey,
        beginnerSuitable = beginnerSuitable,
        autoSelectApproved = autoSelectApproved,
        reviewStatus = ExerciseProgrammingReviewStatus.FITDESI_REVIEWED,
        neverAutoSelectReason = null
    )

    private class FakeCatalogue(
        private val loadResult: ExerciseCatalogueState = ExerciseCatalogueState.NotLoaded
    ) : ExerciseCatalogue {
        private val mutableState = MutableStateFlow<ExerciseCatalogueState>(ExerciseCatalogueState.NotLoaded)
        override val state: StateFlow<ExerciseCatalogueState> = mutableState

        override suspend fun load(): ExerciseCatalogueState = loadResult.also { mutableState.value = it }
        override suspend fun getAll(): List<Exercise> = (loadResult as? ExerciseCatalogueState.Ready)?.exercises.orEmpty()
        override suspend fun getById(id: ExerciseId): Exercise? = getAll().firstOrNull { it.id == id }
        override suspend fun getByExactName(name: String): List<Exercise> = getAll().filter { it.name == name }
        override suspend fun getByAlias(alias: String): List<Exercise> = emptyList()
        override suspend fun search(query: String): List<Exercise> = emptyList()
        override suspend fun filter(filter: ExerciseFilter): List<Exercise> = emptyList()
    }
}
