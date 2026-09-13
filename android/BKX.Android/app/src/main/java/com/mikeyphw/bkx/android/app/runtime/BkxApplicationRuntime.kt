package com.mikeyphw.bkx.android.app.runtime

import android.content.Context
import com.mikeyphw.bkx.android.automation.AutomationDispatcher
import com.mikeyphw.bkx.android.automation.BkxSavedJobScheduler
import com.mikeyphw.bkx.android.core.state.BkxControlPlaneRoot
import com.mikeyphw.bkx.android.core.state.BkxControlPlaneRootResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch

sealed interface BkxApplicationRuntimePhase {
    data object Created : BkxApplicationRuntimePhase
    data object Initializing : BkxApplicationRuntimePhase
    data object Ready : BkxApplicationRuntimePhase
    data class Degraded(val failures: List<String>) : BkxApplicationRuntimePhase
    data object ShuttingDown : BkxApplicationRuntimePhase
}

enum class BkxStartupIngress {
    ApplicationCreated,
    InteractiveUi,
    InteractiveResume,
    AuthorizedAutomation,
    ScheduledJob,
    RecoveryBroadcast,
    ForegroundOperation,
}

data class BkxApplicationRuntimeState(
    val phase: BkxApplicationRuntimePhase = BkxApplicationRuntimePhase.Created,
    val ingress: BkxStartupIngress = BkxStartupIngress.ApplicationCreated,
    val controlPlaneRoot: BkxControlPlaneRoot? = null,
    val completedSteps: List<String> = emptyList(),
)

/**
 * Process-owned runtime lifecycle for BKX Android.
 *
 * The Application object installs this owner but does not execute recovery,
 * parse catalogs or touch JobScheduler. Authorized ingress surfaces call the
 * suspendable barrier before starting background work, and UI startup asks for
 * the same barrier without blocking the main thread.
 */
class BkxApplicationRuntime(
    context: Context,
    private val coordinatorProvider: () -> BkxOperationCoordinator,
) {
    private val appContext = context.applicationContext ?: context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val _state = MutableStateFlow(BkxApplicationRuntimeState())
    val state: StateFlow<BkxApplicationRuntimeState> = _state.asStateFlow()

    fun markApplicationCreated() {
        _state.value = _state.value.copy(
            phase = BkxApplicationRuntimePhase.Created,
            ingress = BkxStartupIngress.ApplicationCreated,
        )
    }

    fun startAfterAuthorizedIngress(ingress: BkxStartupIngress): Job = scope.launch {
        awaitReadyForExecution(ingress)
    }

    suspend fun awaitReadyForExecution(ingress: BkxStartupIngress): BkxApplicationRuntimeState = mutex.withLock {
        val current = _state.value
        if (current.phase is BkxApplicationRuntimePhase.Ready) return@withLock current.copy(ingress = ingress).also { _state.value = it }
        if (current.phase is BkxApplicationRuntimePhase.ShuttingDown) return@withLock current

        val steps = mutableListOf<String>()
        val failures = mutableListOf<String>()
        _state.value = current.copy(phase = BkxApplicationRuntimePhase.Initializing, ingress = ingress)

        val root = BkxControlPlaneRootResolver.resolve(appContext).also { steps += "control-plane:${it.source.name}:${it.reason}" }
        runStep("operation-recovery", steps, failures) {
            coordinatorProvider().initializeAndRecoverInterruptedOperations()
        }
        runStep("automation-recovery", steps, failures) {
            AutomationDispatcher.recoverPending(appContext)
        }
        runStep("schedule-reconcile", steps, failures) {
            BkxSavedJobScheduler.reconcile(appContext)
        }

        val phase = if (failures.isEmpty()) {
            BkxApplicationRuntimePhase.Ready
        } else {
            BkxApplicationRuntimePhase.Degraded(failures.toList())
        }
        BkxApplicationRuntimeState(
            phase = phase,
            ingress = ingress,
            controlPlaneRoot = root,
            completedSteps = steps.toList(),
        ).also { _state.value = it }
    }

    fun shutdownForTests() {
        _state.value = _state.value.copy(phase = BkxApplicationRuntimePhase.ShuttingDown)
        scope.cancel()
    }

    private suspend fun runStep(
        name: String,
        steps: MutableList<String>,
        failures: MutableList<String>,
        block: suspend () -> Unit,
    ) {
        runCatching { block() }
            .onSuccess { steps += name }
            .onFailure { error -> failures += "$name:${error::class.java.simpleName}:${error.message.orEmpty()}" }
    }
}
