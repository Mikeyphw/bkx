package com.mikeyphw.bkx.android.core.usecase

import com.mikeyphw.bkx.android.core.backend.BackendPort
import com.mikeyphw.bkx.android.core.backend.BackendReadiness

class CheckBackendReadinessUseCase(
    private val backendPort: BackendPort,
) {
    suspend operator fun invoke(): BackendReadiness = backendPort.checkReadiness()
}
