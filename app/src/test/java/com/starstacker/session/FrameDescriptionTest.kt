package com.starstacker.session

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Locale

/**
 * T-3.16. The point of the description is that a frame separated from `session.json` can still
 * say what it is, so the tests are about what survives that separation rather than about format.
 */
class FrameDescriptionTest {

    private fun light(index: Int = 7) = FrameDescription.of(
        sessionId = "2026-08-18_0050_session",
        index = index,
        kind = FrameKind.LIGHT,
        iso = 1600,
        exposureNs = 7_399_339_854L,
        capturedAtEpochMs = 1_787_003_420_000L,
        temperatureC = 26.0,
        thermalHeadroom = 0.526,
        batteryPercent = 87,
        focusDiopters = 0.1216f,
    )

    @Test
    fun `a frame names its session, its number and what was asked of the sensor`() {
        val text = light()

        assertTrue(text.startsWith(FrameDescription.MARKER), text)
        assertTrue(text.contains("session=2026-08-18_0050_session"), text)
        assertTrue(text.contains("frame=7"), text)
        assertTrue(text.contains("iso=1600"), text)
        assertTrue(text.contains("exposure=7.3993s"), text)
    }

    /**
     * The one that matters most: `lights/` and `darks/` convey frame kind by directory, and a
     * directory survives exactly as long as nobody moves a file.
     */
    @Test
    fun `a dark is distinguishable from a light by the text alone`() {
        val dark = FrameDescription.of(
            sessionId = "s", index = 1, kind = FrameKind.DARK,
            iso = 1600, exposureNs = 7_399_339_854L, capturedAtEpochMs = 0L,
        )

        assertTrue(dark.contains("kind=DARK"), dark)
        assertTrue(light().contains("kind=LIGHT"), light())
    }

    @Test
    fun `it is one line, because a multi-line tag breaks the tools that read it`() {
        assertEquals(1, light().lines().size)
    }

    /** Absent readings are omitted rather than written as "null", which parses as a value. */
    @Test
    fun `unmeasured values are left out entirely`() {
        val sparse = FrameDescription.of(
            sessionId = "s", index = 1, kind = FrameKind.LIGHT,
            iso = 800, exposureNs = 1_000_000_000L, capturedAtEpochMs = 0L,
        )

        assertFalse(sparse.contains("null"), sparse)
        assertFalse(sparse.contains("batteryTempC"), sparse)
        assertTrue(sparse.contains("iso=800"), sparse)
    }

    /**
     * A comma-decimal locale would emit `exposure=7,3993s` and split the field for anything
     * parsing on separators — the kind of bug that only appears on someone else's phone.
     */
    @Test
    fun `numbers use a decimal point regardless of the device locale`() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertTrue(light().contains("exposure=7.3993s"), light())
        } finally {
            Locale.setDefault(original)
        }
    }
}
