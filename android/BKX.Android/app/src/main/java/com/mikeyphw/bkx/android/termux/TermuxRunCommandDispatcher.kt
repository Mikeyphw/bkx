package com.mikeyphw.bkx.android.termux

import android.content.ComponentName
import android.content.Context
import android.content.Intent

class TermuxRunCommandDispatcher(
    private val context: Context,
) {
    fun dispatch(invocation: RunCommandInvocation): RunCommandDispatchResult = try {
        val started: ComponentName? = context.startService(invocation.toIntent())
        if (started != null) {
            RunCommandDispatchResult.Started
        } else {
            RunCommandDispatchResult.Failed("Termux did not accept the fixed wrapper service intent.")
        }
    } catch (exc: SecurityException) {
        RunCommandDispatchResult.Failed("RUN_COMMAND permission was denied by Android: ${exc.message.orEmpty()}")
    } catch (exc: RuntimeException) {
        RunCommandDispatchResult.Failed("Termux RUN_COMMAND service failed to start: ${exc.message.orEmpty()}")
    }
}

sealed interface RunCommandDispatchResult {
    data object Started : RunCommandDispatchResult
    data class Failed(val reason: String) : RunCommandDispatchResult
}

fun RunCommandInvocation.toIntent(): Intent = Intent().apply {
    setClassName(packageName, serviceClassName)
    action = this@toIntent.action
    putExtra(TermuxRunCommandContract.EXTRA_COMMAND_PATH, commandPath)
    putExtra(TermuxRunCommandContract.EXTRA_ARGUMENTS, arguments.toTypedArray())
    putExtra(TermuxRunCommandContract.EXTRA_STDIN, stdin)
    putExtra(TermuxRunCommandContract.EXTRA_WORKDIR, workingDirectory)
    putExtra(TermuxRunCommandContract.EXTRA_BACKGROUND, background)
    putExtra(TermuxRunCommandContract.EXTRA_COMMAND_LABEL, label)
    putExtra(TermuxRunCommandContract.EXTRA_COMMAND_DESCRIPTION, description)
}
