package com.example.ai.backend

import com.example.identity.AuthIdTokenResult
import com.example.identity.AuthRepository
import com.example.identity.AuthSessionState
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

enum class RemoteAiConsentDecisionStatus {
    NOT_DECIDED,
    GRANTED_CURRENT,
    GRANTED_STALE,
    DECLINED
}

data class RemoteAiConsentDecision(
    val granted: Boolean,
    val current: Boolean,
    val noticeVersion: String?,
    val decidedAt: String?,
    val status: RemoteAiConsentDecisionStatus
)

data class RemoteAiConsentNoticeVersions(
    val standardRemoteAi: String,
    val experimentalTraining: String
)

data class RemoteAiConsentState(
    val schemaVersion: Int,
    val standardRemoteAi: RemoteAiConsentDecision,
    val experimentalTraining: RemoteAiConsentDecision,
    val requiredNoticeVersions: RemoteAiConsentNoticeVersions,
    val updatedAt: String?
)

class RemoteAiConsentRepository(
    private val authRepository: AuthRepository,
    private val service: RemoteAiConsentService
) {
    suspend fun fetch(): BackendResult<RemoteAiConsentState> = withToken(service::consentState)

    suspend fun grantStandard(noticeVersion: String): BackendResult<Unit> = withToken { token ->
        service.updateStandardConsent(token, granted = true, noticeVersion = noticeVersion)
    }

    suspend fun declineStandard(): BackendResult<Unit> = withToken { token ->
        service.updateStandardConsent(token, granted = false)
    }

    suspend fun withdraw(): BackendResult<Unit> = withToken(service::deleteConsent)

    private suspend fun <T> withToken(
        operation: suspend (String) -> BackendResult<T>
    ): BackendResult<T> {
        if (authRepository.session.value !is AuthSessionState.Authenticated) {
            return BackendResult.PublicError(BackendPublicError.AUTH_REQUIRED)
        }
        return when (val token = authRepository.backendIdToken()) {
            is AuthIdTokenResult.Success -> operation(token.token)
            AuthIdTokenResult.AuthenticationRequired ->
                BackendResult.PublicError(BackendPublicError.AUTH_REQUIRED)
            AuthIdTokenResult.InvalidSession ->
                BackendResult.PublicError(BackendPublicError.INVALID_SESSION)
            is AuthIdTokenResult.Failure ->
                BackendResult.PublicError(BackendPublicError.AUTH_VERIFICATION_UNAVAILABLE)
        }
    }
}

internal fun RemoteAiConsentStateDto.toRemoteAiConsentState(): RemoteAiConsentState? {
    if (schemaVersion != 1) return null
    if (!NOTICE_VERSION_PATTERN.matches(requiredNoticeVersions.standardRemoteAi) ||
        !NOTICE_VERSION_PATTERN.matches(requiredNoticeVersions.experimentalTraining)
    ) return null
    val standardCurrent = standardRemoteAi.granted &&
        standardRemoteAi.noticeVersion == requiredNoticeVersions.standardRemoteAi
    val experimentalCurrent = experimentalTraining.granted &&
        experimentalTraining.noticeVersion == requiredNoticeVersions.experimentalTraining &&
        standardCurrent
    if (standardRemoteAi.current != standardCurrent ||
        experimentalTraining.current != experimentalCurrent ||
        (experimentalTraining.granted && !standardRemoteAi.granted)
    ) return null
    if (!standardRemoteAi.hasValidDecision() || !experimentalTraining.hasValidDecision()) return null
    val noDecision = standardRemoteAi.noticeVersion == null && experimentalTraining.noticeVersion == null
    if (updatedAt == null && !noDecision) return null
    if (updatedAt != null &&
        (!updatedAt.isCanonicalIsoUtc() ||
            standardRemoteAi.noticeVersion == null ||
            experimentalTraining.noticeVersion == null)
    ) return null

    return RemoteAiConsentState(
        schemaVersion = schemaVersion,
        standardRemoteAi = standardRemoteAi.toDecision(),
        experimentalTraining = experimentalTraining.toDecision(),
        requiredNoticeVersions = RemoteAiConsentNoticeVersions(
            standardRemoteAi = requiredNoticeVersions.standardRemoteAi,
            experimentalTraining = requiredNoticeVersions.experimentalTraining
        ),
        updatedAt = updatedAt
    )
}

private fun RemoteAiConsentDecisionDto.hasValidDecision(): Boolean {
    val missing = noticeVersion == null && decidedAt == null
    val stored = noticeVersion?.let(NOTICE_VERSION_PATTERN::matches) == true &&
        decidedAt?.isCanonicalIsoUtc() == true
    return (missing || stored) && (!missing || (!granted && !current))
}

private fun RemoteAiConsentDecisionDto.toDecision(): RemoteAiConsentDecision =
    RemoteAiConsentDecision(
        granted = granted,
        current = current,
        noticeVersion = noticeVersion,
        decidedAt = decidedAt,
        status = when {
            noticeVersion == null -> RemoteAiConsentDecisionStatus.NOT_DECIDED
            !granted -> RemoteAiConsentDecisionStatus.DECLINED
            current -> RemoteAiConsentDecisionStatus.GRANTED_CURRENT
            else -> RemoteAiConsentDecisionStatus.GRANTED_STALE
        }
    )

private val NOTICE_VERSION_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")

private fun String.isCanonicalIsoUtc(): Boolean {
    if (length != 24) return false
    val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        isLenient = false
        timeZone = TimeZone.getTimeZone("UTC")
    }
    val position = ParsePosition(0)
    val parsed = formatter.parse(this, position) ?: return false
    return position.index == length && formatter.format(parsed) == this
}
