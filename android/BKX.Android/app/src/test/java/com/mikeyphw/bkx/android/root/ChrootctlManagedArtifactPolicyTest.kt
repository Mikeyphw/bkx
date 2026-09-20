package com.mikeyphw.bkx.android.root

import java.nio.file.Files
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ChrootctlManagedArtifactPolicyTest {
    @Test
    fun `accepts only exact registered chroot export beneath managed root`() {
        val root = Files.createTempDirectory("bkx-chrootctl-managed-").toFile()
        try {
            val artifact = root.resolve("androidos.tar.zst").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
            val response = registryResponse(root.path, artifact.path, "chroot-export", artifact.length())
            val trusted = ChrootctlManagedArtifactPolicy.resolveExport(response, artifact.path)
            assertEquals(artifact.canonicalPath, trusted.path)
            assertEquals(artifact.length(), trusted.sizeBytes)
            assertEquals("artifact-1", trusted.artifactId)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `rejects another managed artifact kind even when path and size match`() {
        val root = Files.createTempDirectory("bkx-chrootctl-managed-").toFile()
        try {
            val artifact = root.resolve("support.tar.gz").apply { writeBytes(byteArrayOf(1)) }
            val response = registryResponse(root.path, artifact.path, "support-bundle", artifact.length())
            assertThrows(IllegalArgumentException::class.java) {
                ChrootctlManagedArtifactPolicy.resolveExport(response, artifact.path)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `rejects requested paths not registered beneath the managed root`() {
        val root = Files.createTempDirectory("bkx-chrootctl-managed-").toFile()
        val outside = Files.createTempFile("bkx-outside-", ".tar.zst").toFile()
        try {
            outside.writeBytes(byteArrayOf(1, 2))
            val response = registryResponse(root.path, root.resolve("other.tar.zst").path, "chroot-export", 2)
            assertThrows(IllegalArgumentException::class.java) {
                ChrootctlManagedArtifactPolicy.resolveExport(response, outside.path)
            }
        } finally {
            outside.delete()
            root.deleteRecursively()
        }
    }

    private fun registryResponse(root: String, path: String, kind: String, size: Long): String = JSONObject()
        .put("protocolVersion", 1)
        .put("requestId", "registry-1")
        .put("ok", true)
        .put(
            "data",
            JSONObject()
                .put("managedRoot", root)
                .put(
                    "items",
                    JSONArray().put(
                        JSONObject()
                            .put("artifactId", "artifact-1")
                            .put("path", path)
                            .put("kind", kind)
                            .put("sizeBytes", size)
                            .put("managed", true),
                    ),
                ),
        )
        .put("warnings", JSONArray())
        .put("nextSteps", JSONArray())
        .toString()
}
