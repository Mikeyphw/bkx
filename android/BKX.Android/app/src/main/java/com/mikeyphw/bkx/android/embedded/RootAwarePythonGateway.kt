package com.mikeyphw.bkx.android.embedded

import com.mikeyphw.bkx.android.core.settings.RootAccessMode
import com.mikeyphw.bkx.android.root.RootBkxServiceClient
import com.mikeyphw.bkx.android.root.RootExecutionPolicy
import com.mikeyphw.bkx.android.root.RootExecutionRoute

class RootAccessRequiredException : IllegalStateException(
    "BKX root access is required but was not granted by the device root manager.",
)

/**
 * Prefers the libsu root engine when the user has granted root.
 *
 * Root execution runs the same bkx.android_api module and the same bkx.core
 * implementation as local execution; privilege is a transport/runtime choice,
 * never a second BKX implementation.
 */
class RootAwarePythonGateway(
    private val localGateway: EmbeddedPythonGateway,
    private val rootClient: RootBkxServiceClient,
    private val mode: RootAccessMode = RootAccessMode.Preferred,
) : EmbeddedPythonGateway {
    override suspend fun readinessJson(): String = when (route()) {
        RootExecutionRoute.Root -> rootClient.readinessJson()
        RootExecutionRoute.Local -> localGateway.readinessJson()
        RootExecutionRoute.Blocked -> throw RootAccessRequiredException()
    }

    override suspend fun executeJson(requestJson: String): String = when (route()) {
        RootExecutionRoute.Root -> rootClient.executeJson(requestJson)
        RootExecutionRoute.Local -> localGateway.executeJson(requestJson)
        RootExecutionRoute.Blocked -> throw RootAccessRequiredException()
    }

    override suspend fun cancelJson(requestId: String): String = when (route()) {
        RootExecutionRoute.Root -> rootClient.cancelJson(requestId)
        RootExecutionRoute.Local -> localGateway.cancelJson(requestId)
        RootExecutionRoute.Blocked -> throw RootAccessRequiredException()
    }

    private suspend fun route(): RootExecutionRoute = RootExecutionPolicy.select(
        mode = mode,
        rootAvailable = rootClient.rootAvailable(),
    )
}
