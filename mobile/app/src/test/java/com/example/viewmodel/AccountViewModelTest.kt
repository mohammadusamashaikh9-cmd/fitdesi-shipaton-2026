package com.example.viewmodel

import com.example.identity.AuthFailure
import com.example.identity.AuthIdTokenResult
import com.example.identity.AuthOperationResult
import com.example.identity.AuthRepository
import com.example.identity.AuthSessionState
import com.example.subscription.RevenueCatIdentityCoordinator
import com.example.subscription.RevenueCatIdentityState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AccountViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `invalid sign in uses enumeration-safe copy and retains no password`() = runTest(dispatcher) {
        val repository = FakeAuthRepository().apply {
            signInResult = AuthOperationResult.Failure(AuthFailure.INVALID_CREDENTIALS)
        }
        val viewModel = AccountViewModel(repository, dispatcher)

        viewModel.signIn(" person@example.com ", "private password")
        advanceUntilIdle()

        assertEquals(" person@example.com ", repository.lastEmail)
        assertEquals("private password", repository.lastPassword)
        assertEquals("Unable to sign in with those credentials.", viewModel.uiState.value.errorMessage)
        assertFalse(viewModel.uiState.value.toString().contains("private password"))
        assertFalse(viewModel.uiState.value.toString().contains("person@example.com"))
    }

    @Test
    fun `password reset success never reveals whether an account exists`() = runTest(dispatcher) {
        val repository = FakeAuthRepository()
        val viewModel = AccountViewModel(repository, dispatcher)

        viewModel.sendPasswordReset("person@example.com")
        advanceUntilIdle()

        assertEquals(
            "If an account exists for that email, reset instructions will be sent.",
            viewModel.uiState.value.successMessage
        )
    }

    @Test
    fun `invalid user password reset has the same privacy-safe success outcome`() = runTest(dispatcher) {
        val repository = FakeAuthRepository().apply {
            passwordResetResult = AuthOperationResult.Failure(AuthFailure.INVALID_CREDENTIALS)
        }
        val viewModel = AccountViewModel(repository, dispatcher)

        viewModel.sendPasswordReset("person@example.com")
        advanceUntilIdle()

        assertEquals(AccountViewModel.PASSWORD_RESET_SUCCESS, viewModel.uiState.value.successMessage)
        assertEquals(null, viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `verification delivery failure reports created account without retrying creation`() = runTest(dispatcher) {
        val repository = FakeAuthRepository().apply {
            createAccountResult =
                AuthOperationResult.AccountCreatedVerificationDeliveryFailed(AuthFailure.NETWORK)
        }
        val viewModel = AccountViewModel(repository, dispatcher)

        viewModel.createAccount("person@example.com", "password", "password")
        advanceUntilIdle()

        val session = viewModel.uiState.value.session as AuthSessionState.Authenticated
        assertEquals(false, session.emailVerified)
        assertEquals(1, repository.createAccountRequests)
        assertEquals(AccountViewModel.VERIFICATION_DELIVERY_FAILED, viewModel.uiState.value.errorMessage)
        assertFalse(viewModel.uiState.value.errorMessage.orEmpty().contains("Unable to create the account"))
    }

    @Test
    fun `reauthentication failure prevents account deletion`() = runTest(dispatcher) {
        val repository = FakeAuthRepository().apply {
            reauthenticateResult = AuthOperationResult.Failure(AuthFailure.INVALID_CREDENTIALS)
        }
        val viewModel = AccountViewModel(repository, dispatcher)

        viewModel.deleteAccount("wrong password")
        advanceUntilIdle()

        assertEquals(1, repository.reauthenticateRequests)
        assertEquals(0, repository.deleteRequests)
        assertEquals(
            "Unable to confirm your password. Your account was not deleted.",
            viewModel.uiState.value.errorMessage
        )
        assertFalse(viewModel.uiState.value.toString().contains("wrong password"))
    }

    @Test
    fun `account deletion reauthenticates before deleting and retains no password`() = runTest(dispatcher) {
        val repository = FakeAuthRepository()
        val viewModel = AccountViewModel(repository, dispatcher)

        viewModel.deleteAccount("private password")
        advanceUntilIdle()

        assertEquals(listOf("reauthenticate", "delete"), repository.actions)
        assertFalse(viewModel.uiState.value.toString().contains("private password"))
        assertEquals(
            "Account deleted. Your local fitness data remains on this device.",
            viewModel.uiState.value.successMessage
        )
    }

    @Test
    fun `empty credentials are rejected without contacting provider`() = runTest(dispatcher) {
        val repository = FakeAuthRepository()
        val viewModel = AccountViewModel(repository, dispatcher)

        viewModel.signIn("   ", "")
        advanceUntilIdle()

        assertEquals(0, repository.signInRequests)
        assertEquals("Enter your email and password.", viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `session lifecycle remains the repository's authoritative state`() = runTest(dispatcher) {
        val repository = FakeAuthRepository()
        val viewModel = AccountViewModel(repository, dispatcher)
        advanceUntilIdle()

        repository.mutableSession.value = AuthSessionState.Authenticated(
            uid = "uid",
            email = "person@example.com",
            emailVerified = false
        )
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.session is AuthSessionState.Authenticated)
        assertEquals(false, (viewModel.uiState.value.session as AuthSessionState.Authenticated).emailVerified)
    }

    @Test
    fun `sign out invalidates commercial state before Firebase and reconciles Guest afterward`() =
        runTest(dispatcher) {
            val repository = FakeAuthRepository().apply {
                mutableSession.value = verifiedSession("firebaseUidA")
            }
            val identity = RecordingIdentityCoordinator(repository.actions)
            val viewModel = AccountViewModel(repository, dispatcher, identity)

            viewModel.signOut()
            advanceUntilIdle()

            assertEquals(
                listOf("commercial-invalidate", "sign-out", "reconcile-Guest"),
                repository.actions
            )
        }

    @Test
    fun `Firebase sign out failure reconciles back to still current account`() = runTest(dispatcher) {
        val repository = FakeAuthRepository().apply {
            mutableSession.value = verifiedSession("firebaseUidA")
            signOutResult = AuthOperationResult.Failure(AuthFailure.NETWORK)
        }
        val identity = RecordingIdentityCoordinator(repository.actions)
        val viewModel = AccountViewModel(repository, dispatcher, identity)

        viewModel.signOut()
        advanceUntilIdle()

        assertEquals(
            listOf(
                "commercial-invalidate",
                "sign-out",
                "reconcile-firebaseUidA"
            ),
            repository.actions
        )
        assertTrue(viewModel.uiState.value.session is AuthSessionState.Authenticated)
    }

    @Test
    fun `account deletion invalidates after reauthentication and reconciles Guest`() =
        runTest(dispatcher) {
            val repository = FakeAuthRepository().apply {
                mutableSession.value = verifiedSession("firebaseUidA")
            }
            val identity = RecordingIdentityCoordinator(repository.actions)
            val viewModel = AccountViewModel(repository, dispatcher, identity)

            viewModel.deleteAccount("private password")
            advanceUntilIdle()

            assertEquals(
                listOf(
                    "reauthenticate",
                    "commercial-invalidate",
                    "delete",
                    "reconcile-Guest"
                ),
                repository.actions
            )
        }

    @Test
    fun `Firebase deletion failure reconciles back to still current account`() = runTest(dispatcher) {
        val repository = FakeAuthRepository().apply {
            mutableSession.value = verifiedSession("firebaseUidA")
            deleteResult = AuthOperationResult.Failure(AuthFailure.NETWORK)
        }
        val identity = RecordingIdentityCoordinator(repository.actions)
        val viewModel = AccountViewModel(repository, dispatcher, identity)

        viewModel.deleteAccount("private password")
        advanceUntilIdle()

        assertEquals(
            listOf(
                "reauthenticate",
                "commercial-invalidate",
                "delete",
                "reconcile-firebaseUidA"
            ),
            repository.actions
        )
        assertTrue(viewModel.uiState.value.session is AuthSessionState.Authenticated)
    }

    @Test
    fun `Profile account summaries distinguish guest verification and verified state`() {
        assertEquals(
            "Guest · local data stays on this device",
            AuthSessionState.Guest.profileAccountValue()
        )
        assertEquals(
            "person@example.com · verification needed",
            AuthSessionState.Authenticated("uid", "person@example.com", false).profileAccountValue()
        )
        assertEquals(
            "person@example.com · verified",
            AuthSessionState.Authenticated("uid", "person@example.com", true).profileAccountValue()
        )
    }

    private class FakeAuthRepository : AuthRepository {
        val mutableSession = MutableStateFlow<AuthSessionState>(AuthSessionState.Guest)
        override val session: StateFlow<AuthSessionState> = mutableSession
        override suspend fun backendIdToken() = AuthIdTokenResult.AuthenticationRequired
        var signInResult: AuthOperationResult = AuthOperationResult.Success
        var createAccountResult: AuthOperationResult = AuthOperationResult.Success
        var passwordResetResult: AuthOperationResult = AuthOperationResult.Success
        var reauthenticateResult: AuthOperationResult = AuthOperationResult.Success
        var signOutResult: AuthOperationResult = AuthOperationResult.Success
        var deleteResult: AuthOperationResult = AuthOperationResult.Success
        var lastEmail: String? = null
        var lastPassword: String? = null
        var signInRequests = 0
        var createAccountRequests = 0
        var reauthenticateRequests = 0
        var deleteRequests = 0
        val actions = mutableListOf<String>()

        override suspend fun createAccount(email: String, password: String): AuthOperationResult {
            createAccountRequests += 1
            if (createAccountResult is AuthOperationResult.AccountCreatedVerificationDeliveryFailed) {
                mutableSession.value = AuthSessionState.Authenticated(
                    uid = "uid",
                    email = email.trim(),
                    emailVerified = false
                )
            }
            return createAccountResult
        }

        override suspend fun signIn(email: String, password: String): AuthOperationResult {
            signInRequests += 1
            lastEmail = email
            lastPassword = password
            return signInResult
        }

        override suspend fun sendPasswordReset(email: String): AuthOperationResult =
            passwordResetResult

        override suspend fun resendVerification(): AuthOperationResult =
            AuthOperationResult.Success

        override suspend fun refreshCurrentUser(): AuthOperationResult =
            AuthOperationResult.Success

        override suspend fun signOut(): AuthOperationResult {
            actions += "sign-out"
            if (signOutResult == AuthOperationResult.Success) {
                mutableSession.value = AuthSessionState.Guest
            }
            return signOutResult
        }

        override suspend fun reauthenticate(password: String): AuthOperationResult {
            reauthenticateRequests += 1
            actions += "reauthenticate"
            lastPassword = password
            return reauthenticateResult
        }

        override suspend fun deleteAccount(): AuthOperationResult {
            deleteRequests += 1
            actions += "delete"
            if (deleteResult == AuthOperationResult.Success) {
                mutableSession.value = AuthSessionState.Guest
            }
            return deleteResult
        }
    }

    private class RecordingIdentityCoordinator(
        private val actions: MutableList<String>
    ) : RevenueCatIdentityCoordinator {
        private val mutableState = MutableStateFlow<RevenueCatIdentityState>(
            RevenueCatIdentityState.AnonymousReady(0L)
        )
        override val state: StateFlow<RevenueCatIdentityState> = mutableState

        override fun reconcile(session: AuthSessionState) {
            val owner = (session as? AuthSessionState.Authenticated)?.uid ?: "Guest"
            actions += "reconcile-$owner"
        }

        override fun retry() = Unit

        override fun invalidateBeforeAuthExit() {
            actions += "commercial-invalidate"
        }
    }

    private companion object {
        fun verifiedSession(uid: String) = AuthSessionState.Authenticated(
            uid = uid,
            email = "person@example.com",
            emailVerified = true
        )
    }
}
