package com.nethra.app.share

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Google sign-in for YouTube uploads, with Google Play services' Authorization API.
 *
 * Only the `youtube.upload` scope is requested: NETHRA can add videos to the
 * channel, and nothing else (it can't read, edit or delete anything).
 *
 * No client secret is in the app. Google matches the app by package name and
 * signing certificate (SHA-1) against an "Android" OAuth client in your Google
 * Cloud project — see SETUP_GUIDE.md §12.
 */
object YouTubeAuth {
    const val UPLOAD_SCOPE = "https://www.googleapis.com/auth/youtube.upload"

    sealed interface Outcome {
        data class Token(val accessToken: String) : Outcome
        /** First time (or after revoking): the screen must launch this consent screen. */
        data class NeedsConsent(val intent: PendingIntent) : Outcome
        data class Failed(val message: String) : Outcome
    }

    private fun request() = AuthorizationRequest.builder()
        .setRequestedScopes(listOf(Scope(UPLOAD_SCOPE)))
        .build()

    /** Returns a fresh access token silently when already granted; otherwise asks for consent. */
    suspend fun authorize(context: Context): Outcome = suspendCancellableCoroutine { cont ->
        Identity.getAuthorizationClient(context).authorize(request())
            .addOnSuccessListener { r ->
                val token = r.accessToken
                val outcome = when {
                    r.hasResolution() && r.pendingIntent != null -> Outcome.NeedsConsent(r.pendingIntent!!)
                    token != null -> Outcome.Token(token)
                    else -> Outcome.Failed("Google didn't return a YouTube sign-in. Try again.")
                }
                if (cont.isActive) cont.resume(outcome)
            }
            .addOnFailureListener { e -> if (cont.isActive) cont.resume(Outcome.Failed(explain(e))) }
    }

    /** Reads the result of the consent screen launched for [Outcome.NeedsConsent]. */
    fun tokenFromConsent(context: Context, data: Intent?): Outcome = if (data == null) Outcome.Failed("YouTube sign-in was cancelled.") else try {
        val r = Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data)
        r.accessToken?.let { Outcome.Token(it) } ?: Outcome.Failed("YouTube access wasn't granted.")
    } catch (e: ApiException) {
        Outcome.Failed(explain(e))
    }

    /** Drops a rejected (expired/revoked) token so the next [authorize] fetches a new one. */
    suspend fun clearToken(context: Context, token: String) = withContext(Dispatchers.IO) {
        runCatching { GoogleAuthUtil.clearToken(context, token) }
    }

    private fun explain(e: Exception): String {
        val code = (e as? ApiException)?.statusCode
        return when (code) {
            // DEVELOPER_ERROR: no matching OAuth client for this package + signing SHA-1.
            10 -> "YouTube sign-in isn't set up for this build. Create an Android OAuth client for com.nethra.app " +
                "with this build's SHA-1 in Google Cloud (SETUP_GUIDE.md §12)."
            7 -> "No internet connection — YouTube sign-in needs it."
            16, 12501 -> "YouTube sign-in was cancelled."
            else -> "YouTube sign-in failed (${code ?: e.javaClass.simpleName}). ${e.message.orEmpty()}".trim()
        }
    }
}
