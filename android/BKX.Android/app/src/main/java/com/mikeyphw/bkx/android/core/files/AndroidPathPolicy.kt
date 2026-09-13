package com.mikeyphw.bkx.android.core.files

object AndroidPathPolicy {
    private val blockedExactRoots = setOf(
        "/",
        "/proc",
        "/sys",
        "/dev",
        "/system",
        "/vendor",
        "/product",
        "/apex",
        "/metadata",
        "/data/system",
        "/data/misc",
        "/data/vendor",
        "/data/property",
    )
    private val privilegedRoots = setOf(
        "/data/adb",
        "/data/data",
        "/data/user",
        "/data/user_de",
        "/data/media",
        "/data/data/com.termux/files",
        "/data/user/0/com.termux/files",
        "/mnt/media_rw",
    )
    private val sharedAliases = listOf(
        "/sdcard",
        "/storage/self/primary",
        "/storage/emulated/0",
    )
    private val restrictedSharedSuffixes = setOf(
        "android/data",
        "android/obb",
    )

    fun canonicalize(path: String): String {
        val trimmed = path.trim().replace('\\', '/')
        if (trimmed.isBlank()) return ""
        val absolute = if (trimmed.startsWith("/")) trimmed else "/$trimmed"
        val parts = ArrayDeque<String>()
        absolute.split('/').forEach { part ->
            when (part) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeLast()
                else -> parts.addLast(part)
            }
        }
        return "/" + parts.joinToString("/")
    }

    fun containsTraversal(path: String): Boolean = path
        .trim()
        .replace('\\', '/')
        .split('/')
        .any { it == ".." }

    fun restrictedWriteReason(path: String): String? {
        val normalized = canonicalize(path)
        if (normalized.isBlank()) return "path is blank"
        if (ContentUriPathPolicy.isContentUri(path)) return "content URIs must be staged before BKX receives filesystem paths"
        val lower = normalized.lowercase()
        blockedExactRoots.firstOrNull { lower == it || lower.startsWith("$it/") }?.let { root ->
            return "$root is a protected Android or pseudo-filesystem root"
        }
        restrictedSharedAlias(lower)?.let { label ->
            return "$label is restricted by modern Android storage rules"
        }
        return null
    }

    fun rootSensitiveReason(path: String): String? {
        restrictedWriteReason(path)?.let { return it }
        val lower = canonicalize(path).lowercase()
        privilegedRoots.firstOrNull { lower == it || lower.startsWith("$it/") }?.let { root ->
            return "$root requires the privileged BKX route"
        }
        return null
    }

    fun requiresRoot(path: String): Boolean = rootSensitiveReason(path) != null && !isSharedStorage(path)

    fun isTermuxPrivate(path: String): Boolean {
        val normalized = canonicalize(path).lowercase()
        return normalized == "/data/data/com.termux/files" || normalized.startsWith("/data/data/com.termux/files/") ||
            normalized == "/data/user/0/com.termux/files" || normalized.startsWith("/data/user/0/com.termux/files/")
    }

    fun isSharedStorage(path: String): Boolean {
        val lower = canonicalize(path).lowercase()
        return lower == "/sdcard" || lower.startsWith("/sdcard/") ||
            lower == "/storage/self/primary" || lower.startsWith("/storage/self/primary/") ||
            lower == "/storage/emulated/0" || lower.startsWith("/storage/emulated/0/")
    }

    private fun restrictedSharedAlias(lower: String): String? {
        sharedAliases.forEach { alias ->
            if (lower == alias) return null
            if (lower.startsWith("$alias/")) {
                val rest = lower.removePrefix("$alias/").trim('/')
                restrictedSharedSuffixes.firstOrNull { rest == it || rest.startsWith("$it/") }?.let { suffix ->
                    return "$alias/$suffix"
                }
            }
        }
        if (lower == "/android/data" || lower.startsWith("/android/data/")) return "/Android/data"
        if (lower == "/android/obb" || lower.startsWith("/android/obb/")) return "/Android/obb"
        return null
    }
}
