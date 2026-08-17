package com.starstacker.focus

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * T-2.5. The monitor exists to raise a flag when focus walks away mid-session — and, just as
 * importantly, to stay quiet when it hasn't. A drift alert that fires on one hazy frame gets
 * ignored, and then the real one gets ignored too.
 */
class FocusMonitorTest {

    private fun stored(hfr: Double) = FocusRecord(
        cameraId = "0",
        fixedFocus = false,
        diopters = 0.05f,
        hfr = hfr,
        starCount = 200,
        altitudeDeg = 45.0,
        exposureNs = 12_000_000_000L,
        iso = 800,
        verdict = "CLEAR_MINIMUM",
        capturedAtEpochMs = 0L,
    )

    @Test
    fun `focus holding at the reference reads as locked`() {
        val monitor = FocusMonitor(referenceHfr = 2.0)
        repeat(5) { monitor.accept(2.05, starCount = 200) }
        assertEquals(FocusStatus.LOCKED, monitor.status)
    }

    @Test
    fun `nothing is claimed until enough frames have been measured`() {
        val monitor = FocusMonitor(referenceHfr = 2.0)
        monitor.accept(2.0, 200)
        assertEquals(FocusStatus.UNKNOWN, monitor.status)
        monitor.accept(2.0, 200)
        assertEquals(FocusStatus.UNKNOWN, monitor.status)
        monitor.accept(2.0, 200)
        assertEquals(FocusStatus.LOCKED, monitor.status)
    }

    @Test
    fun `one bad frame does not trip the alert`() {
        val monitor = FocusMonitor(referenceHfr = 2.0)
        repeat(4) { monitor.accept(2.0, 200) }
        monitor.accept(6.0, 200)
        assertEquals(FocusStatus.LOCKED, monitor.status, "a single hazy frame raised the alert")
    }

    @Test
    fun `a sustained rise is drift, and a large one is lost focus`() {
        val drifting = FocusMonitor(referenceHfr = 2.0)
        repeat(5) { drifting.accept(2.9, 200) }
        assertEquals(FocusStatus.DRIFTING, drifting.status)

        val lost = FocusMonitor(referenceHfr = 2.0)
        repeat(5) { lost.accept(4.0, 200) }
        assertEquals(FocusStatus.LOST, lost.status)
    }

    @Test
    fun `frames with no stars say nothing about focus`() {
        val monitor = FocusMonitor(referenceHfr = 2.0)
        repeat(5) { monitor.accept(null, starCount = 0) }
        assertEquals(FocusStatus.UNKNOWN, monitor.status)

        repeat(5) { monitor.accept(9.0, starCount = 2) }
        assertEquals(FocusStatus.UNKNOWN, monitor.status, "a cloudy sky was read as bad focus")
    }

    @Test
    fun `session-start verification only complains about degradation`() {
        // Better seeing than the night the sweep ran is not a reason to sweep again.
        assertEquals(
            FocusStatus.LOCKED,
            FocusMonitor.verify(measuredHfr = 1.5, starCount = 200, stored = stored(2.0)),
        )
        assertEquals(
            FocusStatus.DRIFTING,
            FocusMonitor.verify(measuredHfr = 2.8, starCount = 200, stored = stored(2.0)),
        )
        assertEquals(
            FocusStatus.LOST,
            FocusMonitor.verify(measuredHfr = 5.0, starCount = 200, stored = stored(2.0)),
        )
        assertEquals(
            FocusStatus.UNKNOWN,
            FocusMonitor.verify(measuredHfr = null, starCount = 0, stored = stored(2.0)),
        )
    }

    @Test
    fun `a very sharp reference is not hair-triggered by seeing`() {
        // 1.0 px reference: without the absolute margin, 1.26 px would already read as drift.
        val monitor = FocusMonitor(referenceHfr = 1.0)
        repeat(5) { monitor.accept(1.3, 200) }
        assertEquals(FocusStatus.LOCKED, monitor.status)
    }
}
