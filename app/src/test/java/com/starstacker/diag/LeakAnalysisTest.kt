package com.starstacker.diag

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * T-1.3's verdict, tested where it can be: the numbers come from a device, the judgement of them
 * does not have to.
 */
class LeakAnalysisTest {

    private fun verdict(fds: List<Int>, threads: List<Int> = emptyList(), nativeKb: Long = 60_000) =
        LeakAnalysis.of(
            "test",
            fds.mapIndexed { i, fd ->
                Resources(fds = fd, threads = threads.getOrElse(i) { 40 }, nativeKb = nativeKb)
            },
        )

    @Test
    fun `a flat run is not leaking`() {
        val result = verdict(List(50) { 412 })

        assertFalse(result.leaking)
        assertTrue(result.conclusive)
        assertEquals(0.0, result.fdPerCycle)
        assertEquals(49, result.settledCycles)
    }

    @Test
    fun `one descriptor per cycle is the signature of a leak`() {
        val result = verdict(List(50) { 412 + it })

        assertTrue(result.leaking)
        assertTrue(result.conclusive)
        assertEquals(1.0, result.fdPerCycle)
        assertEquals(1.0, result.fdMedianStep)
    }

    /**
     * The trap this class exists for, and the shape actually measured on the device: a clean loop
     * climbs 132 → 173 descriptors over its first few cycles as the vendor camera stack spins up,
     * then holds. Judged end to end that is a leak of nearly two descriptors a cycle; judged
     * across the warm tail it is what it is.
     */
    @Test
    fun `warm-up is not a leak`() {
        val result = verdict(listOf(132, 140, 141, 161, 165, 167, 173) + List(43) { 173 })

        assertFalse(result.leaking)
        assertEquals(0.0, result.fdPerCycle)
        assertEquals(0.0, result.fdMedianStep)
    }

    /** A leak that only starts once the loop is warm is still a leak. */
    @Test
    fun `a leak after the warm-up is caught`() {
        val result = verdict(List(20) { 412 } + List(20) { 412 + it })

        assertTrue(result.leaking)
        assertTrue(result.fdPerCycle >= LeakAnalysis.FD_PER_CYCLE_LIMIT)
    }

    /**
     * A tail that happens to end where it started hides a leak from the rate; the median step
     * is the second opinion that does not.
     */
    @Test
    fun `a leak the tail rate misses is caught by the median step`() {
        // Climbs a descriptor per cycle, then hands 20 back in one go at the very end.
        val result = verdict(List(30) { 412 + it } + listOf(412))

        assertTrue(result.leaking)
        assertTrue(result.fdPerCycle < LeakAnalysis.FD_PER_CYCLE_LIMIT)
        assertEquals(1.0, result.fdMedianStep)
    }

    @Test
    fun `a leaked thread convicts on its own`() {
        val result = verdict(fds = List(30) { 412 }, threads = List(30) { 40 + it })

        assertTrue(result.leaking)
        assertEquals(1.0, result.threadPerCycle)
    }

    /** A drift of a descriptor every few cycles is the log file, not the camera. */
    @Test
    fun `noise below the limit passes`() {
        val result = verdict(List(50) { 412 + it / 10 })

        assertFalse(result.leaking)
        assertTrue(result.fdPerCycle < LeakAnalysis.FD_PER_CYCLE_LIMIT)
    }

    /** Unreadable `/proc` must not be reported as a clean run. */
    @Test
    fun `an unreadable proc is inconclusive rather than clean`() {
        val result = verdict(List(30) { -1 })

        assertFalse(result.conclusive)
        assertFalse(result.leaking)
        assertTrue(result.detail.contains("INCONCLUSIVE"))
    }

    @Test
    fun `too few cycles is inconclusive`() {
        val result = verdict(listOf(412, 413, 414))

        assertFalse(result.conclusive)
        assertTrue(result.detail.contains("INCONCLUSIVE"))
    }

    /**
     * The session phase of the 2026-08-18 run, verbatim: twelve cycles whose thread count was
     * still climbing out of warm-up. Judged over its six-reading tail that was +0.80 threads per
     * cycle, and the thirty-cycle run that followed showed it flat at 46 from cycle sixteen on.
     * A phase too short to outlast its own warm-up has nothing to say and must say that.
     */
    @Test
    fun `a phase too short to judge is inconclusive rather than leaking`() {
        val result = verdict(
            fds = List(12) { 166 },
            threads = listOf(35, 37, 37, 38, 41, 41, 41, 41, 43, 45, 45, 45),
        )

        assertFalse(result.leaking)
        assertFalse(result.conclusive)
        assertTrue(result.detail.contains("INCONCLUSIVE"))
    }

    /**
     * The same path measured long enough: threads climb to 46 by the sixteenth session and hold.
     * A bounded pool that has stopped growing is not a leak, and thirty cycles can see that.
     */
    @Test
    fun `a bounded pool that plateaus is not a leak`() {
        val result = verdict(
            fds = List(30) { 173 },
            threads = listOf(36, 38, 39, 42, 44, 43, 43, 43, 43, 43, 45, 45, 45, 45, 45) +
                List(15) { 46 },
        )

        assertFalse(result.leaking)
        assertTrue(result.conclusive)
        assertTrue(result.threadPerCycle < LeakAnalysis.THREAD_PER_CYCLE_LIMIT)
    }

    @Test
    fun `native heap growth alone never convicts`() {
        val growing = List(30) { Resources(fds = 412, threads = 40, nativeKb = 60_000 + it * 4_096L) }
        val result = LeakAnalysis.of("test", growing)

        assertFalse(result.leaking)
        assertTrue(result.nativeKbPerCycle > 0)
    }

    @Test
    fun `the phase names itself in the detail line`() {
        assertTrue(LeakAnalysis.of("cancel", List(30) { Resources(1, 1, 1) }).detail.startsWith("cancel"))
    }
}
