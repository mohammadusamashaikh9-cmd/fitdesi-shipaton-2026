package com.example.viewmodel

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ExerciseLoadingPolicyTest {
    @Test(expected = CancellationException::class)
    fun `navigation cancellation is never converted into an error state`() {
        exerciseLoadFailureMessage(CancellationException("scope left composition"))
    }

    @Test
    fun `genuine failures expose only the safe retry message`() {
        val message = exerciseLoadFailureMessage(IllegalStateException("secret implementation detail"))
        assertEquals("Exercise library could not be loaded. Try again.", message)
        assertFalse(message.contains("implementation detail"))
    }
}
