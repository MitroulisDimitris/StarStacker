package com.starstacker.session

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** T-3.13 / FR-6.4: an interrupted session is resumable rather than lost. */
class SessionRecoveryTest {

    private fun info(
        id: String,
        startedAt: Long,
        state: SessionState,
        lights: Int = 100,
        darks: Int = 15,
    ) = SessionInfo(
        sessionId = id,
        startedAtEpochMs = startedAt,
        deviceModel = "A059P",
        cameraId = "0",
        plannedIso = 800,
        plannedExposureNs = 12_000_000_000L,
        plannedLightCount = lights,
        plannedDarkCount = darks,
        state = state,
    )

    private fun frame(index: Int, kind: FrameKind = FrameKind.LIGHT) = FrameRecord(
        index = index,
        fileName = SessionLayout.frameName(kind, index),
        kind = kind,
        capturedAtEpochMs = 1_755_000_000_000L + index * 12_000L,
        iso = 800,
        exposureNs = 12_000_000_000L,
        temperatureC = 22.0,
        hfr = 2.3,
        starCount = 190,
        eccentricity = 0.2,
        backgroundAdu = 400.0,
        accepted = true,
    )

    private fun write(
        store: SessionStore,
        name: String,
        state: SessionState,
        frames: Int,
        startedAt: Long = 1_755_000_000_000L,
        planned: Int = 100,
    ) {
        val folder = store.createSession(name)
        val log = SessionLog(
            info(name, startedAt, state, lights = planned),
            (1..frames).map { frame(it) },
        )
        folder.writeAtomically(SessionLayout.SESSION_JSON, log.encode().toByteArray())
    }

    @Test
    fun `a session left mid-capture is offered for resume`(@TempDir temp: File) {
        val store = FileSessionStore(temp)
        write(store, "2026-08-17_2200_m31", SessionState.CAPTURING, frames = 42)

        val resumable = SessionRecovery.mostRecent(store)

        assertNotNull(resumable)
        assertEquals("2026-08-17_2200_m31", resumable!!.folderName)
        assertEquals(58, resumable.lightsRemaining)
        assertEquals(15, resumable.darksRemaining)
        assertTrue(resumable.describe().contains("42 of 100 frames"), resumable.describe())
    }

    @Test
    fun `a completed session is not offered`(@TempDir temp: File) {
        val store = FileSessionStore(temp)
        write(store, "done", SessionState.DONE, frames = 100)

        assertNull(SessionRecovery.mostRecent(store))
    }

    /**
     * A session that shot everything it planned and stopped before being marked done has a
     * bookkeeping gap, not lost sky. Offering to "resume" it would promise frames it will not
     * take.
     */
    @Test
    fun `a session with nothing left to shoot is not offered`(@TempDir temp: File) {
        val store = FileSessionStore(temp)
        val folder = store.createSession("all-shot")
        val log = SessionLog(
            info("all-shot", 1L, SessionState.CAPTURING, lights = 10, darks = 2),
            (1..10).map { frame(it) } + (1..2).map { frame(it, FrameKind.DARK) },
        )
        folder.writeAtomically(SessionLayout.SESSION_JSON, log.encode().toByteArray())

        assertNull(SessionRecovery.mostRecent(store))
    }

    @Test
    fun `the most recent interruption is the one offered`(@TempDir temp: File) {
        val store = FileSessionStore(temp)
        write(store, "older", SessionState.CAPTURING, frames = 10, startedAt = 1_000L)
        write(store, "newer", SessionState.PAUSED, frames = 5, startedAt = 9_000L)

        assertEquals(2, SessionRecovery.findInterrupted(store).size)
        assertEquals("newer", SessionRecovery.mostRecent(store)!!.folderName)
    }

    @Test
    fun `a session interrupted during darks is still resumable`(@TempDir temp: File) {
        val store = FileSessionStore(temp)
        val folder = store.createSession("in-darks")
        val log = SessionLog(
            info("in-darks", 5L, SessionState.DARKS, lights = 10, darks = 15),
            (1..10).map { frame(it) } + (1..4).map { frame(it, FrameKind.DARK) },
        )
        folder.writeAtomically(SessionLayout.SESSION_JSON, log.encode().toByteArray())

        val resumable = SessionRecovery.mostRecent(store)!!
        assertEquals(0, resumable.lightsRemaining)
        assertEquals(11, resumable.darksRemaining)
        assertTrue(resumable.worthResuming)
    }

    /** D-10: abandoning stops the offer and deletes nothing. */
    @Test
    fun `abandoning a session keeps every frame on disk`(@TempDir temp: File) {
        val store = FileSessionStore(temp)
        write(store, "abandoned", SessionState.CAPTURING, frames = 7)
        val before = SessionWriter.resume(store.openSession("abandoned")!!)!!.log.frames.size

        SessionRecovery.abandon(store, "abandoned")

        assertNull(SessionRecovery.mostRecent(store))
        val after = SessionWriter.resume(store.openSession("abandoned")!!)!!.log
        assertEquals(before, after.frames.size, "abandoning lost frames")
        assertEquals(SessionState.FAILED, after.info.state)
    }

    @Test
    fun `an unparseable log is skipped rather than crashing the scan`(@TempDir temp: File) {
        val store = FileSessionStore(temp)
        write(store, "good", SessionState.CAPTURING, frames = 3)
        store.createSession("bad").writeAtomically(
            SessionLayout.SESSION_JSON, "{ truncated".toByteArray(),
        )

        val found = SessionRecovery.findInterrupted(store)

        assertEquals(1, found.size)
        assertEquals("good", found.single().folderName)
    }
}
