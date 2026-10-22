package com.mikeyphw.bkx.android.r1

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.os.Bundle
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedReader
import java.io.EOFException
import java.io.InputStreamReader

/** Real installed BKX process, same SO_PEERCRED and verifier as the app UI.
 * Launched ONLY by explicit Skybind root-operator acceptance-bkx command.
 * All 12 phases are ordered by the attested root coordinator. No sensitive
 * raw responses, token strings, or remote entries are logged or persisted.
 */
@RunWith(AndroidJUnit4::class)
class SkybindR1LiveBrokerAcceptanceTest {
    private val control = "skybind-r1-bkx-device-acceptance-v1"
    private val handoff = "skybind-r1-grant-handoff-v1"
    private val readerSocket = "skybind-r1-consumer-gateway-v1"
    private val order = listOf(
        "no_token", "positive_capabilities", "positive_version", "positive_remotes", "positive_list", "positive_size",
        "wrong_operation", "wrong_remote", "wrong_path", "wrong_process", "revocation", "expiry"
    )

    private fun connect(name: String): LocalSocket = LocalSocket().apply {
        try {
            connect(LocalSocketAddress(name, LocalSocketAddress.Namespace.ABSTRACT))
            soTimeout = 18000
        } catch (e: Exception) {
            close()
            throw e
        }
    }
    private fun line(socket: LocalSocket): JSONObject {
        val line = BufferedReader(InputStreamReader(socket.inputStream, Charsets.UTF_8)).readLine()
            ?: throw EOFException("R1 test socket closed")
        require(line.length in 2..(1 shl 20)) { "R1 response bound" }
        return JSONObject(line)
    }
    private fun request(token: String, operation: String, remote: String, path: String): JSONObject {
        val body = JSONObject().put("version", 1).put("token", token)
            .put("operation", operation).put("remote", remote).put("path", path).toString()
        connect(readerSocket).use { sock ->
            sock.outputStream.write(body.toByteArray(Charsets.UTF_8))
            sock.shutdownOutput()
            return line(sock)
        }
    }
    private fun grant(): String {
        val deadline = SystemClock.elapsedRealtime() + 14000
        while (SystemClock.elapsedRealtime() < deadline) {
            try {
                connect(handoff).use { sock ->
                    val value = line(sock)
                    val token = value.getString("token")
                    require(value.getInt("version") == 1 && Regex("[0-9a-f]{64}").matches(token))
                    return token
                }
            } catch (_: java.io.IOException) {
                Thread.sleep(250)
            }
        }
        error("approved one-process token was not delivered")
    }
    private fun controlMessage(socket: LocalSocket, action: String, case: String? = null, state: String? = null): JSONObject {
        val request = JSONObject().put("action", action)
        if (case != null) request.put("case", case)
        if (state != null) request.put("state", state)
        socket.outputStream.write((request.toString() + "\n").toByteArray(Charsets.UTF_8))
        socket.outputStream.flush()
        return line(socket).also { check(it.getBoolean("ok")) { "R1 operator coordinator denied the phase" } }
    }
    private fun denied(response: JSONObject): Boolean =
        response.optInt("version", -1) == 1 && !response.optBoolean("ok", true) &&
            response.optString("error") == "not_authorized" && !response.has("result")
    private fun positive(name: String, response: JSONObject, remote: String): Boolean {
        if (response.optInt("version", -1) != 1 || !response.optBoolean("ok", false) || response.has("error")) return false
        val result = response.opt("result")
        return when (name) {
            "positive_capabilities" -> result is JSONObject &&
                result.optString("schema") == "skybind-r1-metadata-v1" &&
                result.optInt("version") == 1 && result.optBoolean("read_only") &&
                result.optJSONArray("operations")?.length() == 5
            "positive_version" -> result is JSONObject && result.optString("version").startsWith("rclone v")
            "positive_remotes" -> result is JSONObject && result.optJSONArray("remotes")?.let {
                it.length() == 1 && it.optString(0) == remote
            } == true
            "positive_list" -> result is JSONArray
            "positive_size" -> result is JSONObject && result.has("count") && result.has("bytes") &&
                result.optLong("count", -1) >= 0 && result.optLong("bytes", -1) >= 0
            else -> false
        }
    }
    private fun wrongProcessDenied(token: String, remote: String, path: String): Boolean {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val attached = CountDownLatch(1)
        var worker: Messenger? = null
        val binding = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                worker = if (binder != null) Messenger(binder) else null
                attached.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName?) { attached.countDown() }
        }
        val intent = Intent(ctx, SkybindR1ReplayProbeService::class.java)
        if (!ctx.bindService(intent, binding, Context.BIND_AUTO_CREATE)) return false
        try {
            if (!attached.await(8, TimeUnit.SECONDS)) return false
            val target = worker ?: return false
            val completed = CountDownLatch(1)
            var workerPid = -1
            var denied = false
            val reply = Messenger(Handler(Looper.getMainLooper()) { msg ->
                if (msg.what == 2) {
                    workerPid = msg.arg1
                    denied = msg.arg2 == 1
                    completed.countDown()
                }
                true
            })
            val message = Message.obtain(null, 1).apply {
                data = Bundle().apply {
                    putString("token", token)
                    putString("remote", remote)
                    putString("path", path)
                }
                replyTo = reply
            }
            target.send(message)
            return completed.await(12, TimeUnit.SECONDS) && denied &&
                workerPid > 0 && workerPid != Process.myPid()
        } finally {
            ctx.unbindService(binding)
        }
    }

    private fun operation(name: String) = when (name) {
        "positive_version" -> "rclone.version"
        "positive_remotes" -> "rclone.listremotes"
        "positive_list" -> "rclone.lsjson"
        "positive_size" -> "rclone.size"
        "wrong_operation" -> "rclone.lsjson"
        else -> "system.capabilities"
    }
    @Test fun oneOperatorSessionAllBKXLiveSocketCases() {
        connect(control).use { coordinator ->
            val hello = controlMessage(coordinator, "hello")
            val remote = hello.getString("remote")
            val path = hello.getString("path")
            for (name in order) {
                controlMessage(coordinator, "prepare", name)
                val token = if (name == "no_token") "0".repeat(64) else grant()
                val pass = if (name == "wrong_process") {
                    val otherDenied = wrongProcessDenied(token, remote, path)
                    val approvedStillWorks = request(token, "system.capabilities", remote, path)
                    otherDenied && positive("positive_capabilities", approvedStillWorks, remote)
                } else {
                    val result: JSONObject = when (name) {
                        "wrong_remote" -> request(token, operation(name), remote + "_R1denied", path)
                        "wrong_path" -> request(token, operation(name), remote, "../private")
                        "revocation" -> {
                            controlMessage(coordinator, "revoke", name)
                            request(token, operation(name), remote, path)
                        }
                        "expiry" -> {
                            Thread.sleep(3100L)
                            request(token, operation(name), remote, path)
                        }
                        else -> request(token, operation(name), remote, path)
                    }
                    if (name.startsWith("positive_")) positive(name, result, remote) else denied(result)
                }
                assertTrue("R1 socket case $name rejected or returned unexpected schema", pass)
                controlMessage(coordinator, "result", name, "PASS")
            }
            controlMessage(coordinator, "done")
        }
    }
}
