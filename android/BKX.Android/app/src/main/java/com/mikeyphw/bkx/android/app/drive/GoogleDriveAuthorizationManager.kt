package com.mikeyphw.bkx.android.app.drive

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.Scopes
import com.google.android.gms.common.api.Scope
import com.mikeyphw.bkx.android.core.remotes.RcloneConfigManager

sealed interface GoogleDriveAuthorizationOutcome {
    data object Connected : GoogleDriveAuthorizationOutcome
    data class NeedsConsent(val pendingIntent: PendingIntent) : GoogleDriveAuthorizationOutcome
    data class Failed(val message: String) : GoogleDriveAuthorizationOutcome
}

class GoogleDriveAuthorizationManager(
    context: Context,
    private val configManager: RcloneConfigManager = RcloneConfigManager(context),
) {
    private val appContext = context.applicationContext ?: context
    private val client = Identity.getAuthorizationClient(appContext)
    private val driveScope = Scope(Scopes.DRIVE_FILE)

    fun hasManagedConfiguration(): Boolean = configManager.hasManagedGoogleDrive()

    fun authorize(callback: (GoogleDriveAuthorizationOutcome) -> Unit) {
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(driveScope))
            .build()
        client.authorize(request)
            .addOnSuccessListener { result -> callback(consumeAuthorizationResult(result)) }
            .addOnFailureListener { error ->
                callback(GoogleDriveAuthorizationOutcome.Failed(error.message ?: "Google Drive authorization failed."))
            }
    }

    fun completeAuthorization(data: Intent?, callback: (GoogleDriveAuthorizationOutcome) -> Unit) {
        if (data == null) {
            callback(GoogleDriveAuthorizationOutcome.Failed("Google Drive authorization was cancelled."))
            return
        }
        runCatching { client.getAuthorizationResultFromIntent(data) }
            .onSuccess { callback(consumeAuthorizationResult(it)) }
            .onFailure { callback(GoogleDriveAuthorizationOutcome.Failed(it.message ?: "Google Drive authorization failed.")) }
    }

    fun revoke(callback: (Result<Unit>) -> Unit) {
        val request = RevokeAccessRequest.builder().setScopes(listOf(driveScope)).build()
        client.revokeAccess(request)
            .addOnSuccessListener {
                callback(runCatching {
                    configManager.removeManagedGoogleDrive()
                    Unit
                })
            }
            .addOnFailureListener { callback(Result.failure(it)) }
    }

    private fun consumeAuthorizationResult(result: AuthorizationResult): GoogleDriveAuthorizationOutcome {
        if (result.hasResolution()) {
            val pending = result.pendingIntent
                ?: return GoogleDriveAuthorizationOutcome.Failed("Google Drive authorization resolution is unavailable.")
            return GoogleDriveAuthorizationOutcome.NeedsConsent(pending)
        }
        val granted = result.grantedScopes.orEmpty()
        if (Scopes.DRIVE_FILE !in granted) {
            return GoogleDriveAuthorizationOutcome.Failed("Google Drive file access was not granted.")
        }
        val accessToken = result.accessToken?.trim().orEmpty()
        if (accessToken.isBlank()) {
            return GoogleDriveAuthorizationOutcome.Failed("Google Drive did not return an access token.")
        }
        return runCatching {
            configManager.updateManagedGoogleDriveToken(accessToken)
            GoogleDriveAuthorizationOutcome.Connected
        }.getOrElse {
            GoogleDriveAuthorizationOutcome.Failed(it.message ?: "Could not store the Google Drive authorization securely.")
        }
    }
}
