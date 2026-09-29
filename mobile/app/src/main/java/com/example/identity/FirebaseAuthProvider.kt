package com.example.identity

import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthRecentLoginRequiredException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.auth.FirebaseUser
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine

internal class FirebaseAuthProvider(
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
) : AuthProvider {
    override fun currentAccount(): AuthProviderAccount? = auth.currentUser?.toProviderAccount()

    override fun addAuthStateListener(listener: (AuthProviderAccount?) -> Unit): AuthListenerRegistration {
        val firebaseListener = FirebaseAuth.AuthStateListener { currentAuth ->
            listener(currentAuth.currentUser?.toProviderAccount())
        }
        auth.addAuthStateListener(firebaseListener)
        return AuthListenerRegistration { auth.removeAuthStateListener(firebaseListener) }
    }

    override suspend fun createAccount(email: String, password: String): AuthProviderAccount =
        providerCall {
            auth.createUserWithEmailAndPassword(email, password).awaitTask()
            requireCurrentAccount()
        }

    override suspend fun signIn(email: String, password: String): AuthProviderAccount =
        providerCall {
            auth.signInWithEmailAndPassword(email, password).awaitTask()
            requireCurrentAccount()
        }

    override suspend fun sendPasswordReset(email: String) {
        providerCall { auth.sendPasswordResetEmail(email).awaitTask() }
    }

    override suspend fun sendEmailVerification() {
        providerCall {
            val user = auth.currentUser ?: throw AuthProviderException(AuthFailure.UNAVAILABLE)
            user.sendEmailVerification().awaitTask()
        }
    }

    override suspend fun reloadCurrentAccount(): AuthProviderAccount? = providerCall {
        val user = auth.currentUser ?: return@providerCall null
        user.reload().awaitTask()
        auth.currentUser?.toProviderAccount()
    }

    override fun signOut() {
        auth.signOut()
    }

    override suspend fun reauthenticate(password: String) {
        providerCall {
            val user = auth.currentUser ?: throw AuthProviderException(AuthFailure.UNAVAILABLE)
            val email = user.email?.takeIf(String::isNotBlank)
                ?: throw AuthProviderException(AuthFailure.UNAVAILABLE)
            user.reauthenticate(EmailAuthProvider.getCredential(email, password)).awaitTask()
        }
    }

    override suspend fun deleteAccount() {
        providerCall {
            val user = auth.currentUser ?: throw AuthProviderException(AuthFailure.UNAVAILABLE)
            user.delete().awaitTask()
        }
    }

    override suspend fun backendIdToken(): AuthProviderIdTokenResult = providerCall {
        val user = auth.currentUser ?: return@providerCall AuthProviderIdTokenResult.InvalidSession
        val token = user.getIdToken(false).awaitTask().token
            ?.takeIf(String::isNotBlank)
            ?: return@providerCall AuthProviderIdTokenResult.InvalidSession
        AuthProviderIdTokenResult.Success(token)
    }

    private fun requireCurrentAccount(): AuthProviderAccount =
        currentAccount() ?: throw AuthProviderException(AuthFailure.UNAVAILABLE)

    private fun FirebaseUser.toProviderAccount(): AuthProviderAccount = AuthProviderAccount(
        uid = uid,
        email = email.orEmpty(),
        emailVerified = isEmailVerified
    )

    private suspend fun <T> providerCall(operation: suspend () -> T): T = try {
        operation()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: AuthProviderException) {
        throw failure
    } catch (failure: Exception) {
        throw failure.toProviderException()
    }

    private fun Exception.toProviderException(): AuthProviderException = AuthProviderException(
        failure = when (this) {
            is FirebaseNetworkException -> AuthFailure.NETWORK
            is FirebaseAuthRecentLoginRequiredException -> AuthFailure.REQUIRES_RECENT_LOGIN
            is FirebaseTooManyRequestsException -> AuthFailure.TOO_MANY_REQUESTS
            is FirebaseAuthInvalidCredentialsException,
            is FirebaseAuthInvalidUserException -> AuthFailure.INVALID_CREDENTIALS
            is FirebaseAuthUserCollisionException,
            is FirebaseAuthWeakPasswordException -> AuthFailure.ACCOUNT_CREATION_REJECTED
            else -> AuthFailure.UNAVAILABLE
        },
        cause = this
    )

    private suspend fun <T> Task<T>.awaitTask(): T = suspendCancellableCoroutine { continuation ->
        addOnCompleteListener { task ->
            if (!continuation.isActive) return@addOnCompleteListener
            if (task.isSuccessful) {
                continuation.resume(task.result)
            } else {
                continuation.resumeWithException(
                    task.exception ?: AuthProviderException(AuthFailure.UNAVAILABLE)
                )
            }
        }
    }
}
