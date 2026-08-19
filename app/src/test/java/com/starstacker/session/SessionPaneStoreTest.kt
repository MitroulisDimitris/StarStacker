package com.starstacker.session

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * T-3.27 / T-3.28 — what the session pane reads, and what deletion is allowed to touch.
 *
 * `FileSessionStore` rather than the SAF one, which is the arrangement §15 already describes: the
 * layout, the incremental write and now the scan and the delete are all testable on a laptop, and
 * what needs a device is whether a *document provider* honours what it was asked.
 */
class SessionPaneStoreTest {

    private fun info(id: String, label: String, startedAt: Long) = SessionInfo(
        sessionId = id,
        label = label,
        startedAtEpochMs = startedAt,
        deviceModel = "A059P",
        cameraId = "0",
        plannedIso = 800,
        plannedExposureNs = 4_000_000_000L,
        plannedLightCount = 2,
        plannedDarkCount = 1,
        state = SessionState.DONE,
    )

    private fun frame(index: Int, kind: FrameKind, accepted: Boolean) = FrameRecord(
        index = index,
        fileName = SessionLayout.frameName(kind, index),
        kind = kind,
        capturedAtEpochMs = 1_787_087_700_000L + index * 4_000L,
        iso = 800,
        exposureNs = 4_000_000_000L,
        temperatureC = 21.5,
        hfr = 2.4,
        starCount = 140,
        eccentricity = 0.3,
        backgroundAdu = 900.0,
        accepted = accepted,
        rejectReason = if (accepted) null else RejectReason.TRAILED,
        rejectDetail = if (accepted) null else "elongation 2.4 px, budget 1.5",
    )

    /** A session on disk with two lights, one dark, and bytes in the frame files. */
    private fun write(
        store: SessionStore,
        folderName: String,
        label: String,
        startedAt: Long = 1_787_087_700_000L,
        bytesPerFrame: Int = 1024,
    ): SessionFolder {
        val folder = store.createSession(folderName)
        val log = SessionLog(info(folderName, label, startedAt))
            .withFrame(frame(1, FrameKind.LIGHT, accepted = true))
            .withFrame(frame(2, FrameKind.LIGHT, accepted = false))
            .withFrame(frame(1, FrameKind.DARK, accepted = true))
        folder.writeAtomically(SessionLayout.SESSION_JSON, log.encode().toByteArray())
        val payload = ByteArray(bytesPerFrame) { 0x7 }
        folder.createFrame(SessionLayout.LIGHTS, "light_0001.dng").use { it.write(payload) }
        folder.createFrame(SessionLayout.LIGHTS, "light_0002.dng").use { it.write(payload) }
        folder.createFrame(SessionLayout.DARKS, "dark_0001.dng").use { it.write(payload) }
        return folder
    }

    @Test
    fun `the scan reads every session, newest first`(@TempDir root: File) {
        val store = FileSessionStore(root)
        write(store, "2026-08-17_2300_Andromeda", "Andromeda")
        write(store, "2026-08-18_2115_Orion", "Orion")

        val scan = SessionCatalogue.all(store)

        assertEquals(2, scan.sessions.size)
        assertEquals(2, scan.total)
        assertEquals(listOf("Orion", "Andromeda"), scan.sessions.map { it.label })
        assertTrue(scan.unreadable.isEmpty())
    }

    @Test
    fun `the label comes from the log, not from the folder`(@TempDir root: File) {
        // T-3.30's whole point. A session named for the day has no suffix on its folder, so the
        // folder cannot be the source of the name.
        val store = FileSessionStore(root)
        write(store, "2026-08-18_2115", label = "2026-08-18-2")

        val summary = SessionCatalogue.all(store).sessions.single()
        assertEquals("2026-08-18-2", summary.label)
    }

    @Test
    fun `a log written before T-3_30 still names its session`(@TempDir root: File) {
        // Blank label, which is what a schema-1 log decodes to. The folder suffix takes over
        // rather than the row reading empty.
        val store = FileSessionStore(root)
        write(store, "2026-08-18_2115_Orion", label = "")

        assertEquals("Orion", SessionCatalogue.all(store).sessions.single().label)
    }

    @Test
    fun `the scan reports the size on disk`(@TempDir root: File) {
        val store = FileSessionStore(root)
        write(store, "2026-08-18_2115_Orion", "Orion", bytesPerFrame = 2048)

        val summary = SessionCatalogue.all(store).sessions.single()
        // Three frames of 2 KiB, plus the log. Bounded rather than exact because the log's own
        // length is not the thing under test.
        assertTrue(summary.sizeBytes >= 3 * 2048) { "was ${summary.sizeBytes}" }
        assertTrue(summary.sizeBytes < 3 * 2048 + 64 * 1024) { "was ${summary.sizeBytes}" }
    }

    @Test
    fun `a session whose log will not parse is listed, not hidden`(@TempDir root: File) {
        // The DNGs beside a damaged log are still there and still worth having, and the one screen
        // built to find sessions is the worst place to make one invisible.
        val store = FileSessionStore(root)
        write(store, "2026-08-18_2115_Orion", "Orion")
        val broken = store.createSession("2026-08-18_2230_Broken")
        broken.writeAtomically(SessionLayout.SESSION_JSON, "{ this is not json".toByteArray())

        val scan = SessionCatalogue.all(store)

        assertEquals(1, scan.sessions.size)
        assertEquals(listOf("2026-08-18_2230_Broken"), scan.unreadable)
        assertEquals(2, scan.total)
    }

    @Test
    fun `the scan times itself`(@TempDir root: File) {
        // OI-5's measurement, built in rather than instrumented later. Only that it is recorded
        // at all can be asserted here — the figure is a property of the device.
        val store = FileSessionStore(root)
        write(store, "2026-08-18_2115_Orion", "Orion")

        assertTrue(SessionCatalogue.all(store).elapsedMs >= 0L)
    }

    @Test
    fun `deleting a session removes the folder and everything under it`(@TempDir root: File) {
        val store = FileSessionStore(root)
        write(store, "2026-08-18_2115_Orion", "Orion")
        write(store, "2026-08-18_2230", "2026-08-18-2")

        assertTrue(store.deleteSession("2026-08-18_2115_Orion"))

        assertFalse(File(root, "2026-08-18_2115_Orion").exists())
        assertEquals(listOf("2026-08-18_2230"), store.listSessions())
        assertNull(store.openSession("2026-08-18_2115_Orion"))
        assertNotNull(store.openSession("2026-08-18_2230"))
    }

    @Test
    fun `deleting something that is not there fails rather than pretending`(@TempDir root: File) {
        val store = FileSessionStore(root)
        assertFalse(store.deleteSession("2026-08-18_2115_Orion"))
    }

    @Test
    fun `delete cannot escape the root`(@TempDir root: File) {
        // The guard on the one call in the app that can destroy a night's work. A name that is not
        // a plain child of the root names nothing that can be deleted.
        val store = FileSessionStore(File(root, "sessions").apply { mkdirs() })
        val outside = File(root, "DCIM").apply { mkdirs() }
        File(outside, "IMG_0001.jpg").writeText("a photo that is not ours")

        assertFalse(store.deleteSession("../DCIM"))
        assertFalse(store.deleteSession("../.."))
        assertFalse(store.deleteSession(""))
        assertFalse(store.deleteSession("a/b"))

        assertTrue(outside.exists())
        assertTrue(File(outside, "IMG_0001.jpg").exists())
    }

    @Test
    fun `the loss is named before anything is deleted`(@TempDir root: File) {
        // T-3.28: no deletion without a confirmation that says what is about to go. This is the
        // sentence that confirmation is built from, so it has to carry the counts and the bytes.
        val store = FileSessionStore(root)
        write(store, "2026-08-18_2115_Orion", "Orion", bytesPerFrame = 1024 * 512)

        val loss = SessionCatalogue.all(store).sessions.single().describeLoss()

        assertTrue(loss.contains("2 lights")) { loss }
        assertTrue(loss.contains("1 dark")) { loss }
        assertTrue(loss.contains("MB")) { loss }
    }

    @Test
    fun `the detail screen gets the whole frame log`(@TempDir root: File) {
        val store = FileSessionStore(root)
        write(store, "2026-08-18_2115_Orion", "Orion")

        val log = SessionCatalogue.log(store, "2026-08-18_2115_Orion")

        assertNotNull(log)
        assertEquals(3, log!!.frames.size)
        assertEquals(2, log.lights.size)
        assertEquals(1, log.accepted.size)
        // The rejection and its numbers both survive the round trip, which is what makes the log
        // an audit trail rather than a summary (D-10).
        val cut = log.lights.single { !it.accepted }
        assertEquals(RejectReason.TRAILED, cut.rejectReason)
        assertEquals("elongation 2.4 px, budget 1.5", cut.rejectDetail)
    }

    @Test
    fun `a missing session has no log rather than an empty one`(@TempDir root: File) {
        assertNull(SessionCatalogue.log(FileSessionStore(root), "2026-08-18_2115_Orion"))
    }
}
