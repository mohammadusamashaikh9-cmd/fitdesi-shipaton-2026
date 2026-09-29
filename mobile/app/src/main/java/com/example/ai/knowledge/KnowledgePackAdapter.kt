package com.example.ai.knowledge

object KnowledgePackAdapter {
    fun toKnowledgeEntries(catalogue: KnowledgePackCatalogue): List<KnowledgeEntry> {
        val aliasesByFood = catalogue.foodAliases.groupBy(FoodAliasPackRecord::foodId)
        val progressionsByExercise = catalogue.progressions.groupBy(ProgressionPackRecord::fromId)
        val substitutionsByExercise = catalogue.substitutions.groupBy(SubstitutionPackRecord::toId)

        return buildList {
            catalogue.exercises.forEach { record ->
                add(
                    KnowledgeEntry(
                        id = record.id,
                        domain = KnowledgeDomain.EXERCISE,
                        title = record.name,
                        content = "${record.instructions} Safety: ${record.safetyNote}",
                        keywords = (
                            record.aliases + record.bodyTargets + record.secondaryMuscles +
                                listOfNotNull(
                                    record.movementPattern,
                                    record.category,
                                    record.bodyPart,
                                    record.target,
                                    record.muscleGroup
                                )
                            ).toSet(),
                        goals = record.goals.toSet(),
                        experienceLevels = record.experienceLevels.toSet(),
                        equipment = record.equipment.toSet(),
                        exerciseIds = setOf(record.id),
                        progressions = progressionsByExercise[record.id].orEmpty().map(ProgressionPackRecord::toLabel),
                        regressions = substitutionsByExercise[record.id].orEmpty().map(SubstitutionPackRecord::fromLabel),
                        priority = 20,
                        sourceType = KnowledgeSourceType.VERIFIED_PACK,
                        licenseStatus = RuntimeLicenseStatus.VERIFIED
                    )
                )
            }
            catalogue.yogaPoses.forEach { record ->
                add(
                    KnowledgeEntry(
                        id = record.id,
                        domain = KnowledgeDomain.EXERCISE,
                        title = record.name,
                        content = "${record.instructions} Safety: ${record.safetyNote}",
                        keywords = (record.aliases + record.bodyTargets + "yoga" + "mobility").toSet(),
                        goals = record.goals.toSet(),
                        equipment = record.equipment.toSet(),
                        exerciseIds = setOf(record.id),
                        priority = 18,
                        sourceType = KnowledgeSourceType.VERIFIED_PACK,
                        licenseStatus = RuntimeLicenseStatus.VERIFIED
                    )
                )
            }
            catalogue.pakistaniFoods.forEach { record ->
                val aliases = aliasesByFood[record.id].orEmpty().map(FoodAliasPackRecord::alias)
                val nutrition = record.nutritionEstimate?.let {
                    " Estimated ${it.calories} kcal, ${it.proteinGrams} g protein, ${it.carbsGrams} g carbohydrate, and ${it.fatGrams} g fat."
                }.orEmpty()
                add(
                    KnowledgeEntry(
                        id = record.id,
                        domain = KnowledgeDomain.PAKISTANI_FOOD,
                        title = record.name,
                        content = "${record.servingDescription}.$nutrition ${record.uncertaintyNote}",
                        keywords = (record.aliases + aliases + record.category + record.dietaryTags).toSet(),
                        goals = record.goals.toSet(),
                        foodNames = setOf(record.name),
                        priority = 18,
                        sourceType = KnowledgeSourceType.VERIFIED_PACK,
                        licenseStatus = RuntimeLicenseStatus.VERIFIED
                    )
                )
            }
            catalogue.coachingRules.forEach { record ->
                add(record.toEntry(KnowledgeDomain.NUTRITION))
            }
            catalogue.workoutRules.forEach { record ->
                add(
                    KnowledgeEntry(
                        id = record.id,
                        domain = KnowledgeDomain.WORKOUT,
                        title = record.title,
                        content = record.guidance,
                        keywords = record.keywords.toSet(),
                        goals = record.goals.toSet(),
                        experienceLevels = record.experienceLevels.toSet(),
                        equipment = record.equipment.toSet(),
                        priority = record.priority,
                        sourceType = KnowledgeSourceType.VERIFIED_PACK,
                        licenseStatus = RuntimeLicenseStatus.VERIFIED
                    )
                )
            }
            catalogue.safetyRules.forEach { record ->
                add(
                    KnowledgeEntry(
                        id = record.id,
                        domain = KnowledgeDomain.SAFETY,
                        title = record.title,
                        content = record.guidance,
                        keywords = record.triggerTerms.toSet(),
                        priority = record.priority,
                        sourceType = KnowledgeSourceType.VERIFIED_PACK,
                        licenseStatus = RuntimeLicenseStatus.VERIFIED
                    )
                )
            }
            catalogue.medicalEscalations.forEach { record ->
                add(
                    KnowledgeEntry(
                        id = record.id,
                        domain = KnowledgeDomain.MEDICAL_ESCALATION,
                        title = record.title,
                        content = record.action,
                        keywords = record.triggerTerms.toSet(),
                        priority = record.priority,
                        sourceType = KnowledgeSourceType.VERIFIED_PACK,
                        licenseStatus = RuntimeLicenseStatus.VERIFIED
                    )
                )
            }
        }.distinctBy(KnowledgeEntry::id)
    }

    private fun CoachingRulePackRecord.toEntry(domain: KnowledgeDomain): KnowledgeEntry =
        KnowledgeEntry(
            id = id,
            domain = domain,
            title = title,
            content = guidance,
            keywords = keywords.toSet(),
            goals = goals.toSet(),
            experienceLevels = experienceLevels.toSet(),
            equipment = equipment.toSet(),
            priority = priority,
            sourceType = KnowledgeSourceType.VERIFIED_PACK,
            licenseStatus = RuntimeLicenseStatus.VERIFIED
        )
}

object KnowledgePackRegistry {
    @Volatile
    private var loader: KnowledgePackLoader? = null

    @Volatile
    private var verifiedEntries: List<KnowledgeEntry>? = null

    @Volatile
    private var verifiedCatalogue: KnowledgePackCatalogue? = null

    @Volatile
    private var legacyEntries: List<KnowledgeEntry> = emptyList()

    @Volatile
    private var legacyFoods: List<ProjectFoodRecord> = emptyList()

    @Volatile
    var loadError: String? = null
        private set

    fun configure(loader: KnowledgePackLoader) {
        synchronized(this) {
            this.loader = loader
            verifiedEntries = null
            verifiedCatalogue = null
            loadError = null
        }
    }

    fun install(catalogue: KnowledgePackCatalogue) {
        val entries = KnowledgePackAdapter.toKnowledgeEntries(catalogue)
        verifiedCatalogue = catalogue
        verifiedEntries = entries.takeIf { it.isNotEmpty() }
        loadError = if (entries.isEmpty()) "Verified knowledge pack contains no retrievable records." else null
    }

    fun recordFailure(error: Throwable) {
        loadError = error.message ?: "Knowledge pack could not be loaded."
    }

    fun installLegacyFoods(records: List<ProjectFoodRecord>) {
        val validRecords = records
            .filter { it.id.isNotBlank() && it.name.isNotBlank() }
            .distinctBy(ProjectFoodRecord::id)
            .take(MAX_LEGACY_FOODS)
        legacyFoods = validRecords
        legacyEntries = ProjectKnowledgeAdapters.foodEntries(validRecords)
    }

    fun entries(): List<KnowledgeEntry> {
        ensureLoaded()
        return (FitnessKnowledgeCatalog.entries + verifiedEntries.orEmpty() + legacyEntries)
            .distinctBy(KnowledgeEntry::id)
    }

    fun verifiedExercises(): List<ExercisePackRecord> {
        ensureLoaded()
        return verifiedCatalogue?.exercises.orEmpty()
    }

    fun localFoods(): List<ProjectFoodRecord> = legacyFoods

    fun counts(): KnowledgeRuntimeCounts {
        ensureLoaded()
        return KnowledgeRuntimeCounts(
            verifiedPackRecords = verifiedCatalogue?.totalRecords ?: 0,
            exerciseRecords = verifiedCatalogue?.exercises?.size ?: 0,
            legacyFoodRecords = legacyFoods.size
        )
    }

    fun retriever(): FitnessKnowledgeRetriever =
        FitnessKnowledgeRetriever.from(entries())

    private fun ensureLoaded() {
        if (verifiedEntries != null || loader == null) return
        synchronized(this) {
            if (verifiedEntries != null) return
            val pendingLoader = loader ?: return
            loader = null
            runCatching(pendingLoader::load)
                .onSuccess(::install)
                .onFailure(::recordFailure)
        }
    }

    private const val MAX_LEGACY_FOODS = 250
}

data class KnowledgeRuntimeCounts(
    val verifiedPackRecords: Int,
    val exerciseRecords: Int,
    val legacyFoodRecords: Int
)
