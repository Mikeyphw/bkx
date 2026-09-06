package com.mikeyphw.bkx.android.embedded

import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Selects the generated Go runtime when it is packaged for this ABI, otherwise Chaquopy. */
class NativePreferredGateway(
    private val nativeGateway: GoNativeGateway,
    private val pythonFallback: EmbeddedRuntimeGateway,
    private val goEnabledProvider: () -> Boolean = { GoRuntimeCutoverPolicy.ENABLED_BY_DEFAULT },
) : EmbeddedRuntimeGateway {
    private enum class Route { Go, Python }
    private val routes = ConcurrentHashMap<String, Route>()

    override suspend fun readinessJson(): String = gateway(routeNow()).readinessJson()

    override suspend fun executeJson(requestJson: String): String {
        val requestId = requestIdFrom(requestJson)
        val route = routeNow()
        routes[requestId] = route
        return try {
            gateway(route).executeJson(requestJson)
        } finally {
            routes.remove(requestId, route)
        }
    }

    override suspend fun progressJson(requestId: String): String =
        gateway(routes[requestId] ?: routeNow()).progressJson(requestId)

    override suspend fun cancelJson(requestId: String): String =
        gateway(routes[requestId] ?: routeNow()).cancelJson(requestId)

    private fun routeNow(): Route = if (goEnabledProvider() && nativeGateway.isAvailable()) Route.Go else Route.Python

    private fun gateway(route: Route): EmbeddedRuntimeGateway = when (route) {
        Route.Go -> nativeGateway
        Route.Python -> pythonFallback
    }

    private fun requestIdFrom(requestJson: String): String = Json.parseToJsonElement(requestJson)
        .jsonObject["request_id"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        ?: throw IllegalArgumentException("Embedded BKX request is missing request_id.")
}
