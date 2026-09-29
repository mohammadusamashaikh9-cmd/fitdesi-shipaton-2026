package com.example.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.identity.AuthFailure
import com.example.identity.AuthOperationResult
import com.example.identity.AuthRepository
import com.example.identity.AuthSessionState
import com.example.subscription.DisabledRevenueCatIdentityCoordinator
import com.example.subscription.RevenueCatIdentityCoordinator
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

data class AccountUiState(
    val session: AuthSessionState,
    val isLoading: Boolean = false,
    val successMessage: String? = null,
    val errorMessage: String? = null
)

class AccountViewModel internal constructor(
    private val repository: AuthRepository,
    private val operationDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val identityCoordinator: RevenueCatIdentityCoordinator =
        DisabledRevenueCatIdentityCoordinator()
) : ViewModel() {
    private val _uiState = MutableStateFlow(AccountUiState(session = repository.session.value))
    val uiState: StateFlow<AccountUiState> = _uiState.asStateFlow()

    private var operationJob: Job? = null

    init {
        viewModelScope.launch(operationDispatcher) {
            repository.session.collect { session ->
                _uiState.value = _uiState.value.copy(session = session)
            }
        }
    }

    fun createAccount(email: String, password: String, confirmPassword: String) {
        if (email.isBlank() || password.isEmpty()) {
            showValidationError("Enter your email and password.")
            return
        }
        if (password != confirmPassword) {
            showValidationError("Passwords do not match.")
            return
        }
        launchOperation(
            successMessage = "Account created. Check your email for a verification message.",
            failureMessage = ::createAccountFailureMessage,
            partialSuccessMessage = VERIFICATION_DELIVERY_FAILED
        ) {
            repository.createAccount(email, password)
        }
    }

    fun signIn(email: String, password: String) {
        if (email.isBlank() || password.isEmpty()) {
            showValidationError("Enter your email and password.")
            return
        }
        launchOperation(
            successMessage = "Signed in.",
            failureMessage = ::signInFailureMessage
        ) {
            repository.signIn(email, password)
        }
    }

    fun sendPasswordReset(email: String) {
        if (email.isBlank()) {
            showValidationError("Enter your email first.")
            return
        }
        launchOperation(
            successMessage = PASSWORD_RESET_SUCCESS,
            failureMessage = ::passwordResetFailureMessage,
            privacySafeFailures = setOf(AuthFailure.INVALID_CREDENTIALS)
        ) {
            repository.sendPasswordReset(email)
        }
    }

    fun resendVerification() {
        launchOperation(
            successMessage = "Verification email sent.",
            failureMessage = ::verificationFailureMessage
        ) {
            repository.resendVerification()
        }
    }

    fun refreshCurrentUser() {
        launchOperation(
            successMessage = "Verification status refreshed.",
            failureMessage = ::verificationFailureMessage
        ) {
            repository.refreshCurrentUser()
        }
    }

    fun signOut() {
        if (operationJob?.isActive == true) return
        operationJob = viewModelScope.launch(operationDispatcher) {
            setLoading()
            identityCoordinator.invalidateBeforeAuthExit()
            val result = repository.signOut()
            identityCoordinator.reconcile(repository.session.value)
            when (result) {
                AuthOperationResult.Success -> finishWithSuccess(
                    "Signed out. Your local fitness data remains on this device."
                )
                is AuthOperationResult.Failure -> finishWithError(
                    genericFailureMessage(result.reason)
                )
                is AuthOperationResult.AccountCreatedVerificationDeliveryFailed ->
                    finishWithError(genericFailureMessage(result.reason))
            }
        }
    }

    fun deleteAccount(password: String) {
        if (password.isEmpty()) {
            showValidationError("Enter your password to confirm account deletion.")
            return
        }
        if (operationJob?.isActive == true) return

        operationJob = viewModelScope.launch(operationDispatcher) {
            setLoading()
            when (val reauthentication = repository.reauthenticate(password)) {
                AuthOperationResult.Success -> Unit
                is AuthOperationResult.Failure -> {
                    finishWithError(reauthenticationFailureMessage(reauthentication.reason))
                    return@launch
                }
                is AuthOperationResult.AccountCreatedVerificationDeliveryFailed -> {
                    finishWithError(genericFailureMessage(reauthentication.reason))
                    return@launch
                }
            }

            identityCoordinator.invalidateBeforeAuthExit()
            when (val deletion = repository.deleteAccount()) {
                AuthOperationResult.Success -> {
                    identityCoordinator.reconcile(repository.session.value)
                    finishWithSuccess(
                        "Account deleted. Your local fitness data remains on this device."
                    )
                }
                is AuthOperationResult.Failure -> finishWithError(
                    deleteAccountFailureMessage(deletion.reason).also {
                        identityCoordinator.reconcile(repository.session.value)
                    }
                )
                is AuthOperationResult.AccountCreatedVerificationDeliveryFailed -> {
                    identityCoordinator.reconcile(repository.session.value)
                    finishWithError(genericFailureMessage(deletion.reason))
                }
            }
        }
    }

    fun clearTransientState() {
        _uiState.value = _uiState.value.copy(successMessage = null, errorMessage = null)
    }

    private fun launchOperation(
        successMessage: String,
        failureMessage: (AuthFailure) -> String,
        partialSuccessMessage: String? = null,
        privacySafeFailures: Set<AuthFailure> = emptySet(),
        operation: suspend () -> AuthOperationResult
    ) {
        if (operationJob?.isActive == true) return
        operationJob = viewModelScope.launch(operationDispatcher) {
            setLoading()
            when (val result = operation()) {
                AuthOperationResult.Success -> finishWithSuccess(successMessage)
                is AuthOperationResult.Failure -> if (result.reason in privacySafeFailures) {
                    finishWithSuccess(successMessage)
                } else {
                    finishWithError(failureMessage(result.reason))
                }
                is AuthOperationResult.AccountCreatedVerificationDeliveryFailed ->
                    finishWithError(
                        partialSuccessMessage ?: genericFailureMessage(result.reason)
                    )
            }
        }
    }

    private fun setLoading() {
        _uiState.value = _uiState.value.copy(
            isLoading = true,
            successMessage = null,
            errorMessage = null
        )
    }

    private fun finishWithSuccess(message: String) {
        _uiState.value = _uiState.value.copy(
            isLoading = false,
            successMessage = message,
            errorMessage = null
        )
    }

    private fun finishWithError(message: String) {
        _uiState.value = _uiState.value.copy(
            isLoading = false,
            successMessage = null,
            errorMessage = message
        )
    }

    private fun showValidationError(message: String) {
        if (operationJob?.isActive == true) return
        finishWithError(message)
    }

    companion object {
        const val PASSWORD_RESET_SUCCESS =
            "If an account exists for that email, reset instructions will be sent."
        const val VERIFICATION_DELIVERY_FAILED =
            "Your account was created, but the verification email could not be sent. Use Resend verification to try again."

        fun factory(
            repository: AuthRepository,
            identityCoordinator: RevenueCatIdentityCoordinator =
                DisabledRevenueCatIdentityCoordinator()
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(AccountViewModel::class.java))
                    return AccountViewModel(
                        repository = repository,
                        identityCoordinator = identityCoordinator
                    ) as T
                }
            }
    }
}

internal fun AuthSessionState.profileAccountValue(): String = when (this) {
    AuthSessionState.Initializing -> "Checking account"
    AuthSessionState.Guest -> "Guest · local data stays on this device"
    is AuthSessionState.Authenticated -> if (emailVerified) {
        "$email · verified"
    } else {
        "$email · verification needed"
    }
}

private fun networkOrNull(failure: AuthFailure): String? = when (failure) {
    AuthFailure.NETWORK -> "You're offline or the service is unavailable. Try again when connected."
    AuthFailure.TOO_MANY_REQUESTS -> "Too many attempts. Please wait and try again."
    else -> null
}

private fun signInFailureMessage(failure: AuthFailure): String =
    networkOrNull(failure) ?: "Unable to sign in with those credentials."

private fun createAccountFailureMessage(failure: AuthFailure): String =
    networkOrNull(failure) ?: "Unable to create the account. Check the details and try again."

private fun passwordResetFailureMessage(failure: AuthFailure): String =
    networkOrNull(failure) ?: "Unable to send reset instructions right now. Please try again."

private fun verificationFailureMessage(failure: AuthFailure): String =
    networkOrNull(failure) ?: "Unable to update email verification right now. Please try again."

private fun reauthenticationFailureMessage(failure: AuthFailure): String =
    networkOrNull(failure)
        ?: "Unable to confirm your password. Your account was not deleted."

private fun deleteAccountFailureMessage(failure: AuthFailure): String =
    when (failure) {
        AuthFailure.REQUIRES_RECENT_LOGIN ->
            "Please confirm your password again before deleting the account."
        else -> networkOrNull(failure)
            ?: "Unable to delete the account. Your account and local data were not changed."
    }

private fun genericFailureMessage(failure: AuthFailure): String =
    networkOrNull(failure) ?: "Unable to complete that account action. Please try again."
