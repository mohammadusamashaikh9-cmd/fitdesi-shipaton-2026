package com.example

import com.example.security.AiSafetyPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {
    @Test
    fun safetyPolicy_acceptsBoundedOfflineInput() {
        assertTrue(AiSafetyPolicy.isAcceptableInput("Build a balanced workout week"))
        assertFalse(AiSafetyPolicy.isAcceptableInput(" "))
        assertFalse(AiSafetyPolicy.isAcceptableInput("x".repeat(AiSafetyPolicy.MAX_INPUT_CHARACTERS + 1)))
    }

    @Test
    fun safetyPolicy_boundsStoredMessageTextDeterministically() {
        val oversizedMessage = "x".repeat(AiSafetyPolicy.MAX_STORED_MESSAGE_CHARACTERS + 10)

        assertEquals(
            AiSafetyPolicy.MAX_STORED_MESSAGE_CHARACTERS,
            AiSafetyPolicy.boundedMessageText(oversizedMessage).length
        )
    }
}
