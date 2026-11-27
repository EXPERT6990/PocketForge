package com.theexpert9.pocketforge.data.auth

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential.Companion.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.auth
import com.theexpert9.pocketforge.R
import com.theexpert9.pocketforge.ensureFirebaseInitialized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

data class GoogleProfileState(
    val isSignedIn: Boolean = false,
    val uid: String? = null,
    val displayName: String? = null,
    val email: String? = null,
    val photoUrl: String? = null,
    val isAuthenticating: Boolean = false,
    val authError: String? = null
) {
    companion object {
        fun fromFirebaseUser(user: FirebaseUser?): GoogleProfileState {
            if (user == null) return GoogleProfileState()
            return GoogleProfileState(
                isSignedIn = true,
                uid = user.uid,
                displayName = user.displayName?.takeIf { it.isNotBlank() } ?: user.email?.substringBefore('@') ?: "Google User",
                email = user.email,
                photoUrl = user.photoUrl?.toString()
            )
        }
    }
}

sealed interface GoogleSignInOutcome {
    data class Success(val user: FirebaseUser?) : GoogleSignInOutcome
    data object Cancelled : GoogleSignInOutcome
    data class Error(val message: String) : GoogleSignInOutcome
}

/**
 * Native in-app Google Sign-In using Jetpack Credential Manager and Firebase Auth.
 * Strictly enforces one credential option per GetCredentialRequest for Android 16 (API 36) safety.
 */
object GoogleAuthManager {

    private const val TAG = "Auth"

    fun currentUser(context: Context): FirebaseUser? {
        ensureFirebaseInitialized(context) ?: return null
        return runCatching { Firebase.auth.currentUser }.getOrNull()
    }

    /**
     * Silent startup auto sign-in for previously authorized accounts only (`GetGoogleIdOption`).
     */
    suspend fun attemptAutoSignIn(context: Context): FirebaseUser? {
        ensureFirebaseInitialized(context) ?: return null
        val existing = runCatching { Firebase.auth.currentUser }.getOrNull()
        if (existing != null) return existing

        val clientId = try {
            context.getString(R.string.default_web_client_id)
        } catch (_: Exception) {
            return null
        }
        if (clientId.isBlank()) return null

        val activity = findActivity(context) ?: return null
        val credentialManager = CredentialManager.create(context)
        val googleIdOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(true)
            .setServerClientId(clientId)
            .setAutoSelectEnabled(true)
            .build()
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()

        return try {
            val cred = credentialManager.getCredential(activity, request).credential
            if (cred is CustomCredential && cred.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                val idToken = GoogleIdTokenCredential.createFrom(cred.data).idToken
                val authResult = Firebase.auth
                    .signInWithCredential(GoogleAuthProvider.getCredential(idToken, null))
                    .await()
                authResult.user
            } else {
                null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: NoCredentialException) {
            null
        } catch (e: Exception) {
            Log.d(TAG, "Silent Google auto-sign-in skipped: ${e.message}")
            null
        }
    }

    /**
     * Interactive in-app Google Sign-In bottom sheet (`GetSignInWithGoogleOption`).
     */
    suspend fun signInWithGoogle(context: Context): GoogleSignInOutcome {
        val firebaseApp = ensureFirebaseInitialized(context)
            ?: return GoogleSignInOutcome.Error("Firebase is not initialized in this build.")

        val clientId = try {
            context.getString(R.string.default_web_client_id)
        } catch (_: Exception) {
            return GoogleSignInOutcome.Error(
                "Google Sign-In configuration missing: default_web_client_id not found."
            )
        }
        if (clientId.isBlank()) {
            return GoogleSignInOutcome.Error("Google Sign-In Web Client ID is empty.")
        }

        val activity = findActivity(context)
            ?: return GoogleSignInOutcome.Error("Cannot launch Google Sign-In outside an Activity context.")

        val credentialManager = CredentialManager.create(context)
        val signInOption = GetSignInWithGoogleOption.Builder(serverClientId = clientId).build()
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(signInOption)
            .build()

        return try {
            val result = credentialManager.getCredential(activity, request)
            val credential = result.credential
            if (credential is CustomCredential && credential.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                val idToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
                val authResult = Firebase.auth(firebaseApp)
                    .signInWithCredential(GoogleAuthProvider.getCredential(idToken, null))
                    .await()
                GoogleSignInOutcome.Success(authResult.user)
            } else {
                GoogleSignInOutcome.Error("Unexpected credential type returned by Credential Manager.")
            }
        } catch (e: GetCredentialCancellationException) {
            Log.w(TAG, "Google Sign-In flow cancelled or dismissed: ${e.message}", e)
            GoogleSignInOutcome.Cancelled
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Google Sign-In failed", e)
            GoogleSignInOutcome.Error(e.localizedMessage ?: "Google Sign-In failed.")
        }
    }

    /**
     * Clears Credential Manager state and signs out of Firebase Auth inside NonCancellable.
     */
    suspend fun signOut(context: Context) {
        withContext(NonCancellable) {
            val credentialManager = CredentialManager.create(context)
            try {
                credentialManager.clearCredentialState(ClearCredentialStateRequest())
            } catch (e: Exception) {
                Log.w(TAG, "Failed to clear credential state: ${e.message}")
            } finally {
                runCatching {
                    val app = ensureFirebaseInitialized(context)
                    if (app != null) {
                        Firebase.auth(app).signOut()
                    }
                }
            }
        }
    }

    private fun findActivity(context: Context): Activity? {
        var current: Context? = context
        while (current is ContextWrapper) {
            if (current is Activity) return current
            current = current.baseContext
        }
        return current as? Activity
    }
}
