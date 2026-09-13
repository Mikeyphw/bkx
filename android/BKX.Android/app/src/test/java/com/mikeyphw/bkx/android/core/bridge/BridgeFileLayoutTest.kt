package com.mikeyphw.bkx.android.core.bridge

import org.junit.Assert.assertEquals
import org.junit.Test

class BridgeFileLayoutTest {
    @Test
    fun derivesStructuredRequestPathsFromRequestId() {
        val files = BridgeFileLayout.forRequest(
            baseDirectoryPath = "/termux/home/.bkx-android",
            requestId = "req-123",
        )

        assertEquals("req-123", files.requestId)
        assertEquals("/termux/home/.bkx-android/req-123/request.json", files.requestFilePath)
        assertEquals("/termux/home/.bkx-android/req-123/result.json", files.resultFilePath)
        assertEquals("/termux/home/.bkx-android/req-123/progress.jsonl", files.progressFilePath)
        assertEquals("/termux/home/.bkx-android/req-123/cancel.json", files.cancelFilePath)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsRequestIdThatCannotBeUsedAsPathSegment() {
        BridgeFileLayout.forRequest(
            baseDirectoryPath = "/termux/home/.bkx-android",
            requestId = "../escape",
        )
    }


    @Test(expected = IllegalArgumentException::class)
    fun rejectsParentDirectoryRequestIdEvenWithoutSlash() {
        BridgeFileLayout.forRequest(
            baseDirectoryPath = "/termux/home/.bkx-android",
            requestId = "..",
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsCurrentDirectoryRequestIdEvenWithoutSlash() {
        BridgeFileLayout.forRequest(
            baseDirectoryPath = "/termux/home/.bkx-android",
            requestId = ".",
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOverlongRequestId() {
        BridgeFileLayout.forRequest(
            baseDirectoryPath = "/termux/home/.bkx-android",
            requestId = "a".repeat(BridgeRequestIdPolicy.MAX_LENGTH + 1),
        )
    }

}
