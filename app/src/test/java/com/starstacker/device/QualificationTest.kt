package com.starstacker.device

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * T-1.2 acceptance: a forced-fail test for each of the four hard disqualifiers in FR-3.1,
 * plus the quad-Bayer pitch derivation that OI-17 warns about.
 */
class QualificationTest {

    /**
     * A plausible modern phone main camera: 50 MP quad-Bayer sensor delivering a binned
     * 12.5 MP RAW frame. Every test below mutates one field of this.
     */
    private fun goodCamera(
        hardwareLevel: HardwareLevel = HardwareLevel.FULL,
        capabilities: List<String> = listOf("BACKWARD_COMPATIBLE", "MANUAL_SENSOR", "RAW"),
        exposureMaxNs: Long? = 30_000_000_000L,
        rawSizes: List<SizePx> = listOf(SizePx(4080, 3060)),
        pixelArray: SizePx? = SizePx(8160, 6120),
        afAvailableModes: List<Int> = listOf(0, 1, 2, 3, 4),
        minimumFocusDistanceDiopters: Float? = 10f,
    ) = CameraProfile(
        id = "0",
        exposed = true,
        discovery = Discovery.LISTED,
        logicalParentIds = emptyList(),
        physicalChildIds = emptyList(),
        facing = "BACK",
        hardwareLevel = hardwareLevel,
        capabilities = capabilities,
        hasRawCapability = capabilities.contains("RAW"),
        hasManualSensor = capabilities.contains("MANUAL_SENSOR"),
        hasUltraHighResolutionSensor = false,
        pixelArray = pixelArray,
        activeArray = pixelArray,
        physicalSizeMm = SizeMm(7.36f, 5.52f),
        rawSizes = rawSizes,
        binningFactor = SizePx(2, 2),
        focalLengthsMm = listOf(6.9f),
        aperturesF = listOf(1.88f),
        isoMin = 50,
        isoMax = 6400,
        exposureMinNs = 20_000L,
        exposureMaxNs = exposureMaxNs,
        maxFrameDurationNs = 30_000_000_000L,
        cfaArrangement = "RGGB",
        whiteLevel = 1023,
        blackLevelPattern = listOf(64, 64, 64, 64),
        noiseProfile = null,
        timestampSource = "REALTIME",
        focusDistanceCalibration = "APPROXIMATE",
        minimumFocusDistanceDiopters = minimumFocusDistanceDiopters,
        hyperfocalDistanceDiopters = 0.2f,
        afAvailableModes = afAvailableModes,
        oisModes = listOf(0, 1),
        eisModes = listOf(0, 1),
        mandatoryStreamCombinations = emptyList(),
    )

    // ---- the four hard disqualifiers ----------------------------------------------

    @Test
    fun `a capable camera qualifies at Functional tier`() {
        val q = Qualification.qualify(goodCamera())
        // Not FULL: calibration does not exist yet, and FR-3.1.1 says Functional must be enough.
        assertEquals(Tier.FUNCTIONAL, q.tier)
        assertTrue(q.usable)
        assertEquals(null, q.blockingReason)
    }

    @Test
    fun `LEGACY hardware level is unsupported and says so`() {
        val q = Qualification.qualify(goodCamera(hardwareLevel = HardwareLevel.LEGACY))
        assertEquals(Tier.UNSUPPORTED, q.tier)
        assertTrue(q.blockingReason!!.contains("LEGACY"), q.blockingReason)
    }

    @Test
    fun `missing RAW capability is unsupported and says so`() {
        val q = Qualification.qualify(
            goodCamera(capabilities = listOf("BACKWARD_COMPATIBLE", "MANUAL_SENSOR")),
        )
        assertEquals(Tier.UNSUPPORTED, q.tier)
        assertTrue(q.blockingReason!!.contains("RAW"), q.blockingReason)
    }

    @Test
    fun `missing manual sensor control is unsupported and says so`() {
        val q = Qualification.qualify(
            goodCamera(capabilities = listOf("BACKWARD_COMPATIBLE", "RAW")),
        )
        assertEquals(Tier.UNSUPPORTED, q.tier)
        assertTrue(q.blockingReason!!.contains("manual"), q.blockingReason)
    }

    @Test
    fun `max exposure under two seconds is unsupported`() {
        val q = Qualification.qualify(goodCamera(exposureMaxNs = 1_000_000_000L))
        assertEquals(Tier.UNSUPPORTED, q.tier)
        assertTrue(q.blockingReason!!.contains("max exposure"), q.blockingReason)
    }

    @Test
    fun `unreported max exposure is treated as a failure, not a pass`() {
        val q = Qualification.qualify(goodCamera(exposureMaxNs = null))
        assertEquals(Tier.UNSUPPORTED, q.tier)
    }

    // ---- tiers and warnings --------------------------------------------------------

    @Test
    fun `LIMITED hardware level degrades rather than blocks`() {
        val q = Qualification.qualify(goodCamera(hardwareLevel = HardwareLevel.LIMITED))
        assertEquals(Tier.DEGRADED, q.tier)
        assertTrue(q.usable)
    }

    @Test
    fun `a short but usable max exposure warns without blocking - FR-3_2_2`() {
        val q = Qualification.qualify(goodCamera(exposureMaxNs = 5_000_000_000L))
        assertEquals(Tier.FUNCTIONAL, q.tier)
        val expCheck = q.checks.first { it.label == "Max exposure" }
        assertEquals(Verdict.WARN, expCheck.verdict)
    }

    // ---- OI-17: the quad-Bayer pitch trap ------------------------------------------

    @Test
    fun `effective pixel pitch comes from the RAW frame, not the pixel array`() {
        val cam = goodCamera()
        // 7.36 mm across 8160 pixels of array, but the RAW frame is 4080 wide.
        assertEquals(0.902, cam.naivePixelPitchUm!!, 0.001)
        assertEquals(1.804, cam.effectivePixelPitchUm!!, 0.001)
        assertEquals(2.0, cam.rawBinningRatio!!, 0.001)
        assertTrue(cam.rawIsBinned)

        // The whole point: getting this wrong doubles the computed trailing limit.
        val factor = cam.effectivePixelPitchUm!! / cam.naivePixelPitchUm!!
        assertTrue(abs(factor - 2.0) < 0.001)
    }

    @Test
    fun `an unbinned sensor reports equal pitches and is not flagged as binned`() {
        val cam = goodCamera(
            rawSizes = listOf(SizePx(4000, 3000)),
            pixelArray = SizePx(4000, 3000),
        )
        assertEquals(cam.naivePixelPitchUm!!, cam.effectivePixelPitchUm!!, 1e-9)
        assertFalse(cam.rawIsBinned)
    }

    @Test
    fun `the largest RAW size wins when several are offered`() {
        val cam = goodCamera(
            rawSizes = listOf(SizePx(2040, 1530), SizePx(4080, 3060), SizePx(1020, 765)),
        )
        assertEquals(SizePx(4080, 3060), cam.maxRawSize)
    }

    // ---- focus detection (the regression that shipped in the first build) -----------

    @Test
    fun `AF modes beyond OFF mean a motor, even when minimum focus distance is unreported`() {
        // The real Nothing (3a) Pro main camera: afAvailableModes [0,1,2,3,4]. The first
        // build read a null minimum focus distance as "fixed focus" and would have skipped
        // the focus sweep for every session (FR-6.3).
        val cam = goodCamera(
            afAvailableModes = listOf(0, 1, 2, 3, 4),
            minimumFocusDistanceDiopters = null,
        )
        assertEquals(FocusType.MOTOR, cam.focusType)
        assertTrue(cam.hasAfMotor)
    }

    @Test
    fun `AF OFF only with a zero focus distance is genuinely fixed focus`() {
        val cam = goodCamera(
            afAvailableModes = listOf(0),
            minimumFocusDistanceDiopters = 0f,
        )
        assertEquals(FocusType.FIXED, cam.focusType)
    }

    @Test
    fun `no focus evidence at all reports UNKNOWN and warns rather than assuming fixed`() {
        val cam = goodCamera(afAvailableModes = emptyList(), minimumFocusDistanceDiopters = null)
        assertEquals(FocusType.UNKNOWN, cam.focusType)

        val focusCheck = Qualification.qualify(cam).checks.first { it.label == "Focus" }
        assertEquals(Verdict.WARN, focusCheck.verdict)
    }

    @Test
    fun `internal sensor binning is reported even when the RAW frame matches the pixel array`() {
        // The real device: platform reports the already-binned 4096x3072 array, so the
        // stream-vs-array ratio is 1.0 while the sensor is still binning 2x2.
        val cam = goodCamera(
            rawSizes = listOf(SizePx(4096, 3072)),
            pixelArray = SizePx(4096, 3072),
        )
        assertFalse(cam.rawIsBinned)
        assertTrue(cam.sensorBinsInternally)
    }

    @Test
    fun `a hidden camera is flagged as possibly unopenable`() {
        val hidden = goodCamera().copy(id = "3", discovery = Discovery.HIDDEN)
        val check = Qualification.qualify(hidden).checks.first { it.label == "Availability" }
        assertEquals(Verdict.WARN, check.verdict)
        assertTrue(check.note.contains("opening it may still be refused"))
    }

    // ---- device-level verdict ------------------------------------------------------

    @Test
    fun `a device qualifies on its rear camera, not its front one`() {
        val front = goodCamera().copy(id = "1", facing = "FRONT")
        val rearBroken = goodCamera().copy(id = "0", hardwareLevel = HardwareLevel.LEGACY)
        val profile = deviceWith(listOf(rearBroken, front))

        val q = Qualification.qualifyDevice(profile)
        assertEquals(Tier.UNSUPPORTED, q.bestTier)
        assertTrue(q.headline.contains("Unsupported"), q.headline)
    }

    @Test
    fun `device tier is the best of its usable rear cameras`() {
        val wide = goodCamera().copy(id = "0")
        val teleLimited = goodCamera(hardwareLevel = HardwareLevel.LIMITED).copy(id = "2")
        val q = Qualification.qualifyDevice(deviceWith(listOf(wide, teleLimited)))

        assertEquals(Tier.FUNCTIONAL, q.bestTier)
        assertTrue(q.headline.contains("2 published rear camera"), q.headline)
    }

    @Test
    fun `hidden rear cameras are counted separately from published ones`() {
        // The real device shape: one published rear camera, three more the platform hides.
        val published = goodCamera().copy(id = "0", discovery = Discovery.LISTED)
        val hidden = listOf("2", "3", "4").map {
            goodCamera().copy(id = it, discovery = Discovery.HIDDEN)
        }
        val q = Qualification.qualifyDevice(deviceWith(listOf(published) + hidden))

        assertTrue(q.headline.contains("1 published rear camera"), q.headline)
        assertTrue(q.headline.contains("+3 hidden"), q.headline)
    }

    @Test
    fun `a device whose only qualifying rear cameras are hidden is reported as blocked`() {
        val publishedBroken = goodCamera(hardwareLevel = HardwareLevel.LEGACY)
            .copy(id = "0", discovery = Discovery.LISTED)
        val hidden = goodCamera().copy(id = "2", discovery = Discovery.HIDDEN)
        val q = Qualification.qualifyDevice(deviceWith(listOf(publishedBroken, hidden)))

        assertTrue(q.headline.contains("Blocked"), q.headline)
    }

    @Test
    fun `profile encodes to JSON exposing both pitches`() {
        val json = ProfileJson.encode(deviceWith(listOf(goodCamera())))
        // Exported at 4 dp: 7.36 mm / 4080 px = 1.8039 um.
        assertTrue(json.contains("\"effectivePixelPitchUm\": 1.8039"), json.take(400))
        assertTrue(json.contains("\"naivePixelPitchUm\": 0.902"), json.take(400))
        assertTrue(json.contains("\"rawSmallerThanPixelArray\": true"))
        assertTrue(json.contains("\"focusType\": \"MOTOR\""))
        assertTrue(json.contains("\"discovery\": \"LISTED\""))
        assertNotNull(json.lineSequence().firstOrNull { it.contains("\"model\"") })
    }

    private fun deviceWith(cameras: List<CameraProfile>) = DeviceProfile(
        capturedAtEpochMs = 0L,
        manufacturer = "TestCo",
        model = "Test Phone",
        device = "test",
        androidRelease = "15",
        sdkInt = 35,
        supportedAbis = listOf("arm64-v8a"),
        cameras = cameras,
        concurrentCameraIdSets = emptyList(),
        sensors = SensorAvailability(
            accelerometer = true, magnetometer = true, gyroscope = true, gps = true,
        ),
    )
}
