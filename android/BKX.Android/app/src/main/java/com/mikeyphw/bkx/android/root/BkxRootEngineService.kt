package com.mikeyphw.bkx.android.root

import android.app.Application
import android.content.Context
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
    private val rootPythonApplication: Application by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        RootChaquopyApplication(baseContext)
    }

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

    override fun onCreate() {
        super.onCreate()

        prepareFreshChaquopyProcess()
        python()
    }

    override fun onBind(intent: Intent): IBinder = binder

    private fun prepareFreshChaquopyProcess() {
        if (
            !Python.isStarted() &&
            Os.getenv(CHAQUOPY_PROCESS_TYPE_ENV) != null
        ) {
            Os.unsetenv(CHAQUOPY_PROCESS_TYPE_ENV)
        }
    }

    private fun python(): Python {
        if (!Python.isStarted()) {
            synchronized(PYTHON_START_LOCK) {
                if (!Python.isStarted()) {
                    prepareFreshChaquopyProcess()
                    Python.start(
                        AndroidPlatform(rootPythonApplication),
                    )
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
        const val CHAQUOPY_PROCESS_TYPE_ENV = "CHAQUOPY_PROCESS_TYPE"
        const val ROOT_UID = 0
        val PYTHON_START_LOCK = Any()
    }
}

/**
 * libsu RootService is backed by a synthetic ContextImpl rather than a
 * normal ActivityThread Application.
 *
 * Chaquopy AndroidPlatform requires getApplicationContext() to return
 * an Application. This facade supplies that requirement while delegating
 * assets, files and SharedPreferences to libsu's attached package Context.
 */
private class RootChaquopyApplication(base: Context) : Application() {
    init {
        attachBaseContext(base)
    }

    override fun getApplicationContext(): Context = this
}

