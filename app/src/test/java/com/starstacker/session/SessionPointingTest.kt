package com.starstacker.session

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * FR-9.2's pointing half. The declination is the only input to the sub length that leaves no
 * trace in the pixels, so a log that drops it cannot be audited — the 2026-08-18 diagnosis had to
 * infer "no fix" from the exposure matching cos δ = 1 exactly.
 */
class SessionPointingTest {

    private fun info(pointing: SessionPointing?) = SessionInfo(
        sessionId = "s1",
        startedAtEpochMs = 1_755_000_000_000L,
        deviceModel = "A059P",
        cameraId = "0",
        plannedIso = 1600,
        plannedExposureNs = 7_399_339_854L,
        plannedLightCount = 105,
        plannedDarkCount = 16,
        latitudeDeg = pointing?.latitudeDeg,
        longitudeDeg = pointing?.longitudeDeg,
        altitudeDeg = pointing?.altitudeDeg,
        azimuthDeg = pointing?.azimuthTrueDeg,
        declinationDeg = pointing?.declinationDeg,
        fieldRotationArcsecPerSec = pointing?.fieldRotationArcsecPerSec,
        compassAccuracy = pointing?.compassAccuracy,
    )

    private val fix = SessionPointing(
        latitudeDeg = 51.5,
        longitudeDeg = -0.12,
        altitudeDeg = 47.2,
        azimuthTrueDeg = 183.4,
        declinationDeg = -8.6,
        fieldRotationArcsecPerSec = 12.7,
        compassAccuracy = "HIGH",
    )

    @Test
    fun `a pointing fix survives the round trip through session json`() {
        val log = SessionLog(info(fix))

        val back = SessionLog.decode(log.encode()).info

        assertEquals(51.5, back.latitudeDeg)
        assertEquals(-0.12, back.longitudeDeg)
        assertEquals(47.2, back.altitudeDeg)
        assertEquals(183.4, back.azimuthDeg)
        assertEquals(-8.6, back.declinationDeg)
        assertEquals(12.7, back.fieldRotationArcsecPerSec)
        assertEquals("HIGH", back.compassAccuracy)
    }

    /**
     * The accuracy is what makes the declination judgeable later. A metal tripod head beside the
     * magnetometer is an ordinary way to get a confident-looking, wrong azimuth.
     */
    @Test
    fun `compass accuracy is recorded alongside the declination it produced`() {
        val unreliable = SessionLog(info(fix.copy(compassAccuracy = "UNRELIABLE")))

        val back = SessionLog.decode(unreliable.encode()).info

        assertEquals("UNRELIABLE", back.compassAccuracy)
        assertEquals(-8.6, back.declinationDeg)
    }

    /** No fix is a real state, and must read back as absent rather than as zero. */
    @Test
    fun `no fix round trips as null rather than as the equator`() {
        val log = SessionLog(info(null))

        val back = SessionLog.decode(log.encode()).info

        assertNull(back.declinationDeg)
        assertNull(back.latitudeDeg)
        assertNull(back.compassAccuracy)
    }

    @Test
    fun `an all-null fix is reported empty so the log records absence, not six nulls`() {
        assertTrue(SessionPointing().isEmpty)
        assertFalse(fix.isEmpty)
        assertFalse(SessionPointing(compassAccuracy = "LOW").isEmpty)
    }
}
