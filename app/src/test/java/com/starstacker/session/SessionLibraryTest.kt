package com.starstacker.session

import com.starstacker.calibration.Staleness
import com.starstacker.stacking.MasterVersions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Phase 4's session library — T-6.2, T-6.3, T-6.5, T-6.6 and T-6.7.
 *
 * Each of these is a small piece of logic guarding a decision someone makes about a night's work,
 * so the tests are arranged around the *decisions* — which frames get stacked, which master a
 * reader sees, what is safe to delete — rather than around the data structures.
 */
class SessionLibraryTest {

    private fun light(index: Int, accepted: Boolean, included: Boolean? = null) = FrameRecord(
        index = index,
        fileName = "light_%03d.dng".format(index),
        kind = FrameKind.LIGHT,
        capturedAtEpochMs = index * 1000L,
        iso = 800,
        exposureNs = 1_000_000_000L,
        temperatureC = null,
        hfr = null,
        starCount = null,
        eccentricity = null,
        backgroundAdu = null,
        accepted = accepted,
        rejectReason = if (accepted) null else RejectReason.REGISTRATION,
        included = included,
    )

    private fun log(vararg frames: FrameRecord, camera: String = "0") = SessionLog(
        info = SessionInfo(
            sessionId = "s", startedAtEpochMs = 1_000L, deviceModel = "x", cameraId = camera,
            plannedIso = 800, plannedExposureNs = 1_000_000_000L,
            plannedLightCount = frames.size, plannedDarkCount = 0,
        ),
        frames = frames.toList(),
    )

    // ------------------------------------------------------------------ T-6.3

    @Test
    fun `the stackable set is the gate's verdict until someone overrides it`() {
        val l = log(light(1, true), light(2, false), light(3, true))
        assertEquals(listOf(1, 3), l.stackable.map { it.index })
    }

    @Test
    fun `a rejected frame can be forced back in without losing why it was rejected`() {
        val l = log(light(1, true), light(2, false)).withOverride(2, include = true)

        assertEquals(listOf(1, 2), l.stackable.map { it.index })

        // The whole point of keeping the two fields apart: the gate's reasoning survives.
        val frame = l.lights.first { it.index == 2 }
        assertFalse(frame.accepted, "the gate still says it rejected this")
        assertEquals(RejectReason.REGISTRATION, frame.rejectReason, "and still says why")
        assertEquals(true, frame.included)
    }

    @Test
    fun `an accepted frame can be excluded by hand`() {
        val l = log(light(1, true), light(2, true)).withOverride(1, include = false)
        assertEquals(listOf(2), l.stackable.map { it.index })
    }

    /**
     * An override that agrees with the gate is not an override.
     *
     * Storing agreement would silently pin the frame: a later change to the gate — a better
     * registration fallback, say — could no longer move it, and nothing would say why.
     */
    @Test
    fun `agreeing with the gate clears the override rather than recording it`() {
        val l = log(light(1, true)).withOverride(1, include = true)
        assertNull(l.lights.first().included)
        assertTrue(l.overridden.isEmpty())
    }

    @Test
    fun `clearing an override restores the gate's verdict`() {
        val l = log(light(1, false))
            .withOverride(1, include = true)
            .withOverride(1, include = null)

        assertTrue(l.stackable.isEmpty())
        assertNull(l.lights.first().included)
    }

    @Test
    fun `overrides survive session_json`() {
        val original = log(light(1, true), light(2, false)).withOverride(2, include = true)
        val decoded = SessionLog.decode(original.encode())

        assertEquals(listOf(1, 2), decoded.stackable.map { it.index })
        assertEquals(1, decoded.overridden.size)
    }

    // ------------------------------------------------------------------ T-6.2

    private fun summary(
        name: String,
        label: String,
        startedAt: Long,
        camera: String = "0",
        seconds: Double = 60.0,
        bytes: Long = 1000,
        stacked: Boolean = false,
        state: SessionState = SessionState.DONE,
    ) = SessionSummary(
        folderName = name, label = label, startedAtEpochMs = startedAt,
        lights = 10, accepted = 9, darks = 2, integrationSeconds = seconds,
        state = state, sizeBytes = bytes, stacked = stacked, cameraId = camera,
    )

    /**
     * A session nobody named.
     *
     * Its label *is* its start time, which is what [SessionSummary.labelIsStartTime] detects — so
     * the label has to be built from the same formatter rather than typed in, or the fixture is
     * just a session that happens to be called "02:14".
     */
    private fun unlabelled(name: String, startedAt: Long, camera: String = "3") =
        summary(name, "", startedAt, camera = camera).let { it.copy(label = it.startedAtClock()) }

    private fun sessions() = listOf(
        summary("a", "Orion", 3000, camera = "0", seconds = 300.0, bytes = 300, stacked = true),
        unlabelled("b", 1000).copy(integrationSeconds = 100.0, sizeBytes = 900),
        summary("c", "Andromeda", 2000, camera = "0", seconds = 200.0, bytes = 100),
    )

    @Test
    fun `newest first is the default and is a total order`() {
        val sorted = SessionFilter().apply(sessions())
        assertEquals(listOf("a", "c", "b"), sorted.map { it.folderName })
    }

    @Test
    fun `ties break by folder name so the list does not reshuffle between scans`() {
        val tied = listOf(
            summary("z", "One", 5000),
            summary("a", "Two", 5000),
        )
        assertEquals(listOf("a", "z"), SessionFilter().apply(tied).map { it.folderName })
    }

    /**
     * An unnamed session's label is its start time, so sorting alphabetically would file them all
     * under whatever hour they began — a block of "02:14" in the middle of the alphabet.
     */
    @Test
    fun `sorting by target puts unlabelled sessions last`() {
        val sorted = SessionFilter(sort = SessionFilter.Sort.TARGET).apply(sessions())
        assertEquals(listOf("c", "a", "b"), sorted.map { it.folderName })
    }

    @Test
    fun `filters compose`() {
        val filter = SessionFilter(cameras = setOf("0"), onlyStacked = true)
        assertEquals(listOf("a"), filter.apply(sessions()).map { it.folderName })
    }

    @Test
    fun `the query matches the label and the folder name, case-insensitively`() {
        assertEquals(
            listOf("c"),
            SessionFilter(query = "andro").apply(sessions()).map { it.folderName },
        )
        assertEquals(
            listOf("b"),
            SessionFilter(query = "B").apply(sessions()).map { it.folderName },
        )
    }

    @Test
    fun `an active filter says so, and the default does not pretend to be one`() {
        assertTrue(SessionFilter().isDefault)
        assertEquals("Newest first", SessionFilter().describe())
        assertTrue(SessionFilter(query = "orion").describe().contains("orion"))
    }

    @Test
    fun `only the cameras and states actually present are offered`() {
        assertEquals(listOf("0", "3"), SessionFilter.camerasIn(sessions()))
        assertEquals(listOf(SessionState.DONE), SessionFilter.statesIn(sessions()))
    }

    // ------------------------------------------------------------------ T-6.6

    @Test
    fun `a stack that recorded nothing is unknown rather than fresh`() {
        val report = Staleness.compare(emptyMap(), mapOf("flat.0" to "123/16f/4096x3072"))
        assertEquals(Staleness.Verdict.UNKNOWN, report.verdict)
        assertFalse(report.isStale, "unknown is not stale either — it is unknown")
        assertTrue(report.describe().contains("does not record"))
    }

    @Test
    fun `matching versions are fresh`() {
        val v = mapOf("flat.0" to "123/16f/4096x3072")
        assertEquals(Staleness.Verdict.FRESH, Staleness.compare(v, v).verdict)
    }

    @Test
    fun `a replaced flat is stale and says what changed`() {
        val report = Staleness.compare(
            mapOf("flat.0" to "123/16f/4096x3072"),
            mapOf("flat.0" to "999/24f/4096x3072"),
        )
        assertTrue(report.isStale)
        assertTrue(report.changes.single().contains("123/16f"), report.changes.toString())
        assertTrue(report.changes.single().contains("999/24f"), report.changes.toString())
    }

    @Test
    fun `a flat that has appeared since is stale too`() {
        // The case FR-10.4.2 most wants surfaced: a master stacked with no flat, on a camera that
        // now has one. Restacking would genuinely change the picture.
        val report = Staleness.compare(
            mapOf("flat.0" to "123/16f/4096x3072"),
            mapOf("flat.0" to "123/16f/4096x3072", "flat.3" to "500/16f/4096x3072"),
        )
        assertTrue(report.isStale)
        assertTrue(report.changes.single().contains("new since"), report.changes.toString())
    }

    @Test
    fun `two flats from the same night with different frame counts are different masters`() {
        // A timestamp alone would call these the same, which is why the version carries the count.
        val a = Staleness.flatVersion(flatInfo(capturedAt = 100, frames = 8))
        val b = Staleness.flatVersion(flatInfo(capturedAt = 100, frames = 16))
        assertTrue(a != b, "$a and $b should differ")
    }

    private fun flatInfo(capturedAt: Long, frames: Int) =
        com.starstacker.calibration.CalibrationLibrary.FlatInfo(
            cameraId = "0", capturedAtEpochMs = capturedAt, width = 4096, height = 3072,
            frames = frames, falloff = 4.0, iso = 100, exposureNs = 1000L, focusDiopters = null,
        )

    // ------------------------------------------------------------------ T-6.7

    @Test
    fun `deleting the subs is refused when there is no master to keep`() {
        val plan = StoragePlan.of(
            "s", lightBytes = 1000, darkBytes = 200, masterBytes = 0,
            hasMaster = false, state = SessionState.DONE,
        )
        val subs = plan.actions.first { it.label.startsWith("Delete subs") }
        assertFalse(subs.available)
        assertTrue(
            subs.blockedBecause!!.contains("delete the whole session"),
            subs.blockedBecause!!,
        )
    }

    @Test
    fun `deleting the subs is offered once a master exists, and is marked irreversible`() {
        val plan = StoragePlan.of(
            "s", lightBytes = 1000, darkBytes = 200, masterBytes = 150,
            hasMaster = true, state = SessionState.DONE,
        )
        val subs = plan.actions.first { it.label.startsWith("Delete subs") }
        assertTrue(subs.available)
        assertEquals(1200L, subs.bytes)
        assertFalse(subs.reversible, "subs are a night that happened; they do not come back")
    }

    @Test
    fun `deleting the masters is the only reversible action`() {
        val plan = StoragePlan.of(
            "s", lightBytes = 1000, darkBytes = 200, masterBytes = 150,
            hasMaster = true, state = SessionState.DONE,
        )
        assertEquals(
            listOf("Delete the masters, keep the subs"),
            plan.actions.filter { it.reversible }.map { it.label },
        )
    }

    @Test
    fun `a session still capturing is left alone`() {
        val plan = StoragePlan.of(
            "s", lightBytes = 1000, darkBytes = 200, masterBytes = 150,
            hasMaster = true, state = SessionState.CAPTURING,
        )
        assertFalse(plan.actions.first { it.label.startsWith("Delete subs") }.available)
    }

    @Test
    fun `the best action is the one that frees most of what can be freed`() {
        val plan = StoragePlan.of(
            "s", lightBytes = 5000, darkBytes = 200, masterBytes = 150,
            hasMaster = true, state = SessionState.DONE,
        )
        assertEquals("Delete subs, keep the master", plan.best?.label)
    }

    @Test
    fun `totals only count stacked sessions as reclaimable`() {
        val totals = StoragePlan.totals(sessions())
        assertEquals(3, totals.sessions)
        assertEquals(1300L, totals.totalBytes)
        assertEquals(300L, totals.reclaimableBytes, "only session a is stacked")
    }

    // ------------------------------------------------------------------ T-6.5

    @Test
    fun `a session with no index reads its master from the old location`(@TempDir dir: File) {
        // Every session already on the phone is in this state, and must keep working.
        File(dir, SessionLayout.MASTER).mkdirs()
        assertEquals(
            File(dir, SessionLayout.MASTER).absolutePath,
            MasterVersions.currentDir(dir).absolutePath,
        )
    }

    @Test
    fun `versions are allocated in order and do not overwrite each other`(@TempDir dir: File) {
        val (first, firstId) = MasterVersions.allocate(dir)
        MasterVersions.record(dir, version(firstId, "sigma"))
        val (second, secondId) = MasterVersions.allocate(dir)
        MasterVersions.record(dir, version(secondId, "median"))

        assertEquals(1, firstId)
        assertEquals(2, secondId)
        assertTrue(first.absolutePath != second.absolutePath)
        assertEquals(listOf(1, 2), MasterVersions.read(dir).versions.map { it.id })
    }

    @Test
    fun `the newest version becomes current, and an older one can be chosen back`(@TempDir dir: File) {
        MasterVersions.allocate(dir).also { MasterVersions.record(dir, version(it.second, "sigma")) }
        MasterVersions.allocate(dir).also { MasterVersions.record(dir, version(it.second, "median")) }

        assertEquals(2, MasterVersions.read(dir).current?.id)
        assertEquals("v2", MasterVersions.currentDir(dir).name)

        MasterVersions.makeCurrent(dir, 1)
        assertEquals(1, MasterVersions.read(dir).current?.id)
        assertEquals("v1", MasterVersions.currentDir(dir).name)
    }

    @Test
    fun `the last version cannot be deleted`(@TempDir dir: File) {
        MasterVersions.allocate(dir).also { MasterVersions.record(dir, version(it.second, "sigma")) }
        val after = MasterVersions.delete(dir, 1)
        assertEquals(1, after.versions.size, "deleting the last version would orphan the reader")
    }

    @Test
    fun `deleting the current version falls back to another`(@TempDir dir: File) {
        MasterVersions.allocate(dir).also { MasterVersions.record(dir, version(it.second, "sigma")) }
        MasterVersions.allocate(dir).also { MasterVersions.record(dir, version(it.second, "median")) }

        val after = MasterVersions.delete(dir, 2)
        assertEquals(listOf(1), after.versions.map { it.id })
        assertEquals(1, after.current?.id)
    }

    @Test
    fun `the index round-trips`(@TempDir dir: File) {
        MasterVersions.allocate(dir).also { MasterVersions.record(dir, version(it.second, "sigma")) }
        val reread = MasterVersions.read(dir)
        assertEquals("sigma", reread.versions.single().label)
        assertEquals(114, reread.versions.single().frames)
    }

    @Test
    fun `a comparison names what changed`() {
        val a = version(1, "sigma").copy(settings = mapOf("method" to "SIGMA_CLIP", "keep" to "95"))
        val b = version(2, "median").copy(settings = mapOf("method" to "MEDIAN", "keep" to "95"))

        val diff = MasterVersions.differences(a, b)
        assertEquals(1, diff.size)
        assertTrue(diff.single().contains("SIGMA_CLIP") && diff.single().contains("MEDIAN"), diff.toString())
    }

    @Test
    fun `identical settings are not reported as identical results`() {
        // Same settings and same frames, different output: the inputs or the code changed, and
        // claiming they are the same would be a claim this cannot support.
        val a = version(1, "sigma")
        val b = version(2, "sigma")
        assertTrue(MasterVersions.differences(a, b).single().contains("must have changed"))
    }

    private fun version(id: Int, label: String) = MasterVersions.Version(
        id = id,
        createdAtEpochMs = id * 1000L,
        settings = mapOf("method" to "SIGMA_CLIP"),
        label = label,
        frames = 114,
        region = "3887x2828 at (168, 209)",
    )
}
