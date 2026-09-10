package com.mikeyphw.bkx.android.automation

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskerSharedCatalogTest {
    @Test
    fun readsSharedNonSecretJobAndRepositoryCatalog() {
        val root = Files.createTempDirectory("bkx-tasker-catalog").toFile()
        root.resolve("jobs").mkdirs()
        root.resolve("repositories").mkdirs()
        root.resolve("jobs/jobs.json").writeText(
            """{
              "schema_version":2,
              "jobs":[{
                "schema_version":2,
                "job_id":"phone-backup",
                "name":"Phone backup",
                "description":"",
                "tags":[],
                "revision":1,
                "enabled":true,
                "operation":{"schema_version":2,"operation":"backup.snapshot","parameters":{},"repository_ref":"phone-drive","credential_refs":[]},
                "created_at":"2026-09-10T00:00:00Z",
                "updated_at":"2026-09-10T00:00:00Z"
              }],
              "revisions":{}
            }""".trimIndent(),
        )
        root.resolve("repositories/repositories.json").writeText(
            """{
              "schema_version":1,
              "repositories":[{
                "schema_version":1,
                "repository_id":"phone-drive",
                "name":"Phone Drive",
                "engine":"restic",
                "backend":"rclone",
                "location":"rclone://drive/BKX/restic/phone",
                "credential_ref":"phone-restic-password",
                "managed_by_bkx":false,
                "created_at":"2026-09-10T00:00:00Z",
                "updated_at":"2026-09-10T00:00:00Z",
                "retention":null
              }]
            }""".trimIndent(),
        )

        val catalog = BkxTaskerSharedCatalog.read(root)
        assertEquals(listOf("phone-backup"), catalog.jobs.map { it.jobId })
        assertEquals(listOf("phone-drive"), catalog.repositories.map { it.repositoryId })
        assertEquals("phone-restic-password", catalog.repositories.single().credentialRef)
        assertTrue(root.resolve("jobs/jobs.json").readText().contains("credential_refs"))
        assertTrue(root.resolve("repositories/repositories.json").readText().contains("credential_ref"))
    }

    @Test
    fun missingOrMalformedCatalogFailsClosedToEmptyLists() {
        val root = Files.createTempDirectory("bkx-tasker-catalog-empty").toFile()
        root.resolve("jobs").mkdirs()
        root.resolve("jobs/jobs.json").writeText("not-json")

        val catalog = BkxTaskerSharedCatalog.read(root)
        assertTrue(catalog.jobs.isEmpty())
        assertTrue(catalog.repositories.isEmpty())
    }
}
