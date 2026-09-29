package com.example.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.exercise.ExerciseBrowserAdapter
import com.example.exercise.ExerciseCatalogue
import com.example.exercise.ExerciseCatalogueState
import com.example.exercise.ExerciseFilter
import com.example.exercise.ExerciseRepositoryProvider
import com.example.fitdesi.data.Exercise
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed interface ExerciseUiState {
    object Loading : ExerciseUiState
    data class Success(val exercises: List<Exercise>) : ExerciseUiState
    data class Error(val message: String) : ExerciseUiState
}

internal fun exerciseLoadFailureMessage(error: Throwable): String {
    if (error is CancellationException) throw error
    return ExerciseViewModel.EXERCISE_LOAD_ERROR
}

class ExerciseViewModel internal constructor(
    application: Application,
    private val repository: ExerciseCatalogue,
    private val workDispatcher: CoroutineDispatcher
) : AndroidViewModel(application) {
    constructor(application: Application) : this(
        application = application,
        repository = ExerciseRepositoryProvider.getRepository(application),
        workDispatcher = Dispatchers.IO
    )

    private val _uiState = MutableStateFlow<ExerciseUiState>(ExerciseUiState.Loading)
    val uiState: StateFlow<ExerciseUiState> = _uiState

    private val _exercises = MutableStateFlow<List<Exercise>>(emptyList())
    val exercises: StateFlow<List<Exercise>> = _exercises

    private var operationJob: Job? = null

    init {
        loadOfflineExercises()
    }

    fun loadOfflineExercises() {
        runOperation {
            _uiState.value = ExerciseUiState.Loading
            when (val state = repository.load()) {
                is ExerciseCatalogueState.Ready -> publish(state.exercises)
                is ExerciseCatalogueState.Error -> {
                    _exercises.value = emptyList()
                    _uiState.value = ExerciseUiState.Error(state.message)
                }
                ExerciseCatalogueState.Loading,
                ExerciseCatalogueState.NotLoaded -> Unit
            }
        }
    }

    fun filterByCategory(category: String) {
        runOperation {
            _uiState.value = ExerciseUiState.Loading
            val filter = category
                .takeUnless { it.isBlank() || it.equals("all", ignoreCase = true) }
                ?.let { ExerciseFilter(category = it) }
            publish(if (filter == null) repository.getAll() else repository.filter(filter))
        }
    }

    fun searchExercises(query: String) {
        runOperation {
            _uiState.value = ExerciseUiState.Loading
            publish(repository.search(query))
        }
    }

    private fun runOperation(operation: suspend () -> Unit) {
        operationJob?.cancel()
        operationJob = viewModelScope.launch(workDispatcher) {
            try {
                operation()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _uiState.value = ExerciseUiState.Error(exerciseLoadFailureMessage(error))
            }
        }
    }

    private fun publish(canonicalExercises: List<com.example.exercise.Exercise>) {
        val browserExercises = ExerciseBrowserAdapter.toBrowserExercises(canonicalExercises)
        _exercises.value = browserExercises
        _uiState.value = ExerciseUiState.Success(browserExercises)
    }

    companion object {
        internal const val EXERCISE_LOAD_ERROR = "Exercise library could not be loaded. Try again."
    }
}
