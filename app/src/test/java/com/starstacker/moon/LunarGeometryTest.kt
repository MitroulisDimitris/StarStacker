package com.starstacker.moon

import com.starstacker.device.CameraProfile
import com.starstacker.device.Discovery
import com.starstacker.device.HardwareLevel
import com.starstacker.device.SizeMm
import com.starstacker.device.SizePx
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * T-11.2 — the camera report.
 *
 * The reference device's cameras are used as worked examples, with the **measured** sensor sizes
 * and focal lengths `CameraPickerTest` already records, because §1.45 states the plate scales they
 * produce. A disagreement here means either the code or the plan is wrong.
 *
 * **They are examples and not expectations.** The whole point of the task is that nothing is
 * hard-coded, so there are also tests for a one-camera phone, for a phone whose tele is worse than
 * its main sensor, and for a device that cannot expose the moon at all.
 */
class LunarGeometryTest {

    private fun camera(
        id: String,
        focalMm: Float,
        apertureF: Float,
        sensorMm: SizeMm,
        rawSize: SizePx,
        isoMin: Int? = 50,
        exposureMinNs: Long? = 42_000L,
    ) = CameraProfile(
        id = id,
        exposed = true,
        discovery = Discovery.LISTED,
        logicalParentIds = emptyList(),
        physicalChildIds = emptyList(),
        facing = "BACK",
        hardwareLevel = HardwareLevel.LEVEL_3,
        capabilities = listOf("BACKWARD_COMPATIBLE", "MANUAL_SENSOR", "RAW"),
        hasRawCapability = true,
        hasManualSensor = true,
        hasUltraHighResolutionSensor = false,
        pixelArray = rawSize,
        activeArray = rawSize,
        physicalSizeMm = sensorMm,
        rawSizes = listOf(rawSize),
        binningFactor = SizePx(2, 2),
        focalLengthsMm = listOf(focalMm),
        aperturesF = listOf(apertureF),
        isoMin = isoMin,
        isoMax = 12800,
        exposureMinNs = exposureMinNs,
        exposureMaxNs = 34_900_000_000L,
        maxFrameDurationNs = 34_900_000_000L,
        cfaArrangement = "GRBG",
        whiteLevel = 1023,
        blackLevelPattern = listOf(64, 64, 64, 64),
        noiseProfile = null,
        timestampSource = "REALTIME",
        focusDistanceCalibration = "APPROXIMATE",
        minimumFocusDistanceDiopters = 10f,
        hyperfocalDistanceDiopters = 0.2f,
        afAvailableModes = listOf(0, 1),
        oisModes = listOf(0, 1),
        eisModes = listOf(0, 1),
        mandatoryStreamCombinations = emptyList(),
    )

    private fun main() = camera("0", 5.56f, 1.88f, SizeMm(8.192f, 6.144f), SizePx(4096, 3072))
    private fun ultrawide() = camera("2", 1.64f, 2.2f, SizeMm(3.67f, 2.76f), SizePx(3280, 2464))
    private fun tele() = camera("3", 13.30f, 2.55f, SizeMm(6.55f, 4.92f), SizePx(4096, 3072))

    @Test
    fun `arcsec per pixel follows the small-angle formula`() {
        // 206265 x 0.002 mm / 5.56 mm = 74.2 arcsec/px, which is the plan's figure for the main.
        val value = checkNotNull(LunarGeometry.arcsecPerPixel(8.192 / 4096, 5.56f))
        assertEquals(74.2, value, 0.1)
    }

    /** The three plate scales §1.45 tabulates, recomputed from the measured profile. */
    @Test
    fun `the reference device reproduces the plan's plate scales`() {
        val report = LunarGeometry.report(listOf(main(), ultrawide(), tele()))

        assertEquals(74.2, checkNotNull(report.optionFor("0")).arcsecPerPixel, 0.2)
        // NOT the 112.8 that §1.45's table first carried. The plan's own device table (§2) records
        // this sensor as 3.67 mm over 3280 px = 1.12 um, which gives 140.7 — so the table
        // contradicted the measurement it was supposedly derived from. Corrected 2026-09-08.
        assertEquals(140.7, checkNotNull(report.optionFor("2")).arcsecPerPixel, 0.5)
        assertEquals(24.8, checkNotNull(report.optionFor("3")).arcsecPerPixel, 0.2)
    }

    @Test
    fun `the moon's size in pixels is its angular size over the plate scale`() {
        assertEquals(75, LunarGeometry.moonPixelsAcross(24.8))
        assertEquals(13, LunarGeometry.moonPixelsAcross(140.7))
    }

    @Test
    fun `the plan's 75 px against 25 px falls out of the measured profile`() {
        val report = LunarGeometry.report(listOf(main(), ultrawide(), tele()))
        assertEquals(75, checkNotNull(report.optionFor("3")).moonPixelsAcross)
        assertEquals(25, checkNotNull(report.optionFor("0")).moonPixelsAcross)
        assertEquals(13, checkNotNull(report.optionFor("2")).moonPixelsAcross)
    }

    @Test
    fun `the largest disc is recommended, with the number as the reason`() {
        val report = LunarGeometry.report(listOf(main(), ultrawide(), tele()))

        assertEquals("3", report.recommendedId)
        // FR-11.3 wants the *number* as the reason, not a bare recommendation.
        assertTrue(report.reason.contains("75"), "the reason must carry the count: ${report.reason}")
        // Sorted largest first, so the UI renders the list as it comes.
        assertEquals(listOf("3", "0", "2"), report.options.map { it.id })
    }

    /**
     * The architectural rule as a test: nothing may assume a phone has three cameras, or that its
     * longest lens is its best one.
     */
    @Test
    fun `a single-camera phone gets a recommendation rather than an error`() {
        val report = LunarGeometry.report(listOf(main()))
        assertEquals("0", report.recommendedId)
        // There *is* a warning, and it is the right one: 25 px is a small moon and the UI should
        // say so. What there must not be is a "cannot expose" warning, because this camera can.
        assertFalse(
            checkNotNull(report.warning).contains("cannot expose"),
            "a camera that can expose the moon must not be warned against: ${report.warning}",
        )
    }

    @Test
    fun `a phone whose tele is worse than its main sensor recommends the main sensor`() {
        // Longer lens, but on a small coarse sensor: 3.0 um pitch against 0.8 um.
        val fineMain = camera("0", 6.0f, 1.8f, SizeMm(6.4f, 4.8f), SizePx(8000, 6000))
        val coarseTele = camera("3", 12.0f, 2.4f, SizeMm(6.0f, 4.5f), SizePx(2000, 1500))

        val report = LunarGeometry.report(listOf(fineMain, coarseTele))
        assertEquals("0", report.recommendedId, "the better plate scale wins, not the longer lens")
    }

    /** "Refuse to fail quietly": a camera that cannot expose the moon must say so. */
    @Test
    fun `a camera whose shortest exposure still clips is excluded and named`() {
        // f/1.8 at ISO 50 wants (1.8/11)^2 / 50 = 0.536 ms; a 5 ms floor is ten times too long.
        val big = camera("0", 30f, 1.8f, SizeMm(8.192f, 6.144f), SizePx(4096, 3072), exposureMinNs = 5_000_000L)
        val small = camera("1", 5f, 1.8f, SizeMm(8.192f, 6.144f), SizePx(4096, 3072), exposureMinNs = 40_000L)

        val report = LunarGeometry.report(listOf(big, small))
        assertFalse(checkNotNull(report.optionFor("0")).canExposeMoon)
        assertTrue(checkNotNull(report.optionFor("1")).canExposeMoon)

        // The bigger disc must NOT be recommended if it would come back as a white blob.
        assertEquals("1", report.recommendedId, "a white blob is not a recommendation")

        val note = checkNotNull(report.noteFor("0"))
        assertTrue(note.contains("clipping"), note)
        assertTrue(note.contains("1"), "the note must name a camera that works: $note")
    }

    @Test
    fun `the reference tele can expose the moon, as the plan says`() {
        // 0.102 ms floor against (2.55/11)^2 / 50 = 1.07 ms wanted at ISO 50 — comfortable.
        val option = checkNotNull(LunarGeometry.report(listOf(tele())).optionFor("3"))
        assertTrue(option.canExposeMoon)
        assertTrue(
            checkNotNull(option.wantedExposureNs) > checkNotNull(option.minExposureNs),
            "wanted ${option.wantedExposureNs} ns should exceed the floor ${option.minExposureNs} ns",
        )
    }

    @Test
    fun `a device where nothing can expose the moon warns rather than pretending`() {
        val slow = camera("0", 10f, 1.8f, SizeMm(8.192f, 6.144f), SizePx(4096, 3072), exposureMinNs = 5_000_000L)
        val report = LunarGeometry.report(listOf(slow))

        val warning = checkNotNull(report.warning)
        assertTrue(warning.contains("No camera"), warning)
        assertNotNull(report.recommendedId, "still name the best available, alongside the warning")
    }

    @Test
    fun `a small disc is called out before the shoot rather than discovered after`() {
        // The reference device's best is 75 px, which is below the honesty threshold.
        val report = LunarGeometry.report(listOf(main(), ultrawide(), tele()))
        val warning = checkNotNull(report.warning)
        assertTrue(warning.contains("75"), warning)
    }

    @Test
    fun `a camera with no physical size is left out rather than guessed at`() {
        val unknown = main().copy(id = "9", physicalSizeMm = null)
        val report = LunarGeometry.report(listOf(unknown, tele()))
        assertEquals(listOf("3"), report.options.map { it.id })
    }

    @Test
    fun `a device that reports nothing usable says so instead of recommending`() {
        val report = LunarGeometry.report(listOf(main().copy(focalLengthsMm = emptyList())))
        assertTrue(report.options.isEmpty())
        assertNull(report.recommendedId)
        assertNotNull(report.warning)
    }

    @Test
    fun `choosing a wider camera than recommended is allowed and explained`() {
        val report = LunarGeometry.report(listOf(main(), tele()))
        // Picking the wide one is a legitimate framing choice, so the note explains rather than
        // objects — the app offers tools and states consequences.
        val note = checkNotNull(report.noteFor("0"))
        assertTrue(note.contains("75") && note.contains("25"), note)
        assertNull(report.noteFor("3"), "no note against the recommended camera")
    }
}
