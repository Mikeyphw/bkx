package com.mikeyphw.bkx.android.termux

import com.mikeyphw.bkx.android.core.bridge.BridgeRequestFiles

class TermuxRunCommandInvocationFactory(
    private val wrapper: ApprovedWrapperIdentity = TermuxRunCommandContract.approvedWrapper,
) {
    fun create(
        files: BridgeRequestFiles,
        requestJson: String,
    ): RunCommandInvocation = RunCommandInvocation(
        packageName = TermuxRunCommandContract.TERMUX_PACKAGE_NAME,
        serviceClassName = TermuxRunCommandContract.RUN_COMMAND_SERVICE_CLASS,
        action = TermuxRunCommandContract.ACTION_RUN_COMMAND,
        commandPath = wrapper.absolutePath,
        arguments = TermuxRunCommandContract.fixedArguments(files),
        stdin = requestJson,
        workingDirectory = TermuxRunCommandContract.TERMUX_HOME,
        background = true,
        label = wrapper.label,
        description = "Runs the fixed BKX Android bridge wrapper with one JSON request and structured result/progress files.",
    )

    fun createCancel(
        requestId: String,
        cancelFilePath: String,
        cancelJson: String,
    ): RunCommandInvocation = RunCommandInvocation(
        packageName = TermuxRunCommandContract.TERMUX_PACKAGE_NAME,
        serviceClassName = TermuxRunCommandContract.RUN_COMMAND_SERVICE_CLASS,
        action = TermuxRunCommandContract.ACTION_RUN_COMMAND,
        commandPath = wrapper.absolutePath,
        arguments = TermuxRunCommandContract.fixedCancelArguments(requestId, cancelFilePath),
        stdin = cancelJson,
        workingDirectory = TermuxRunCommandContract.TERMUX_HOME,
        background = true,
        label = wrapper.label,
        description = "Requests cancellation by request id through the fixed BKX Android bridge wrapper.",
    )
}
