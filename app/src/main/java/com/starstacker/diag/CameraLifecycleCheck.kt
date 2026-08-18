package com.starstacker.diag

import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.os.Debug
import android.os.SystemClock
import com.starstacker.camera.CameraAccess
import com.starstacker.camera.FramingRequest
import com.starstacker.camera.FramingSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * One reading of what this process is holding on to.
 *
 * File descriptors and threads are the two things a leaked `CameraDevice` costs that can be
 * counted from inside the process. Native heap is recorded because it is free to record and
 * deliberately kept out of the verdict: the allocator does not hand memory back promptly and
 * `ImageReader` buffers are recycled lazily, so its trend is noise at this timescale.
 */
data class Resources(val fds: Int, val threads: Int, val nativeKb: Long) {

    fun describe(): String = "fd %s · thr %s · native %.1f MB".format(
        if (fds < 0) "?" else fds.toString(),
        if (threads < 0) "?" else threads.toString(),
        nativeKb / 1024.0,
    )

    companion object {
        /** `/proc` is readable for a process's own state on every Android; -1 means it was not. */
        fun sample(): Resources = Resources(
            fds = File("/proc/self/fd").list()?.size ?: -1,
            threads = File("/proc/self/task").list()?.size ?: -1,
            nativeKb = Debug.getNativeHeapAllocatedSize() / 1024,
        )
    }
}

/**
 * Does the resource count grow *per cycle*?
 *
 * Pure, so the arithmetic is unit-tested on the JVM while the numbers it judges can only come
 * from a device. The one thing it must get right is that **warm-up is not a leak.** Measured on
 * the Nothing Phone (3a) Pro, a clean open/close loop climbs from 132 to ~173 descriptors over
 * its first handful of cycles — the vendor camera stack spinning up its own threads and their
 * descriptors, once — and is then flat for as long as the loop runs. Anything that judges the end
 * of the run against its beginning calls that a leak.
 *
 * So two statistics, both of which have to stay small:
 *
 * - **the rate across the warm tail** (the last half of the run), which is where a process that
 *   has finished initialising either holds steady or does not;
 * - **the median per-cycle step**, because a leak costs its descriptor on *every* cycle while a
 *   warm-up is a few large steps among zeroes — a median ignores the second and not the first.
 */
object LeakAnalysis {

    /**
     * A leaked `CameraDevice` costs at least one descriptor and one thread every cycle, so its
     * signature is a rate near 1.0. Half of that sits comfortably above the noise from the log
     * file being appended to and `/proc` being read.
     */
    const val FD_PER_CYCLE_LIMIT = 0.5
    const val THREAD_PER_CYCLE_LIMIT = 0.5

    /**
     * Twenty settled cycles, so the warm tail is at least ten and lands past the warm-up.
     *
     * Measured on 2026-08-18, warm-up costs about eight open/close cycles and about sixteen
     * configured sessions. Both earlier values for this constant — three, then nine — were shorter
     * than that, so both convicted a phase of leaking when what they were looking at was the ramp:
     * six sessions read as +1.00 threads per cycle, ten open/close cycles as +1.00 descriptors.
     * A phase too short to judge has to say so rather than guess, and saying so fails the run,
     * because the fix is more cycles rather than a shrug.
     */
    const val MINIMUM_CYCLES = 20

    data class Verdict(
        val phase: String,
        val settledCycles: Int,
        val fdPerCycle: Double,
        val fdMedianStep: Double,
        val threadPerCycle: Double,
        val threadMedianStep: Double,
        val nativeKbPerCycle: Double,
        val leaking: Boolean,
        /** False when `/proc` was unreadable or there were too few cycles — *not* a pass. */
        val conclusive: Boolean,
        val detail: String,
    )

    fun of(phase: String, samples: List<Resources>): Verdict {
        // The first cycle carries the largest one-time cost of all and never repeats.
        val settled = samples.drop(1)
        if (settled.size < MINIMUM_CYCLES) {
            return Verdict(
                phase = phase,
                settledCycles = settled.size,
                fdPerCycle = Double.NaN,
                fdMedianStep = Double.NaN,
                threadPerCycle = Double.NaN,
                threadMedianStep = Double.NaN,
                nativeKbPerCycle = Double.NaN,
                leaking = false,
                conclusive = false,
                detail = "$phase — leak: INCONCLUSIVE, ${samples.size} cycles where " +
                    "${MINIMUM_CYCLES + 1} are the fewest that show a trend",
            )
        }

        val tail = settled.takeLast(maxOf(2, (settled.size + 1) / 2))
        val fd = rate(tail.first().fds, tail.last().fds, tail.size - 1)
        val threads = rate(tail.first().threads, tail.last().threads, tail.size - 1)
        val fdStep = medianStep(settled.map { it.fds })
        val threadStep = medianStep(settled.map { it.threads })
        val native = (tail.last().nativeKb - tail.first().nativeKb).toDouble() / (tail.size - 1)
        val conclusive = !fd.isNaN() && !threads.isNaN()

        // NaN fails every comparison, so an unreadable /proc can never look like a leak — and
        // never like a pass either: `conclusive` is what says whether the question was answered.
        val leaking = fd >= FD_PER_CYCLE_LIMIT || threads >= THREAD_PER_CYCLE_LIMIT ||
            fdStep >= FD_PER_CYCLE_LIMIT || threadStep >= THREAD_PER_CYCLE_LIMIT

        return Verdict(
            phase = phase,
            settledCycles = settled.size,
            fdPerCycle = fd,
            fdMedianStep = fdStep,
            threadPerCycle = threads,
            threadMedianStep = threadStep,
            nativeKbPerCycle = native,
            leaking = leaking,
            conclusive = conclusive,
            detail = ("%s — leak: %s · fd %s to %s across the warm %d (%s/cycle, median step %s) · " +
                "threads %s to %s (%s/cycle, median step %s) · " +
                "native %+.0f kB/cycle (informational) · %d settled cycles").format(
                phase,
                if (!conclusive) "INCONCLUSIVE" else if (leaking) "LEAKING" else "none",
                count(tail.first().fds), count(tail.last().fds), tail.size,
                signed(fd), signed(fdStep),
                count(tail.first().threads), count(tail.last().threads),
                signed(threads), signed(threadStep),
                native,
                settled.size,
            ),
        )
    }

    private fun rate(first: Int, last: Int, span: Int): Double =
        if (first < 0 || last < 0 || span < 1) Double.NaN else (last - first).toDouble() / span

    /** Median of the per-cycle differences; NaN if any sample was unreadable. */
    private fun medianStep(values: List<Int>): Double {
        if (values.any { it < 0 } || values.size < 2) return Double.NaN
        val steps = values.zipWithNext { a, b -> b - a }.sorted()
        val middle = steps.size / 2
        return if (steps.size % 2 == 1) steps[middle].toDouble()
        else (steps[middle - 1] + steps[middle]) / 2.0
    }

    private fun count(value: Int) = if (value < 0) "?" else value.toString()

    private fun signed(rate: Double) = if (rate.isNaN()) "?" else "%+.2f".format(rate)
}

/**
 * **T-1.3's acceptance**, driven from `adb`: open and close the camera 50 times without leaking,
 * and leave it takeable by another app.
 *
 * ```
 * adb shell am start -n com.starstacker/.MainActivity --es diag lifecycle \
 *     --ei cycles 50 --ei sessions 30 --ez handoff true
 * ```
 *
 * Four phases, because a loop of the path that was always going to work tests very little:
 *
 * 1. **open/close** — [CameraAccess.withDevice], `cycles` times, timing each open and each close.
 *    This is the acceptance's own loop and the one the verdict is read from.
 * 2. **configured sessions** — a whole [FramingSession] opened, streamed and closed. This is the
 *    cycle the app actually performs; it allocates two `ImageReader`s, a capture session and a
 *    coroutine scope, which is where a leak would live if the bare open/close loop is clean.
 * 3. **throw** — the block inside `withDevice` throws. The `finally` must still close.
 * 4. **cancel** — the coroutine is cancelled around the moment the device arrives, so the device
 *    is handed to a callback with no coroutine left to receive it. If nothing closes it there it
 *    is gone until the process dies.
 *
 * **Re-opening the camera proves nothing about a leak in this process.** A second `openCamera` of
 * a device this process already holds does not fail — the framework disconnects the first client
 * and hands the camera over. So the loop cannot detect its own leak by carrying on, which is why
 * the evidence here is descriptor and thread counts per cycle ([LeakAnalysis]) and the camera
 * service's availability callback, which speaks for the whole phone rather than for us.
 *
 * The second clause — *another app can take the camera afterwards* — is answered by handing off
 * to the phone's own camera app and watching a camera go unavailable from over there.
 */
object CameraLifecycleCheck {

    private const val RELEASE_TIMEOUT_MS = 3_000L
    private const val HANDOFF_TIMEOUT_MS = 25_000L

    /** Long enough for the finalizer thread to run, short enough to pay fifty times. */
    private const val SETTLE_MS = 60L

    /** Enough to clear [LeakAnalysis.MINIMUM_CYCLES]; each costs about as much as a bare open. */
    private const val THROW_CYCLES = 25

    private const val OPEN_PHASE = "open/close"
    private const val SESSION_PHASE = "sessions"
    private const val THROW_PHASE = "throw"
    private const val CANCEL_PHASE = "cancel"

    /**
     * Where to cancel, as a fraction of the measured median open. Dense either side of 1.0
     * because that is where the cancellation and `onOpened` land together, with a few coarse
     * values below it to cover cancelling before the request has even reached the HAL.
     */
    private val CANCEL_FRACTIONS =
        listOf(0.05, 0.3, 0.5) + (70..130 step 5).map { it / 100.0 } + listOf(1.6, 2.0)

    /** The ladder runs twice: the interesting window is microseconds wide, so sample it twice. */
    private const val CANCEL_PASSES = 2

    data class Result(
        val cameraId: String,
        val openCycles: Int,
        /** The acceptance's own loop. */
        val leak: LeakAnalysis.Verdict,
        val phases: List<LeakAnalysis.Verdict>,
        val failures: List<String>,
        val releasesWitnessed: Int,
        val releasesExpected: Int,
        val availabilityIsSilent: Boolean,
        val handoff: String?,
    ) {
        val passed: Boolean
            get() = failures.isEmpty() && phases.isNotEmpty() &&
                phases.all { it.conclusive && !it.leaking }

        fun summary(): String = buildString {
            append(if (passed) "T-1.3 PASS" else "T-1.3 FAIL")
            append(" · camera ").append(cameraId)
            append(" · ").append(openCycles).append(" open/close cycles")
            append(" · released ").append(releasesWitnessed).append(" of ").append(releasesExpected)
            append(" · ").append(leak.detail)
            if (failures.isNotEmpty()) append(" · failures: ").append(failures.joinToString("; "))
            handoff?.let { append(" · handoff: ").append(it) }
        }
    }

    suspend fun run(
        access: CameraAccess,
        cameraId: String,
        cycles: Int,
        streamCycles: Int,
        framesPerStream: Int,
        iso: Int,
        exposureNs: Long,
        handoff: (() -> String?)?,
        log: (String) -> Unit,
    ): Result {
        log("--- camera lifecycle (T-1.3): camera $cameraId · $cycles open/close · $streamCycles configured sessions")

        val failures = mutableListOf<String>()
        val samples = LinkedHashMap<String, MutableList<Resources>>()
        val openTimes = mutableListOf<Long>()
        var releases = 0
        var expected = 0
        var silent = false
        var handoffResult: String? = null

        fun record(phase: String, sample: Resources) {
            samples.getOrPut(phase) { mutableListOf() } += sample
        }

        Watcher(access).use { watch ->
            log("baseline ${settle().describe()}")

            // ---- Phase 1: open and close, the acceptance's own loop.
            repeat(cycles) { index ->
                val mark = watch.mark()
                val startedAt = SystemClock.elapsedRealtime()
                var openedAt = startedAt
                val failure = attempt {
                    access.withDevice(cameraId) { openedAt = SystemClock.elapsedRealtime() }
                }
                val closedAt = SystemClock.elapsedRealtime()

                if (failure != null) {
                    failures += "open/close ${index + 1}: ${describe(failure)}"
                    log("cycle ${index + 1}: FAILED — ${describe(failure)}")
                    return@repeat
                }
                openTimes += openedAt - startedAt

                expected++
                val back = if (silent) null else watch.awaitAvailable(cameraId, mark, RELEASE_TIMEOUT_MS)
                if (back != null) releases++
                if (back == null && !silent && index == 0) {
                    silent = true
                    log(
                        "note: the camera service reported no availability change for a close this " +
                            "process made, so availability cannot witness release here — the " +
                            "descriptor counts and the handoff are the evidence",
                    )
                }

                val after = settle()
                record(OPEN_PHASE, after)
                log(
                    "cycle %d: open %d ms · close %d ms · release %s · %s".format(
                        index + 1,
                        openedAt - startedAt,
                        closedAt - openedAt,
                        releaseNote(back, closedAt),
                        after.describe(),
                    ),
                )
            }

            if (openTimes.isEmpty()) {
                log("the camera never opened — nothing further can be measured")
                return finish(cameraId, 0, samples, failures, releases, expected, silent, null, log)
            }

            val sorted = openTimes.sorted()
            val median = sorted[sorted.size / 2]
            log(
                "open/close done: %d cycles · open %d/%d/%d ms (min/median/max) · release witnessed %d of %d"
                    .format(openTimes.size, sorted.first(), median, sorted.last(), releases, expected),
            )

            // ---- Phase 2: the cycle the app actually performs.
            repeat(streamCycles) { index ->
                val mark = watch.mark()
                val startedAt = SystemClock.elapsedRealtime()
                var configuredAt = startedAt
                var frames = 0
                val failure = attempt {
                    FramingSession.open(access, cameraId).use { session ->
                        configuredAt = SystemClock.elapsedRealtime()
                        session.apply(FramingRequest(iso, exposureNs, null))
                        val budget = exposureNs / 1_000_000 *
                            (framesPerStream + FramingSession.PIPELINE_DEPTH_FRAMES) + 10_000L
                        withTimeoutOrNull(budget) {
                            session.frames.take(framesPerStream).collect { frames++ }
                        }
                    }
                }
                val closedAt = SystemClock.elapsedRealtime()

                if (failure != null) {
                    failures += "session ${index + 1}: ${describe(failure)}"
                    log("session ${index + 1}: FAILED — ${describe(failure)}")
                    return@repeat
                }
                // A configured session that stops delivering is how a buffer leak announces
                // itself: the queue fills, the repeating request stalls, and nothing throws.
                if (frames < framesPerStream) {
                    failures += "session ${index + 1}: $frames of $framesPerStream frames"
                }

                expected++
                val back = if (silent) null else watch.awaitAvailable(cameraId, mark, RELEASE_TIMEOUT_MS)
                if (back != null) releases++
                val after = settle()
                record(SESSION_PHASE, after)
                log(
                    "session %d: configure %d ms · %d/%d frames · close %d ms · release %s · %s".format(
                        index + 1,
                        configuredAt - startedAt,
                        frames, framesPerStream,
                        closedAt - configuredAt,
                        releaseNote(back, closedAt),
                        after.describe(),
                    ),
                )
            }

            // ---- Phase 3: the exception path. `withDevice` promises to close on any path.
            repeat(THROW_CYCLES) { index ->
                val mark = watch.mark()
                var entered = false
                val failure = attempt {
                    access.withDevice(cameraId) {
                        entered = true
                        throw DeliberateFailure()
                    }
                }
                when {
                    !entered -> failures += "throw ${index + 1}: never opened"
                    failure !is DeliberateFailure ->
                        failures += "throw ${index + 1}: expected the block's own exception, got ${describe(failure)}"
                }
                expected++
                val back = if (silent) null else watch.awaitAvailable(cameraId, mark, RELEASE_TIMEOUT_MS)
                if (back != null) releases++
                record(THROW_PHASE, settle())
            }
            log("throw: $THROW_CYCLES cycles · ${samples[THROW_PHASE]?.last()?.describe()}")

            // ---- Phase 4: cancelled mid-open.
            var completedAnyway = 0
            repeat(CANCEL_PASSES) { pass ->
                for (fraction in CANCEL_FRACTIONS) {
                    val at = maxOf(1L, (median * fraction).toLong())
                    val mark = watch.mark()
                    var device: CameraDevice? = null
                    val failure = attempt {
                        device = withTimeoutOrNull(at) { access.openDevice(cameraId) }
                    }
                    val cancelledAt = SystemClock.elapsedRealtime()
                    // Won the race rather than lost it: the open finished inside the budget, so
                    // this iteration owns the device and closes it like any other caller would.
                    val opened = device
                    if (opened != null) {
                        completedAnyway++
                        opened.close()
                    }
                    if (failure != null) failures += "cancel at $at ms: ${describe(failure)}"

                    expected++
                    val back = if (silent) null else watch.awaitAvailable(cameraId, mark, RELEASE_TIMEOUT_MS)
                    if (back != null) releases++
                    val after = settle()
                    record(CANCEL_PHASE, after)
                    log(
                        "cancel %d at %d ms (%.2f x median): %s · release %s · %s".format(
                            pass + 1, at, fraction,
                            if (opened != null) "opened before the cancellation" else "cancelled while opening",
                            releaseNote(back, cancelledAt),
                            after.describe(),
                        ),
                    )
                }
            }
            log(
                "cancel: ${CANCEL_FRACTIONS.size * CANCEL_PASSES} attempts, " +
                    "$completedAnyway completed before the cancellation",
            )

            // ---- Can this process still have the camera?
            val reopen = attempt { access.withDevice(cameraId) { } }
            if (reopen != null) failures += "final open: ${describe(reopen)}"
            log("final open after everything: ${if (reopen == null) "OK" else "FAILED"} · ${settle().describe()}")

            if (!watch.everFired) {
                log("the availability callback never fired at all — nothing here witnessed the camera service")
            }

            // ---- The acceptance's second clause.
            if (handoff != null) {
                val mark = watch.mark()
                val target = handoff()
                handoffResult = if (target == null) {
                    "no other camera app to hand off to"
                } else {
                    log("handing off to $target — waiting ${HANDOFF_TIMEOUT_MS / 1000} s for it to take a camera")
                    val taken = watch.awaitAnyUnavailable(mark, HANDOFF_TIMEOUT_MS)
                    if (taken != null) {
                        "$target took camera ${taken.cameraId} — the camera was free"
                    } else {
                        // Measured 2026-08-18: this phone's own camera app opens camera 4, the
                        // unpublished logical device, which is absent from getCameraIdList() and
                        // so never appears in an availability callback. The camera service logs
                        // the handover either way, and that log is the authority here.
                        "$target started, but no availability change reached this process within " +
                            "${HANDOFF_TIMEOUT_MS / 1000} s — read the handover off the service " +
                            "itself: adb shell dumpsys media.camera"
                    }
                }
                log("handoff: $handoffResult")
            } else {
                log(
                    "handoff not requested (--ez handoff true). Open the phone's own camera app and " +
                        "check it shows a picture — that is the acceptance's second clause",
                )
            }
        }

        return finish(
            cameraId, openTimes.size, samples, failures, releases, expected, silent, handoffResult, log,
        )
    }

    private fun finish(
        cameraId: String,
        openCycles: Int,
        samples: Map<String, List<Resources>>,
        failures: List<String>,
        releases: Int,
        expected: Int,
        silent: Boolean,
        handoff: String?,
        log: (String) -> Unit,
    ): Result {
        val phases = samples.map { (phase, readings) -> LeakAnalysis.of(phase, readings) }
        phases.forEach { log(it.detail) }
        return Result(
            cameraId = cameraId,
            openCycles = openCycles,
            leak = phases.firstOrNull { it.phase == OPEN_PHASE }
                ?: LeakAnalysis.of(OPEN_PHASE, emptyList()),
            phases = phases,
            failures = failures,
            releasesWitnessed = releases,
            releasesExpected = expected,
            availabilityIsSilent = silent,
            handoff = handoff,
        ).also { log(it.summary()) }
    }

    /**
     * The camera service can mark a camera available before our own `close()` has returned, so
     * this is occasionally negative. That is worth printing rather than clamping: it says the
     * release happened inside the call rather than after it.
     */
    private fun releaseNote(event: Watcher.Event?, closedAt: Long): String =
        event?.let { "%+d ms".format(it.atMs - closedAt) } ?: "—"

    /**
     * Collect before counting: a descriptor held by an object waiting to be finalized looks
     * exactly like a leaked one from `/proc`, and the difference between them is the question.
     */
    private suspend fun settle(): Resources {
        System.gc()
        delay(SETTLE_MS)
        return Resources.sample()
    }

    /** Like `runCatching`, but a cancelled *caller* stays cancelled instead of being swallowed. */
    private suspend fun attempt(block: suspend () -> Unit): Throwable? = try {
        block()
        null
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (t: Throwable) {
        t
    }

    private fun describe(t: Throwable?): String =
        t?.let { "${it::class.java.simpleName}: ${it.message}" } ?: "no failure"

    private class DeliberateFailure : Exception("deliberate failure inside withDevice")

    /**
     * The camera service's own account of who holds a camera.
     *
     * Events are sequenced so a wait can ask for *the next* change rather than the last one: with
     * a replayed state alone, a stale "available" from before the open answers instantly and the
     * measurement becomes a tautology.
     */
    private class Watcher(access: CameraAccess) : AutoCloseable {

        data class Event(
            val seq: Long,
            val cameraId: String,
            val available: Boolean,
            val atMs: Long,
        )

        /** Written only from the camera handler thread, read from anywhere. */
        @Volatile private var counter = 0L

        @Volatile var everFired = false
            private set

        // Replayed, because the event that matters can arrive between close() and the start of
        // the wait for it.
        private val events = MutableSharedFlow<Event>(
            replay = 32,
            extraBufferCapacity = 32,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

        private val registration = access.watchAvailability(
            object : CameraManager.AvailabilityCallback() {
                override fun onCameraAvailable(cameraId: String) = emit(cameraId, true)
                override fun onCameraUnavailable(cameraId: String) = emit(cameraId, false)
            },
        )

        private fun emit(cameraId: String, available: Boolean) {
            everFired = true
            counter += 1
            events.tryEmit(Event(counter, cameraId, available, SystemClock.elapsedRealtime()))
        }

        fun mark(): Long = counter

        suspend fun awaitAvailable(cameraId: String, after: Long, timeoutMs: Long): Event? =
            withTimeoutOrNull(timeoutMs) {
                events.first { it.seq > after && it.cameraId == cameraId && it.available }
            }

        suspend fun awaitAnyUnavailable(after: Long, timeoutMs: Long): Event? =
            withTimeoutOrNull(timeoutMs) { events.first { it.seq > after && !it.available } }

        override fun close() = registration.close()
    }
}
