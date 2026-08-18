package com.starstacker.core

/**
 * T-0.7 — time, behind a seam.
 *
 * Two clocks, because they answer different questions and are not interchangeable:
 *
 * - [nowEpochMs] is wall-clock. It names sessions and timestamps frames, and it can jump backwards
 *   when the network corrects it.
 * - [elapsedRealtimeNanos] is monotonic and counts through sleep. Every *duration* must come from
 *   it — a timeout measured on wall-clock time silently doubles or vanishes when the clock steps,
 *   and a 15-minute darks prompt that resolves instantly is worse than one that never fires.
 *
 * §1.14's discovery makes the second one load-bearing: `SensorEvent.timestamp` and the camera's
 * `SENSOR_TIMESTAMP` are both on this base, which is the only reason a gyro window can be compared
 * against an exposure at all.
 */
interface Clock {
    fun nowEpochMs(): Long
    fun elapsedRealtimeNanos(): Long
}

/** The real one. The only implementation that touches the platform. */
object SystemClock : Clock {
    override fun nowEpochMs(): Long = System.currentTimeMillis()
    override fun elapsedRealtimeNanos(): Long = android.os.SystemClock.elapsedRealtimeNanos()
}

/**
 * A clock tests drive by hand.
 *
 * Deliberately not thread-safe and deliberately not clever: a test that needs a racing clock has a
 * design problem the clock cannot fix.
 */
class FixedClock(
    private var epochMs: Long = 0L,
    private var nanos: Long = 0L,
) : Clock {
    override fun nowEpochMs(): Long = epochMs
    override fun elapsedRealtimeNanos(): Long = nanos

    fun advance(millis: Long) {
        epochMs += millis
        nanos += millis * 1_000_000
    }
}
