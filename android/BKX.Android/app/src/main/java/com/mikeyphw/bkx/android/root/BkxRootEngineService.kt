package com.mikeyphw.bkx.android.root

import android.content.Intent
import android.os.IBinder
import android.system.Os
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.topjohnwu.superuser.ipc.RootService

/**
 * Privileged BKX execution process.
 *
 * libsu starts this service with root privileges after the user's root manager
 * authorizes BKX. The service deliberately exposes only the canonical BKX
 * JSON API: callers cannot submit shell commands or arbitrary Python code.
 */
class BkxRootEngineService : RootService() {
    private val binder = object : IBkxRootEngineService.Stub() {
        override fun uid(): Int = Os.getuid()

        override fun readinessJson(): String = requireRoot {
            module().callAttr("readiness_json").toString()
        }

        override fun executeJson(requestJson: String): String = requireRoot {
            module().callAttr("execute_json", requestJson).toString()
        }

        override fun progressJson(requestId: String): String = requireRoot {
            module().callAttr("progress_json", requestId).toString()
        }

        override fun cancelJson(requestId: String): String = requireRoot {
            module().callAttr("cancel_json", requestId).toString()
        }
    }

    override fun onBind(intent: Intent): IBinder = binder

    private fun python(): Python {
        if (!Python.isStarted()) {
            synchronized(PYTHON_START_LOCK) {
                if (!Python.isStarted()) {
                    Python.start(AndroidPlatform(applicationContext))
                }
            }
        }
        return Python.getInstance()
    }

    private fun module() = python().getModule("bkx.android_api")

    private inline fun <T> requireRoot(block: () -> T): T {
        check(Os.getuid() == ROOT_UID) {
            "BKX privileged engine refused to run because the libsu service is not UID 0."
        }
        return block()
    }

    private companion object {
        const val ROOT_UID = 0
        val PYTHON_START_LOCK = Any()
    }
}
