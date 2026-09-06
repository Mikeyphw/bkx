package com.mikeyphw.bkx.android.embedded

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GoNativeSessionWireTest {
    @Test
    fun commandCarriesCorrelatedExecuteRequestWithoutShellText() {
        val raw = GoNativeSessionWire.command(
            id = "android-7",
            entrypoint = "execute",
            requestJson = """{"protocol_version":1,"request_id":"req-7","operation":"storage.locations","payload":{}}""",
        )
        val root = kotlinx.serialization.json.Json.parseToJsonElement(raw).jsonObject

        assertEquals("android-7", root.getValue("id").jsonPrimitive.content)
        assertEquals("execute", root.getValue("entrypoint").jsonPrimitive.content)
        assertEquals("req-7", root.getValue("request").jsonObject.getValue("request_id").jsonPrimitive.content)
        assertTrue("command" !in root)
    }

    @Test
    fun replyRequiresExactCorrelationAndReturnsValueDocument() {
        val value = GoNativeSessionWire.reply(
            """{"id":"android-8","value":{"protocol_version":1,"runtime":"go-native-android"}}""",
            "android-8",
        )

        assertEquals("go-native-android", value.jsonObject.getValue("runtime").jsonPrimitive.content)
    }

    @Test(expected = IllegalArgumentException::class)
    fun replyRejectsUnexpectedFields() {
        GoNativeSessionWire.reply(
            """{"id":"android-9","value":null,"extra":true}""",
            "android-9",
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun replyRejectsMismatchedCorrelationId() {
        GoNativeSessionWire.reply("""{"id":"wrong","value":null}""", "android-10")
    }
}
