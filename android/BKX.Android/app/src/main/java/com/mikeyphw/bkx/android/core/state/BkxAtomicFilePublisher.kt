package com.mikeyphw.bkx.android.core.state

import android.os.Process
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Durable sibling-temp publication for Android-owned BKX control-plane files.
 *
 * Direct overwrite after a failed rename is deliberately forbidden: request,
 * result, cancel, marker and managed-config documents must either publish as
 * a complete file or fail before another process observes a torn document.
 */
object BkxAtomicFilePublisher {
    fun publishText(target: File, text: String) = publishBytes(target, text.toByteArray(Charsets.UTF_8))

    fun publishBytes(target: File, bytes: ByteArray) {
        val parent = target.parentFile ?: throw IOException("BKX atomic target has no parent: ${target.absolutePath}")
        if (!parent.exists() && !parent.mkdirs()) {
            throw IOException("Could not create BKX atomic directory: ${parent.absolutePath}")
        }
        val temp = File(parent, ".${target.name}.tmp-${Process.myPid()}-${Process.myTid()}-${System.nanoTime().toString(16)}")
        try {
            FileOutputStream(temp).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            if (!temp.renameTo(target)) {
                throw IOException("Could not atomically publish ${target.absolutePath}")
            }
        } finally {
            if (temp.exists()) temp.delete()
        }
    }
}
