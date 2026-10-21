package com.mikeyphw.bkx.android.r1

import android.net.LocalSocket
import android.net.LocalSocketAddress
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.time.Instant
import android.os.SystemClock

/** App-process-only, explicit read-only Skybind R1 transport.
 * Never falls back to BKX native rclone after Skybind authorization fails.
 * Operator must approve THIS live BKX PID, remote/prefix/operation and TTL.
 * Tokens live only within this function invocation, never in logs or storage.
 */
object SkybindR1ReadClient {
    private const val GRANTS = "skybind-r1-grant-handoff-v1"
    private const val READ = "skybind-r1-consumer-gateway-v1"
    private const val MAX_RESPONSE = 1 shl 20
    private const val MAX_WAIT_MS = 90_000L
    private val operations = setOf("system.capabilities", "rclone.version", "rclone.listremotes", "rclone.lsjson", "rclone.size")
    private val remoteName = Regex("[A-Za-z][A-Za-z0-9_-]{0,62}")
    private val tokenHex = Regex("[0-9a-f]{64}")

    private fun connect(name: String): LocalSocket = LocalSocket().apply {
        try {
            connect(LocalSocketAddress(name, LocalSocketAddress.Namespace.ABSTRACT))
            soTimeout = 6000
        } catch (e: Exception) {
            close()
            throw e
        }
    }
    private fun boundedLine(socket: LocalSocket): String {
        val data = ByteArrayOutputStream()
        while (true) {
            val ch = socket.inputStream.read()
            if (ch == -1) throw java.io.EOFException("R1 response incomplete")
            if (ch == 10) break
            if (data.size() >= MAX_RESPONSE) error("R1 response too large")
            data.write(ch)
        }
        return data.toString(Charsets.UTF_8.name())
    }
    private fun validPath(path: String): Boolean =
        path.length <= 4096 && !path.startsWith("/") &&
        path.none { it == '\\' || it == ':' || it == '%' || it == '?' || it == '#' || it == '\r' || it == '\n' || it == '\u0000' } &&
        (path.isEmpty() || path.split('/').all { it.isNotEmpty() && it != "." && it != ".." })

    /** Blocks the caller's background worker while the root operator approves the PID. */
    fun read(operation: String, remote: String, path: String = ""): Any {
        require(operation in operations) { "Only read-only R1 operations are supported" }
        require(remoteName.matches(remote)) { "Invalid remote" }
        require(validPath(path)) { "Invalid relative path" }
        val deadline = SystemClock.elapsedRealtime() + MAX_WAIT_MS
        var grant: JSONObject? = null
        // Missing handoff is not an authorization grant. Retry without accepting
        // self-declared scopes; the server independently validates the exact PID.
        while (SystemClock.elapsedRealtime() < deadline) {
            try {
                val offer = connect(GRANTS).use { JSONObject(boundedLine(it)) }
                if (offer.length() != 3 || offer.getInt("version") != 1 ||
                    !tokenHex.matches(offer.getString("token")) ||
                    !Instant.parse(offer.getString("expires_at")).isAfter(Instant.now())) {
                    error("R1 grant invalid or expired")
                }
                grant = offer
                break
            } catch (e: java.io.IOException) {
                // No pending offer / SELinux denial / not listening: remain denied.
            }
            Thread.sleep(500L)
        }
        val approved = grant ?: error("R1 grant unavailable; operator approval or socket access required")
        val request = JSONObject()
            .put("version", 1).put("token", approved.getString("token"))
            .put("operation", operation).put("remote", remote).put("path", path)
        val raw = request.toString().toByteArray(Charsets.UTF_8)
        require(raw.size <= 16 * 1024) { "R1 request oversized" }
        return connect(READ).use { socket ->
            socket.outputStream.write(raw)
            socket.shutdownOutput()
            val response = JSONObject(boundedLine(socket))
            require(response.getInt("version") == 1 && response.getBoolean("ok") && !response.has("error")) {
                "Skybind denied the read-only request"
            }
            response.get("result")
        }
    }
}
