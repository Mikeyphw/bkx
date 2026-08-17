package com.mikeyphw.bkx.android.core.bridge

object BridgeFileLayout {
    fun forRequest(
        baseDirectoryPath: String,
        requestId: String,
    ): BridgeRequestFiles {
        val normalizedBase = baseDirectoryPath.trimEnd('/')
        val safeRequestId = requestId.trim()
        require(safeRequestId.matches(Regex("[A-Za-z0-9._-]+"))) {
            "requestId must be file-name safe"
        }
        val requestDirectory = "$normalizedBase/$safeRequestId"
        return BridgeRequestFiles(
            requestId = safeRequestId,
            requestFilePath = "$requestDirectory/request.json",
            resultFilePath = "$requestDirectory/result.json",
            progressFilePath = "$requestDirectory/progress.jsonl",
            cancelFilePath = "$requestDirectory/cancel.json",
        )
    }
}
