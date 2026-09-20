package com.mikeyphw.bkx.android.root

import com.mikeyphw.bkx.android.core.chrootctl.ChrootctlReadiness
import com.mikeyphw.bkx.android.core.chrootctl.ChrootctlReadinessCodec
import com.mikeyphw.bkx.android.core.chrootctl.ChrootctlReadinessPort
import com.mikeyphw.bkx.android.core.settings.RootAccessMode
import kotlinx.coroutines.CancellationException

/**
 * App-process view of chrootctl readiness.
 *
 * The filesystem/package probe itself always runs inside BKX's existing UID-0
 * RootService. The Android app process never reads /data/adb directly and this
 * port exposes no arbitrary command execution surface.
 */
class RootChrootctlReadinessPort(
    private val rootClient: RootBkxServiceClient,
    private val modeProvider: () -> RootAccessMode,
) : ChrootctlReadinessPort {
    override suspend fun probe(): ChrootctlReadiness {
        if (modeProvider() == RootAccessMode.Disabled) return ChrootctlReadiness.rootDisabled()
        if (!rootClient.rootAvailable()) return ChrootctlReadiness.rootUnavailable()

        return try {
            ChrootctlReadinessCodec.decode(rootClient.chrootctlReadinessJson())
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            ChrootctlReadiness.rootTransportUnavailable(
                "BKX root transport could not verify chrootctl readiness: ${error.message ?: error.javaClass.simpleName}",
            )
        }
    }
}
