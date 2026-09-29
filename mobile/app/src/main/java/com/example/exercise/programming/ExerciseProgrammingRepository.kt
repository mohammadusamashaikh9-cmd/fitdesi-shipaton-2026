package com.example.exercise.programming

import android.content.Context
import com.example.exercise.Exercise
import com.example.exercise.ExerciseCatalogue
import com.example.exercise.ExerciseCatalogueState
import com.example.security.SafeLog
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

sealed interface ExerciseProgrammingState {
    data object NotLoaded : ExerciseProgrammingState
    data object Loading : ExerciseProgrammingState
    data class Ready(
        val records: List<ExerciseProgrammingMetadata>,
        val coverage: ExerciseProgrammingCoverage
    ) : ExerciseProgrammingState
    data class Error(val message: String) : ExerciseProgrammingState
}

interface ExerciseProgrammingCatalogue {
    val state: StateFlow<ExerciseProgrammingState>

    suspend fun load(): ExerciseProgrammingState
    suspend fun getAll(): List<ExerciseProgrammingMetadata>
    suspend fun getByExerciseId(exerciseId: String): ExerciseProgrammingMetadata?
    suspend fun getByRole(role: ExerciseProgrammingRole): List<ExerciseProgrammingMetadata>
    suspend fun getByProgrammingRegion(region: ExerciseProgrammingRegion): List<ExerciseProgrammingMetadata>
    suspend fun getByEquipment(equipment: String): List<ExerciseProgrammingMetadata>
    suspend fun getByDuplicateFamily(familyKey: String): List<ExerciseProgrammingMetadata>
    suspend fun coverage(): ExerciseProgrammingCoverage
}

internal fun interface ExerciseProgrammingAssetSource {
    fun read(): String
}

internal class AndroidExerciseProgrammingAssetSource(context: Context) : ExerciseProgrammingAssetSource {
    private val assets = context.applicationContext.assets

    override fun read(): String =
        assets.open(ExerciseProgrammingRepository.ASSET_PATH).bufferedReader().use { it.readText() }
}

class ExerciseProgrammingRepository internal constructor(
    private val source: ExerciseProgrammingAssetSource,
    private val canonicalCatalogue: ExerciseCatalogue
) : ExerciseProgrammingCatalogue {
    private val loadMutex = Mutex()
    private val _state = MutableStateFlow<ExerciseProgrammingState>(ExerciseProgrammingState.NotLoaded)
    override val state: StateFlow<ExerciseProgrammingState> = _state.asStateFlow()

    @Volatile
    private var snapshot: ProgrammingSnapshot? = null

    @Volatile
    private var terminalState: ExerciseProgrammingState? = null

    override suspend fun load(): ExerciseProgrammingState {
        terminalState?.let { return it }
        return loadMutex.withLock {
            terminalState?.let { return@withLock it }
            _state.value = ExerciseProgrammingState.Loading
            try {
                val canonical = canonicalExercises()
                val loaded = withContext(Dispatchers.IO) { parse(source.read(), canonical) }
                snapshot = loaded
                loaded.readyState().also {
                    terminalState = it
                    _state.value = it
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                SafeLog.error(TAG, "Exercise programming metadata load failed", error)
                ExerciseProgrammingState.Error(LOAD_ERROR).also {
                    terminalState = it
                    _state.value = it
                }
            }
        }
    }

    override suspend fun getAll(): List<ExerciseProgrammingMetadata> = requireSnapshot().records

    override suspend fun getByExerciseId(exerciseId: String): ExerciseProgrammingMetadata? =
        requireSnapshot().byExerciseId[exerciseId]

    override suspend fun getByRole(role: ExerciseProgrammingRole): List<ExerciseProgrammingMetadata> =
        requireSnapshot().byRole[role].orEmpty()

    override suspend fun getByProgrammingRegion(
        region: ExerciseProgrammingRegion
    ): List<ExerciseProgrammingMetadata> = requireSnapshot().byRegion[region].orEmpty()

    override suspend fun getByEquipment(equipment: String): List<ExerciseProgrammingMetadata> =
        requireSnapshot().byEquipment[normalize(equipment)].orEmpty()

    override suspend fun getByDuplicateFamily(familyKey: String): List<ExerciseProgrammingMetadata> =
        requireSnapshot().byDuplicateFamily[normalize(familyKey)].orEmpty()

    override suspend fun coverage(): ExerciseProgrammingCoverage = requireSnapshot().coverage

    private suspend fun canonicalExercises(): List<Exercise> = when (val result = canonicalCatalogue.load()) {
        is ExerciseCatalogueState.Ready -> result.exercises
        is ExerciseCatalogueState.Error -> throw ExerciseProgrammingUnavailableException(LOAD_ERROR)
        ExerciseCatalogueState.Loading,
        ExerciseCatalogueState.NotLoaded -> throw ExerciseProgrammingUnavailableException(LOAD_ERROR)
    }

    private suspend fun requireSnapshot(): ProgrammingSnapshot {
        snapshot?.let { return it }
        return when (val result = load()) {
            is ExerciseProgrammingState.Ready -> snapshot
                ?: error("Programming metadata reached Ready without a snapshot.")
            is ExerciseProgrammingState.Error -> throw ExerciseProgrammingUnavailableException(result.message)
            ExerciseProgrammingState.Loading,
            ExerciseProgrammingState.NotLoaded -> error("Programming metadata load did not finish.")
        }
    }

    private fun parse(text: String, canonicalExercises: List<Exercise>): ProgrammingSnapshot {
        val pack = json.decodeFromString(ExerciseProgrammingPackDto.serializer(), text)
        check(pack.schemaVersion == SCHEMA_VERSION) { "Unsupported programming schema version." }

        val canonicalById = canonicalExercises.associateBy { it.id.value }
        check(canonicalById.size == canonicalExercises.size) { "Canonical exercise IDs are not unique." }
        val seenIds = HashSet<String>(pack.records.size)
        val records = pack.records.map { record ->
            validateRecord(record, seenIds)
            val canonical = canonicalById[record.exerciseId]
                ?: error("Unknown canonical exercise ID.")
            record.toDomain(canonical)
        }.sortedBy(ExerciseProgrammingMetadata::exerciseId).immutableProgrammingList()

        return ProgrammingSnapshot.create(records)
    }

    private fun validateRecord(
        record: ExerciseProgrammingRecordDto,
        seenIds: MutableSet<String>
    ) {
        check(record.exerciseId.isNotBlank() && record.exerciseId == record.exerciseId.trim()) {
            "Exercise ID must be exact and nonblank."
        }
        check(seenIds.add(record.exerciseId)) { "Duplicate programming exercise ID." }
        check(record.roles.isNotEmpty() && record.roles.distinct().size == record.roles.size) {
            "Programming roles must be unique and nonempty."
        }
        check(
            record.programmingRegions.isNotEmpty() &&
                record.programmingRegions.distinct().size == record.programmingRegions.size
        ) { "Programming regions must be unique and nonempty." }
        check(record.joints.distinct().size == record.joints.size) { "Programming joints must be unique." }
        check(record.movementPatterns.all { it.isNotBlank() && it == it.trim() }) {
            "Movement patterns must be exact and nonblank."
        }
        check(record.movementPatterns.map(::normalize).distinct().size == record.movementPatterns.size) {
            "Movement patterns must be unique."
        }
        check(record.equipmentOverride.all { it.isNotBlank() && it == it.trim() }) {
            "Equipment overrides must be exact and nonblank."
        }
        check(record.equipmentOverride.map(::normalize).distinct().size == record.equipmentOverride.size) {
            "Equipment overrides must be unique."
        }
        record.duplicateFamilyKey?.let {
            check(it.isNotBlank() && it == it.trim()) { "Duplicate family must be exact and nonblank." }
        }
        validatePrescription(record)

        val neverAutoSelect = ExerciseProgrammingRole.NEVER_AUTO_SELECT in record.roles
        if (neverAutoSelect) {
            check(!record.autoSelectApproved) { "Never-auto-select records cannot be approved." }
            check(!record.neverAutoSelectReason.isNullOrBlank()) {
                "Never-auto-select records require a reason."
            }
        }
        if (record.autoSelectApproved) {
            check(record.roles.any { it in AUTO_SELECT_ROLES }) {
                "Approved records require a usable programming role."
            }
            check(record.reviewStatus == ExerciseProgrammingReviewStatus.FITDESI_REVIEWED) {
                "Approved records require FitDesi review."
            }
        }
    }

    private fun validatePrescription(record: ExerciseProgrammingRecordDto) {
        when (record.prescriptionMode) {
            ExercisePrescriptionMode.REPETITIONS -> check(
                record.defaultRepetitions?.let { it in MIN_REPETITIONS..MAX_REPETITIONS } == true &&
                    record.defaultDurationSeconds == null &&
                    record.freeTextPrescription == null
            ) { "Invalid repetition prescription." }
            ExercisePrescriptionMode.DURATION_SECONDS -> check(
                record.defaultDurationSeconds?.let {
                    it in MIN_DURATION_SECONDS..MAX_DURATION_SECONDS
                } == true &&
                    record.defaultRepetitions == null &&
                    record.freeTextPrescription == null
            ) { "Invalid duration prescription." }
            ExercisePrescriptionMode.FREE_TEXT -> check(
                !record.freeTextPrescription.isNullOrBlank() &&
                    record.freeTextPrescription.length <= MAX_FREE_TEXT_LENGTH &&
                    record.defaultRepetitions == null &&
                    record.defaultDurationSeconds == null
            ) { "Invalid free-text prescription." }
        }
    }

    private fun ExerciseProgrammingRecordDto.toDomain(
        canonical: Exercise
    ): ExerciseProgrammingMetadata {
        val resolvedEquipment = (equipmentOverride.ifEmpty { canonical.equipment })
            .distinctBy(::normalize)
            .sortedBy(::normalize)
            .immutableProgrammingList()
        return ExerciseProgrammingMetadata(
            exerciseId = exerciseId,
            roles = roles.immutableProgrammingList(),
            programmingRegions = programmingRegions.immutableProgrammingList(),
            joints = joints.immutableProgrammingList(),
            movementPatterns = movementPatterns.immutableProgrammingList(),
            prescriptionMode = prescriptionMode,
            defaultRepetitions = defaultRepetitions,
            defaultDurationSeconds = defaultDurationSeconds,
            freeTextPrescription = freeTextPrescription,
            perSide = perSide,
            equipmentOverride = equipmentOverride.immutableProgrammingList(),
            equipment = resolvedEquipment,
            duplicateFamilyKey = duplicateFamilyKey,
            beginnerSuitable = beginnerSuitable,
            autoSelectApproved = autoSelectApproved,
            reviewStatus = reviewStatus,
            neverAutoSelectReason = neverAutoSelectReason
        )
    }

    private data class ProgrammingSnapshot(
        val records: List<ExerciseProgrammingMetadata>,
        val byExerciseId: Map<String, ExerciseProgrammingMetadata>,
        val byRole: Map<ExerciseProgrammingRole, List<ExerciseProgrammingMetadata>>,
        val byRegion: Map<ExerciseProgrammingRegion, List<ExerciseProgrammingMetadata>>,
        val byEquipment: Map<String, List<ExerciseProgrammingMetadata>>,
        val byDuplicateFamily: Map<String, List<ExerciseProgrammingMetadata>>,
        val coverage: ExerciseProgrammingCoverage
    ) {
        fun readyState(): ExerciseProgrammingState.Ready =
            ExerciseProgrammingState.Ready(records, coverage)

        companion object {
            fun create(records: List<ExerciseProgrammingMetadata>): ProgrammingSnapshot {
                val byExerciseId = records.associateBy(ExerciseProgrammingMetadata::exerciseId)
                    .immutableProgrammingMap()
                val byRole = ExerciseProgrammingRole.entries.associateWith { role ->
                    records.filter { role in it.roles }.immutableProgrammingList()
                }.immutableProgrammingMap()
                val byRegion = ExerciseProgrammingRegion.entries.associateWith { region ->
                    records.filter { region in it.programmingRegions }.immutableProgrammingList()
                }.immutableProgrammingMap()
                val equipmentKeys = records.flatMap(ExerciseProgrammingMetadata::equipment)
                    .map { ExerciseProgrammingRepository.normalize(it) }
                    .filter(String::isNotBlank)
                    .distinct()
                    .sorted()
                val byEquipment = equipmentKeys.associateWith { key ->
                    records.filter { record ->
                        record.equipment.any { ExerciseProgrammingRepository.normalize(it) == key }
                    }
                        .immutableProgrammingList()
                }.immutableProgrammingMap()
                val familyKeys = records.mapNotNull(ExerciseProgrammingMetadata::duplicateFamilyKey)
                    .map { ExerciseProgrammingRepository.normalize(it) }
                    .distinct()
                    .sorted()
                val byDuplicateFamily = familyKeys.associateWith { key ->
                    records.filter {
                        ExerciseProgrammingRepository.normalize(it.duplicateFamilyKey.orEmpty()) == key
                    }
                        .immutableProgrammingList()
                }.immutableProgrammingMap()

                val preparationRoles = setOf(
                    ExerciseProgrammingRole.DYNAMIC_PREPARATION,
                    ExerciseProgrammingRole.MOBILITY,
                    ExerciseProgrammingRole.ACTIVATION
                )
                val coverage = ExerciseProgrammingCoverage(
                    totalOverlayRecords = records.size,
                    countByRole = ExerciseProgrammingRole.entries.associateWith { byRole.getValue(it).size }
                        .immutableProgrammingMap(),
                    countByProgrammingRegion = ExerciseProgrammingRegion.entries.associateWith {
                        byRegion.getValue(it).size
                    }.immutableProgrammingMap(),
                    countByEquipment = byEquipment.mapValues { it.value.size }.immutableProgrammingMap(),
                    regionsWithNoReviewedPreparationOption = ExerciseProgrammingRegion.entries.filter { region ->
                        byRegion.getValue(region).none { record ->
                            record.autoSelectApproved && record.roles.any(preparationRoles::contains)
                        }
                    }.immutableProgrammingList(),
                    regionsWithNoReviewedCooldownOption = ExerciseProgrammingRegion.entries.filter { region ->
                        byRegion.getValue(region).none { record ->
                            record.autoSelectApproved &&
                                ExerciseProgrammingRole.STATIC_COOLDOWN in record.roles
                        }
                    }.immutableProgrammingList()
                )
                return ProgrammingSnapshot(
                    records = records,
                    byExerciseId = byExerciseId,
                    byRole = byRole,
                    byRegion = byRegion,
                    byEquipment = byEquipment,
                    byDuplicateFamily = byDuplicateFamily,
                    coverage = coverage
                )
            }
        }
    }

    companion object {
        internal const val ASSET_PATH = "programming/v1/exercise_roles.json"
        internal const val SCHEMA_VERSION = 1
        internal const val LOAD_ERROR = "Exercise programming data could not be loaded. Try again."
        private const val TAG = "ExerciseProgramming"
        private const val MIN_REPETITIONS = 1
        private const val MAX_REPETITIONS = 100
        private const val MIN_DURATION_SECONDS = 5
        private const val MAX_DURATION_SECONDS = 600
        private const val MAX_FREE_TEXT_LENGTH = 120
        private val AUTO_SELECT_ROLES = ExerciseProgrammingRole.entries
            .filterNot { it == ExerciseProgrammingRole.NEVER_AUTO_SELECT }
            .toSet()
        private val whitespace = Regex("\\s+")
        private val json = Json {
            ignoreUnknownKeys = false
            isLenient = false
            coerceInputValues = false
            explicitNulls = true
        }

        internal fun normalize(value: String): String =
            value.trim().lowercase(Locale.ROOT).replace(whitespace, " ")
    }
}

class ExerciseProgrammingUnavailableException internal constructor(message: String) :
    IllegalStateException(message)
