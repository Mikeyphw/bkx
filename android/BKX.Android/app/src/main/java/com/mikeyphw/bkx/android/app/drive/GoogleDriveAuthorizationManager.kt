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
import com.mikeyphw.bkx.android.automation.BkxTaskerConditionPublisher

sealed interface GoogleDriveAuthorizationOutcome {
    data object Connected : GoogleDriveAuthorizationOutcome
    data class NeedsConsent(val pendingIntent: PendingIntent) : GoogleDriveAuthorizationOutcome
    data class Failed(val message: String) : GoogleDriveAuthorizationOutcome
}

class GoogleDriveAuthorizationManager(
    context: Context,
    private val configManager: RcloneConfigManager = RcloneConfigManager(context),
    private val taskerConditionPublisher: BkxTaskerConditionPublisher? = null,
) {
    private val appContext = context.applicationContext ?: context
    private val client = Identity.getAuthorizationClient(appContext)
    private val driveScope = Scope(Scopes.DRIVE_FILE)

    fun hasManagedConfiguration(): Boolean = configManager.hasManagedGoogleDrive()

    fun authorize(callback: (GoogleDriveAuthorizationOutcome) -> Unit) {
        taskerConditionPublisher?.driveConnecting()
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(driveScope))
            .build()
        client.authorize(request)
            .addOnSuccessListener { result -> deliver(consumeAuthorizationResult(result), callback) }
            .addOnFailureListener { error ->
                deliver(GoogleDriveAuthorizationOutcome.Failed(error.message ?: "Google Drive authorization failed."), callback)
            }
    }

    fun completeAuthorization(data: Intent?, callback: (GoogleDriveAuthorizationOutcome) -> Unit) {
        taskerConditionPublisher?.driveConnecting()
        if (data == null) {
            deliver(GoogleDriveAuthorizationOutcome.Failed("Google Drive authorization was cancelled."), callback)
            return
        }
        runCatching { client.getAuthorizationResultFromIntent(data) }
            .onSuccess { deliver(consumeAuthorizationResult(it), callback) }
            .onFailure { deliver(GoogleDriveAuthorizationOutcome.Failed(it.message ?: "Google Drive authorization failed."), callback) }
    }

    fun revoke(callback: (Result<Unit>) -> Unit) {
        val request = RevokeAccessRequest.builder().setScopes(listOf(driveScope)).build()
        client.revokeAccess(request)
            .addOnSuccessListener {
                callback(runCatching {
                    configManager.removeManagedGoogleDrive()
                    taskerConditionPublisher?.driveDisconnected()
                    Unit
                })
            }
            .addOnFailureListener { error ->
                taskerConditionPublisher?.driveError(error.message ?: "Google Drive revoke failed.")
                callback(Result.failure(error))
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
        return listOf("authoriz", "access token", "not granted", "cancelled", "consent").any(value::contains)
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
