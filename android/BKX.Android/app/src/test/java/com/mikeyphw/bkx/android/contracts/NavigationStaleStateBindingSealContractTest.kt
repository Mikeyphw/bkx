package com.mikeyphw.bkx.android.contracts

import com.mikeyphw.bkx.android.app.BkxHomeEvent
import com.mikeyphw.bkx.android.app.BkxHomeUiState
import com.mikeyphw.bkx.android.app.navigation.ArchiveDetailSection
import com.mikeyphw.bkx.android.app.navigation.BackupRoute
import com.mikeyphw.bkx.android.app.navigation.BkxBoundEntityKind
import com.mikeyphw.bkx.android.app.navigation.BkxDeepLinkCodec
import com.mikeyphw.bkx.android.app.navigation.BkxDestination
import com.mikeyphw.bkx.android.app.navigation.BkxResponseBinding
import com.mikeyphw.bkx.android.app.navigation.BkxRouteKey
import com.mikeyphw.bkx.android.app.navigation.MoreRoute
import com.mikeyphw.bkx.android.core.archives.ArchiveLocation
import com.mikeyphw.bkx.android.core.bridge.BridgeResultEnvelope
import com.mikeyphw.bkx.android.core.bridge.BridgeResultStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationStaleStateBindingSealContractTest {
    @Test
    fun routeKeysAreStructuredAndDeepLinksResolveNestedRoutes() {
        val state = BkxHomeUiState(
            selectedDestination = BkxDestination.Backup,
            backupRoute = BackupRoute.Snapshots,
            moreRoute = MoreRoute.Transfer,
            archiveDetailSection = ArchiveDetailSection.Restore,
        )

        assertEquals("Backup/Snapshots", state.currentRouteKey.stableKey)
        assertEquals(BkxDestination.More, BkxDeepLinkCodec.parse("bkx://more/transfer")?.destination)
        assertEquals(MoreRoute.GoogleDrive, BkxDeepLinkCodec.parse("bkx://drive")?.moreRoute)
        assertEquals(ArchiveDetailSection.Contents, BkxDeepLinkCodec.parse("bkx://archives/contents/archive-1")?.archiveSection)
    }

    @Test
    fun responseBindingsRejectWrongRequestWrongRouteAndWrongEntity() {
        val binding = BkxResponseBinding(
            requestId = "archive-verify-1",
            routeKey = BkxRouteKey(BkxDestination.Archives, archiveSection = ArchiveDetailSection.Overview),
            routeGeneration = 7,
            entityKind = BkxBoundEntityKind.Archive,
            entityId = "archive-a",
        )

        assertTrue(
            binding.accepts(
                resultRequestId = "archive-verify-1",
                currentRouteKey = BkxRouteKey(BkxDestination.Archives, archiveSection = ArchiveDetailSection.Overview),
                currentRouteGeneration = 7,
                currentEntityId = "archive-a",
            ),
        )
        assertFalse(
            binding.accepts(
                resultRequestId = "archive-verify-2",
                currentRouteKey = BkxRouteKey(BkxDestination.Archives, archiveSection = ArchiveDetailSection.Overview),
                currentRouteGeneration = 7,
                currentEntityId = "archive-a",
            ),
        )
        assertFalse(
            binding.accepts(
                resultRequestId = "archive-verify-1",
                currentRouteKey = BkxRouteKey(BkxDestination.Backup, backupRoute = BackupRoute.History),
                currentRouteGeneration = 7,
                currentEntityId = "archive-a",
            ),
        )
        assertFalse(
            binding.accepts(
                resultRequestId = "archive-verify-1",
                currentRouteKey = BkxRouteKey(BkxDestination.Archives, archiveSection = ArchiveDetailSection.Overview),
                currentRouteGeneration = 8,
                currentEntityId = "archive-a",
            ),
        )
        assertFalse(
            binding.accepts(
                resultRequestId = "archive-verify-1",
                currentRouteKey = BkxRouteKey(BkxDestination.Archives, archiveSection = ArchiveDetailSection.Overview),
                currentRouteGeneration = 7,
                currentEntityId = "archive-b",
            ),
        )
    }

    @Test
    fun staleArchiveVerificationEventCannotOverwriteTheCurrentSelection() {
        val archiveA = ArchiveLocation("archive-a", "A.bakery", "/shared/A.bakery")
        val archiveB = ArchiveLocation("archive-b", "B.bakery", "/shared/B.bakery")
        val state = BkxHomeUiState(
            selectedDestination = BkxDestination.Archives,
            archiveDetailSection = ArchiveDetailSection.Overview,
            routeGeneration = 5,
            archiveReadOnly = com.mikeyphw.bkx.android.core.archives.ArchiveReadOnlyUiState(selectedArchive = archiveB),
        )
        val binding = BkxResponseBinding(
            requestId = "verify-a",
            routeKey = state.currentRouteKey,
            routeGeneration = 5,
            entityKind = BkxBoundEntityKind.Archive,
            entityId = archiveA.archiveId,
        )
        val event = BkxHomeEvent.ApplyArchiveVerification(
            result = BridgeResultEnvelope(
                protocolVersion = 1,
                requestId = "verify-a",
                status = BridgeResultStatus.Success,
                summary = "A passed",
            ),
            binding = binding,
        )

        assertEquals("archive-b", state.archiveReadOnly.selectedArchive?.archiveId)
        assertEquals("verify-a", event.result.requestId)
        assertFalse(binding.accepts(event.result.requestId, state.currentRouteKey, state.routeGeneration, state.archiveReadOnly.selectedArchive?.archiveId))
    }
}
