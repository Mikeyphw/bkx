package com.mikeyphw.bkx.android.embedded

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform

class ChaquopyPythonGateway(
    context: Context,
) : EmbeddedPythonGateway {
    private val appContext = context.applicationContext

    private fun python(): Python {
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(appContext))
        }
        return Python.getInstance()
    }

    private fun module() = python().getModule("bkx.android_api")

    override suspend fun readinessJson(): String = module().callAttr("readiness_json").toString()

    override suspend fun executeJson(requestJson: String): String =
        module().callAttr("execute_json", requestJson).toString()

    override suspend fun progressJson(requestId: String): String =
        module().callAttr("progress_json", requestId).toString()

    override suspend fun cancelJson(requestId: String): String =
        module().callAttr("cancel_json", requestId).toString()
}
