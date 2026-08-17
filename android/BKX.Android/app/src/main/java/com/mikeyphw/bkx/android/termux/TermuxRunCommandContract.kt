package com.mikeyphw.bkx.android.termux

import com.mikeyphw.bkx.android.core.bridge.BridgeJson
import com.mikeyphw.bkx.android.core.bridge.BridgeRequestFiles

object TermuxRunCommandContract {
    const val TERMUX_PACKAGE_NAME = "com.termux"
    const val RUN_COMMAND_SERVICE_CLASS = "com.termux.app.RunCommandService"
    const val ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND"
    const val PERMISSION_RUN_COMMAND = "com.termux.permission.RUN_COMMAND"

    const val EXTRA_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH"
    const val EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
    const val EXTRA_STDIN = "com.termux.RUN_COMMAND_STDIN"
    const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
    const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
    const val EXTRA_COMMAND_LABEL = "com.termux.RUN_COMMAND_LABEL"
    const val EXTRA_COMMAND_DESCRIPTION = "com.termux.RUN_COMMAND_DESCRIPTION"

    private val termuxFilesRoot: String = listOf(
        "",
        "data",
        "data",
        TERMUX_PACKAGE_NAME,
        "files",
    ).joinToString("/")

    val TERMUX_HOME: String = listOf(termuxFilesRoot, "home").joinToString("/")
    val APPROVED_WRAPPER_PATH: String = listOf(termuxFilesRoot, "usr", "bin", "bkx-android-bridge").joinToString("/")

    val approvedWrapper = ApprovedWrapperIdentity(
        absolutePath = APPROVED_WRAPPER_PATH,
        label = "BKX Android bridge",
    )

    fun fixedArguments(files: BridgeRequestFiles): List<String> = listOf(
        "--protocol-version", BridgeJson.SUPPORTED_PROTOCOL_VERSION.toString(),
        "--request-id", files.requestId,
        "--request", files.requestFilePath,
        "--result", files.resultFilePath,
        "--progress", files.progressFilePath,
        "--cancel", files.cancelFilePath,
    )

    fun fixedCancelArguments(requestId: String, cancelFilePath: String): List<String> {
        require(requestId.isNotBlank()) { "cancel request id must not be blank" }
        require(cancelFilePath.isNotBlank()) { "cancel file path must not be blank" }
        return listOf(
            "--protocol-version", BridgeJson.SUPPORTED_PROTOCOL_VERSION.toString(),
            "--request-id", requestId,
            "--cancel", cancelFilePath,
            "--operation", "run.cancel",
        )
    }
}

data class ApprovedWrapperIdentity(
    val absolutePath: String,
    val label: String,
) {
    init {
        require(absolutePath == TermuxRunCommandContract.APPROVED_WRAPPER_PATH) {
            "Only the approved BKX Android bridge wrapper may be invoked."
        }
    }
}

data class RunCommandInvocation(
    val packageName: String,
    val serviceClassName: String,
    val action: String,
    val commandPath: String,
    val arguments: List<String>,
    val stdin: String,
    val workingDirectory: String,
    val background: Boolean,
    val label: String,
    val description: String,
) {
    init {
        require(commandPath == TermuxRunCommandContract.APPROVED_WRAPPER_PATH) {
            "Only the approved BKX Android bridge wrapper may be invoked."
        }
        require(arguments.none { it.contains("&&") || it.contains(";") || it.contains("|") }) {
            "RUN_COMMAND arguments must remain fixed argv tokens, not shell fragments."
        }
        require(stdin.isNotBlank()) { "RUN_COMMAND stdin must carry one structured bridge request JSON document." }
    }
}
