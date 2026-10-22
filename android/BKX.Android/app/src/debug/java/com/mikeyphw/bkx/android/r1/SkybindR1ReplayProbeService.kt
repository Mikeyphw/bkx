package com.mikeyphw.bkx.android.r1

import android.app.Service
import android.content.Intent
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

/** Debug-only isolated process, exported=false, never available in release.
 * It receives a one-use acceptance token over an app-internal Binder message;
 * it attempts an actual separate-PID R1 socket read. Never persists/logs it.
 */
class SkybindR1ReplayProbeService : Service() {
    private val messenger = Messenger(Handler(Looper.getMainLooper(), Handler.Callback { message ->
        handleReplay(message)
    }))

    private fun handleReplay(msg: Message): Boolean {
        if (msg.what != 1) return false
        val token = msg.data?.getString("token").orEmpty()
        val remote = msg.data?.getString("remote").orEmpty()
        val path = msg.data?.getString("path").orEmpty()
        val recipient = msg.replyTo
        Thread {
            val denied = runCatching { replayDenied(token, remote, path) }.getOrDefault(false)
            val reply = Message.obtain(null, 2).apply {
                arg1 = Process.myPid()
                arg2 = if (denied) 1 else 0
            }
            runCatching { recipient?.send(reply) }
        }.start()
        return true
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    private fun replayDenied(token: String, remote: String, path: String): Boolean {
        if (!Regex("[0-9a-f]{64}").matches(token)) return false
        val data = JSONObject().put("version", 1).put("token", token)
            .put("operation", "system.capabilities").put("remote", remote).put("path", path).toString()
        LocalSocket().use { sock ->
            sock.connect(LocalSocketAddress("skybind-r1-consumer-gateway-v1", LocalSocketAddress.Namespace.ABSTRACT))
            sock.soTimeout = 6000
            sock.outputStream.write(data.toByteArray(Charsets.UTF_8))
            sock.shutdownOutput()
            val line = BufferedReader(InputStreamReader(sock.inputStream, Charsets.UTF_8)).readLine()
                ?: return false
            if (line.length > 4096) return false
            val answer = JSONObject(line)
            return answer.optInt("version") == 1 && !answer.optBoolean("ok", true) &&
                answer.optString("error") == "not_authorized" && !answer.has("result")
        }
    }
}
