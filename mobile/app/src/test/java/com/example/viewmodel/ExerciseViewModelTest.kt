package com.example.viewmodel

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.exercise.Exercise
import com.example.exercise.ExerciseCatalogue
import com.example.exercise.ExerciseCatalogueState
import com.example.exercise.ExerciseFilter
import com.example.exercise.ExerciseId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ExerciseViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val application: Application = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `canonical success exposes 534 browser records with exact stable IDs`() = runTest(dispatcher) {
        val repository = FakeExerciseCatalogue(canonicalExercises)
        val viewModel = ExerciseViewModel(application, repository, dispatcher)

        advanceUntilIdle()

        val state = viewModel.uiState.value as ExerciseUiState.Success
        assertEquals(534, state.exercises.size)
        assertEquals("0001", state.exercises.single { it.id == "0001" }.id)
        assertEquals(
            "fd-exercise-chair-squat",
            state.exercises.single { it.id == "fd-exercise-chair-squat" }.id
        )
        assertEquals(
            listOf("0088", "1371"),
            state.exercises
                .filter { it.name == "Barbell Seated Calf Raise" }
                .map { it.id }
        )
        assertEquals(534, viewModel.exercises.value.size)
        assertEquals(1, repository.loadCalls)

        repeat(3) {
            assertEquals(534, viewModel.uiState.value.let { it as ExerciseUiState.Success }.exercises.size)
        }
        assertEquals(1, repository.loadCalls)
    }

    @Test
    fun `category filtering and search publish deterministic repository results`() = runTest(dispatcher) {
        val repository = FakeExerciseCatalogue(exercises)
        val viewModel = ExerciseViewModel(application, repository, dispatcher)
        advanceUntilIdle()

        viewModel.filterByCategory("arms")
        advanceUntilIdle()
        assertEquals(listOf("Cable Curl"), viewModel.exercises.value.map { it.name })
        assertEquals("arms", repository.lastFilter?.category)

        viewModel.searchExercises("chair")
        advanceUntilIdle()
        assertEquals(listOf("Chair Squat"), viewModel.exercises.value.map { it.name })
        assertEquals("chair", repository.lastQuery)
    }

    @Test
    fun `repository load error is exposed as an error state`() = runTest(dispatcher) {
        val repository = FakeExerciseCatalogue(
            exercises = emptyList(),
            loadResult = ExerciseCatalogueState.Error(ExerciseViewModel.EXERCISE_LOAD_ERROR)
        )
        val viewModel = ExerciseViewModel(application, repository, dispatcher)

        advanceUntilIdle()

        assertEquals(
            ExerciseUiState.Error(ExerciseViewModel.EXERCISE_LOAD_ERROR),
            viewModel.uiState.value
        )
        assertTrue(viewModel.exercises.value.isEmpty())
    }

    @Test
    fun `retry after repository error publishes success without duplicating records`() = runTest(dispatcher) {
        val repository = FakeExerciseCatalogue(
            exercises = exercises,
            loadResult = ExerciseCatalogueState.Error(ExerciseViewModel.EXERCISE_LOAD_ERROR)
        )
        val viewModel = ExerciseViewModel(application, repository, dispatcher)
        advanceUntilIdle()

        repository.returnReadyOnNextLoad()
        viewModel.loadOfflineExercises()
        advanceUntilIdle()

        assertEquals(2, repository.loadCalls)
        assertEquals(
            listOf("Chair Squat", "Cable Curl"),
            (viewModel.uiState.value as ExerciseUiState.Success).exercises.map { it.name }
        )
    }

    private class FakeExerciseCatalogue(
        private val exercises: List<Exercise>,
        private var loadResult: ExerciseCatalogueState = ExerciseCatalogueState.Ready(exercises)
    ) : ExerciseCatalogue {
        private val mutableState = MutableStateFlow<ExerciseCatalogueState>(ExerciseCatalogueState.NotLoaded)
        override val state: StateFlow<ExerciseCatalogueState> = mutableState
        var loadCalls: Int = 0
        var lastFilter: ExerciseFilter? = null
        var lastQuery: String? = null

        fun returnReadyOnNextLoad() {
            loadResult = ExerciseCatalogueState.Ready(exercises)
        }

        override suspend fun load(): ExerciseCatalogueState {
            loadCalls += 1
            mutableState.value = loadResult
            return loadResult
        }

        override suspend fun getAll(): List<Exercise> = exercises

        override suspend fun getById(id: ExerciseId): Exercise? =
            exercises.firstOrNull { it.id == id }

        override suspend fun getByExactName(name: String): List<Exercise> =
            exercises.filter { it.name.equals(name, ignoreCase = true) }

        override suspend fun getByAlias(alias: String): List<Exercise> =
            exercises.filter { exercise -> exercise.aliases.any { it.equals(alias, ignoreCase = true) } }

        override suspend fun search(query: String): List<Exercise> {
            lastQuery = query
            return exercises.filter { exercise ->
                exercise.name.contains(query, ignoreCase = true) ||
                    exercise.aliases.any { it.contains(query, ignoreCase = true) }
            }
        }

        override suspend fun filter(filter: ExerciseFilter): List<Exercise> {
            lastFilter = filter
            return exercises.filter { exercise ->
                filter.category == null || exercise.category.equals(filter.category, ignoreCase = true)
            }
        }
    }

    companion object {
        private val canonicalExercises = listOf(
            exercise(
                id = "0001",
                name = "Leading Zero Press",
                category = "chest",
                equipment = listOf("barbell")
            ),
            exercise(
                id = "fd-exercise-chair-squat",
                name = "Chair Squat",
                category = "legs",
                equipment = listOf("chair")
            ),
            exercise(
                id = "0088",
                name = "Barbell Seated Calf Raise",
                category = "lower legs",
                equipment = listOf("barbell")
            ),
            exercise(
                id = "1371",
                name = "Barbell Seated Calf Raise",
                category = "lower legs",
                equipment = listOf("barbell")
            )
        ) + (1..530).map { index ->
            exercise(
                id = "test-canonical-$index",
                name = "Test Canonical Exercise $index",
                category = "test",
                equipment = listOf("none")
            )
        }

        private val exercises = listOf(
            exercise(
                id = "fd-exercise-chair-squat",
                name = "Chair Squat",
                category = "legs",
                equipment = listOf("chair")
            ),
            exercise(
                id = "0099",
                name = "Cable Curl",
                category = "arms",
                equipment = listOf("cable")
            )
        )

        private fun exercise(
            id: String,
            name: String,
            category: String,
            equipment: List<String>
        ) = Exercise(
            id = ExerciseId(id),
            name = name,
            aliases = emptyList(),
            category = category,
            movementPattern = category,
            bodyPart = category,
            bodyTargets = emptyList(),
            primaryMuscles = listOf(category),
            secondaryMuscles = emptyList(),
            equipment = equipment,
            goals = emptyList(),
            experienceLevels = emptyList(),
            instructions = "Test instructions",
            safetyNote = "Test safety note"
        )
    }
}
