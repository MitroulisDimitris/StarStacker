package com.starstacker.device

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * T-2.7. The picker is built from the reference device's real measurements (§1.5), because the
 * interesting case is exactly the one FR-11.2 gets wrong: its ultrawide is the *worst* astro
 * camera here, not the best, and the picker has to say so from the numbers rather than from a
 * rule of thumb.
 */
class CameraPickerTest {

    private fun camera(
        id: String,
        focalMm: Float,
        apertureF: Float,
        sensorMm: SizeMm,
        rawSize: SizePx,
        discovery: Discovery = Discovery.LISTED,
        facing: String = "BACK",
        afModes: List<Int> = listOf(0, 1),
        exposureMaxNs: Long = 34_900_000_000L,
        capabilities: List<String> = listOf("BACKWARD_COMPATIBLE", "MANUAL_SENSOR", "RAW"),
    ) = CameraProfile(
        id = id,
        exposed = discovery == Discovery.LISTED,
        discovery = discovery,
        logicalParentIds = emptyList(),
        physicalChildIds = emptyList(),
        facing = facing,
        hardwareLevel = HardwareLevel.LEVEL_3,
        capabilities = capabilities,
        hasRawCapability = capabilities.contains("RAW"),
        hasManualSensor = capabilities.contains("MANUAL_SENSOR"),
        hasUltraHighResolutionSensor = false,
        pixelArray = rawSize,
        activeArray = rawSize,
        physicalSizeMm = sensorMm,
        rawSizes = listOf(rawSize),
        binningFactor = SizePx(2, 2),
        focalLengthsMm = listOf(focalMm),
        aperturesF = listOf(apertureF),
        isoMin = 50,
        isoMax = 12800,
        exposureMinNs = 42_000L,
        exposureMaxNs = exposureMaxNs,
        maxFrameDurationNs = exposureMaxNs,
        cfaArrangement = "GRBG",
        whiteLevel = 1023,
        blackLevelPattern = listOf(64, 64, 64, 64),
        noiseProfile = null,
        timestampSource = "REALTIME",
        focusDistanceCalibration = "APPROXIMATE",
        minimumFocusDistanceDiopters = if (afModes.any { it != 0 }) 10f else null,
        hyperfocalDistanceDiopters = 0.2f,
        afAvailableModes = afModes,
        oisModes = listOf(0, 1),
        eisModes = listOf(0, 1),
        mandatoryStreamCombinations = emptyList(),
    )

    /** The Nothing Phone (3a) Pro as measured: main, ultrawide, tele, front. */
    private fun referenceDevice() = DeviceProfile(
        capturedAtEpochMs = 0L,
        manufacturer = "Nothing",
        model = "A059P",
        device = "Asteroids",
        androidRelease = "16",
        sdkInt = 36,
        supportedAbis = listOf("arm64-v8a"),
        cameras = listOf(
            camera("0", 5.56f, 1.88f, SizeMm(8.192f, 6.144f), SizePx(4096, 3072),
                exposureMaxNs = 49_640_000_000L),
            camera("1", 3.61f, 2.2f, SizeMm(5.22f, 3.93f), SizePx(4080, 3072),
                facing = "FRONT", afModes = listOf(0), exposureMaxNs = 480_000_000L),
            camera("2", 1.64f, 2.2f, SizeMm(3.67f, 2.76f), SizePx(3280, 2464),
                discovery = Discovery.HIDDEN, afModes = listOf(0)),
            camera("3", 13.30f, 2.55f, SizeMm(6.55f, 4.92f), SizePx(4096, 3072),
                discovery = Discovery.HIDDEN, exposureMaxNs = 36_100_000_000L),
        ),
        concurrentCameraIdSets = emptyList(),
        sensors = SensorAvailability(true, true, true, true),
    )

    private fun options() = with(referenceDevice()) {
        CameraPicker.options(this, Qualification.qualifyDevice(this))
    }

    @Test
    fun `the front camera is not offered as an astro camera`() {
        assertTrue(options().none { it.id == "1" }, "the selfie camera was offered")
    }

    @Test
    fun `roles come from where each lens sits against the best one`() {
        val byId = options().associateBy { it.id }
        assertEquals("Ultrawide", byId.getValue("2").name)
        assertEquals("Main", byId.getValue("0").name)
        assertEquals("Tele", byId.getValue("3").name)
    }

    @Test
    fun `the main camera is recommended, not the ultrawide FR-11_2 guessed at`() {
        val recommended = options().single { it.recommended }
        assertEquals("0", recommended.id)
        assertTrue(
            recommended.note.contains("2.00 µm") && recommended.note.contains("f/1.9"),
            "the recommendation should quote its own measurements: ${recommended.note}",
        )
    }

    @Test
    fun `focal lengths are shown in the units people compare`() {
        val byId = options().associateBy { it.id }
        // 5.56 mm on an 8.192x6.144 mm sensor is a 10.24 mm diagonal: ~23 mm equivalent.
        assertEquals(23, byId.getValue("0").equivalent35mm)
        // 13.30 mm on a 8.19 mm diagonal: ~70 mm equivalent.
        assertEquals(70, byId.getValue("3").equivalent35mm)
        assertEquals("23mm", byId.getValue("0").headline)
    }

    @Test
    fun `unpublished cameras are offered but flagged, because opening is not capturing`() {
        val tele = options().single { it.id == "3" }
        assertTrue(tele.selectable)
        assertNotNull(tele.warning)
        assertTrue(tele.warning!!.contains("capture is unproven"), tele.warning!!)
    }

    @Test
    fun `a fixed-focus lens says so, since there is nothing to calibrate`() {
        val ultrawide = options().single { it.id == "2" }
        assertTrue(ultrawide.fixedFocus)
        assertTrue(ultrawide.note.contains("Fixed focus"), ultrawide.note)
    }

    @Test
    fun `the widest lens is sold on field of view, the longest on reach`() {
        val byId = options().associateBy { it.id }
        assertTrue(byId.getValue("2").note.contains("Widest field"), byId.getValue("2").note)
        assertTrue(byId.getValue("3").note.contains("Tightest field"), byId.getValue("3").note)
        assertTrue(
            byId.getValue("3").note.contains("fewer stars to register on"),
            byId.getValue("3").note,
        )
    }

    @Test
    fun `a camera that fails the hard requirements is not selectable and says why`() {
        val device = referenceDevice().let { profile ->
            profile.copy(
                cameras = profile.cameras.map {
                    if (it.id == "3") it.copy(exposureMaxNs = 500_000_000L) else it
                },
            )
        }
        val tele = CameraPicker.options(device, Qualification.qualifyDevice(device))
            .single { it.id == "3" }

        assertFalse(tele.selectable)
        assertTrue(tele.warning!!.contains("max exposure"), tele.warning!!)
    }

    @Test
    fun `a short exposure ceiling is called out even when the camera still qualifies`() {
        val device = referenceDevice().let { profile ->
            profile.copy(
                cameras = profile.cameras.map {
                    if (it.id == "3") it.copy(exposureMaxNs = 4_000_000_000L) else it
                },
            )
        }
        val tele = CameraPicker.options(device, Qualification.qualifyDevice(device))
            .single { it.id == "3" }

        assertTrue(tele.selectable)
        assertTrue(tele.warning!!.contains("caps out at 4.0 s"), tele.warning!!)
    }

    @Test
    fun `a single-camera device gets a sensible description rather than a comparison`() {
        val single = referenceDevice().let { profile ->
            profile.copy(cameras = profile.cameras.filter { it.id == "0" })
        }
        val only = CameraPicker.options(single, Qualification.qualifyDevice(single)).single()

        assertEquals("Main", only.name)
        assertTrue(only.note.contains("only rear camera"), only.note)
        assertNull(only.warning)
    }
}
