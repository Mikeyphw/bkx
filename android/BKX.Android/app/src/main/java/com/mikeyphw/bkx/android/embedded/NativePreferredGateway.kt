package com.mikeyphw.bkx.android.embedded

import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ProductionGoRuntimeUnavailableException : IllegalStateException(
    "BKX Go runtime cutover is enabled for ARM64, but the packaged Go runtime is unavailable.",
)

/** Selects Go for production while retaining Python only as an explicit rollback/non-ARM64 lane. */
class NativePreferredGateway(
    private val nativeGateway: AvailableEmbeddedRuntimeGateway,
    private val pythonFallback: EmbeddedRuntimeGateway,
    private val goEnabledProvider: () -> Boolean = { GoRuntimeCutoverPolicy.ENABLED_BY_DEFAULT },
    private val goRequiredProvider: () -> Boolean = { GoRuntimeCutoverPolicy.requireGoOnCurrentDevice() },
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

    private fun routeNow(): Route {
        if (!goEnabledProvider()) return Route.Python
        if (nativeGateway.isAvailable()) return Route.Go
        if (goRequiredProvider()) throw ProductionGoRuntimeUnavailableException()
        return Route.Python
    }

    private fun gateway(route: Route): EmbeddedRuntimeGateway = when (route) {
        Route.Go -> nativeGateway
        Route.Python -> pythonFallback
    }

    private fun requestIdFrom(requestJson: String): String = Json.parseToJsonElement(requestJson)
        .jsonObject["request_id"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        ?: throw IllegalArgumentException("Embedded BKX request is missing request_id.")
}
