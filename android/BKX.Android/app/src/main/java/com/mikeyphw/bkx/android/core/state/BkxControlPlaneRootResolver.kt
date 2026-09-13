package com.mikeyphw.bkx.android.core.state

import android.content.Context
import android.os.Build
import android.os.Environment
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Single Android-side resolver for the BKX control-plane document root.
 *
 * Startup, scheduler reconciliation and the embedded Go runtime must not each
 * invent their own shared/private fallback rule. This resolver keeps shared
 * state behind the same marker/permission check and gives callers bounded
 * document reads so corrupted external files cannot stall Application startup.
 */
data class BkxControlPlaneRoot(
    val directory: File,
    val source: Source,
    val sharedMarkerValid: Boolean,
    val reason: String,
) {
    enum class Source { Shared, PrivateFallback }
}

object BkxControlPlaneRootResolver {
    const val MAX_MARKER_BYTES = 4 * 1024L
    const val MAX_CONTROL_PLANE_DOCUMENT_BYTES = 1024 * 1024L
    const val MAX_CONTROL_PLANE_ITEMS = 2_000

    private val json = Json { ignoreUnknownKeys = true }

    fun resolve(context: Context, stateNamespace: String = LOCAL_STATE_NAMESPACE): BkxControlPlaneRoot {
        val appContext = context.applicationContext ?: context
        val shared = File(BkxSharedStateContract.ROOT)
        val sharedAllowed = stateNamespace == ROOT_STATE_NAMESPACE ||
            Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
            Environment.isExternalStorageManager()
        val marker = shared.resolve(BkxSharedStateContract.MARKER)
        val markerValid = sharedAllowed && marker.isFile && marker.canRead() && marker.length() in 1..MAX_MARKER_BYTES && markerIsValid(marker)
        if (markerValid) {
            return BkxControlPlaneRoot(shared, BkxControlPlaneRoot.Source.Shared, sharedMarkerValid = true, reason = "valid-shared-marker")
        }
        if (sharedAllowed && !marker.exists()) {
            runCatching {
                shared.mkdirs()
                marker.writeText(
                    """{"schema_version":1,"kind":"${BkxSharedStateContract.MARKER_KIND}","creator":"android"}""" + "\n",
                    Charsets.UTF_8,
                )
            }
            if (marker.isFile && marker.canRead() && marker.length() in 1..MAX_MARKER_BYTES && markerIsValid(marker)) {
                return BkxControlPlaneRoot(shared, BkxControlPlaneRoot.Source.Shared, sharedMarkerValid = true, reason = "created-shared-marker")
            }
        }
        val privateRoot = appContext.filesDir.resolve("bkx/$stateNamespace/home/.local/share/bkx").apply { mkdirs() }
        return BkxControlPlaneRoot(
            directory = privateRoot,
            source = BkxControlPlaneRoot.Source.PrivateFallback,
            sharedMarkerValid = false,
            reason = if (sharedAllowed) "shared-marker-unavailable-or-invalid" else "shared-storage-not-granted",
        )
    }

    fun resolveDocument(context: Context, relativePath: String, stateNamespace: String = LOCAL_STATE_NAMESPACE): File? {
        val root = resolve(context, stateNamespace).directory
        val clean = relativePath.trimStart('/').replace("\\", "/")
        require(".." !in clean.split('/')) { "BKX control-plane relative path must not traverse." }
        return root.resolve(clean).takeIf { it.isFile && it.canRead() }
    }

    fun readBoundedDocument(file: File, maxBytes: Long = MAX_CONTROL_PLANE_DOCUMENT_BYTES): String? {
        if (!file.isFile || !file.canRead()) return null
        val length = file.length()
        if (length <= 0L || length > maxBytes) return null
        return runCatching { file.readText(Charsets.UTF_8) }.getOrNull()
    }

    private fun markerIsValid(marker: File): Boolean = runCatching {
        val element = json.parseToJsonElement(marker.readText(Charsets.UTF_8)).jsonObject
        element["schema_version"]?.jsonPrimitive?.content == "1" &&
            element["kind"]?.jsonPrimitive?.content == BkxSharedStateContract.MARKER_KIND
    }.getOrDefault(false)

    private const val LOCAL_STATE_NAMESPACE = "go-local"
    private const val ROOT_STATE_NAMESPACE = "go-root"
}
