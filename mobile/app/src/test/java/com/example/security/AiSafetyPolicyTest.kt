package com.example.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiSafetyPolicyTest {
    @Test
    fun rejectsBlankAndOversizedInput() {
        assertFalse(AiSafetyPolicy.isAcceptableInput("   "))
        assertTrue(AiSafetyPolicy.isAcceptableInput("a".repeat(AiSafetyPolicy.MAX_INPUT_CHARACTERS)))
        assertFalse(AiSafetyPolicy.isAcceptableInput("a".repeat(AiSafetyPolicy.MAX_INPUT_CHARACTERS + 1)))
    }

    @Test
    fun boundsHistoryToMostRecentMessages() {
        val history = (1..25).toList()

        assertEquals((6..25).toList(), AiSafetyPolicy.boundedHistory(history))
    }

    @Test
    fun boundsStoredMessageText() {
        val oversized = "a".repeat(AiSafetyPolicy.MAX_STORED_MESSAGE_CHARACTERS + 100)

        assertEquals(
            AiSafetyPolicy.MAX_STORED_MESSAGE_CHARACTERS,
            AiSafetyPolicy.boundedMessageText(oversized).length
        )
    }

    @Test
    fun friendlyErrorsDoNotContainBackendBodies() {
        assertEquals(
            "The coaching service is temporarily unavailable. Please try again later.",
            AiSafetyPolicy.friendlyError(500)
        )
        assertFalse(AiSafetyPolicy.friendlyError(500).contains("response", ignoreCase = true))
    }
}
