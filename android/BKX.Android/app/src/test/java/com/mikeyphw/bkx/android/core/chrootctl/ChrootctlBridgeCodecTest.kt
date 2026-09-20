package com.mikeyphw.bkx.android.core.chrootctl

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ChrootctlBridgeCodecTest {
    @Test
    fun `request codec emits protocol-v1 camelCase envelope and enforces operation fields`() {
        val request = ChrootctlBridgeCodec.request(
            requestId = "bkx-cct02-1",
            operation = ChrootctlOperation.ChrootsGet,
            payload = ChrootctlBridgeCodec.chrootNamePayload("androidos"),
        )
        val encoded = ChrootctlBridgeCodec.encodeRequest(request)
        assertTrue("\"protocolVersion\":1" in encoded)
        assertTrue("\"requestId\":\"bkx-cct02-1\"" in encoded)
        assertTrue("\"operation\":\"chroots.get\"" in encoded)

        assertThrows(IllegalArgumentException::class.java) {
            ChrootctlBridgeCodec.request(
                requestId = "bkx-cct02-2",
                operation = ChrootctlOperation.ChrootsGet,
                payload = buildJsonObject { put("shell", "id") },
            )
        }
    }

    @Test
    fun `query response translates chroot records into BKX typed models`() {
        val raw = """{
          "protocolVersion":1,
          "requestId":"bkx-list-1",
          "ok":true,
          "data":[{
            "name":"androidos","distro":"ubuntu","path":"/data/local/chroots/androidos",
            "arch":"arm64","release_name":"24.04","version":"24.04","phase":"ready",
            "installed":true,"created_at":"2026-09-19T00:00:00Z","updated_at":"2026-09-19T00:00:00Z",
            "metadata":{"source":"test"}
          }],
          "warnings":[],"nextSteps":[]
        }"""
        val response = ChrootctlBridgeCodec.decodeResponse(raw, "bkx-list-1")
        val rows = ChrootctlBridgeCodec.chroots(response)
        assertEquals(1, rows.size)
        assertEquals("androidos", rows.single().name)
        assertEquals("24.04", rows.single().releaseName)
        assertTrue(rows.single().installed)
    }

    @Test
    fun `NDJSON events are request-bound and terminal states are explicit`() {
        val raw = """{
          "protocolVersion":1,"type":"operation_succeeded","operationId":"abc123",
          "requestId":"bkx-run-1","timestamp":1.0,"payload":{"result":{"archive":"/tmp/a.tar.zst"}}
        }"""
        val event = ChrootctlBridgeCodec.decodeEvent(raw, "bkx-run-1")
        assertEquals("abc123", event.operationId)
        assertTrue(event.terminal)
        assertThrows(ChrootctlBridgeProtocolException::class.java) {
            ChrootctlBridgeCodec.decodeEvent(raw, "other-request")
        }
    }

    @Test
    fun `preview contract can be rebound to the matching operation request`() {
        val raw = """{
          "protocolVersion":1,"requestId":"bkx-preview-1","ok":true,
          "data":{"ok":true,"previewId":"p1","payloadHash":"h1","resourceRevision":"r1","actionOperation":"transfer.export"},
          "warnings":[],"nextSteps":[]
        }"""
        val contract = ChrootctlBridgeCodec.previewContract(
            ChrootctlBridgeCodec.decodeResponse(raw, "bkx-preview-1"),
        )
        val bound = ChrootctlBridgeCodec.bindPreview(
            ChrootctlBridgeCodec.exportPayload("androidos"),
            contract,
        )
        val request = ChrootctlBridgeCodec.request("bkx-run-2", ChrootctlOperation.Export, bound)
        assertEquals("p1", request.payload["previewId"]?.toString()?.trim('"'))
        assertEquals("h1", request.payload["payloadHash"]?.toString()?.trim('"'))
        assertEquals("r1", request.payload["resourceRevision"]?.toString()?.trim('"'))
    }

    @Test
    fun `bridge operation allowlist exposes no generic shell or command operation`() {
        val names = ChrootctlOperation.entries.map { it.wireName }.toSet()
        assertEquals(
            setOf(
                "chroots.list",
                "chroots.get",
                "transfer.preview_export",
                "transfer.import_preview",
                "transfer.export",
                "transfer.import",
            ),
            names,
        )
        assertFalse(names.any { "shell" in it || "exec" in it || "command" in it })
    }
}
