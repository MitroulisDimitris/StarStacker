package com.starstacker.json

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The writer already shipped with the device profile; the reader is new, and it is the half that
 * has to survive contact with files the app did not write — `session.json` copied back from a PC
 * (FR-10.6.4), hand-edited, or truncated by a session that died mid-write.
 */
class JsonTest {

    @Test
    fun `what is written can be read back`() {
        val original = linkedMapOf<String, Any?>(
            "schemaVersion" to 1,
            "cameras" to listOf(
                linkedMapOf<String, Any?>(
                    "cameraId" to "0",
                    "diopters" to 0.0525,
                    "fixedFocus" to false,
                    "altitudeDeg" to null,
                ),
            ),
        )

        val round = Json.parseObject(Json.write(original))

        assertEquals(1, round.int("schemaVersion"))
        val camera = round.objects("cameras").single()
        assertEquals("0", camera.string("cameraId"))
        assertEquals(0.0525, camera.double("diopters")!!, 1e-12)
        assertEquals(false, camera.boolean("fixedFocus"))
        assertNull(camera.double("altitudeDeg"))
    }

    @Test
    fun `escapes survive the round trip`() {
        val text = "quote \" backslash \\ newline \n tab \t unicode µm"
        val round = Json.parseObject(Json.write(mapOf("s" to text)))
        assertEquals(text, round.string("s"))
    }

    @Test
    fun `numbers keep enough precision for a focus position`() {
        val map = Json.parseObject(Json.write(mapOf("d" to 0.123456789)))
        assertEquals(0.123456789, map.double("d")!!, 1e-15)

        val negative = Json.parseObject(Json.write(mapOf("d" to -1.5e-7)))
        assertEquals(-1.5e-7, negative.double("d")!!, 1e-20)
    }

    @Test
    fun `non-finite numbers are written as null rather than as invalid JSON`() {
        val encoded = Json.write(mapOf("a" to Double.NaN, "b" to Double.POSITIVE_INFINITY))
        assertTrue(encoded.contains("null"), encoded)
        assertNull(Json.parseObject(encoded).double("a"))
    }

    @Test
    fun `empty containers survive`() {
        assertEquals("{}", Json.write(emptyMap<String, Any?>()))
        assertEquals("[]", Json.write(emptyList<Any?>()))
        assertTrue(Json.parseObject("{}").isEmpty())
        assertTrue((Json.parse("[]") as List<*>).isEmpty())
    }

    @Test
    fun `whitespace between tokens is ignored`() {
        val parsed = Json.parseObject("  {\n \"a\" : [ 1 , 2 ] ,\n \"b\" : true\n}\n")
        assertEquals(2, (parsed["a"] as List<*>).size)
        assertEquals(true, parsed.boolean("b"))
    }

    @Test
    fun `a truncated file fails loudly rather than returning half a record`() {
        assertThrows(Json.JsonException::class.java) {
            Json.parse("""{"cameras": [{"cameraId": "0", """)
        }
        assertThrows(Json.JsonException::class.java) { Json.parse("""{"a": 1} trailing""") }
        assertThrows(Json.JsonException::class.java) { Json.parse("") }
    }

    @Test
    fun `nested structures keep their shape`() {
        val source = mapOf(
            "outer" to listOf(
                mapOf("inner" to listOf(1, 2, 3)),
                mapOf("inner" to emptyList<Int>()),
            ),
        )
        val round = Json.parseObject(Json.write(source))
        val outer = round.objects("outer")
        assertEquals(2, outer.size)
        assertEquals(3, (outer[0]["inner"] as List<*>).size)
        assertEquals(0, (outer[1]["inner"] as List<*>).size)
    }
}
