package com.mikeyphw.bkx.android.embedded

import com.mikeyphw.bkx.android.core.settings.RootAccessMode
import com.mikeyphw.bkx.android.root.RootBkxServiceClient
import com.mikeyphw.bkx.android.root.RootExecutionPolicy
import com.mikeyphw.bkx.android.root.RootExecutionRequest
import com.mikeyphw.bkx.android.root.RootExecutionRoute
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
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
        val parsed = parseRequest(requestJson)
        val selectedRoute = route(parsed.request)
        if (selectedRoute == RootExecutionRoute.Blocked) throw RootAccessRequiredException()
        requestRoutes[parsed.requestId] = selectedRoute
        return try {
            when (selectedRoute) {
                RootExecutionRoute.Root -> rootClient.executeJson(requestJson)
                RootExecutionRoute.Local -> localGateway.executeJson(requestJson)
                RootExecutionRoute.Blocked -> error("Blocked route was already rejected.")
            }
        } finally {
            requestRoutes.remove(parsed.requestId, selectedRoute)
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

    private fun parseRequest(requestJson: String): ParsedRootRequest {
        val root = Json.parseToJsonElement(requestJson).jsonObject
        val requestId = root["request_id"]
            ?.jsonPrimitive
            ?.content
            ?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("Embedded BKX request is missing request_id.")
        val operation = root["operation"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val paths = candidatePathsFrom(root["payload"]).distinct()
        return ParsedRootRequest(requestId, RootExecutionRequest(operation = operation, candidatePaths = paths))
    }

    private fun candidatePathsFrom(element: JsonElement?): List<String> = when (element) {
        is JsonObject -> element.flatMap { (key, value) ->
            val direct = if (PATH_KEYS.any { key.equals(it, ignoreCase = true) || key.endsWith("_$it", ignoreCase = true) }) {
                stringValues(value)
            } else {
                emptyList()
            }
            direct + candidatePathsFrom(value)
        }
        is JsonArray -> element.flatMap { candidatePathsFrom(it) }
        else -> emptyList()
    }

    private fun stringValues(element: JsonElement): List<String> = when (element) {
        is JsonArray -> element.mapNotNull { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
        is JsonObject -> candidatePathsFrom(element)
        else -> listOfNotNull(runCatching { element.jsonPrimitive.contentOrNull }.getOrNull())
    }.filter { it.isNotBlank() }

    private suspend fun route(): RootExecutionRoute = RootExecutionPolicy.select(
        mode = modeProvider(),
        rootAvailable = rootClient.rootAvailable(),
    )

    private suspend fun route(request: RootExecutionRequest): RootExecutionRoute = RootExecutionPolicy.select(
        mode = modeProvider(),
        rootAvailable = rootClient.rootAvailable(),
        request = request,
    )

    private data class ParsedRootRequest(val requestId: String, val request: RootExecutionRequest)

    private companion object {
        val PATH_KEYS = setOf("path", "staged_path", "staged_directory_path", "destination_path", "archive", "location", "repository")
    }
}
