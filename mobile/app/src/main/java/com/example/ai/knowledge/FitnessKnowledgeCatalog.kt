package com.example.ai.knowledge

/**
 * Original FitDesi-authored educational guidance. Existing exercise and food records are
 * referenced by stable IDs/names rather than copied into a second production dataset.
 */
object FitnessKnowledgeCatalog {
    val entries: List<KnowledgeEntry> = listOf(
        KnowledgeEntry(
            id = "safety-medical-escalation",
            domain = KnowledgeDomain.MEDICAL_ESCALATION,
            title = "Medical and urgent symptom boundary",
            content = "Do not diagnose or prescribe treatment. Stop exercise and seek qualified care for injury, disease, severe or sudden pain, chest pain, fainting, breathing difficulty, pregnancy concerns, medication interactions, or eating-disorder concerns.",
            keywords = setOf(
                "chest pain",
                "fainting",
                "feel faint",
                "severe breathing difficulty",
                "difficulty breathing",
                "sudden severe pain",
                "loss of consciousness",
                "emergency symptoms"
            ),
            priority = 100
        ),
        KnowledgeEntry(
            id = "safety-training-boundaries",
            domain = KnowledgeDomain.SAFETY,
            title = "General training safety",
            content = "Use controlled technique, progress gradually, leave recovery time, and stop a set when form breaks down. General fitness guidance is not medical diagnosis or treatment.",
            keywords = setOf("safe", "safety", "form", "pain", "recovery", "overtraining"),
            priority = 30
        ),
        KnowledgeEntry(
            id = "exercise-push-pattern",
            domain = KnowledgeDomain.EXERCISE,
            title = "Horizontal push progression",
            content = "Choose a pushing variation that allows controlled repetitions without pain.",
            keywords = setOf("push", "push-up", "pushup", "bench", "chest", "triceps"),
            goals = setOf("build muscle", "gain muscle", "get stronger", "strength"),
            equipment = setOf("bodyweight", "body weight", "barbell", "dumbbells"),
            exerciseIds = setOf("0025"),
            regressions = listOf("Wall push-up", "Incline push-up"),
            progressions = listOf("Floor push-up", "Barbell bench press"),
            priority = 12
        ),
        KnowledgeEntry(
            id = "exercise-squat-pattern",
            domain = KnowledgeDomain.EXERCISE,
            title = "Squat progression",
            content = "Use a squat depth and load that preserve balance and controlled knee and hip movement.",
            keywords = setOf("squat", "legs", "quads", "glutes", "lower body"),
            goals = setOf("build muscle", "gain muscle", "get stronger", "fat loss"),
            equipment = setOf("bodyweight", "body weight", "barbell", "dumbbells", "kettlebells", "band"),
            exerciseIds = setOf("0043", "1004"),
            regressions = listOf("Chair squat", "Supported bodyweight squat"),
            progressions = listOf("Goblet squat", "Barbell squat"),
            priority = 12
        ),
        KnowledgeEntry(
            id = "exercise-pull-pattern",
            domain = KnowledgeDomain.EXERCISE,
            title = "Row and pull progression",
            content = "Choose a pulling exercise that matches available equipment and keeps the torso controlled.",
            keywords = setOf("pull", "row", "back", "biceps", "upper back"),
            goals = setOf("build muscle", "gain muscle", "get stronger", "strength"),
            equipment = setOf("barbell", "dumbbells", "band", "cable", "machine", "pull-up bar"),
            exerciseIds = setOf("0027", "0988"),
            regressions = listOf("Band row", "Supported one-arm row"),
            progressions = listOf("One-arm dumbbell row", "Barbell bent-over row"),
            priority = 12
        ),
        KnowledgeEntry(
            id = "workout-muscle",
            domain = KnowledgeDomain.WORKOUT,
            title = "Muscle-building guidance",
            content = "Repeat a manageable plan, train major movement patterns consistently, and add repetitions or small load increases only while technique remains controlled.",
            keywords = setOf("muscle", "hypertrophy", "size", "bulk"),
            goals = setOf("build muscle", "gain muscle", "hypertrophy"),
            experienceLevels = setOf("beginner", "intermediate", "advanced"),
            priority = 15
        ),
        KnowledgeEntry(
            id = "workout-strength",
            domain = KnowledgeDomain.WORKOUT,
            title = "Strength guidance",
            content = "Prioritize repeatable compound movements, adequate rest between demanding sets, and gradual load progression recorded in Track Workout.",
            keywords = setOf("strength", "stronger", "power", "one rep max", "1rm"),
            goals = setOf("get stronger", "strength"),
            experienceLevels = setOf("beginner", "intermediate", "advanced"),
            priority = 15
        ),
        KnowledgeEntry(
            id = "workout-fat-loss",
            domain = KnowledgeDomain.WORKOUT,
            title = "Fat-loss activity guidance",
            content = "Combine repeatable strength training with walking or cardio that fits recovery. Exercise supports health and energy expenditure but does not guarantee a rate of weight loss.",
            keywords = setOf("fat loss", "lose weight", "weight loss", "cardio", "steps"),
            goals = setOf("fat loss", "lose body fat", "weight loss"),
            priority = 15
        ),
        KnowledgeEntry(
            id = "workout-beginner",
            domain = KnowledgeDomain.WORKOUT,
            title = "Beginner training guidance",
            content = "Begin with two or three short full-body sessions, modest effort, simple movements, and enough rest to repeat the plan.",
            keywords = setOf("beginner", "start", "new", "first workout"),
            experienceLevels = setOf("beginner"),
            priority = 14
        ),
        KnowledgeEntry(
            id = "nutrition-balanced-meals",
            domain = KnowledgeDomain.NUTRITION,
            title = "Balanced meal guidance",
            content = "Build meals around a protein source, vegetables or fruit, a suitable portion of carbohydrate, and enough fluids. Adjust portions gradually using real progress rather than extreme restriction.",
            keywords = setOf("nutrition", "diet", "meal", "calorie", "protein", "carbs", "fat"),
            goals = setOf("build muscle", "gain muscle", "fat loss", "weight loss", "get stronger"),
            priority = 10
        ),
        KnowledgeEntry(
            id = "nutrition-protein",
            domain = KnowledgeDomain.NUTRITION,
            title = "Protein guidance",
            content = "Spread protein foods across meals. Exact targets depend on body size, training, total diet, preferences, and medical context; supplements are optional.",
            keywords = setOf("protein", "muscle", "recovery", "supplement"),
            goals = setOf("build muscle", "gain muscle", "fat loss", "get stronger"),
            priority = 13
        ),
        KnowledgeEntry(
            id = "food-pakistani-protein",
            domain = KnowledgeDomain.PAKISTANI_FOOD,
            title = "Pakistani protein matches",
            content = "Match familiar meals with a clear protein source and treat nutrition values as recipe-dependent estimates.",
            keywords = setOf("pakistani", "desi", "protein", "daal", "chana", "chicken", "fish", "dahi"),
            goals = setOf("build muscle", "gain muscle", "fat loss", "get stronger"),
            foodNames = setOf(
                "Daal Masoor (Red Lentils)",
                "Daal Chana (Split Chickpeas)",
                "Chole (Chickpea Curry)",
                "Grilled Chicken Breast",
                "Grilled Fish",
                "Plain Yogurt (Dahi)"
            ),
            priority = 14
        ),
        KnowledgeEntry(
            id = "food-pakistani-portions",
            domain = KnowledgeDomain.PAKISTANI_FOOD,
            title = "Pakistani mixed-dish portions",
            content = "Home and restaurant recipes vary substantially in oil, meat, rice, and serving size. Use the bundled food records as planning estimates, not precise laboratory values.",
            keywords = setOf("pakistani", "desi", "biryani", "karahi", "nihari", "roti", "rice", "calorie"),
            foodNames = setOf(
                "Roti (Whole Wheat, Medium)",
                "Chicken Biryani",
                "Chicken Karahi",
                "Nihari (Beef)"
            ),
            priority = 14
        ),
        KnowledgeEntry(
            id = "recovery-recent-workout",
            domain = KnowledgeDomain.WORKOUT,
            title = "Recent workout recovery",
            content = "Use the real recent workout summary to avoid blindly repeating a demanding muscle group. Reduce load or choose recovery work when soreness, fatigue, or performance decline is present.",
            keywords = setOf("recent workout", "yesterday", "sore", "fatigue", "recovery", "trained"),
            priority = 16
        )
    )
}

object ProjectKnowledgeAdapters {
    fun exerciseEntries(records: List<ProjectExerciseRecord>): List<KnowledgeEntry> =
        records.mapNotNull { record ->
            val id = record.id.trim()
            val name = record.name.trim()
            if (id.isEmpty() || name.isEmpty()) return@mapNotNull null
            val equipment = record.equipment.normalizedOrNull()
            KnowledgeEntry(
                id = "project-exercise-$id",
                domain = KnowledgeDomain.EXERCISE,
                title = name,
                content = "Project exercise reference for ${record.target ?: record.muscleGroup ?: record.bodyPart ?: "general training"}.",
                keywords = listOfNotNull(
                    name,
                    record.bodyPart.normalizedOrNull(),
                    record.target.normalizedOrNull(),
                    record.muscleGroup.normalizedOrNull()
                ).toSet(),
                equipment = listOfNotNull(equipment).toSet(),
                exerciseIds = setOf(id),
                sourceType = KnowledgeSourceType.LEGACY_LOCAL,
                licenseStatus = RuntimeLicenseStatus.UNKNOWN
            )
        }

    fun foodEntries(records: List<ProjectFoodRecord>): List<KnowledgeEntry> =
        records.mapNotNull { record ->
            val name = record.name.trim()
            if (name.isEmpty() || record.calories < 0 || record.proteinGrams < 0 ||
                record.carbsGrams < 0 || record.fatGrams < 0
            ) return@mapNotNull null
            KnowledgeEntry(
                id = "legacy-food-${record.id}",
                domain = KnowledgeDomain.PAKISTANI_FOOD,
                title = name,
                content = if (record.isNutritionReviewed) {
                    "${record.servingSize}: about ${record.calories} kcal, ${record.proteinGrams} g protein, ${record.carbsGrams} g carbohydrate, and ${record.fatGrams} g fat. Recipe and portion estimates vary."
                } else {
                    "Nutrition reference available, but its serving basis is under FitDesi review. Do not treat the source values as a logged serving."
                },
                keywords = (setOf(name, record.category) + record.aliases),
                foodNames = setOf(name),
                sourceType = KnowledgeSourceType.LEGACY_LOCAL,
                licenseStatus = RuntimeLicenseStatus.UNKNOWN
            )
        }

    private fun String?.normalizedOrNull(): String? = this?.trim()?.takeIf(String::isNotEmpty)
}
