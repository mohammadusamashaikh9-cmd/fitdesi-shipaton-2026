package com.example.exercise

import android.content.Context
import com.example.exercise.pack.ExercisePackDto
import com.example.security.SafeLog
import java.util.Collections
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

sealed interface ExerciseCatalogueState {
    object NotLoaded : ExerciseCatalogueState
    object Loading : ExerciseCatalogueState
    data class Ready(val exercises: List<Exercise>) : ExerciseCatalogueState
    data class Error(val message: String) : ExerciseCatalogueState
}

data class ExerciseFilter(
    val equipment: String? = null,
    val primaryMuscle: String? = null,
    val secondaryMuscle: String? = null,
    val bodyPart: String? = null,
    val movementPattern: String? = null,
    val goal: String? = null,
    val experienceLevel: String? = null,
    val category: String? = null
)

interface ExerciseCatalogue {
    val state: StateFlow<ExerciseCatalogueState>

    suspend fun load(): ExerciseCatalogueState
    suspend fun getAll(): List<Exercise>
    suspend fun getById(id: ExerciseId): Exercise?
    suspend fun getByExactName(name: String): List<Exercise>
    suspend fun getByAlias(alias: String): List<Exercise>
    suspend fun search(query: String): List<Exercise>
    suspend fun filter(filter: ExerciseFilter): List<Exercise>
}

internal fun interface ExerciseAssetSource {
    fun read(): String
}

internal class AndroidExerciseAssetSource(context: Context) : ExerciseAssetSource {
    private val assets = context.applicationContext.assets

    override fun read(): String =
        assets.open(ExerciseRepository.ASSET_PATH).bufferedReader().use { it.readText() }
}

class ExerciseRepository internal constructor(
    private val source: ExerciseAssetSource
) : ExerciseCatalogue {
    private val loadMutex = Mutex()
    private val _state = MutableStateFlow<ExerciseCatalogueState>(ExerciseCatalogueState.NotLoaded)
    override val state: StateFlow<ExerciseCatalogueState> = _state.asStateFlow()

    @Volatile
    private var snapshot: CatalogueSnapshot? = null

    override suspend fun load(): ExerciseCatalogueState {
        snapshot?.let { return ExerciseCatalogueState.Ready(it.exercises) }
        return loadMutex.withLock {
            snapshot?.let { return@withLock ExerciseCatalogueState.Ready(it.exercises) }
            _state.value = ExerciseCatalogueState.Loading
            try {
                val loaded = withContext(Dispatchers.IO) { parse(source.read()) }
                snapshot = loaded
                ExerciseCatalogueState.Ready(loaded.exercises).also { _state.value = it }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                SafeLog.error("ExerciseRepository", "Bundled exercise catalogue load failed", error)
                ExerciseCatalogueState.Error(LOAD_ERROR).also { _state.value = it }
            }
        }
    }

    override suspend fun getAll(): List<Exercise> = requireSnapshot().exercises

    override suspend fun getById(id: ExerciseId): Exercise? {
        val loaded = requireSnapshot()
        return loaded.idIndex[id]?.let(loaded.exercises::get)
    }

    override suspend fun getByExactName(name: String): List<Exercise> {
        val loaded = requireSnapshot()
        return loaded.resolve(loaded.nameIndex[normalize(name)])
    }

    override suspend fun getByAlias(alias: String): List<Exercise> {
        val loaded = requireSnapshot()
        return loaded.resolve(loaded.aliasIndex[normalize(alias)])
    }

    override suspend fun search(query: String): List<Exercise> {
        val loaded = requireSnapshot()
        val normalizedQuery = normalize(query)
        if (normalizedQuery.isBlank()) return loaded.exercises

        return loaded.exercises
            .asSequence()
            .mapIndexedNotNull { index, exercise ->
                searchRank(exercise, normalizedQuery)?.let { rank -> SearchMatch(index, rank) }
            }
            .sortedWith(
                compareBy<SearchMatch> { it.rank }
                    .thenBy { normalize(loaded.exercises[it.index].name) }
                    .thenBy { loaded.exercises[it.index].id.value }
            )
            .map { loaded.exercises[it.index] }
            .toList()
            .immutable()
    }

    override suspend fun filter(filter: ExerciseFilter): List<Exercise> {
        val loaded = requireSnapshot()
        val requested = listOfNotNull(
            filter.equipment.indexLookup(loaded.equipmentIndex),
            filter.primaryMuscle.indexLookup(loaded.primaryMuscleIndex),
            filter.secondaryMuscle.indexLookup(loaded.secondaryMuscleIndex),
            filter.bodyPart.indexLookup(loaded.bodyPartIndex),
            filter.movementPattern.indexLookup(loaded.movementPatternIndex),
            filter.goal.indexLookup(loaded.goalIndex),
            filter.experienceLevel.indexLookup(loaded.experienceLevelIndex),
            filter.category.indexLookup(loaded.categoryIndex)
        )
        if (requested.isEmpty()) return loaded.exercises

        val matching = requested
            .drop(1)
            .fold(requested.first().toSet()) { result, offsets -> result intersect offsets.toSet() }
        return matching
            .asSequence()
            .sorted()
            .map(loaded.exercises::get)
            .toList()
            .immutable()
    }

    private suspend fun requireSnapshot(): CatalogueSnapshot {
        snapshot?.let { return it }
        return when (val result = load()) {
            is ExerciseCatalogueState.Ready -> snapshot
                ?: error("Exercise catalogue reached Ready without a snapshot.")
            is ExerciseCatalogueState.Error -> throw ExerciseCatalogueUnavailableException(result.message)
            ExerciseCatalogueState.Loading,
            ExerciseCatalogueState.NotLoaded -> error("Exercise catalogue load did not finish.")
        }
    }

    private fun parse(text: String): CatalogueSnapshot {
        val records = json.decodeFromString(ListSerializer(ExercisePackDto.serializer()), text)
        val exercises = records
            .map(::toDomain)
            .sortedWith(compareBy<Exercise> { normalize(it.name) }.thenBy { it.id.value })
            .immutable()
        check(exercises.size == EXPECTED_RECORD_COUNT) {
            "Exercise catalogue count mismatch: expected $EXPECTED_RECORD_COUNT, found ${exercises.size}."
        }

        val idIndex = LinkedHashMap<ExerciseId, Int>(exercises.size)
        val nameIndex = mutableIndex()
        val aliasIndex = mutableIndex()
        val equipmentIndex = mutableIndex()
        val primaryMuscleIndex = mutableIndex()
        val secondaryMuscleIndex = mutableIndex()
        val bodyPartIndex = mutableIndex()
        val movementPatternIndex = mutableIndex()
        val goalIndex = mutableIndex()
        val experienceLevelIndex = mutableIndex()
        val categoryIndex = mutableIndex()

        exercises.forEachIndexed { index, exercise ->
            check(idIndex.put(exercise.id, index) == null) {
                "Duplicate exercise ID: ${exercise.id.value}."
            }
            nameIndex.add(exercise.name, index)
            exercise.aliases.forEach { aliasIndex.add(it, index) }
            exercise.equipment.forEach { equipmentIndex.add(it, index) }
            exercise.primaryMuscles.forEach { primaryMuscleIndex.add(it, index) }
            exercise.secondaryMuscles.forEach { secondaryMuscleIndex.add(it, index) }
            bodyPartIndex.add(exercise.bodyPart, index)
            movementPatternIndex.add(exercise.movementPattern, index)
            exercise.goals.forEach { goalIndex.add(it, index) }
            exercise.experienceLevels.forEach { experienceLevelIndex.add(it, index) }
            categoryIndex.add(exercise.category, index)
        }

        return CatalogueSnapshot(
            exercises = exercises,
            idIndex = idIndex.immutable(),
            nameIndex = nameIndex.freeze(),
            aliasIndex = aliasIndex.freeze(),
            equipmentIndex = equipmentIndex.freeze(),
            primaryMuscleIndex = primaryMuscleIndex.freeze(),
            secondaryMuscleIndex = secondaryMuscleIndex.freeze(),
            bodyPartIndex = bodyPartIndex.freeze(),
            movementPatternIndex = movementPatternIndex.freeze(),
            goalIndex = goalIndex.freeze(),
            experienceLevelIndex = experienceLevelIndex.freeze(),
            categoryIndex = categoryIndex.freeze()
        )
    }

    private fun toDomain(record: ExercisePackDto): Exercise = Exercise(
        id = ExerciseId(record.id),
        name = record.name,
        aliases = record.aliases.distinctNormalized(),
        category = record.category,
        movementPattern = record.movementPattern,
        bodyPart = record.bodyPart,
        bodyTargets = record.bodyTargets.distinctNormalized(),
        primaryMuscles = listOfNotNull(record.target, record.muscleGroup)
            .ifEmpty { record.bodyTargets }
            .distinctNormalized(),
        secondaryMuscles = record.secondaryMuscles.distinctNormalized(),
        equipment = record.equipment.distinctNormalized(),
        goals = record.goals.distinctNormalized(),
        experienceLevels = record.experienceLevels.distinctNormalized(),
        instructions = record.instructions,
        safetyNote = record.safetyNote
    )

    private fun searchRank(exercise: Exercise, query: String): Int? {
        val name = normalize(exercise.name)
        val aliases = exercise.aliases.map(::normalize)
        return when {
            name == query -> 0
            query in aliases -> 1
            name.startsWith(query) -> 2
            aliases.any { it.startsWith(query) } -> 3
            name.contains(query) -> 4
            aliases.any { it.contains(query) } -> 5
            exercise.searchableFacets().any { normalize(it).contains(query) } -> 6
            else -> null
        }
    }

    private fun Exercise.searchableFacets(): Sequence<String> = sequence {
        yield(category)
        yield(movementPattern)
        yield(bodyPart)
        yieldAll(bodyTargets)
        yieldAll(primaryMuscles)
        yieldAll(secondaryMuscles)
        yieldAll(equipment)
        yieldAll(goals)
        yieldAll(experienceLevels)
    }

    private data class SearchMatch(val index: Int, val rank: Int)

    private data class CatalogueSnapshot(
        val exercises: List<Exercise>,
        val idIndex: Map<ExerciseId, Int>,
        val nameIndex: Map<String, List<Int>>,
        val aliasIndex: Map<String, List<Int>>,
        val equipmentIndex: Map<String, List<Int>>,
        val primaryMuscleIndex: Map<String, List<Int>>,
        val secondaryMuscleIndex: Map<String, List<Int>>,
        val bodyPartIndex: Map<String, List<Int>>,
        val movementPatternIndex: Map<String, List<Int>>,
        val goalIndex: Map<String, List<Int>>,
        val experienceLevelIndex: Map<String, List<Int>>,
        val categoryIndex: Map<String, List<Int>>
    ) {
        fun resolve(offsets: List<Int>?): List<Exercise> =
            offsets.orEmpty().map(exercises::get).immutable()
    }

    companion object {
        internal const val ASSET_PATH = "knowledge/v1/exercises.json"
        internal const val EXPECTED_RECORD_COUNT = 534
        internal const val LOAD_ERROR = "Exercise library could not be loaded. Try again."

        private val whitespace = Regex("\\s+")
        private val json = Json {
            ignoreUnknownKeys = false
            isLenient = false
            coerceInputValues = false
            explicitNulls = true
        }

        internal fun fromAssets(context: Context): ExerciseRepository =
            ExerciseRepository(AndroidExerciseAssetSource(context))

        internal fun normalize(value: String): String =
            value.trim().lowercase(Locale.ROOT).replace(whitespace, " ")
    }
}

class ExerciseCatalogueUnavailableException internal constructor(message: String) :
    IllegalStateException(message)

object ExerciseRepositoryProvider {
    @Volatile
    private var instance: ExerciseRepository? = null

    fun getRepository(context: Context): ExerciseRepository =
        instance ?: synchronized(this) {
            instance ?: ExerciseRepository.fromAssets(context.applicationContext).also { instance = it }
        }
}

private typealias MutableExerciseIndex = MutableMap<String, MutableList<Int>>

private fun mutableIndex(): MutableExerciseIndex = LinkedHashMap()

private fun MutableExerciseIndex.add(value: String, index: Int) {
    val key = ExerciseRepository.normalize(value)
    if (key.isNotBlank()) getOrPut(key) { mutableListOf() }.add(index)
}

private fun MutableExerciseIndex.freeze(): Map<String, List<Int>> {
    val frozen = LinkedHashMap<String, List<Int>>(size)
    forEach { (key, value) -> frozen[key] = value.distinct().immutable() }
    return frozen.immutable()
}

private fun String?.indexLookup(index: Map<String, List<Int>>): List<Int>? =
    this?.takeIf { it.isNotBlank() }?.let { index[ExerciseRepository.normalize(it)].orEmpty() }

private fun List<String>.distinctNormalized(): List<String> {
    val seen = HashSet<String>(size)
    return filter { value ->
        val normalized = ExerciseRepository.normalize(value)
        normalized.isNotBlank() && seen.add(normalized)
    }.immutable()
}

private fun <T> List<T>.immutable(): List<T> =
    Collections.unmodifiableList(toList())

private fun <K, V> Map<K, V>.immutable(): Map<K, V> =
    Collections.unmodifiableMap(LinkedHashMap(this))
