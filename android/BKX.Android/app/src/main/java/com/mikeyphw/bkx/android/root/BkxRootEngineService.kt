package com.mikeyphw.bkx.android.root

import android.content.Intent
import android.os.IBinder
import android.system.Os
import com.mikeyphw.bkx.android.core.chrootctl.ChrootctlReadinessCodec
import com.mikeyphw.bkx.android.embedded.GoNativeGateway
import com.topjohnwu.superuser.ipc.RootService
import kotlinx.coroutines.runBlocking

/**
 * Privileged protocol-v1 BKX execution process.
 *
 * libsu starts this service as UID 0. GO13 launches the same app-owned Go runtime
 * used by the local route, but from the privileged process. The AIDL surface remains
 * narrow JSON only and never accepts arbitrary shell commands or executable paths.
 */
class BkxRootEngineService : RootService() {
    private val goGateway: GoNativeGateway by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        GoNativeGateway(baseContext, stateNamespace = ROOT_STATE_NAMESPACE)
    }

    private val binder = object : IBkxRootEngineService.Stub() {
        override fun uid(): Int = Os.getuid()
        override fun readinessJson(): String = requireRoot { runBlocking { goGateway.readinessJson() } }
        override fun chrootctlReadinessJson(): String = requireRoot {
            ChrootctlReadinessCodec.encode(ChrootctlRuntimeResolver.resolve())
        }
        override fun executeJson(requestJson: String): String = requireRoot { runBlocking { goGateway.executeJson(requestJson) } }
        override fun progressJson(requestId: String): String = requireRoot { runBlocking { goGateway.progressJson(requestId) } }
        override fun cancelJson(requestId: String): String = requireRoot { runBlocking { goGateway.cancelJson(requestId) } }
    }

    override fun onCreate() {
        super.onCreate()
        check(Os.getuid() == ROOT_UID) { "BKX privileged engine refused to initialize outside UID 0." }
        check(goGateway.isAvailable()) { "BKX privileged Go runtime is unavailable for this ABI." }
    }

    override fun onDestroy() {
        goGateway.close()
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder = binder

    private inline fun <T> requireRoot(block: () -> T): T {
        check(Os.getuid() == ROOT_UID) {
            "BKX privileged engine refused to run because the libsu service is not UID 0."
        }
        return block()
    }

    private companion object {
        const val ROOT_UID = 0
        const val ROOT_STATE_NAMESPACE = "go-root"
    }
}
