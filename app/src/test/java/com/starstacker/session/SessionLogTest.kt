package com.starstacker.session

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.TimeZone

/**
 * T-3.7 and T-3.8. The acceptance that matters is the crash one: *kill the process mid-session
 * and `session.json` describes every frame written.* It is tested by simply not calling any
 * "finish" method — if the log is only correct once a session ends cleanly, the test fails, and
 * so would the 3 a.m. session it is standing in for.
 */
class SessionLogTest {

    private fun info(id: String = "s1") = SessionInfo(
        sessionId = id,
        startedAtEpochMs = 1_755_000_000_000L,
        deviceModel = "A059P",
        cameraId = "0",
        plannedIso = 800,
        plannedExposureNs = 12_000_000_000L,
        plannedLightCount = 150,
        plannedDarkCount = 22,
        exposureDerivation = listOf("ISO 800 chosen: 4.2x read noise", "trailing limit 12.4 s"),
        latitudeDeg = 51.5,
        declinationDeg = 22.3,
        focusDiopters = 0.047f,
    )

    private fun frame(index: Int, accepted: Boolean = true) = FrameRecord(
        index = index,
        fileName = SessionLayout.frameName(FrameKind.LIGHT, index),
        kind = FrameKind.LIGHT,
        capturedAtEpochMs = 1_755_000_000_000L + index * 12_000L,
        iso = 800,
        exposureNs = 12_000_000_000L,
        temperatureC = 21.5 + index * 0.1,
        hfr = 2.4,
        starCount = 180,
        eccentricity = 0.21,
        backgroundAdu = 412.0,
        accepted = accepted,
        rejectReason = if (accepted) null else RejectReason.TRAILED,
        rejectDetail = if (accepted) null else "eccentricity 0.78 over 0.60",
        thermalHeadroom = 0.62,
        batteryPercent = 84,
    )

    // --- layout ------------------------------------------------------------------------

    @Test
    fun `the folder name is local time and a sanitised label`() {
        val utc = TimeZone.getTimeZone("UTC")
        val name = SessionLayout.folderName(1_755_000_000_000L, "M31 / Andromeda!", utc)

        assertTrue(name.endsWith("_M31-Andromeda"), name)
        assertTrue(name.matches(Regex("\\d{4}-\\d{2}-\\d{2}_\\d{4}_.+")), name)
    }

    @Test
    fun `an empty or hostile label still produces a usable folder name`() {
        assertEquals("session", SessionLayout.sanitise("   "))
        assertEquals("session", SessionLayout.sanitise("///"))
        assertEquals("a-b", SessionLayout.sanitise("a  b"))
        assertTrue(SessionLayout.sanitise("x".repeat(200)).length <= 48)
    }

    @Test
    fun `frame names sort in capture order as text`() {
        val names = (1..12).map { SessionLayout.frameName(FrameKind.LIGHT, it) }
        assertEquals(names, names.sorted())
        assertEquals("light_0001.dng", names.first())
        assertEquals("dark_0007.dng", SessionLayout.frameName(FrameKind.DARK, 7))
    }

    // --- round trip --------------------------------------------------------------------

    @Test
    fun `a log survives a round trip through JSON with every field intact`() {
        val original = SessionLog(info(), listOf(frame(1), frame(2, accepted = false)))

        val restored = SessionLog.decode(original.encode())

        assertEquals(original.info, restored.info)
        assertEquals(original.frames, restored.frames)
        assertEquals(2, restored.frames.size)
        assertEquals(RejectReason.TRAILED, restored.frames[1].rejectReason)
        assertEquals("eccentricity 0.78 over 0.60", restored.frames[1].rejectDetail)
    }

    @Test
    fun `a transform round trips once Phase 2 starts writing one`() {
        val withTransform = frame(1).copy(transform = listOf(1.0, 0.001, -0.001, 1.0, 3.5, -2.25))
        val restored = SessionLog.decode(SessionLog(info(), listOf(withTransform)).encode())

        assertEquals(withTransform.transform, restored.frames.single().transform)
    }

    @Test
    fun `the summary counts accepted lights and their integration, not every frame`() {
        val log = SessionLog(
            info(),
            listOf(
                frame(1), frame(2), frame(3, accepted = false),
                frame(4).copy(kind = FrameKind.DARK, fileName = "dark_0001.dng"),
            ),
        )

        assertEquals(3, log.lights.size)
        assertEquals(2, log.accepted.size)
        assertEquals(1, log.darks.size)
        assertEquals(24.0, log.acceptedIntegrationSeconds, 1e-9)
    }

    // --- the crash acceptance ----------------------------------------------------------

    /**
     * T-3.7's acceptance. Nothing here calls a finish method, because the session being tested is
     * one that never got to.
     */
    @Test
    fun `a session killed mid-capture still describes every frame written`(@TempDir temp: File) {
        val store = FileSessionStore(temp)
        val folder = store.createSession("2026-08-17_2130_M31")
        val writer = SessionWriter(folder, SessionLog(info()))
        writer.begin()

        repeat(7) { i ->
            writer.writeFrame(
                kind = FrameKind.LIGHT,
                index = i + 1,
                record = { name -> frame(i + 1).copy(fileName = name) },
                write = { out -> out.write(ByteArray(64) { it.toByte() }) },
            )
        }

        // --- the process dies here. Everything below is a fresh reader. ---

        val reopened = store.openSession("2026-08-17_2130_M31")!!
        val recovered = SessionWriter.resume(reopened)
        assertNotNull(recovered)

        assertEquals(7, recovered!!.log.frames.size)
        assertEquals(SessionState.CAPTURING, recovered.log.info.state)
        assertEquals(
            (1..7).map { SessionLayout.frameName(FrameKind.LIGHT, it) },
            recovered.log.frames.map { it.fileName },
        )

        // And every frame the log names is actually on disk.
        val onDisk = reopened.listFrames(SessionLayout.LIGHTS)
        assertEquals(7, onDisk.size)
        assertEquals(recovered.log.frames.map { it.fileName }.sorted(), onDisk)
    }

    /**
     * The ordering rule: bytes first, log second. A frame that fails to write must not appear in
     * the log, because a log naming a file that is not there is the failure that breaks a restack
     * — whereas a file the log has not caught up with is still sitting in the folder.
     */
    @Test
    fun `a frame whose bytes fail to write is not recorded`(@TempDir temp: File) {
        val folder = FileSessionStore(temp).createSession("s")
        val writer = SessionWriter(folder, SessionLog(info()))
        writer.begin()

        writer.writeFrame(FrameKind.LIGHT, 1, { frame(1).copy(fileName = it) }) {
            it.write(ByteArray(8))
        }

        val failure = runCatching {
            writer.writeFrame(FrameKind.LIGHT, 2, { frame(2).copy(fileName = it) }) {
                throw java.io.IOException("volume went away")
            }
        }

        assertTrue(failure.isFailure)
        assertEquals(1, writer.log.frames.size, "a frame that never landed was logged anyway")
        assertEquals(1, SessionWriter.resume(folder)!!.log.frames.size)
    }

    @Test
    fun `the folder layout is created in full`(@TempDir temp: File) {
        FileSessionStore(temp).createSession("2026-08-17_2130_M31")

        val dir = File(temp, "2026-08-17_2130_M31")
        for (name in SessionLayout.DIRECTORIES) {
            assertTrue(File(dir, name).isDirectory, "$name was not created")
        }
    }

    /**
     * FR-10.6.4: a session is whatever the folder says it is, so listing must find one that this
     * app never created — a folder copied back from a PC — and must not offer a bare directory
     * that holds no log.
     */
    @Test
    fun `sessions are discovered by scanning, including ones copied back from a PC`(
        @TempDir temp: File,
    ) {
        val store = FileSessionStore(temp)
        store.createSession("2026-08-16_2200_native").also {
            it.writeAtomically(SessionLayout.SESSION_JSON, SessionLog(info()).encode().toByteArray())
        }

        // A folder that arrived by USB: correct layout, log written elsewhere.
        val foreign = File(temp, "2026-08-15_2100_from-pc").apply { mkdirs() }
        SessionLayout.DIRECTORIES.forEach { File(foreign, it).mkdirs() }
        File(foreign, SessionLayout.SESSION_JSON)
            .writeText(SessionLog(info("copied")).encode())

        // And a directory that is not a session at all.
        File(temp, "random-folder").mkdirs()

        val found = store.listSessions()

        assertEquals(2, found.size, "found $found")
        assertTrue(found.contains("2026-08-15_2100_from-pc"))
        assertTrue(!found.contains("random-folder"))
        assertEquals("copied", SessionWriter.resume(store.openSession(found.last())!!)!!.log.info.sessionId)
    }

    @Test
    fun `an unreadable log is refused rather than resumed as an empty session`(@TempDir temp: File) {
        val store = FileSessionStore(temp)
        val folder = store.createSession("broken")
        folder.writeAtomically(SessionLayout.SESSION_JSON, "{ this is not json".toByteArray())

        assertNull(SessionWriter.resume(folder))
    }

    @Test
    fun `state changes are persisted as they happen`(@TempDir temp: File) {
        val folder = FileSessionStore(temp).createSession("s")
        val writer = SessionWriter(folder, SessionLog(info()))
        writer.begin()

        writer.setState(SessionState.DARKS)
        assertEquals(SessionState.DARKS, SessionWriter.resume(folder)!!.log.info.state)

        writer.setState(SessionState.DONE, finishedAtEpochMs = 1_755_000_100_000L)
        val done = SessionWriter.resume(folder)!!.log
        assertEquals(SessionState.DONE, done.info.state)
        assertEquals(1_755_000_100_000L, done.info.finishedAtEpochMs)
    }
}
