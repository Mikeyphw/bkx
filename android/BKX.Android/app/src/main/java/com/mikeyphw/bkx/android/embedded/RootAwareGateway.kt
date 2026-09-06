package com.mikeyphw.bkx.android.embedded

import com.mikeyphw.bkx.android.core.settings.RootAccessMode
import com.mikeyphw.bkx.android.root.RootBkxServiceClient
import com.mikeyphw.bkx.android.root.RootExecutionPolicy
import com.mikeyphw.bkx.android.root.RootExecutionRoute
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class RootAccessRequiredException : IllegalStateException(
    "BKX root access is required but was not granted by the device root manager.",
)

/**
 * Pins every active request to either the local or privileged protocol-v1 route.
 *
 * GO13 makes both production routes Go-native on supported ARM64 devices. The local
 * route retains the explicit Python fallback for unsupported development ABIs, while
 * the privileged libsu route fails closed unless the packaged Go runtime is available
 * as UID 0. Root preference changes apply to the next request without process restart.
 */
class RootAwareGateway(
    private val localGateway: EmbeddedRuntimeGateway,
    private val rootClient: RootBkxServiceClient,
    private val modeProvider: () -> RootAccessMode = { RootAccessMode.Preferred },
) : EmbeddedRuntimeGateway {
    private val requestRoutes = ConcurrentHashMap<String, RootExecutionRoute>()

    override suspend fun readinessJson(): String = when (route()) {
        RootExecutionRoute.Root -> rootClient.readinessJson()
        RootExecutionRoute.Local -> localGateway.readinessJson()
        RootExecutionRoute.Blocked -> throw RootAccessRequiredException()
    }

    override suspend fun executeJson(requestJson: String): String {
        val requestId = requestIdFrom(requestJson)
        val selectedRoute = route()
        if (selectedRoute == RootExecutionRoute.Blocked) throw RootAccessRequiredException()
        requestRoutes[requestId] = selectedRoute
        return try {
            when (selectedRoute) {
                RootExecutionRoute.Root -> rootClient.executeJson(requestJson)
                RootExecutionRoute.Local -> localGateway.executeJson(requestJson)
                RootExecutionRoute.Blocked -> error("Blocked route was already rejected.")
            }
        } finally {
            requestRoutes.remove(requestId, selectedRoute)
        }
    }

    override suspend fun progressJson(requestId: String): String = when (requestRoutes[requestId] ?: route()) {
        RootExecutionRoute.Root -> rootClient.progressJson(requestId)
        RootExecutionRoute.Local -> localGateway.progressJson(requestId)
        RootExecutionRoute.Blocked -> throw RootAccessRequiredException()
    }

    override suspend fun cancelJson(requestId: String): String = when (requestRoutes[requestId] ?: route()) {
        RootExecutionRoute.Root -> rootClient.cancelJson(requestId)
        RootExecutionRoute.Local -> localGateway.cancelJson(requestId)
        RootExecutionRoute.Blocked -> throw RootAccessRequiredException()
    }

    private fun requestIdFrom(requestJson: String): String = Json.parseToJsonElement(requestJson)
        .jsonObject["request_id"]
        ?.jsonPrimitive
        ?.content
        ?.takeIf { it.isNotBlank() }
        ?: throw IllegalArgumentException("Embedded BKX request is missing request_id.")

    private suspend fun route(): RootExecutionRoute = RootExecutionPolicy.select(
        mode = modeProvider(),
        rootAvailable = rootClient.rootAvailable(),
    )
}
