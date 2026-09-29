package com.example.data

import com.example.ai.GeneratedDay
import com.example.ai.GeneratedExercise
import com.example.ai.GeneratedRoutine
import com.example.ai.RampUpLoadCue
import com.example.ai.RampUpSetPrescription
import com.example.subscription.SubscriptionCapability
import com.example.subscription.SubscriptionPolicy
import com.example.subscription.SubscriptionTier
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedRoutineRepositoryTest {
    @Test
    fun `historical routine exercise IDs deserialize without rewriting snapshots`() = runTest {
        val historicalJson = historicalRoutineLibraryJson()
        val storage = FakeRoutineStorage(
            routineLibrary = historicalJson,
            migrationComplete = true
        )
        val repository = SavedRoutineRepository(storage)

        val restored = repository.loadRoutines().getOrThrow().single()
        val exercises = restored.routine.days.flatMap(GeneratedDay::exercises)

        assertEquals("historical-routine-id", restored.routineId)
        assertEquals("historical-plan-id", restored.routine.planId)
        assertEquals(
            listOf("Missing ID", "Empty ID", "Leading Zero", "Opaque ID", "Unknown ID"),
            exercises.map(GeneratedExercise::name)
        )
        assertEquals(
            listOf("", "", "0001", "fd-exercise-chair-squat", "historical-exercise-id"),
            exercises.map(GeneratedExercise::exerciseId)
        )
        assertEquals(listOf(2, 3, 4, 5, 6), exercises.map(GeneratedExercise::sets))
        assertEquals(listOf("6", "8", "10", "12", "15"), exercises.map(GeneratedExercise::reps))
        assertEquals(
            listOf("Chest", "Back", "Legs", "Quads", "Core"),
            exercises.map(GeneratedExercise::targetMuscle)
        )
        assertEquals(
            listOf("Instruction 1", "Instruction 2", "Instruction 3", "Instruction 4", "Instruction 5"),
            exercises.map(GeneratedExercise::instructions)
        )
        assertEquals(listOf(0, 0, 0, 0, 0), exercises.map(GeneratedExercise::restSeconds))
        assertTrue(restored.routine.days.all { it.warmupExercises.isEmpty() && it.cooldownExercises.isEmpty() })
        assertTrue(exercises.all { it.rampUpSets.isEmpty() && it.activityPrescription == null && it.safetyNote.isEmpty() })
        assertEquals(listOf("Day 2", "Day 1"), restored.routine.days.map(GeneratedDay::dayName))
        assertEquals(historicalJson, storage.routineLibrary)
    }

    @Test
    fun `mixed historical exercise IDs survive Gson round trip`() {
        val gson = Gson()
        val decoded = gson.fromJson(
            historicalRoutineJson(),
            GeneratedRoutine::class.java
        )

        val roundTripped = gson.fromJson(
            gson.toJson(decoded),
            GeneratedRoutine::class.java
        )

        assertEquals("historical-plan-id", roundTripped.planId)
        assertEquals(
            listOf("", "", "0001", "fd-exercise-chair-squat", "historical-exercise-id"),
            roundTripped.days.flatMap(GeneratedDay::exercises).map(GeneratedExercise::exerciseId)
        )
        assertEquals(listOf("Day 2", "Day 1"), roundTripped.days.map(GeneratedDay::dayName))

        val serializedExercise = JsonParser.parseString(gson.toJson(decoded))
            .asJsonObject["days"].asJsonArray[0].asJsonObject["exercises"]
            .asJsonArray[0].asJsonObject
        assertEquals(
            setOf(
                "name", "sets", "reps", "targetMuscle", "instructions", "restSeconds", "exerciseId",
                "rampUpSets", "safetyNote"
            ),
            serializedExercise.keySet()
        )
    }

    @Test
    fun `historical blank plan ID retains its exact pre-B1 stable hash`() {
        val repository = SavedRoutineRepository(FakeRoutineStorage())
        val historicalShape = routine("", "Legacy hash routine")

        assertEquals("routine-c7a73a59a3277b265c2c0bc3", repository.stableRoutineId(historicalShape))
    }

    @Test
    fun `historical active plan without structured fields remains readable`() = runTest {
        val storage = FakeRoutineStorage(legacyActive = historicalRoutineJson())

        val restored = SavedRoutineRepository(storage).loadRoutines().getOrThrow().single()

        assertEquals("historical-plan-id", restored.routineId)
        assertTrue(restored.routine.days.all { it.warmupExercises.isEmpty() && it.cooldownExercises.isEmpty() })
        assertTrue(restored.routine.days.flatMap { it.exercises }.all { it.rampUpSets.isEmpty() })
        assertEquals(historicalRoutineJson(), storage.legacyActive)
    }

    @Test
    fun `malformed structured routine collection fails without rewriting stored JSON`() = runTest {
        val malformed = historicalRoutineLibraryJson().replace(
            "\"exercises\":[",
            "\"warmupExercises\":null,\"exercises\":["
        )
        val storage = FakeRoutineStorage(routineLibrary = malformed, migrationComplete = true)

        val result = SavedRoutineRepository(storage).loadRoutines()

        assertTrue(result.isFailure)
        assertEquals(malformed, storage.routineLibrary)
    }

    @Test
    fun `saving routines appends build plans without overwriting`() = runTest {
        val repository = SavedRoutineRepository(FakeRoutineStorage())
        val first = routine("routine-a", "Strength A")
        val second = routine("routine-b", "Strength B")

        assertEquals(SavedRoutineResult.SAVED, repository.saveRoutine(first, SavedRoutineOrigin.BUILD_ROUTINE))
        assertEquals(SavedRoutineResult.SAVED, repository.saveRoutine(second, SavedRoutineOrigin.BUILD_ROUTINE))

        val restored = repository.loadRoutines().getOrThrow()
        assertEquals(setOf("routine-a", "routine-b"), restored.map { it.routineId }.toSet())
        assertTrue(restored.all { it.origin == SavedRoutineOrigin.BUILD_ROUTINE })
    }

    @Test
    fun `ai generator plan remains after build routine save`() = runTest {
        val repository = SavedRoutineRepository(FakeRoutineStorage())
        val generated = routine("generator-a", "Offline muscle gain")
        val built = routine("builder-a", "My gym routine")

        assertEquals(SavedRoutineResult.SAVED, repository.saveRoutine(generated, SavedRoutineOrigin.AI_WORKOUT_GENERATOR))
        assertEquals(SavedRoutineResult.SAVED, repository.saveRoutine(built, SavedRoutineOrigin.BUILD_ROUTINE))

        val restored = repository.loadRoutines().getOrThrow()
        assertEquals(2, restored.size)
        assertEquals(SavedRoutineOrigin.AI_WORKOUT_GENERATOR, restored.first { it.routineId == "generator-a" }.origin)
        assertEquals(SavedRoutineOrigin.BUILD_ROUTINE, restored.first { it.routineId == "builder-a" }.origin)
    }

    @Test
    fun `repeated save of one routine is duplicate protected`() = runTest {
        val repository = SavedRoutineRepository(FakeRoutineStorage())
        val routine = routine("routine-a", "Strength A")

        assertEquals(SavedRoutineResult.SAVED, repository.saveRoutine(routine, SavedRoutineOrigin.BUILD_ROUTINE))
        assertEquals(SavedRoutineResult.ALREADY_SAVED, repository.saveRoutine(routine, SavedRoutineOrigin.BUILD_ROUTINE))
        assertEquals(1, repository.loadRoutines().getOrThrow().size)
    }

    @Test
    fun `Basic zero saves first build routine and Basic one saves second custom workout`() = runTest {
        val repository = SavedRoutineRepository(FakeRoutineStorage(migrationComplete = true))

        assertEquals(
            SavedRoutineResult.SAVED,
            repository.saveRoutine(routine("build-1", "Build one"), SavedRoutineOrigin.BUILD_ROUTINE)
        )
        assertEquals(
            SavedRoutineResult.SAVED,
            repository.saveRoutine(routine("custom-2", "Custom two"), SavedRoutineOrigin.CUSTOM_WORKOUT)
        )
        val restored = repository.loadRoutines().getOrThrow()

        assertEquals(2, restored.size)
        assertEquals(
            SavedRoutineOrigin.BUILD_ROUTINE,
            restored.first { it.routineId == "build-1" }.origin
        )
        assertEquals(
            SavedRoutineOrigin.CUSTOM_WORKOUT,
            restored.first { it.routineId == "custom-2" }.origin
        )
    }

    @Test
    fun `Basic at limit blocks unique build and custom authored routines`() = runTest {
        val repository = SavedRoutineRepository(FakeRoutineStorage(migrationComplete = true))
        assertEquals(
            SavedRoutineResult.SAVED,
            repository.saveRoutine(routine("build-1", "Build one"), SavedRoutineOrigin.BUILD_ROUTINE)
        )
        assertEquals(
            SavedRoutineResult.SAVED,
            repository.saveRoutine(routine("custom-2", "Custom two"), SavedRoutineOrigin.CUSTOM_WORKOUT)
        )

        assertEquals(
            SavedRoutineResult.LIMIT_REACHED,
            repository.saveRoutine(routine("build-3", "Build three"), SavedRoutineOrigin.BUILD_ROUTINE)
        )
        assertEquals(
            SavedRoutineResult.LIMIT_REACHED,
            repository.saveRoutine(routine("custom-3", "Custom three"), SavedRoutineOrigin.CUSTOM_WORKOUT)
        )
        assertEquals(SubscriptionPolicy.BASIC_AUTHORED_ROUTINE_LIMIT, repository.loadRoutines().getOrThrow().size)
    }

    @Test
    fun `Plus and Pro save counted routines beyond the Basic limit`() = runTest {
        listOf(SubscriptionTier.PLUS, SubscriptionTier.PRO).forEach { tier ->
            val repository = SavedRoutineRepository(
                storage = FakeRoutineStorage(migrationComplete = true),
                hasUnlimitedAuthoredRoutineCapability = {
                    SubscriptionPolicy.hasCapability(tier, SubscriptionCapability.UNLIMITED_ROUTINES)
                }
            )

            repeat(SubscriptionPolicy.BASIC_AUTHORED_ROUTINE_LIMIT + 1) { index ->
                assertEquals(
                    SavedRoutineResult.SAVED,
                    repository.saveRoutine(
                        routine("$tier-$index", "$tier routine $index"),
                        if (index % 2 == 0) SavedRoutineOrigin.BUILD_ROUTINE else SavedRoutineOrigin.CUSTOM_WORKOUT
                    )
                )
            }
        }
    }

    @Test
    fun `only build routine and custom workout origins count toward the Basic limit`() = runTest {
        assertTrue(SavedRoutineOrigin.BUILD_ROUTINE.isBasicAuthoredRoutineOrigin())
        assertTrue(SavedRoutineOrigin.CUSTOM_WORKOUT.isBasicAuthoredRoutineOrigin())
        assertFalse(SavedRoutineOrigin.AI_WORKOUT_GENERATOR.isBasicAuthoredRoutineOrigin())
        assertFalse(SavedRoutineOrigin.LEGACY_ACTIVE.isBasicAuthoredRoutineOrigin())
    }

    @Test
    fun `excluded origins do not consume a Basic authored slot`() = runTest {
        val repository = SavedRoutineRepository(FakeRoutineStorage(migrationComplete = true))
        assertEquals(
            SavedRoutineResult.SAVED,
            repository.saveRoutine(routine("generated", "Generated"), SavedRoutineOrigin.AI_WORKOUT_GENERATOR)
        )
        assertEquals(
            SavedRoutineResult.SAVED,
            repository.saveRoutine(routine("legacy", "Legacy"), SavedRoutineOrigin.LEGACY_ACTIVE)
        )
        assertEquals(
            SavedRoutineResult.SAVED,
            repository.saveRoutine(routine("build", "Build"), SavedRoutineOrigin.BUILD_ROUTINE)
        )
        assertEquals(
            SavedRoutineResult.SAVED,
            repository.saveRoutine(routine("custom", "Custom"), SavedRoutineOrigin.CUSTOM_WORKOUT)
        )

        assertEquals(4, repository.loadRoutines().getOrThrow().size)
    }

    @Test
    fun `duplicate at the Basic limit returns already saved before cap evaluation`() = runTest {
        var capabilityChecks = 0
        val storage = FakeRoutineStorage(migrationComplete = true)
        val repository = SavedRoutineRepository(
            storage = storage,
            hasUnlimitedAuthoredRoutineCapability = {
                capabilityChecks += 1
                false
            }
        )
        val first = routine("build-1", "Build one")
        assertEquals(SavedRoutineResult.SAVED, repository.saveRoutine(first, SavedRoutineOrigin.BUILD_ROUTINE))
        assertEquals(
            SavedRoutineResult.SAVED,
            repository.saveRoutine(routine("custom-2", "Custom two"), SavedRoutineOrigin.CUSTOM_WORKOUT)
        )
        val checksBeforeDuplicate = capabilityChecks

        assertEquals(SavedRoutineResult.ALREADY_SAVED, repository.saveRoutine(first, SavedRoutineOrigin.BUILD_ROUTINE))
        assertEquals(checksBeforeDuplicate, capabilityChecks)
    }

    @Test
    fun `grandfathered excess remains unchanged while duplicate succeeds and unique save is blocked`() = runTest {
        var unlimited = true
        val storage = FakeRoutineStorage(migrationComplete = true)
        val repository = SavedRoutineRepository(storage) { unlimited }
        val grandfathered = (1..(SubscriptionPolicy.BASIC_AUTHORED_ROUTINE_LIMIT + 1)).map { index ->
            routine("grandfathered-$index", "Grandfathered $index")
        }
        grandfathered.forEach { saved ->
            assertEquals(SavedRoutineResult.SAVED, repository.saveRoutine(saved, SavedRoutineOrigin.BUILD_ROUTINE))
        }
        unlimited = false
        val beforeBlockedSave = storage.routineLibrary
        val writesBeforeBlockedSave = storage.writeCount

        assertEquals(
            SavedRoutineResult.ALREADY_SAVED,
            repository.saveRoutine(grandfathered.first(), SavedRoutineOrigin.BUILD_ROUTINE)
        )
        assertEquals(
            SavedRoutineResult.LIMIT_REACHED,
            repository.saveRoutine(routine("new-authored", "New authored"), SavedRoutineOrigin.CUSTOM_WORKOUT)
        )
        assertEquals(beforeBlockedSave, storage.routineLibrary)
        assertEquals(writesBeforeBlockedSave, storage.writeCount)
        assertEquals(grandfathered.size, repository.loadRoutines().getOrThrow().size)
        assertTrue(repository.deleteRoutine(grandfathered.last().planId))
        assertEquals(grandfathered.size - 1, repository.loadRoutines().getOrThrow().size)
    }

    @Test
    fun `historical generator-classified routine is never reclassified or counted`() = runTest {
        val storage = FakeRoutineStorage(migrationComplete = true)
        val paidRepository = SavedRoutineRepository(storage) { true }
        val historicalBuildWorkout = routine("historical-misclassified", "Historical Build Workout")
        assertEquals(
            SavedRoutineResult.SAVED,
            paidRepository.saveRoutine(historicalBuildWorkout, SavedRoutineOrigin.AI_WORKOUT_GENERATOR)
        )

        val basicRepository = SavedRoutineRepository(storage)
        repeat(SubscriptionPolicy.BASIC_AUTHORED_ROUTINE_LIMIT) { index ->
            assertEquals(
                SavedRoutineResult.SAVED,
                basicRepository.saveRoutine(
                    routine("authored-$index", "Authored $index"),
                    SavedRoutineOrigin.BUILD_ROUTINE
                )
            )
        }

        val restoredHistorical = basicRepository.getRoutine(historicalBuildWorkout.planId)
        assertEquals(SavedRoutineOrigin.AI_WORKOUT_GENERATOR, restoredHistorical?.origin)
    }

    @Test
    fun `invalid routine remains invalid before capability evaluation`() = runTest {
        var capabilityChecks = 0
        val repository = SavedRoutineRepository(
            storage = FakeRoutineStorage(migrationComplete = true),
            hasUnlimitedAuthoredRoutineCapability = {
                capabilityChecks += 1
                false
            }
        )

        assertEquals(
            SavedRoutineResult.INVALID_ROUTINE,
            repository.saveRoutine(routine("invalid", "Invalid").copy(days = emptyList()), SavedRoutineOrigin.BUILD_ROUTINE)
        )
        assertEquals(0, capabilityChecks)
    }

    @Test
    fun `storage write failure remains error`() = runTest {
        val storage = FakeRoutineStorage(migrationComplete = true, failWrites = true)
        val repository = SavedRoutineRepository(storage)

        assertEquals(
            SavedRoutineResult.ERROR,
            repository.saveRoutine(routine("write-error", "Write error"), SavedRoutineOrigin.BUILD_ROUTINE)
        )
    }

    @Test
    fun `concurrent unique Basic saves cannot cross the authored limit`() = runTest {
        val repository = SavedRoutineRepository(FakeRoutineStorage(migrationComplete = true))
        assertEquals(
            SavedRoutineResult.SAVED,
            repository.saveRoutine(routine("existing", "Existing"), SavedRoutineOrigin.BUILD_ROUTINE)
        )
        val start = CompletableDeferred<Unit>()
        val saves = listOf(
            async {
                start.await()
                repository.saveRoutine(routine("concurrent-a", "Concurrent A"), SavedRoutineOrigin.BUILD_ROUTINE)
            },
            async {
                start.await()
                repository.saveRoutine(routine("concurrent-b", "Concurrent B"), SavedRoutineOrigin.CUSTOM_WORKOUT)
            }
        )

        start.complete(Unit)
        val results = saves.awaitAll()

        assertEquals(1, results.count { it == SavedRoutineResult.SAVED })
        assertEquals(1, results.count { it == SavedRoutineResult.LIMIT_REACHED })
        assertEquals(SubscriptionPolicy.BASIC_AUTHORED_ROUTINE_LIMIT, repository.loadRoutines().getOrThrow().size)
    }

    @Test
    fun `capability provider is evaluated at each unique counted save`() = runTest {
        var unlimited = false
        var capabilityChecks = 0
        val repository = SavedRoutineRepository(
            storage = FakeRoutineStorage(migrationComplete = true),
            hasUnlimitedAuthoredRoutineCapability = {
                capabilityChecks += 1
                unlimited
            }
        )
        repeat(SubscriptionPolicy.BASIC_AUTHORED_ROUTINE_LIMIT) { index ->
            assertEquals(
                SavedRoutineResult.SAVED,
                repository.saveRoutine(routine("basic-$index", "Basic $index"), SavedRoutineOrigin.BUILD_ROUTINE)
            )
        }
        unlimited = true

        assertEquals(
            SavedRoutineResult.SAVED,
            repository.saveRoutine(routine("paid-third", "Paid third"), SavedRoutineOrigin.CUSTOM_WORKOUT)
        )
        assertEquals(1, capabilityChecks)
    }

    @Test
    fun `incomplete routine is never persisted`() = runTest {
        val repository = SavedRoutineRepository(FakeRoutineStorage())
        val incomplete = routine("routine-invalid", "Invalid").copy(days = emptyList())

        assertEquals(
            SavedRoutineResult.INVALID_ROUTINE,
            repository.saveRoutine(incomplete, SavedRoutineOrigin.BUILD_ROUTINE)
        )
        assertTrue(repository.loadRoutines().getOrThrow().isEmpty())
    }

    @Test
    fun `routines survive repository recreation`() = runTest {
        val storage = FakeRoutineStorage()
        val firstRepository = SavedRoutineRepository(storage)
        val routine = routine("routine-a", "Strength A")

        assertEquals(SavedRoutineResult.SAVED, firstRepository.saveRoutine(routine, SavedRoutineOrigin.BUILD_ROUTINE))

        val restartedRepository = SavedRoutineRepository(storage)
        val restored = restartedRepository.getRoutine("routine-a")
        assertNotNull(restored)
        assertEquals("Strength A", restored?.routine?.name)
    }

    @Test
    fun `canonical exercise IDs survive save and repository recreation`() = runTest {
        val storage = FakeRoutineStorage()
        val canonicalRoutine = routine("canonical-plan", "Canonical routine").copy(
            days = listOf(
                GeneratedDay(
                    dayName = "Day 1",
                    title = "Full Body",
                    description = "Canonical snapshots.",
                    exercises = listOf(
                        GeneratedExercise("Leading Zero", 3, "8-12", "Legs", "Snapshot one.", exerciseId = "0001"),
                        GeneratedExercise("Opaque", 4, "4-6", "Core", "Snapshot two.", exerciseId = "fd-exercise-chair-squat")
                    )
                )
            )
        )

        assertEquals(
            SavedRoutineResult.SAVED,
            SavedRoutineRepository(storage).saveRoutine(canonicalRoutine, SavedRoutineOrigin.BUILD_ROUTINE)
        )
        val restored = SavedRoutineRepository(storage).getRoutine("canonical-plan")

        assertEquals("canonical-plan", restored?.routineId)
        assertEquals("canonical-plan", restored?.routine?.planId)
        assertEquals(
            listOf("0001", "fd-exercise-chair-squat"),
            restored?.routine?.days?.single()?.exercises?.map(GeneratedExercise::exerciseId)
        )
        assertEquals(listOf("Leading Zero", "Opaque"), restored?.routine?.days?.single()?.exercises?.map(GeneratedExercise::name))
    }

    @Test
    fun `legacy active plan migrates once without being removed`() = runTest {
        val legacy = routine("legacy-active", "Existing active routine")
        val storage = FakeRoutineStorage(legacyActive = Gson().toJson(legacy))
        val repository = SavedRoutineRepository(storage)

        val restored = repository.loadRoutines().getOrThrow()

        assertEquals(1, restored.size)
        assertEquals("legacy-active", restored.single().routineId)
        assertEquals(SavedRoutineOrigin.LEGACY_ACTIVE, restored.single().origin)
        assertTrue(storage.migrationComplete)
        assertEquals(Gson().toJson(legacy), storage.legacyActive)
    }

    @Test
    fun `deleting one routine preserves other saved routines`() = runTest {
        val storage = FakeRoutineStorage()
        val repository = SavedRoutineRepository(storage)
        assertEquals(SavedRoutineResult.SAVED, repository.saveRoutine(routine("routine-a", "A"), SavedRoutineOrigin.BUILD_ROUTINE))
        assertEquals(SavedRoutineResult.SAVED, repository.saveRoutine(routine("routine-b", "B"), SavedRoutineOrigin.BUILD_ROUTINE))

        assertTrue(repository.deleteRoutine("routine-a"))
        val remaining = repository.loadRoutines().getOrThrow()
        assertEquals(listOf("routine-b"), remaining.map { it.routineId })
        val afterKnownDelete = storage.routineLibrary
        assertFalse(repository.deleteRoutine("routine-a"))
        assertEquals(afterKnownDelete, storage.routineLibrary)
    }

    @Test
    fun `deleting a sibling preserves opaque IDs and structured routine fields`() = runTest {
        val repository = SavedRoutineRepository(FakeRoutineStorage())
        val preserved = routine("preserved-plan", "Preserved").copy(
            days = listOf(
                GeneratedDay(
                    dayName = "Day 2",
                    title = "Structured day",
                    description = "Keep exact structure",
                    exercises = listOf(
                        GeneratedExercise(
                            name = "Opaque sequence",
                            sets = 4,
                            reps = "6",
                            targetMuscle = "Full body",
                            instructions = "Keep exact fields.",
                            restSeconds = 75,
                            exerciseId = "0257",
                            rampUpSets = listOf(
                                RampUpSetPrescription(1, RampUpLoadCue.LIGHT, 5, 30)
                            ),
                            safetyNote = "Use controlled form."
                        ),
                        GeneratedExercise("Second", 3, "8", "Back", "Keep ID.", exerciseId = "0643"),
                        GeneratedExercise("Third", 2, "10", "Core", "Keep ID.", exerciseId = "1576"),
                        GeneratedExercise(
                            "Chair Squat",
                            3,
                            "12",
                            "Legs",
                            "Keep opaque authored ID.",
                            exerciseId = "fd-exercise-chair-squat"
                        )
                    )
                )
            )
        )
        assertEquals(SavedRoutineResult.SAVED, repository.saveRoutine(routine("delete-me", "Delete me"), SavedRoutineOrigin.BUILD_ROUTINE))
        assertEquals(SavedRoutineResult.SAVED, repository.saveRoutine(preserved, SavedRoutineOrigin.BUILD_ROUTINE))

        assertTrue(repository.deleteRoutine("delete-me"))

        val restored = repository.loadRoutines().getOrThrow().single()
        assertEquals(preserved, restored.routine)
        assertEquals(
            listOf("0257", "0643", "1576", "fd-exercise-chair-squat"),
            restored.routine.days.single().exercises.map(GeneratedExercise::exerciseId)
        )
    }

    private fun routine(id: String, name: String) = GeneratedRoutine(
        name = name,
        description = "A local routine.",
        splitType = "Full Body",
        frequency = "2 days/week",
        days = listOf(
            GeneratedDay(
                dayName = "Day 1",
                title = "Full Body",
                description = "Controlled training.",
                exercises = listOf(
                    GeneratedExercise(
                        name = "Goblet Squat",
                        sets = 3,
                        reps = "8-12",
                        targetMuscle = "Legs",
                        instructions = "Use controlled form."
                    )
                )
            )
        ),
        planId = id,
        createdAt = 1_700_000_000_000L
    )

    private fun historicalRoutineLibraryJson(): String =
        """[{"routineId":"historical-routine-id","routine":${historicalRoutineJson()},"origin":"BUILD_ROUTINE","createdAt":1700000000000}]"""

    private fun historicalRoutineJson(): String = """
        {
          "name":"Historical mixed-ID routine",
          "description":"Historical snapshot",
          "splitType":"Upper / Lower",
          "frequency":"2 days/week",
          "days":[
            {
              "dayName":"Day 2",
              "title":"Upper Body",
              "description":"Preserve this day first",
              "exercises":[
                {"name":"Missing ID","sets":2,"reps":"6","targetMuscle":"Chest","instructions":"Instruction 1"},
                {"name":"Empty ID","sets":3,"reps":"8","targetMuscle":"Back","instructions":"Instruction 2","exerciseId":""},
                {"name":"Leading Zero","sets":4,"reps":"10","targetMuscle":"Legs","instructions":"Instruction 3","exerciseId":"0001"}
              ]
            },
            {
              "dayName":"Day 1",
              "title":"Lower Body",
              "description":"Preserve this day second",
              "exercises":[
                {"name":"Opaque ID","sets":5,"reps":"12","targetMuscle":"Quads","instructions":"Instruction 4","exerciseId":"fd-exercise-chair-squat"},
                {"name":"Unknown ID","sets":6,"reps":"15","targetMuscle":"Core","instructions":"Instruction 5","exerciseId":"historical-exercise-id"}
              ]
            }
          ],
          "planId":"historical-plan-id",
          "createdAt":1700000000000
        }
    """.trimIndent()

    private class FakeRoutineStorage(
        var routineLibrary: String? = null,
        var legacyActive: String? = null,
        var migrationComplete: Boolean = false,
        var failWrites: Boolean = false
    ) : SavedRoutineStorage {
        var writeCount: Int = 0
            private set

        override suspend fun readRoutineLibrary(): String? = routineLibrary
        override suspend fun writeRoutineLibrary(value: String) {
            writeCount += 1
            if (failWrites) error("Synthetic write failure")
            routineLibrary = value
        }
        override suspend fun readLegacyActiveRoutine(): String? = legacyActive
        override suspend fun isLegacyMigrationComplete(): Boolean = migrationComplete
        override suspend fun markLegacyMigrationComplete() { migrationComplete = true }
    }
}
