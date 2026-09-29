package com.example.ai.knowledge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FitnessKnowledgeRetrieverTest {
    @Test
    fun retrievalUsesQuestionAndRecentWorkout() {
        val matches = FitnessKnowledgeRetriever.default.retrieve(
            KnowledgeQuery(
                question = "What should I train after rows yesterday?",
                context = CoachContext(
                    goal = "Get Stronger",
                    experience = "Intermediate",
                    equipment = setOf("Barbell"),
                    recentWorkoutSummary = "Barbell row back workout"
                )
            )
        )

        assertTrue(matches.isNotEmpty())
        assertTrue(matches.any { "recent_workout" in it.reasons })
        assertTrue(matches.any { it.entry.id == "exercise-pull-pattern" })
    }

    @Test
    fun incompatibleEquipmentIsFiltered() {
        val matches = FitnessKnowledgeRetriever.default.retrieve(
            KnowledgeQuery(
                question = "Give me a chest push exercise",
                context = CoachContext(equipment = setOf("Resistance Bands"))
            )
        )

        assertTrue(matches.none { it.entry.id == "exercise-push-pattern" })
    }

    @Test
    fun goalMatchRaisesGoalSpecificGuidance() {
        val matches = FitnessKnowledgeRetriever.default.retrieve(
            KnowledgeQuery(
                question = "How should I plan training?",
                context = CoachContext(goal = "Lose Fat")
            )
        )

        assertEquals("workout-fat-loss", matches.first().entry.id)
        assertTrue("goal" in matches.first().reasons)
    }

    @Test
    fun medicalEscalationAlwaysRanksFirst() {
        val matches = FitnessKnowledgeRetriever.default.retrieve(
            KnowledgeQuery("I have chest pain and feel faint after exercise")
        )

        assertEquals(KnowledgeDomain.MEDICAL_ESCALATION, matches.first().entry.domain)
    }

    @Test
    fun genuineSafetyLanguageRaisesSafetyGuidance() {
        val matches = FitnessKnowledgeRetriever.default.retrieve(
            KnowledgeQuery("How can I train safely when a set causes pain?")
        )

        assertEquals(KnowledgeDomain.SAFETY, matches.first().entry.domain)
    }

    @Test
    fun malformedEntriesAreIgnored() {
        val retriever = FitnessKnowledgeRetriever.from(
            listOf(
                KnowledgeEntry("", KnowledgeDomain.WORKOUT, "Bad", "Content"),
                KnowledgeEntry("bad-title", KnowledgeDomain.WORKOUT, "", "Content"),
                KnowledgeEntry("good", KnowledgeDomain.WORKOUT, "Good plan", "Train consistently", keywords = setOf("plan"))
            )
        )

        val matches = retriever.retrieve(KnowledgeQuery("plan"))
        assertEquals(listOf("good"), matches.map { it.entry.id })
    }

    @Test
    fun yogaRetrievalIsDeterministicAndRespondsToReviewedBodyTargets() {
        val retriever = FitnessKnowledgeRetriever.from(yogaEntries())

        val generic = retriever.retrieve(KnowledgeQuery("Show beginner yoga and mobility options", limit = 5))
        val hips = retriever.retrieve(KnowledgeQuery("Show yoga options for hips", limit = 5))
        val shoulders = retriever.retrieve(KnowledgeQuery("Show yoga for shoulders and upper back", limit = 5))
        val twist = retriever.retrieve(KnowledgeQuery("Show a gentle supine twist", limit = 5))

        assertEquals(
            listOf("fd-yoga-bound-angle", "fd-yoga-cat-cow", "fd-yoga-supine-twist", "fd-yoga-thread-the-needle"),
            generic.map { it.entry.id }
        )
        assertEquals("fd-yoga-bound-angle", hips.first().entry.id)
        assertEquals("fd-yoga-thread-the-needle", shoulders.first().entry.id)
        assertEquals("fd-yoga-supine-twist", twist.first().entry.id)
        assertEquals(generic.map { it.entry.id }, retriever.retrieve(KnowledgeQuery("Show beginner yoga and mobility options", limit = 5)).map { it.entry.id })
        assertTrue(generic.size <= 5)
    }

    @Test
    fun emptyDataReturnsSafeEmptyResult() {
        val matches = FitnessKnowledgeRetriever.from(emptyList())
            .retrieve(KnowledgeQuery("build muscle"))

        assertTrue(matches.isEmpty())
    }

    @Test
    fun adaptersReuseProjectRecordsAndRejectMalformedData() {
        val exercises = ProjectKnowledgeAdapters.exerciseEntries(
            listOf(
                ProjectExerciseRecord("0025", "Barbell bench press", "chest", "pectorals", null, "barbell"),
                ProjectExerciseRecord("", "Malformed", null, null, null, null)
            )
        )
        val foods = ProjectKnowledgeAdapters.foodEntries(
            listOf(
                ProjectFoodRecord("1", "Daal Masoor (Red Lentils)", "Legumes", "1 bowl", 230, 16.0, 35.0, 4.0),
                ProjectFoodRecord("", "", "Bad", "1 bowl", -1, 0.0, 0.0, 0.0)
            )
        )

        assertEquals(setOf("0025"), exercises.single().exerciseIds)
        assertEquals(setOf("Daal Masoor (Red Lentils)"), foods.single().foodNames)
    }

    private fun yogaEntries() = listOf(
        yogaEntry("fd-yoga-cat-cow", "Cat-Cow Mobility", setOf("spine", "trunk")),
        yogaEntry("fd-yoga-bound-angle", "Bound Angle Pose", setOf("hips", "inner thighs")),
        yogaEntry("fd-yoga-supine-twist", "Supine Twist", setOf("hips", "trunk", "twist")),
        yogaEntry("fd-yoga-thread-the-needle", "Thread the Needle", setOf("shoulders", "upper back"))
    )

    private fun yogaEntry(id: String, title: String, bodyTargets: Set<String>) = KnowledgeEntry(
        id = id,
        domain = KnowledgeDomain.EXERCISE,
        title = title,
        content = "Move within a comfortable range. Safety: stop for sharp pain or dizziness.",
        keywords = bodyTargets + setOf("yoga", "mobility"),
        exerciseIds = setOf(id),
        priority = 18,
        sourceType = KnowledgeSourceType.VERIFIED_PACK,
        licenseStatus = RuntimeLicenseStatus.VERIFIED
    )
}
