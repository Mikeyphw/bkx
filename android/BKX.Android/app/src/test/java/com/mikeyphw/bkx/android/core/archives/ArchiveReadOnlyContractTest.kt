package com.mikeyphw.bkx.android.core.archives

import com.mikeyphw.bkx.android.core.bridge.BridgeError
import com.mikeyphw.bkx.android.core.bridge.BridgeResultEnvelope
import com.mikeyphw.bkx.android.core.bridge.BridgeResultStatus
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveReadOnlyContractTest {
    private val archive = ArchiveLocation(
        archiveId = "archive-1",
        displayName = "backup.bkx",
        stagedPath = "/shared/bkx/staging/archive-1/backup.bkx",
    )

    @Test
    fun archiveScreensBuildOnlyReadOnlyListInfoBrowseAndVerifyRequests() {
        val builder = ArchiveRequestBuilder()
        val list = builder.buildListRequest("req-list")
        val info = builder.buildInfoRequest("req-info", archive)
        val browse = builder.buildBrowseRequest(
            requestId = "req-browse",
            page = ArchiveBrowsePageRequest(archive = archive, pageToken = "page-2", pageSize = 25),
        )
        val verify = builder.buildVerifyRequest("req-verify", archive)

        assertEquals("archive.list", list.operation)
        assertEquals("archive.info", info.operation)
        assertEquals("archive.browse", browse.operation)
        assertEquals("archive.verify", verify.operation)
        listOf(list, info, browse, verify).forEach { request ->
            assertTrue(request.payload["read_only"]?.jsonPrimitive?.boolean == true)
        }
        assertEquals(25, browse.payload["page"]?.jsonObject?.get("page_size")?.jsonPrimitive?.int)
    }


    @Test
    fun archiveUiStateRepresentsListInfoBrowseAndVerifyWorkflows() {
        val item = ArchiveListItem(archive = archive, sizeBytes = 4096, verification = ArchiveVerificationUiState.NotStarted)
        val info = ArchiveInfoSummary(
            archive = archive,
            entryCount = 42,
            compressedSizeBytes = 4096,
            uncompressedSizeBytes = 8192,
            formatVersion = "bkx-v1",
            manifestHash = "sha256:abc",
            warnings = listOf("Archive was created on another device."),
        )
        val state = ArchiveReadOnlyUiState(
            selectedArchive = archive,
            listState = ArchiveWorkflowUiState.Content(listOf(item)),
            infoState = ArchiveWorkflowUiState.Content(info),
            browsePage = ArchiveBrowsePage(
                request = ArchiveBrowsePageRequest(archive = archive),
                entries = listOf(ArchiveEntrySummary("manifest.json", ArchiveEntryType.File)),
            ),
            verification = ArchiveVerificationUiState.NotStarted,
        )

        assertEquals(ArchiveReadOnlyOperation.entries.toSet(), state.availableWorkflows)
        assertEquals("backup.bkx", (state.listState as ArchiveWorkflowUiState.Content).value.single().archive.displayName)
        assertEquals(42, (state.infoState as ArchiveWorkflowUiState.Content).value.entryCount)
        assertEquals(false, state.hasMoreBrowsePages)
    }

    @Test
    fun browsePageRepresentsPagedAndLargeArchiveState() {
        val page = ArchiveBrowsePage(
            request = ArchiveBrowsePageRequest(archive = archive, pageSize = 2),
            entries = listOf(
                ArchiveEntrySummary("one.txt", ArchiveEntryType.File, sizeBytes = 1),
                ArchiveEntrySummary("nested", ArchiveEntryType.Directory),
            ),
            nextPageToken = "next-page",
            totalEntriesEstimate = 50,
        )
        val state = ArchiveReadOnlyUiState(selectedArchive = archive, browsePage = page)

        assertTrue(page.isLargeArchive)
        assertTrue(state.hasMoreBrowsePages)
        assertTrue(state.isLargeArchive)
    }

    @Test
    fun verificationFailureIsRenderedAsFailureNotWarningOnlySuccess() {
        val result = BridgeResultEnvelope(
            protocolVersion = 1,
            requestId = "req-verify",
            status = BridgeResultStatus.Failure,
            summary = "hash mismatch",
            errors = listOf(BridgeError("archive_corrupt", "Archive member hash mismatch.")),
        )

        val state = result.toArchiveVerificationUiState()

        assertTrue(state is ArchiveVerificationUiState.Failed)
        assertEquals("archive_corrupt", (state as ArchiveVerificationUiState.Failed).errors.single().code)
    }

    @Test(expected = IllegalArgumentException::class)
    fun mutatingArchiveOperationsAreRejectedFromReadOnlyScreens() {
        ArchiveRequestBuilder().requireReadOnlyOperation("archive.delete")
    }

    @Test(expected = IllegalArgumentException::class)
    fun archiveEntryPathsMustStayArchiveRelative() {
        ArchiveEntrySummary("/absolute/escape", ArchiveEntryType.File)
    }

    @Test(expected = IllegalArgumentException::class)
    fun contentUrisCannotBeUsedAsArchiveStagedPaths() {
        ArchiveLocation("archive-2", "bad", "content://provider/archive")
    }
}
