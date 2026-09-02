package com.starstacker.stacking

import com.starstacker.dng.TestDng
import com.starstacker.registration.RigidTransform
import com.starstacker.session.FrameKind
import com.starstacker.session.FrameRecord
import com.starstacker.session.RejectReason
import com.starstacker.session.SessionInfo
import com.starstacker.session.SessionLayout
import com.starstacker.session.SessionLog
import com.starstacker.session.SessionState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

/**
 * T-5.3's missing half: a session folder driving the stacking loop.
 *
 * Every DNG here is written by [TestDng] in the device's measured shape — uncompressed, 16-bit,
 * one strip per row — so what the reader walks is the file layout §1.6 found on the phone rather
 * than a convenient fiction.
 */
class DngFrameSourceTest {

    @TempDir
    lateinit var tempDir: Path

    private val w = 8
    private val h = 6

    // ------------------------------------------------------------------ which frames get stacked

    @Test
    fun `stacks the accepted lights and leaves the rejected ones on disk`() {
        val dir = session(
            lights = listOf(
                light(1, accepted = true),
                light(2, accepted = false, reason = RejectReason.TRAILED),
                light(3, accepted = true),
            ),
        )
        open(dir).use { source ->
            assertNotNull(source)
            assertEquals(2, source!!.count)
            assertEquals(listOf("light_0001.dng", "light_0003.dng"), source.fileNames)
            // The rejected frame is still there — D-10 keeps it, the stack just declines it.
            assertTrue(File(File(dir, SessionLayout.LIGHTS), "light_0002.dng").isFile)
        }
    }

    @Test
    fun `a light named in the log but missing from disk is named, not silently dropped`() {
        val dir = session(lights = listOf(light(1), light(2)))
        File(File(dir, SessionLayout.LIGHTS), "light_0002.dng").delete()

        open(dir).use { source ->
            assertEquals(1, source!!.count)
            assertTrue(source.skipped.any { it.contains("light_0002.dng") && it.contains("not on disk") })
        }
    }

    @Test
    fun `a frame of the wrong size is refused rather than stacked at an offset`() {
        val dir = session(lights = listOf(light(1), light(2)))
        // Overwrite the second with a differently shaped frame, as a folder assembled by hand on a
        // PC could easily contain (FR-10.6.4).
        TestDng.write(
            file = File(File(dir, SessionLayout.LIGHTS), "light_0002.dng"),
            width = w + 2, height = h, samples = IntArray((w + 2) * h), rowsPerStrip = 1,
        )
        open(dir).use { source ->
            assertEquals(1, source!!.count)
            assertTrue(source.skipped.any { it.contains("10x6") && it.contains("8x6") })
        }
    }

    @Test
    fun `a session with nothing accepted returns null rather than an empty stack`() {
        val dir = session(lights = listOf(light(1, accepted = false, reason = RejectReason.CLOUD)))
        assertNull(open(dir))
    }

    // ----------------------------------------------------------------------------- the transform

    @Test
    fun `the reference frame has no transform and the others come back from the log`() {
        val placed = RigidTransform(rotationDeg = 1.5, dx = 3.0, dy = -2.0, centreX = 4.0, centreY = 3.0)
        val dir = session(
            lights = listOf(
                light(1, transform = null),
                light(2, transform = placed.toMatrix()),
            ),
        )
        open(dir).use { source ->
            assertNull(source!!.transform(0))
            assertEquals(0, source.referenceIndex)

            val recovered = source.transform(1)!!
            // Not field equality: `fromMatrix` re-expresses the map about the origin, which is a
            // different parameterisation of the same transform. What has to agree is where points
            // land, so that is what is asserted.
            listOf(0.0 to 0.0, 7.0 to 5.0, 4.0 to 3.0).forEach { (x, y) ->
                val (ex, ey) = placed.apply(x, y)
                val (ax, ay) = recovered.apply(x, y)
                assertEquals(ex, ax, 1e-9)
                assertEquals(ey, ay, 1e-9)
            }
        }
    }

    @Test
    fun `a transform that is not a rotation is refused`() {
        // A scale of 1.05 sneaked into the matrix — the shape a hand-edited or foreign
        // session.json could carry. Flattening it to its rotation would misregister silently.
        val dir = session(
            lights = listOf(
                light(1, transform = null),
                light(2, transform = listOf(1.05, 0.0, 0.0, 1.05, 2.0, 1.0)),
            ),
        )
        open(dir).use { source ->
            assertEquals(1, source!!.count)
            assertTrue(source.skipped.any { it.contains("not a rotation") })
        }
    }

    @Test
    fun `frames with no transform at all are stacked where they lie, and it is recorded`() {
        val dir = session(lights = listOf(light(1), light(2), light(3)))
        open(dir).use { source ->
            assertEquals(3, source!!.count)
            assertTrue(source.skipped.any { it.contains("carry no transform") })
        }
    }

    // ------------------------------------------------------------------------------- pixel reads

    @Test
    fun `rows come back exactly as they were written`() {
        val pixels = IntArray(w * h) { it * 7 }
        val dir = session(lights = listOf(light(1)), lightPixels = { pixels })
        open(dir).use { source ->
            val into = ShortArray(w * 3)
            assertEquals(3, source!!.rows(0, 2, 3, into))
            for (r in 0 until 3) {
                for (x in 0 until w) {
                    assertEquals(pixels[(2 + r) * w + x], into[r * w + x].toInt() and 0xFFFF)
                }
            }
        }
    }

    @Test
    fun `geometry and CFA come from the file`() {
        val dir = session(lights = listOf(light(1)))
        open(dir).use { source ->
            assertEquals(w, source!!.width)
            assertEquals(h, source.height)
            assertEquals(listOf(1, 0, 2, 1), source.cfaCodes)
            assertEquals(64.0, source.blackLevel, 1e-9)
        }
    }

    @Test
    fun `a DNG with no CFA pattern falls back to GRBG and says so`() {
        val dir = session(lights = listOf(light(1)), cfa = null)
        open(dir).use { source ->
            assertEquals(listOf(1, 0, 2, 1), source!!.cfaCodes)
            assertTrue(source.skipped.any { it.contains("assuming GRBG") })
        }
    }

    @Test
    fun `a per-channel black level is averaged into the one number the pipeline takes`() {
        val dir = session(lights = listOf(light(1)), blackLevels = listOf(64.0, 66.0, 66.0, 68.0))
        open(dir).use { source ->
            assertEquals(66.0, source!!.blackLevel, 1e-6)
            assertTrue(source.skipped.any { it.contains("differs per channel") })
        }
    }

    // ----------------------------------------------------------------------------- the masters

    @Test
    fun `no calibration frames means pass-through masters`() {
        val dir = session(lights = listOf(light(1)))
        open(dir).use { source ->
            assertFalse(source!!.masters.hasDark)
            assertFalse(source.masters.hasFlat)
        }
    }

    @Test
    fun `the master dark is the median of the session's darks`() {
        // Three darks, one of them carrying a cosmic ray at pixel 5. The median must not see it.
        val dir = session(
            lights = listOf(light(1)),
            darks = listOf(
                IntArray(w * h) { 100 },
                IntArray(w * h) { if (it == 5) 40000 else 104 },
                IntArray(w * h) { 102 },
            ),
        )
        open(dir).use { source ->
            val dark = source!!.masters.dark!!
            assertEquals(102f, dark[0])
            assertEquals(102f, dark[5], "a hit in one dark must not reach the master")
        }
    }

    @Test
    fun `the master dark is identical however many bands it is built in`() {
        // The property that defines a banded build, the same one T-5.3 asserts for the stack: the
        // banding must be invisible in the answer.
        val darks = listOf(
            IntArray(w * h) { it % 13 },
            IntArray(w * h) { (it * 3) % 17 },
            IntArray(w * h) { (it * 5) % 11 },
        )
        val dir = session(lights = listOf(light(1)), darks = darks)

        val whole = open(dir).use { it!!.masters.dark!!.copyOf() }
        // One row at a time: width * 2 bytes * 3 darks is the cost of a single row.
        val banded = open(dir, masterBudget = w * 2L * 3).use { it!!.masters.dark!!.copyOf() }

        assertArrayEqualsExactly(whole, banded)
    }

    @Test
    fun `hot pixels are found from the master dark`() {
        val hotAt = 11
        val darks = List(3) { IntArray(w * h) { i -> if (i == hotAt) 9000 else 100 } }
        val dir = session(lights = listOf(light(1)), darks = darks)
        open(dir).use { source ->
            val hot = source!!.masters.hotPixels!!
            assertTrue(hot.contains(hotAt), "the consistently hot photosite should be listed")
        }
    }

    // -------------------------------------------------------------------------------- lifecycle

    @Test
    fun `close releases every file handle`() {
        val dir = session(lights = listOf(light(1), light(2)))
        val source = open(dir)!!
        source.close()
        // Windows refuses to delete a file that is still open, so this is a real assertion here
        // and a harmless one elsewhere.
        File(File(dir, SessionLayout.LIGHTS), "light_0001.dng").let {
            assertTrue(it.delete(), "the reader still holds ${it.name} open")
        }
    }

    // ------------------------------------------------------------------------------- end to end

    @Test
    fun `drives the stacking loop from a folder of files`() {
        // Two frames, no transforms, no darks. The first time the loop has read a file rather
        // than a lambda.
        val a = IntArray(w * h) { 100 }
        val b = IntArray(w * h) { 200 }
        val dir = session(
            lights = listOf(light(1), light(2)),
            lightPixels = { index -> if (index == 1) a else b },
        )

        open(dir).use { source ->
            val master = FloatArray(w * h * 3)
            assertTrue(
                TiledStacker(source!!, PassThroughResampler(), { TiledStacker.Combiner.Mean })
                    .stack(master),
            )
            // Not 150. With no dark, T-5.2 subtracts the DNG's 64 ADU pedestal explicitly — the
            // pedestal has to leave exactly once, and here there is no dark to carry it out. So
            // the answer is the mean of 36 and 136, and a test expecting 150 would be asserting
            // that calibration had been skipped.
            for (p in 0 until w * h * 3) assertEquals(86f, master[p], 1e-3f)
        }
    }

    @Test
    fun `a dark is subtracted, at the right rows, through the whole loop`() {
        // The regression that matters: a gradient dark whose value depends on the row. Applied at
        // the wrong offset it still subtracts something, and the master is merely wrong.
        val dark = IntArray(w * h) { 10 + (it / w) * 10 }
        val lightPixels = IntArray(w * h) { 500 + (it / w) * 10 }
        val dir = session(
            lights = listOf(light(1)),
            lightPixels = { lightPixels },
            darks = List(3) { dark },
        )

        open(dir).use { source ->
            val master = FloatArray(w * h * 3)
            assertTrue(
                TiledStacker(source!!, PassThroughResampler(), { TiledStacker.Combiner.Mean })
                    .stack(master),
            )
            // Every row should come back at exactly 490: the row's own dark, not row 0's.
            for (p in 0 until w * h * 3) assertEquals(490f, master[p], 1e-3f)
        }
    }

    // --------------------------------------------------------------------------------- fixtures

    /** Replicates each CFA sample into three channels and does not warp. Enough to drive the loop. */
    private class PassThroughResampler : TiledStacker.Resampler {
        override fun debayer(
            cfa: ShortArray,
            width: Int,
            height: Int,
            cfaCodes: List<Int>,
            out: FloatArray,
        ): Boolean {
            for (i in 0 until width * height) {
                val v = (cfa[i].toInt() and 0xFFFF).toFloat()
                out[i * 3] = v
                out[i * 3 + 1] = v
                out[i * 3 + 2] = v
            }
            return true
        }

        override fun warpBand(
            src: FloatArray,
            width: Int,
            srcRows: Int,
            srcTop: Int,
            channels: Int,
            dstRows: Int,
            dstTop: Int,
            transform: RigidTransform,
            out: FloatArray,
        ): Boolean {
            // No warp, but the offsets still have to be honoured: output row k is whole-frame row
            // dstTop + k, which sits at (dstTop + k - srcTop) inside the source band.
            val skip = (dstTop - srcTop) * width * channels
            src.copyInto(out, 0, skip, skip + dstRows * width * channels)
            return true
        }
    }

    private fun assertArrayEqualsExactly(expected: FloatArray, actual: FloatArray) {
        assertEquals(expected.size, actual.size)
        for (i in expected.indices) assertEquals(expected[i], actual[i], 0f, "differs at $i")
    }

    private fun light(
        index: Int,
        accepted: Boolean = true,
        reason: RejectReason? = null,
        transform: List<Double>? = null,
    ) = FrameRecord(
        index = index,
        fileName = SessionLayout.frameName(FrameKind.LIGHT, index),
        kind = FrameKind.LIGHT,
        capturedAtEpochMs = 1_000L * index,
        iso = 3200,
        exposureNs = 8_000_000_000L,
        temperatureC = 20.0,
        hfr = 2.0,
        starCount = 40,
        eccentricity = 0.2,
        backgroundAdu = 300.0,
        accepted = accepted,
        rejectReason = reason,
        transform = transform,
    )

    /**
     * Writes a session folder: `lights/`, optional `darks/`, and the log that describes them.
     *
     * @param lightPixels the CFA plane for a given frame index, so a test can make frames differ.
     */
    private fun session(
        lights: List<FrameRecord>,
        lightPixels: (Int) -> IntArray = { IntArray(w * h) { i -> i } },
        darks: List<IntArray> = emptyList(),
        cfa: IntArray? = intArrayOf(1, 0, 2, 1),
        blackLevels: List<Double>? = null,
    ): File {
        val dir = File(tempDir.toFile(), "2026-09-02_2115_test").apply { mkdirs() }
        SessionLayout.DIRECTORIES.forEach { File(dir, it).mkdirs() }

        lights.forEach { record ->
            TestDng.write(
                file = File(File(dir, SessionLayout.LIGHTS), record.fileName),
                width = w, height = h,
                samples = lightPixels(record.index),
                rowsPerStrip = 1,
                blackLevel = if (blackLevels == null) 64.0 else null,
                blackLevels = blackLevels,
                cfaCodes = cfa,
                iso = record.iso,
            )
        }

        val darkRecords = darks.mapIndexed { i, pixels ->
            val record = FrameRecord(
                index = i + 1,
                fileName = SessionLayout.frameName(FrameKind.DARK, i + 1),
                kind = FrameKind.DARK,
                capturedAtEpochMs = 9_000L + i,
                iso = 3200,
                exposureNs = 8_000_000_000L,
                temperatureC = 21.0,
                hfr = null, starCount = null, eccentricity = null, backgroundAdu = null,
                accepted = true,
            )
            TestDng.write(
                file = File(File(dir, SessionLayout.DARKS), record.fileName),
                width = w, height = h, samples = pixels, rowsPerStrip = 1,
                blackLevel = 64.0, cfaCodes = cfa,
            )
            record
        }

        val log = SessionLog(
            info = SessionInfo(
                sessionId = "test",
                startedAtEpochMs = 1_000L,
                deviceModel = "test",
                cameraId = "0",
                plannedIso = 3200,
                plannedExposureNs = 8_000_000_000L,
                plannedLightCount = lights.size,
                plannedDarkCount = darks.size,
                state = SessionState.DONE,
            ),
            frames = lights + darkRecords,
        )
        File(dir, SessionLayout.SESSION_JSON).writeText(log.encode())
        logs[dir.path] = log
        return dir
    }

    private val logs = mutableMapOf<String, SessionLog>()

    private fun open(
        dir: File,
        masterBudget: Long = DngFrameSource.DEFAULT_MASTER_BUDGET,
        settings: StackSettings = StackSettings(keepBestPercent = 100, weightByQuality = false),
    ) = DngFrameSource.open(dir, logs.getValue(dir.path), settings, masterBudget)
}
