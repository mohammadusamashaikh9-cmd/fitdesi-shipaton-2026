package com.example.ai.knowledge

class FitnessKnowledgeRetriever private constructor(
    private val entries: List<KnowledgeEntry>
) {
    fun retrieve(query: KnowledgeQuery): List<KnowledgeMatch> {
        if (entries.isEmpty() || query.question.isBlank() || query.limit <= 0) return emptyList()

        val questionTokens = tokenize(query.question)
        val normalizedQuestion = normalize(query.question)
        val workoutTokens = tokenize(query.context.recentWorkoutSummary.orEmpty())
        val goal = normalizeGoal(query.context.goal)
        val experience = normalize(query.context.experience)
        val equipment = query.context.equipment.map(::normalizeEquipment).filter(String::isNotEmpty).toSet()

        return entries.mapNotNull { entry ->
            var score = entry.priority
            val reasons = linkedSetOf<String>()
            val searchableContent = if (entry.domain == KnowledgeDomain.MEDICAL_ESCALATION) {
                listOf(entry.title).plus(entry.keywords)
            } else {
                listOf(entry.title, entry.content).plus(entry.keywords)
            }
            val entryTokens = tokenize(searchableContent.joinToString(" "))
            val textHits = questionTokens.intersect(entryTokens).size
            if (textHits > 0) {
                score += textHits * 8
                reasons += "question"
            }
            if (goal.isNotEmpty() && entry.goals.any { normalizeGoal(it) == goal }) {
                score += 18
                reasons += "goal"
            }
            if (experience.isNotEmpty() && entry.experienceLevels.any { normalize(it) == experience }) {
                score += 10
                reasons += "experience"
            }
            val entryEquipment = entry.equipment.map(::normalizeEquipment).toSet()
            if (equipment.isNotEmpty() && entryEquipment.isNotEmpty()) {
                if (equipment.intersect(entryEquipment).isEmpty()) return@mapNotNull null
                score += 12
                reasons += "equipment"
            }
            val workoutHits = workoutTokens.intersect(entryTokens).size
            if (workoutHits > 0) {
                score += workoutHits * 4
                reasons += "recent_workout"
            }

            val medicalRelevant = entry.domain == KnowledgeDomain.MEDICAL_ESCALATION &&
                entry.keywords.any { keyword ->
                    val phrase = normalize(keyword)
                    phrase.isNotBlank() && normalizedQuestion.contains(phrase)
                }
            if (entry.domain == KnowledgeDomain.MEDICAL_ESCALATION && !medicalRelevant) {
                return@mapNotNull null
            }
            val safetyRelevant = entry.domain == KnowledgeDomain.SAFETY &&
                entry.keywords.any { keyword ->
                    val phrase = normalize(keyword)
                    phrase.isNotBlank() && normalizedQuestion.contains(phrase)
                }
            if (reasons.isEmpty() && !safetyRelevant && !medicalRelevant) return@mapNotNull null
            if (safetyRelevant || medicalRelevant) score += 100
            KnowledgeMatch(entry, score, reasons)
        }.sortedWith(
            compareByDescending<KnowledgeMatch> { it.score }
                .thenBy { it.entry.id }
        ).take(query.limit.coerceAtMost(MAX_RESULTS))
    }

    companion object {
        private const val MAX_RESULTS = 10

        fun from(entries: List<KnowledgeEntry>): FitnessKnowledgeRetriever =
            FitnessKnowledgeRetriever(entries.filter(::isValid))

        val default: FitnessKnowledgeRetriever by lazy {
            from(FitnessKnowledgeCatalog.entries)
        }

        private fun isValid(entry: KnowledgeEntry): Boolean =
            entry.id.isNotBlank() &&
                entry.title.isNotBlank() &&
                entry.content.isNotBlank() &&
                entry.priority in 0..100

        private fun tokenize(value: String): Set<String> =
            normalize(value)
                .split(' ')
                .filter { it.length >= 2 }
                .toSet()

        private fun normalize(value: String?): String =
            value.orEmpty()
                .lowercase()
                .replace(Regex("[^a-z0-9]+"), " ")
                .trim()

        private fun normalizeGoal(value: String?): String = when (normalize(value)) {
            "gain muscle", "build muscle", "hypertrophy" -> "muscle"
            "lose fat", "lose body fat", "fat loss", "weight loss", "lose weight" -> "fat_loss"
            "build strength", "get stronger", "strength" -> "strength"
            "maintain weight", "maintenance" -> "maintenance"
            "increase endurance", "endurance" -> "endurance"
            else -> normalize(value)
        }

        private fun normalizeEquipment(value: String?): String = when (normalize(value)) {
            "body weight", "bodyweight", "bodyweight only" -> "bodyweight"
            "resistance band", "resistance bands", "band", "bands" -> "band"
            "dumbbell", "dumbbells" -> "dumbbell"
            "kettlebell", "kettlebells" -> "kettlebell"
            "cable", "cables" -> "cable"
            "pull up bar", "pullup bar" -> "pull_up_bar"
            else -> normalize(value)
        }
    }
}
