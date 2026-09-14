package com.mikeyphw.bkx.android.app.drive

import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.Scopes
import com.google.android.gms.common.api.Scope
import com.mikeyphw.bkx.android.automation.BkxTaskerConditionPublisher
import com.mikeyphw.bkx.android.core.drive.GoogleDriveRemoteReadiness
import com.mikeyphw.bkx.android.core.remotes.RcloneConfigManager

class GoogleDriveAuthorizationManager(
    context: Context,
    configManager: RcloneConfigManager = RcloneConfigManager(context),
    private val taskerConditionPublisher: BkxTaskerConditionPublisher? = null,
) {
    private val appContext = context.applicationContext ?: context
    private val coordinator = GoogleDriveAuthorizationCoordinator(
        client = GoogleIdentityAuthorizationClient(appContext),
        credentialStore = RcloneGoogleDriveCredentialStore(configManager),
    )

    fun managedRemoteReadiness(): GoogleDriveRemoteReadiness = coordinator.readiness()

    fun hasManagedConfiguration(): Boolean = managedRemoteReadiness() == GoogleDriveRemoteReadiness.Ready

    fun authorize(callback: (GoogleDriveAuthorizationOutcome) -> Unit) {
        taskerConditionPublisher?.driveConnecting()
        coordinator.authorize { outcome -> deliver(outcome, callback) }
    }

    fun completeAuthorization(data: Intent?, callback: (GoogleDriveAuthorizationOutcome) -> Unit) {
        taskerConditionPublisher?.driveConnecting()
        coordinator.completeAuthorization(data) { outcome -> deliver(outcome, callback) }
    }

    fun revoke(callback: (Result<Unit>) -> Unit) {
        coordinator.revoke { result ->
            result.onSuccess {
                taskerConditionPublisher?.driveDisconnected()
            }.onFailure { error ->
                taskerConditionPublisher?.driveError(error.message ?: "Google Drive revoke failed after local disconnect.")
            }
            callback(result)
        }
    }

    private fun deliver(
        outcome: GoogleDriveAuthorizationOutcome,
        callback: (GoogleDriveAuthorizationOutcome) -> Unit,
    ) {
        when (outcome) {
            GoogleDriveAuthorizationOutcome.Connected -> taskerConditionPublisher?.driveConnected()
            is GoogleDriveAuthorizationOutcome.NeedsConsent -> taskerConditionPublisher?.driveNeedsAuthorization()
            is GoogleDriveAuthorizationOutcome.Failed -> {
                if (isAuthorizationFailure(outcome.message)) taskerConditionPublisher?.driveNeedsAuthorization(outcome.message)
                else taskerConditionPublisher?.driveError(outcome.message)
            }
        }
        callback(outcome)
    }

    private fun isAuthorizationFailure(message: String): Boolean {
        val value = message.lowercase()
        return listOf("authoriz", "access token", "not granted", "cancelled", "consent", "disconnect").any(value::contains)
    }
}

private class RcloneGoogleDriveCredentialStore(
    private val configManager: RcloneConfigManager,
) : ManagedGoogleDriveCredentialStore {
    override fun readiness(): GoogleDriveRemoteReadiness = configManager.managedGoogleDriveReadiness()

    override fun updateAccessToken(accessToken: String) {
        configManager.updateManagedGoogleDriveToken(accessToken)
    }

    override fun disconnectLocal() {
        configManager.removeManagedGoogleDrive()
    }
}

private class GoogleIdentityAuthorizationClient(context: Context) : GoogleDriveAuthorizationClient {
    private val client = Identity.getAuthorizationClient(context.applicationContext ?: context)
    private val driveScope = Scope(Scopes.DRIVE_FILE)

    override fun authorize(callback: (GoogleDriveAuthorizationClientOutcome) -> Unit) {
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(driveScope))
            .build()
        client.authorize(request)
            .addOnSuccessListener { result -> callback(result.toClientOutcome()) }
            .addOnFailureListener { error ->
                callback(GoogleDriveAuthorizationClientOutcome.Failed(error.message ?: "Google Drive authorization failed."))
            }
    }

    override fun completeAuthorization(data: Intent?, callback: (GoogleDriveAuthorizationClientOutcome) -> Unit) {
        if (data == null) {
            callback(GoogleDriveAuthorizationClientOutcome.Failed("Google Drive authorization was cancelled."))
            return
        }
        runCatching { client.getAuthorizationResultFromIntent(data) }
            .onSuccess { callback(it.toClientOutcome()) }
            .onFailure {
                callback(GoogleDriveAuthorizationClientOutcome.Failed(it.message ?: "Google Drive authorization failed."))
            }
    }

    override fun revoke(callback: (Result<Unit>) -> Unit) {
        val request = RevokeAccessRequest.builder().setScopes(listOf(driveScope)).build()
        client.revokeAccess(request)
            .addOnSuccessListener { callback(Result.success(Unit)) }
            .addOnFailureListener { error -> callback(Result.failure(error)) }
    }

    private fun AuthorizationResult.toClientOutcome(): GoogleDriveAuthorizationClientOutcome {
        if (hasResolution()) {
            val pending = pendingIntent
                ?: return GoogleDriveAuthorizationClientOutcome.Failed("Google Drive authorization resolution is unavailable.")
            return GoogleDriveAuthorizationClientOutcome.NeedsConsent(pending)
        }
        val granted = grantedScopes.orEmpty()
        if (Scopes.DRIVE_FILE !in granted) {
            return GoogleDriveAuthorizationClientOutcome.Failed("Google Drive file access was not granted.")
        }
        val token = accessToken?.trim().orEmpty()
        if (token.isBlank()) {
            return GoogleDriveAuthorizationClientOutcome.Failed("Google Drive did not return an access token.")
        }
        return GoogleDriveAuthorizationClientOutcome.Granted(token)
    }
}
