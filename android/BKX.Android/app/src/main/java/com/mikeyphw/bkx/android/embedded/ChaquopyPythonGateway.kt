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

    override fun readinessJson(): String = module().callAttr("readiness_json").toString()

    override fun executeJson(requestJson: String): String =
        module().callAttr("execute_json", requestJson).toString()

    override fun cancelJson(requestId: String): String =
        module().callAttr("cancel_json", requestId).toString()
}
