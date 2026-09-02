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
 * T-6.4 — the whole pipeline, from a folder of DNGs to a written TIFF, in a JVM test.
 *
 * That this is possible at all is the point of splitting [StackJob] from `StackingService`: the
 * platform half needs a phone, the work half does not, and injecting the `Resampler` (§1.31)
 * is what keeps OpenCV out of the way.
 */
class StackJobTest {

    @TempDir
    lateinit var tempDir: Path

    private val w = 8
    private val h = 6

    // ---------------------------------------------------------------------------- the happy path

    @Test
    fun `stacks a folder of DNGs into a linear master`() {
        val dir = session(lights = 2, pixels = { index -> IntArray(w * h) { if (index == 1) 100 else 200 } })

        val result = job(dir).run()

        assertTrue(result.succeeded, "failed: ${result.error}")
        assertEquals(2, result.frames)
        assertEquals(
            File(File(dir, SessionLayout.MASTER), LinearMaster.FILE_NAME).path,
            result.masterFile?.path,
        )
        assertTrue(result.masterFile!!.isFile)
        assertEquals(result.masterFile!!.length(), result.bytesWritten)
        // (100 - 64 + 200 - 64) / 2. The pedestal leaves once, since there is no dark.
        assertEquals(86.0, result.stats!!.mean, 1e-3)
    }

    @Test
    fun `the master lands in the session's own master folder`() {
        val dir = session(lights = 1)
        job(dir).run()
        assertTrue(File(dir, "${SessionLayout.MASTER}/${LinearMaster.FILE_NAME}").isFile)
    }

    @Test
    fun `progress runs from preparing through to done`() {
        val dir = session(lights = 2)
        val seen = mutableListOf<StackJob.State>()
        val result = job(dir).run(onProgress = { seen += it.state })

        assertTrue(result.succeeded)
        assertEquals(StackJob.State.PREPARING, seen.first())
        assertEquals(StackJob.State.DONE, seen.last())
        assertTrue(seen.contains(StackJob.State.STACKING))
        assertTrue(seen.contains(StackJob.State.WRITING))
        // Monotonic: a bar that goes backwards reads as a restart.
        val percents = seen.map { StackJob.Progress(it, "s").percent }.filter { it > 0 }
        assertEquals(percents.sorted(), percents)
    }

    // ------------------------------------------------------------------------------ the settings

    @Test
    fun `the chosen combination method is the one that runs`() {
        // Three frames, one carrying a bright intruder. The mean keeps it, the median does not.
        val dir = session(
            lights = 3,
            pixels = { index -> IntArray(w * h) { if (index == 2) 9000 else 100 } },
        )

        val mean = job(dir, StackSettings(method = Combine.Method.MEAN)).run()
        val median = job(dir, StackSettings(method = Combine.Method.MEDIAN)).run()

        assertTrue(mean.stats!!.mean > median.stats!!.mean, "the mean should carry the intruder")
        assertEquals(36.0, median.stats!!.mean, 1e-3)
    }

    @Test
    fun `the crop choice reaches the written file`() {
        val dir = session(lights = 1)
        val full = job(dir, StackSettings(crop = LinearMaster.Crop.FULL_FRAME)).run()
        assertEquals(LinearMaster.Region(0, 0, w, h), full.region)
    }

    @Test
    fun `what produced the master is recorded in session json`() {
        val dir = session(lights = 2)
        val settings = StackSettings(
            method = Combine.Method.KAPPA_SIGMA,
            crop = LinearMaster.Crop.FULL_FRAME,
        )
        job(dir, settings).run()

        val reread = SessionLog.decode(File(dir, SessionLayout.SESSION_JSON).readText())
        // FR-9.2: a restack must reproduce a master, so the settings travel with the session
        // rather than living only in the app's preferences, which can change.
        assertEquals(settings, StackSettings.fromMap(reread.info.stacking))
        assertEquals("2", reread.info.stacking["frames"])
        assertEquals(LinearMaster.FILE_NAME, reread.info.stacking["master"])
    }

    @Test
    fun `recording the stack does not destroy the frame log`() {
        val dir = session(lights = 2)
        val before = SessionLog.decode(File(dir, SessionLayout.SESSION_JSON).readText())
        job(dir).run()
        val after = SessionLog.decode(File(dir, SessionLayout.SESSION_JSON).readText())

        assertEquals(before.frames, after.frames)
        assertEquals(before.info.sessionId, after.info.sessionId)
    }

    // ------------------------------------------------------------------ refusing, and saying why

    @Test
    fun `a session with nothing accepted fails with a reason rather than an empty master`() {
        val dir = session(lights = 2, accepted = false)
        val result = job(dir).run()

        assertFalse(result.succeeded)
        assertEquals(StackJob.State.FAILED, result.state)
        assertNotNull(result.error)
        assertFalse(File(dir, "${SessionLayout.MASTER}/${LinearMaster.FILE_NAME}").exists())
    }

    @Test
    fun `an unparseable log fails without writing anything`() {
        val dir = session(lights = 1)
        File(dir, SessionLayout.SESSION_JSON).writeText("{ not json")
        val result = job(dir).run()

        assertFalse(result.succeeded)
        assertFalse(File(dir, "${SessionLayout.MASTER}/${LinearMaster.FILE_NAME}").exists())
    }

    @Test
    fun `frames left out are reported rather than silently dropped`() {
        val dir = session(lights = 3)
        File(dir, "${SessionLayout.LIGHTS}/light_0002.dng").delete()
        val result = job(dir).run()

        assertTrue(result.succeeded)
        assertEquals(2, result.frames)
        assertTrue(result.notes.any { it.contains("light_0002.dng") })
    }

    // ------------------------------------------------------------------------------ cancellation

    @Test
    fun `cancelling stops the run and writes no master`() {
        // A partial master is worse than none: it looks like an image and is wrong in a band.
        val dir = session(lights = 2)
        val result = job(dir).run(cancelled = { true })

        assertEquals(StackJob.State.CANCELLED, result.state)
        assertNull(result.masterFile)
        assertFalse(File(dir, "${SessionLayout.MASTER}/${LinearMaster.FILE_NAME}").exists())
    }

    @Test
    fun `a cancelled run leaves any earlier master alone`() {
        val dir = session(lights = 2)
        job(dir).run()
        val master = File(dir, "${SessionLayout.MASTER}/${LinearMaster.FILE_NAME}")
        val firstLength = master.length()

        job(dir).run(cancelled = { true })

        assertTrue(master.isFile, "the previous master must survive a cancelled restack")
        assertEquals(firstLength, master.length())
    }

    // ---------------------------------------------------------------------------- master statistics

    @Test
    fun `uncovered pixels are counted, not averaged in`() {
        // NaN sorts above every number in Kotlin (1.32), so a naive max reports an uncovered
        // pixel as the brightest thing in the frame.
        val stats = StackJob.MasterStats.of(
            floatArrayOf(10f, Float.NaN, 20f, Float.NaN, 30f, 40f),
        )
        assertEquals(2, stats.uncovered)
        assertEquals(6, stats.samples)
        assertEquals(40.0, stats.max)
        assertEquals(10.0, stats.min)
        assertEquals(25.0, stats.mean, 1e-9)
    }

    @Test
    fun `a wholly uncovered master says so rather than reporting NaN statistics`() {
        val stats = StackJob.MasterStats.of(FloatArray(9) { Float.NaN })
        assertTrue(stats.describe().contains("entirely uncovered"))
    }

    // --------------------------------------------------------------------------------- fixtures

    private fun job(dir: File, settings: StackSettings = StackSettings()) =
        StackJob(dir, settings, PassThroughResampler())

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
            height: Int,
            channels: Int,
            rowOffset: Int,
            transform: RigidTransform,
            out: FloatArray,
        ): Boolean {
            src.copyInto(out, 0, 0, width * height * channels)
            return true
        }
    }

    private fun session(
        lights: Int,
        accepted: Boolean = true,
        pixels: (Int) -> IntArray = { IntArray(w * h) { i -> 100 + i } },
    ): File {
        val dir = File(tempDir.toFile(), "2026-09-02_2200_stackjob").apply { mkdirs() }
        SessionLayout.DIRECTORIES.forEach { File(dir, it).mkdirs() }

        val records = (1..lights).map { index ->
            val name = SessionLayout.frameName(FrameKind.LIGHT, index)
            TestDng.write(
                file = File(File(dir, SessionLayout.LIGHTS), name),
                width = w, height = h,
                samples = pixels(index),
                rowsPerStrip = 1,
                blackLevel = 64.0,
                cfaCodes = intArrayOf(1, 0, 2, 1),
                iso = 3200,
            )
            FrameRecord(
                index = index,
                fileName = name,
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
                rejectReason = if (accepted) null else RejectReason.CLOUD,
            )
        }

        val log = SessionLog(
            info = SessionInfo(
                sessionId = "stackjob",
                label = "test",
                startedAtEpochMs = 1_000L,
                deviceModel = "test",
                cameraId = "0",
                plannedIso = 3200,
                plannedExposureNs = 8_000_000_000L,
                plannedLightCount = lights,
                plannedDarkCount = 0,
                state = SessionState.DONE,
            ),
            frames = records,
        )
        File(dir, SessionLayout.SESSION_JSON).writeText(log.encode())
        return dir
    }
}
