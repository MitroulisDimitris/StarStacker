package com.starstacker.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** T-0.7. The seam exists so durations can be driven rather than waited out. */
class ClockTest {

    @Test
    fun `a fixed clock does not move on its own`() {
        val clock = FixedClock(epochMs = 1_000L)

        assertEquals(1_000L, clock.nowEpochMs())
        assertEquals(1_000L, clock.nowEpochMs())
    }

    @Test
    fun `advancing moves both clocks together`() {
        val clock = FixedClock(epochMs = 1_000L, nanos = 5_000_000_000L)

        clock.advance(2_500L)

        assertEquals(3_500L, clock.nowEpochMs())
        assertEquals(7_500_000_000L, clock.elapsedRealtimeNanos())
    }

    /**
     * The distinction the interface exists for: a 15-minute darks prompt measured on wall-clock
     * time resolves instantly the moment the network corrects the clock backwards.
     */
    @Test
    fun `a wall-clock jump does not have to move the monotonic clock`() {
        var epoch = 10_000L
        val clock = object : Clock {
            override fun nowEpochMs(): Long = epoch
            override fun elapsedRealtimeNanos(): Long = 1_000_000_000L
        }

        val monotonicBefore = clock.elapsedRealtimeNanos()
        epoch -= 60_000L

        assertTrue(clock.nowEpochMs() < 10_000L)
        assertEquals(monotonicBefore, clock.elapsedRealtimeNanos())
    }
}
