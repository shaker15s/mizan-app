package app.mizan.service

import app.mizan.service.json.Json
import app.mizan.service.json.JsonParseException
import app.mizan.service.json.JsonValue
import app.mizan.service.json.asObject
import app.mizan.service.json.field
import app.mizan.service.json.flag
import app.mizan.service.json.text
import app.mizan.service.json.whole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The service parses untrusted bodies. A parser that guesses is a bug. */
class JsonTest {

    @Test
    fun roundTripsAnObject() {
        val original = Json.obj(
            "tenant" to Json.str("sim-alamal"),
            "count" to Json.num(42L),
            "ok" to Json.bool(true),
            "missing" to Json.str(null),
        )
        val parsed = Json.parse(Json.write(original)).asObject()
        assertEquals("sim-alamal", parsed?.text("tenant"))
        assertEquals(42L, parsed?.whole("count"))
        assertEquals(true, parsed?.flag("ok"))
        assertNull(parsed?.text("missing"))
    }

    @Test
    fun escapesControlCharactersAndQuotes() {
        val value = Json.str("line\nbreak \"quoted\" \\ back")
        val text = Json.write(value)
        assertTrue(text.contains("\\n"))
        assertTrue(text.contains("\\\""))
        assertTrue(text.contains("\\\\"))
        assertEquals("line\nbreak \"quoted\" \\ back", Json.parse(text).let { (it as JsonValue.Str).value })
    }

    @Test
    fun parsesNestedArrays() {
        val parsed = Json.parse("""{"items":[1,2,{"name":"x"}],"empty":[],"nil":null}""").asObject()
        val items = parsed?.field("items")?.let { it as JsonValue.Arr }
        assertEquals(3, items?.items?.size)
        assertEquals(0, parsed?.field("empty")?.let { (it as JsonValue.Arr).items.size })
        assertTrue(parsed?.field("nil") is JsonValue.Null)
    }

    @Test
    fun rejectsMalformedInput() {
        val broken = listOf(
            """{"a":}""",
            """{"a" 1}""",
            """[1,2""",
            """{"a":"unterminated}""",
            """{"a":1}{"b":2}""",
            """nope""",
            """{"a":+1}""",
        )
        broken.forEach { text ->
            assertNull("expected a rejection for $text", Json.parseOrNull(text))
        }
    }

    @Test
    fun throwsWithAPositionForDiagnostics() {
        val error = runCatching { Json.parse("""{"a":[1,]}""") }.exceptionOrNull()
        assertTrue(error is JsonParseException)
        assertTrue((error as JsonParseException).message?.contains("at") == true)
    }
}
