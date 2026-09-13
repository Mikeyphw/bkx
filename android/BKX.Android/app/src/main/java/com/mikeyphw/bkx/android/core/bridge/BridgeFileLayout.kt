package com.mikeyphw.bkx.android.core.bridge

object BridgeFileLayout {
    fun forRequest(
        baseDirectoryPath: String,
        requestId: String,
    ): BridgeRequestFiles {
        val normalizedBase = baseDirectoryPath.trimEnd('/')
        val safeRequestId = BridgeRequestIdPolicy.normalize(requestId)
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
