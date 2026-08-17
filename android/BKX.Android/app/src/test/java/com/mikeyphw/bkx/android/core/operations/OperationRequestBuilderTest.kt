package com.mikeyphw.bkx.android.core.operations

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OperationRequestBuilderTest {
    @Test
    fun reconnectUsesRunsShowKeyedByTargetRequestId() {
        val request = OperationRequestBuilder.buildReconnectStatusRequest(
            requestId = "status-1",
            targetRequestId = "backup-1",
            createdAtEpochMillis = 10,
        )

        assertEquals("runs.show", request.operation)
        assertEquals("\"backup-1\"", request.payload["target_request_id"].toString())
    }

    @Test
    fun cancellationUsesRunCancelWithRequestIdAndCancelFile() {
        val request = OperationRequestBuilder.buildCancelRequest(
            cancelRequestId = "cancel-1",
            targetRequestId = "backup-1",
            cancelFilePath = "/tmp/bkx/backup-1/cancel.json",
            createdAtEpochMillis = 11,
        )
        val cancel = OperationRequestBuilder.buildCancelEnvelope("backup-1")

        assertEquals("run.cancel", request.operation)
        assertEquals("\"backup-1\"", request.payload["target_request_id"].toString())
        assertTrue(request.payload["cancel_file_path"].toString().contains("cancel.json"))
        assertEquals("backup-1", cancel.requestId)
        assertEquals("cancel", cancel.action)
    }
}
