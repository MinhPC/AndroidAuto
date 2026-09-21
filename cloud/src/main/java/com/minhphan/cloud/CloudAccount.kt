package com.minhphan.cloud

import android.app.Activity
import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.tasks.await

sealed interface AccountState {
    /** This build has no Firebase project (no google-services.json), so there is nothing to sign in to. */
    data object NotConfigured : AccountState

    data object SignedOut : AccountState

    data class SignedIn(val uid: String, val email: String?) : AccountState
}

/** Why a sign-in did not happen; [detail] is for the person to read. */
class SignInException(val detail: String, val cancelled: Boolean = false) : Exception(detail)

/**
 * The Google account whose Firebase user owns the trips. Firebase keeps the session and renews it by itself, so
 * the car signs in once. The phone app signs in to the same account and so reads what the car wrote.
 */
class CloudAccount(private val context: Context) {
    private val auth: FirebaseAuth? =
        if (FirebaseApp.getApps(context).isEmpty()) null else FirebaseAuth.getInstance()

    private val _state = MutableStateFlow(read())
    val state: StateFlow<AccountState> = _state

    init {
        auth?.addAuthStateListener { _state.value = read() }
    }

    private fun read(): AccountState {
        val auth = auth ?: return AccountState.NotConfigured
        val user = auth.currentUser ?: return AccountState.SignedOut
        return AccountState.SignedIn(user.uid, user.email)
    }

    /** The signed-in user's id, or null when nobody is. */
    val uid: String? get() = (state.value as? AccountState.SignedIn)?.uid

    suspend fun signInWithGoogle(activity: Activity): Result<Unit> {
        val auth = auth ?: return Result.failure(SignInException("Firebase is not set up in this build"))
        val webClientId = webClientId()
            ?: return Result.failure(SignInException("google-services.json has no web client: turn on Google sign-in in Firebase"))
        return try {
            val request = GetCredentialRequest.Builder()
                .addCredentialOption(GetSignInWithGoogleOption.Builder(webClientId).build())
                .build()
            val credential = CredentialManager.create(activity).getCredential(activity, request).credential
            if (credential !is CustomCredential || credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                return Result.failure(SignInException("Unexpected credential type"))
            }
            val idToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
            auth.signInWithCredential(GoogleAuthProvider.getCredential(idToken, null)).await()
            _state.value = read()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: GetCredentialCancellationException) {
            Result.failure(SignInException("Cancelled", cancelled = true))
        } catch (e: GetCredentialException) {
            Result.failure(SignInException(e.message ?: e.type))
        } catch (e: Exception) {
            Result.failure(SignInException(e.message ?: e.javaClass.simpleName))
        }
    }

    fun signOut() {
        auth?.signOut()
        _state.value = read()
    }

    /** google-services.json becomes this string resource when the project has Google sign-in turned on. */
    private fun webClientId(): String? {
        val id = context.resources.getIdentifier("default_web_client_id", "string", context.packageName)
        return if (id == 0) null else context.getString(id)
    }
}
