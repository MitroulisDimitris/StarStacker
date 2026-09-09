package com.starstacker.moon

import com.starstacker.session.ExposureSet
import com.starstacker.session.FrameKind
import com.starstacker.session.FrameRecord
import com.starstacker.session.SessionInfo
import com.starstacker.session.SessionLog
import com.starstacker.session.TargetType
import com.starstacker.stacking.TiledStacker
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * T-11.9's bookkeeping — the parts of the two-master flow that can be checked without a camera.
 *
 * The stacking itself is `StackJob`'s and already covered; what is new here is that the two halves
 * are *kept apart correctly*, which is the thing a single wrong filter would silently ruin.
 */
class BracketedStackTest {

    private fun frame(index: Int, set: ExposureSet, epochMs: Long) = FrameRecord(
        index = index,
        fileName = "light_%03d.dng".format(index),
        kind = FrameKind.LIGHT,
        capturedAtEpochMs = epochMs,
        iso = 400,
        exposureNs = if (set == ExposureSet.GROUND) 2_500_000_000L else 130_000L,
        temperatureC = null,
        hfr = null,
        starCount = null,
        eccentricity = null,
        backgroundAdu = null,
        exposureSet = set,
        accepted = true,
    )

    private fun log(vararg frames: FrameRecord) = SessionLog(
        info = SessionInfo(
            sessionId = "test",
            startedAtEpochMs = 0L,
            deviceModel = "test",
            cameraId = "0",
            plannedIso = 400,
            plannedExposureNs = 130_000L,
            plannedLightCount = 2,
            plannedDarkCount = 0,
            targetType = TargetType.MOON_SCENE,
            groundIso = 400,
            groundExposureNs = 2_500_000_000L,
            groundLightCount = 2,
        ),
        frames = frames.toList(),
    )

    // ----------------------------------------------------------------- the record round-trips

    @Test
    fun `the target type and both exposures survive session_json`() {
        val original = log(
            frame(1, ExposureSet.MOON, 1000L),
            frame(2, ExposureSet.GROUND, 2000L),
        )
        val decoded = SessionLog.decode(original.encode())

        assertEquals(TargetType.MOON_SCENE, decoded.info.targetType)
        assertEquals(2_500_000_000L, decoded.info.groundExposureNs)
        assertEquals(400, decoded.info.groundIso)
        assertEquals(2, decoded.info.groundLightCount)
    }

    @Test
    fun `each frame remembers which set it belongs to`() {
        val original = log(
            frame(1, ExposureSet.MOON, 1000L),
            frame(2, ExposureSet.GROUND, 2000L),
        )
        val decoded = SessionLog.decode(original.encode())

        assertEquals(ExposureSet.MOON, decoded.lights[0].exposureSet)
        assertEquals(ExposureSet.GROUND, decoded.lights[1].exposureSet)
    }

    /**
     * The compatibility clause: every session written before T-11.8 was a deep-sky session, so the
     * default is the truth about them rather than a guess.
     */
    @Test
    fun `a log with no target type reads as deep sky and unbracketed`() {
        val plain = SessionLog(
            info = SessionInfo(
                sessionId = "old", startedAtEpochMs = 0L, deviceModel = "x", cameraId = "0",
                plannedIso = 800, plannedExposureNs = 1_000_000_000L,
                plannedLightCount = 1, plannedDarkCount = 0,
            ),
            frames = listOf(frame(1, ExposureSet.MOON, 0L).copy(exposureSet = null)),
        )
        val decoded = SessionLog.decode(plain.encode())

        assertEquals(TargetType.DEEP_SKY, decoded.info.targetType)
        assertTrue(!decoded.info.targetType.isBracketed)
        assertEquals(null, decoded.lights[0].exposureSet)
    }

    // ----------------------------------------------------------------- the flow's guards

    @Test
    fun `an unbracketed session is declined rather than stacked twice`() {
        val plain = SessionLog(
            info = SessionInfo(
                sessionId = "d", startedAtEpochMs = 0L, deviceModel = "x", cameraId = "0",
                plannedIso = 800, plannedExposureNs = 1_000L,
                plannedLightCount = 1, plannedDarkCount = 0,
            ),
            frames = emptyList(),
        )
        val result = BracketedStack.run(
            File("nowhere"), plain, com.starstacker.stacking.StackSettings(),
            newJob = { error("should not have been asked for a job") },
        )
        assertEquals("not a bracketed session", result.error)
    }

    @Test
    fun `a missing master is refused with a reason rather than a crash`() {
        val result = BracketedStack.composite(
            File("nowhere"), log(), groundMaster = null, moonMaster = File("also-nowhere"),
        )
        assertEquals("a master is missing", result.error)
        assertTrue(!result.succeeded)
    }

    // ----------------------------------------------------------------- luminance

    @Test
    fun `luminance averages the channels and treats NaN as empty`() {
        val data = floatArrayOf(
            0.2f, 0.4f, 0.6f, // mean 0.4
            Float.NaN, Float.NaN, Float.NaN, // uncovered
        )
        val lum = BracketedStack.luminance(data, 2, 1, TiledStacker.CHANNELS)

        assertEquals(0.4, lum[0], 1e-6)
        assertEquals(0.0, lum[1], 1e-9, "NaN must not propagate into the disc search")
    }

    @Test
    fun `a single-channel master is passed through`() {
        val lum = BracketedStack.luminance(floatArrayOf(0.3f, 0.7f), 2, 1, channels = 1)
        assertEquals(0.3, lum[0], 1e-6)
        assertEquals(0.7, lum[1], 1e-6)
    }

    // ----------------------------------------------------------------- naming

    @Test
    fun `the outputs are named apart so two masters can share one folder`() {
        assertNotNull(BracketedStack.COMPOSITE_FILE_NAME)
        assertTrue(BracketedStack.COMPOSITE_FILE_NAME.endsWith(".tif"))
        assertTrue(BracketedStack.LAYER_FILE_NAME.endsWith(".tif"))
        assertTrue(
            BracketedStack.COMPOSITE_FILE_NAME != BracketedStack.LAYER_FILE_NAME,
            "the composite and the layer must not overwrite each other",
        )
    }
}
