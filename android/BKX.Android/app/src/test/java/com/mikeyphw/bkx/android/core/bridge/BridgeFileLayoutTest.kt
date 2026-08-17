package com.mikeyphw.bkx.android.core.bridge

import org.junit.Assert.assertEquals
import org.junit.Test

class BridgeFileLayoutTest {
    @Test
    fun derivesStructuredRequestPathsFromRequestId() {
        val files = BridgeFileLayout.forRequest(
            baseDirectoryPath = "/data/data/com.termux/files/home/.bkx-android",
            requestId = "req-123",
        )

        assertEquals("req-123", files.requestId)
        assertEquals("/data/data/com.termux/files/home/.bkx-android/req-123/request.json", files.requestFilePath)
        assertEquals("/data/data/com.termux/files/home/.bkx-android/req-123/result.json", files.resultFilePath)
        assertEquals("/data/data/com.termux/files/home/.bkx-android/req-123/progress.jsonl", files.progressFilePath)
        assertEquals("/data/data/com.termux/files/home/.bkx-android/req-123/cancel.json", files.cancelFilePath)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsRequestIdThatCannotBeUsedAsPathSegment() {
        BridgeFileLayout.forRequest(
            baseDirectoryPath = "/data/data/com.termux/files/home/.bkx-android",
            requestId = "../escape",
        )
    }
}
