package com.mikeyphw.bkx.android.core.remotes

internal val SAFE_RCLONE_REMOTE_NAME = Regex("^[A-Za-z0-9._-]+$")

object RcloneConfigParser {
    fun parse(text: String, configured: Boolean = true): RcloneConfigSnapshot = parseLines(text.lineSequence().toList(), configured)

    fun parseLines(lines: List<String>, configured: Boolean = true): RcloneConfigSnapshot {
        data class MutableRemote(val name: String, var type: String = "unknown")

        val remotes = mutableListOf<MutableRemote>()
        var current: MutableRemote? = null
        lines.forEach { raw ->
            val line = raw.trim()
            if (line.isBlank() || line.startsWith('#') || line.startsWith(';')) return@forEach
            if (line.startsWith('[') && line.endsWith(']') && line.length > 2) {
                val name = line.substring(1, line.length - 1).trim()
                current = MutableRemote(name).also(remotes::add)
                return@forEach
            }
            val active = current ?: return@forEach
            val separator = line.indexOf('=')
            if (separator > 0 && line.substring(0, separator).trim().equals("type", ignoreCase = true)) {
                active.type = line.substring(separator + 1).trim().ifBlank { "unknown" }
            }
        }

        val safe = remotes
            .filter { SAFE_RCLONE_REMOTE_NAME.matches(it.name) }
            .distinctBy { it.name }
            .map { RcloneRemoteProfile(it.name, it.type) }
            .sortedBy { it.name.lowercase() }
        val ignored = remotes
            .map { it.name }
            .filterNot(SAFE_RCLONE_REMOTE_NAME::matches)
            .distinct()
            .sorted()
        return RcloneConfigSnapshot(configured = configured, profiles = safe, ignoredRemoteNames = ignored)
    }
}
