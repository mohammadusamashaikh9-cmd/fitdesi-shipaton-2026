package com.example.security

import com.example.BuildConfig

/**
 * Central safety boundary for Build Week integrations.
 *
 * Remote integrations remain disabled by default. They may be enabled only in
 * a deliberately configured build that points at the FitDesi backend proxy.
 */
object BuildWeekRuntimeConfig {
    val safeMode: Boolean = BuildConfig.BUILD_WEEK_SAFE_MODE

    private val hasConfiguredBackend: Boolean
        get() = BuildConfig.FITDESI_BACKEND_BASE_URL.isNotBlank() &&
            !BuildConfig.FITDESI_BACKEND_BASE_URL.contains("example.invalid")

    val aiProxyEnabled: Boolean
        get() = !safeMode && BuildConfig.AI_PROXY_ENABLED && hasConfiguredBackend

    val exerciseProxyEnabled: Boolean
        get() = !safeMode && BuildConfig.EXERCISE_PROXY_ENABLED && hasConfiguredBackend

    val firestoreSyncEnabled: Boolean
        get() = !safeMode && BuildConfig.FIRESTORE_SYNC_ENABLED

    val destructiveMigrationAllowed: Boolean
        get() = !safeMode && BuildConfig.ALLOW_DESTRUCTIVE_MIGRATION
}

object AiSafetyPolicy {
    const val MAX_INPUT_CHARACTERS = 2_000
    const val MAX_STORED_MESSAGE_CHARACTERS = 8_000
    const val MAX_HISTORY_MESSAGES = 20

    fun isAcceptableInput(input: String): Boolean {
        val trimmed = input.trim()
        return trimmed.isNotEmpty() && trimmed.length <= MAX_INPUT_CHARACTERS
    }

    fun <T> boundedHistory(messages: List<T>): List<T> =
        messages.takeLast(MAX_HISTORY_MESSAGES)

    fun boundedMessageText(text: String): String =
        text.take(MAX_STORED_MESSAGE_CHARACTERS)

    fun friendlyError(statusCode: Int? = null): String = when {
        statusCode == 400 -> "That request could not be processed. Please shorten or rephrase it."
        statusCode == 401 || statusCode == 403 -> "The coaching service is not available for this build."
        statusCode == 408 || statusCode == 429 -> "The coaching service is busy. Please wait a moment and try again."
        statusCode != null && statusCode in 500..599 -> "The coaching service is temporarily unavailable. Please try again later."
        else -> "We could not reach the coaching service. Please check your connection and try again."
    }
}
