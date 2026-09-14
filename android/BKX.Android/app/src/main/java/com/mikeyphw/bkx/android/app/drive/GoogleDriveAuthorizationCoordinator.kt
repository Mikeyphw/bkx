package com.mikeyphw.bkx.android.app.drive

import android.app.PendingIntent
import android.content.Intent
import com.mikeyphw.bkx.android.core.drive.GoogleDriveRemoteReadiness

sealed interface GoogleDriveAuthorizationOutcome {
    data object Connected : GoogleDriveAuthorizationOutcome
    data class NeedsConsent(val pendingIntent: PendingIntent) : GoogleDriveAuthorizationOutcome
    data class Failed(val message: String) : GoogleDriveAuthorizationOutcome
}

internal sealed interface GoogleDriveAuthorizationClientOutcome {
    data class Granted(val accessToken: String) : GoogleDriveAuthorizationClientOutcome
    data class NeedsConsent(val pendingIntent: PendingIntent) : GoogleDriveAuthorizationClientOutcome
    data class Failed(val message: String) : GoogleDriveAuthorizationClientOutcome
}

internal interface GoogleDriveAuthorizationClient {
    fun authorize(callback: (GoogleDriveAuthorizationClientOutcome) -> Unit)
    fun completeAuthorization(data: Intent?, callback: (GoogleDriveAuthorizationClientOutcome) -> Unit)
    fun revoke(callback: (Result<Unit>) -> Unit)
}

internal interface ManagedGoogleDriveCredentialStore {
    fun readiness(): GoogleDriveRemoteReadiness
    fun updateAccessToken(accessToken: String)
    fun disconnectLocal()
}

/**
 * Deterministic authorization lifecycle shared by UI and automation callers.
 *
 * Concurrent refreshes coalesce onto one client request. Disconnect advances a
 * generation before clearing the local managed remote, so a late authorization
 * callback cannot resurrect credentials after the user disconnected.
 */
internal class GoogleDriveAuthorizationCoordinator(
    private val client: GoogleDriveAuthorizationClient,
    private val credentialStore: ManagedGoogleDriveCredentialStore,
) {
    private val lock = Any()
    private var generation = 0L
    private var activeAuthorizationGeneration: Long? = null
    private var waiters = mutableListOf<(GoogleDriveAuthorizationOutcome) -> Unit>()

    fun readiness(): GoogleDriveRemoteReadiness = credentialStore.readiness()

    fun authorize(callback: (GoogleDriveAuthorizationOutcome) -> Unit) {
        val generationToLaunch: Long
        synchronized(lock) {
            val active = activeAuthorizationGeneration
            if (active != null) {
                waiters += callback
                return
            }
            generation += 1
            generationToLaunch = generation
            activeAuthorizationGeneration = generationToLaunch
            waiters += callback
        }
        client.authorize { outcome -> finishAuthorization(generationToLaunch, outcome) }
    }

    fun completeAuthorization(data: Intent?, callback: (GoogleDriveAuthorizationOutcome) -> Unit) {
        val (generationToLaunch, staleCallbacks) = synchronized(lock) {
            generation += 1
            val nextGeneration = generation
            val stale = waiters.toList()
            waiters.clear()
            activeAuthorizationGeneration = nextGeneration
            waiters += callback
            nextGeneration to stale
        }
        staleCallbacks.forEach {
            it(GoogleDriveAuthorizationOutcome.Failed("Google Drive authorization was superseded by a newer consent result."))
        }
        client.completeAuthorization(data) { outcome -> finishAuthorization(generationToLaunch, outcome) }
    }

    fun revoke(callback: (Result<Unit>) -> Unit) {
        val (staleCallbacks, localDisconnect) = synchronized(lock) {
            generation += 1
            activeAuthorizationGeneration = null
            val stale = waiters.toList()
            waiters.clear()
            stale to runCatching { credentialStore.disconnectLocal() }
        }
        staleCallbacks.forEach {
            it(GoogleDriveAuthorizationOutcome.Failed("Google Drive authorization result was ignored because the connection was disconnected."))
        }
        client.revoke { remoteResult ->
            callback(
                when {
                    localDisconnect.isFailure -> Result.failure(localDisconnect.exceptionOrNull()!!)
                    remoteResult.isFailure -> Result.failure(remoteResult.exceptionOrNull()!!)
                    else -> Result.success(Unit)
                },
            )
        }
    }

    private fun finishAuthorization(
        callbackGeneration: Long,
        clientOutcome: GoogleDriveAuthorizationClientOutcome,
    ) {
        val delivery = synchronized(lock) {
            if (callbackGeneration != generation || activeAuthorizationGeneration != callbackGeneration) {
                null
            } else {
                val outcome = consume(clientOutcome)
                val callbacks = waiters.toList()
                waiters.clear()
                activeAuthorizationGeneration = null
                callbacks to outcome
            }
        } ?: return
        delivery.first.forEach { it(delivery.second) }
    }

    private fun consume(clientOutcome: GoogleDriveAuthorizationClientOutcome): GoogleDriveAuthorizationOutcome = when (clientOutcome) {
        is GoogleDriveAuthorizationClientOutcome.Granted -> runCatching {
            val token = clientOutcome.accessToken.trim()
            require(token.isNotBlank()) { "Google Drive did not return an access token." }
            credentialStore.updateAccessToken(token)
            GoogleDriveAuthorizationOutcome.Connected
        }.getOrElse {
            GoogleDriveAuthorizationOutcome.Failed(it.message ?: "Could not store the Google Drive authorization securely.")
        }
        is GoogleDriveAuthorizationClientOutcome.NeedsConsent -> GoogleDriveAuthorizationOutcome.NeedsConsent(clientOutcome.pendingIntent)
        is GoogleDriveAuthorizationClientOutcome.Failed -> GoogleDriveAuthorizationOutcome.Failed(clientOutcome.message)
    }
}
