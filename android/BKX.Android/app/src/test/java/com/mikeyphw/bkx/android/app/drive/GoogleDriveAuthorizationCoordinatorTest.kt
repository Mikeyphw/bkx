package com.mikeyphw.bkx.android.app.drive

import android.content.Intent
import com.mikeyphw.bkx.android.core.drive.GoogleDriveRemoteReadiness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleDriveAuthorizationCoordinatorTest {
    @Test
    fun concurrentUiAndAutomationRefreshCoalesceOntoOneAuthorizationRequest() {
        val client = FakeAuthorizationClient()
        val store = FakeCredentialStore()
        val coordinator = GoogleDriveAuthorizationCoordinator(client, store)
        val outcomes = mutableListOf<GoogleDriveAuthorizationOutcome>()

        coordinator.authorize(outcomes::add)
        coordinator.authorize(outcomes::add)

        assertEquals(1, client.authorizeCalls)
        client.completeAuthorize(GoogleDriveAuthorizationClientOutcome.Granted("token-1"))

        assertEquals(2, outcomes.size)
        assertTrue(outcomes.all { it == GoogleDriveAuthorizationOutcome.Connected })
        assertEquals("token-1", store.token)
        assertEquals(1, store.tokenWrites)
    }

    @Test
    fun consentCompletionSupersedesOlderAuthorizeGenerationAndRejectsItsLateCallback() {
        val client = FakeAuthorizationClient()
        val store = FakeCredentialStore()
        val coordinator = GoogleDriveAuthorizationCoordinator(client, store)
        val oldOutcomes = mutableListOf<GoogleDriveAuthorizationOutcome>()
        val consentOutcomes = mutableListOf<GoogleDriveAuthorizationOutcome>()

        coordinator.authorize(oldOutcomes::add)
        coordinator.completeAuthorization(data = null, callback = consentOutcomes::add)

        assertEquals(1, oldOutcomes.size)
        assertTrue(oldOutcomes.single() is GoogleDriveAuthorizationOutcome.Failed)
        client.completeAuthorize(GoogleDriveAuthorizationClientOutcome.Granted("stale-token"))
        assertNull(store.token)

        client.completeConsent(GoogleDriveAuthorizationClientOutcome.Granted("fresh-token"))
        assertEquals(listOf(GoogleDriveAuthorizationOutcome.Connected), consentOutcomes)
        assertEquals("fresh-token", store.token)
        assertEquals(1, store.tokenWrites)
    }

    @Test
    fun disconnectClearsLocalCredentialBeforeRemoteRevokeCompletesAndRejectsLateAuthorizeCallback() {
        val client = FakeAuthorizationClient()
        val store = FakeCredentialStore(token = "old-token")
        val coordinator = GoogleDriveAuthorizationCoordinator(client, store)
        val authorizationOutcomes = mutableListOf<GoogleDriveAuthorizationOutcome>()
        var revokeResult: Result<Unit>? = null

        coordinator.authorize(authorizationOutcomes::add)
        coordinator.revoke { revokeResult = it }

        assertTrue(store.disconnected)
        assertNull(store.token)
        assertNull("remote revoke has not completed yet", revokeResult)
        assertEquals(1, authorizationOutcomes.size)
        assertTrue(authorizationOutcomes.single() is GoogleDriveAuthorizationOutcome.Failed)

        client.completeAuthorize(GoogleDriveAuthorizationClientOutcome.Granted("late-token"))
        assertNull("late authorize callback must not resurrect credentials", store.token)
        assertEquals(0, store.tokenWrites)

        client.completeRevoke(Result.failure(IllegalStateException("offline")))
        assertTrue(revokeResult?.isFailure == true)
        assertNull(store.token)
    }

    @Test
    fun failedAuthorizationDoesNotMutateExistingCredential() {
        val client = FakeAuthorizationClient()
        val store = FakeCredentialStore(token = "still-valid")
        val coordinator = GoogleDriveAuthorizationCoordinator(client, store)
        var outcome: GoogleDriveAuthorizationOutcome? = null

        coordinator.authorize { outcome = it }
        client.completeAuthorize(GoogleDriveAuthorizationClientOutcome.Failed("typed authorization failure"))

        assertTrue(outcome is GoogleDriveAuthorizationOutcome.Failed)
        assertEquals("still-valid", store.token)
        assertEquals(0, store.tokenWrites)
    }

    private class FakeAuthorizationClient : GoogleDriveAuthorizationClient {
        var authorizeCalls = 0
        private var authorizeCallback: ((GoogleDriveAuthorizationClientOutcome) -> Unit)? = null
        private var consentCallback: ((GoogleDriveAuthorizationClientOutcome) -> Unit)? = null
        private var revokeCallback: ((Result<Unit>) -> Unit)? = null

        override fun authorize(callback: (GoogleDriveAuthorizationClientOutcome) -> Unit) {
            authorizeCalls += 1
            authorizeCallback = callback
        }

        override fun completeAuthorization(data: Intent?, callback: (GoogleDriveAuthorizationClientOutcome) -> Unit) {
            consentCallback = callback
        }

        override fun revoke(callback: (Result<Unit>) -> Unit) {
            revokeCallback = callback
        }

        fun completeAuthorize(outcome: GoogleDriveAuthorizationClientOutcome) {
            authorizeCallback?.invoke(outcome)
        }

        fun completeConsent(outcome: GoogleDriveAuthorizationClientOutcome) {
            consentCallback?.invoke(outcome)
        }

        fun completeRevoke(result: Result<Unit>) {
            revokeCallback?.invoke(result)
        }
    }

    private class FakeCredentialStore(
        var token: String? = null,
    ) : ManagedGoogleDriveCredentialStore {
        var tokenWrites = 0
        var disconnected = false

        override fun readiness(): GoogleDriveRemoteReadiness = when {
            token.isNullOrBlank() -> GoogleDriveRemoteReadiness.MissingAccessToken
            else -> GoogleDriveRemoteReadiness.Ready
        }

        override fun updateAccessToken(accessToken: String) {
            token = accessToken
            tokenWrites += 1
        }

        override fun disconnectLocal() {
            disconnected = true
            token = null
        }
    }
}
