package com.starstacker.diag

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.util.Range
import android.util.Size
import com.starstacker.camera.CameraAccess
import com.starstacker.camera.ManualRequest
import com.starstacker.camera.SequenceSession

/**
 * OI-26 — what does changing the exposure between frames actually cost?
 *
 * ### The question, and why a guess would not do
 *
 * T-11.9 shoots a moon and its landscape in one session by alternating two exposures roughly four
 * orders of magnitude apart: sub-millisecond for the disc, seconds for the ground. The plan costed
 * that against the **2 ms per-frame overhead** measured in §4 — but that figure came from a
 * *repeating request at a constant exposure*, where the 25 MB DNG write hides entirely behind the
 * next 1 s frame. Neither half of that holds here:
 *
 * - Every switch is a new `setRepeatingRequest`, and the sensor takes some number of frames to
 *   apply it. Those frames are warm-up, not subs (D-21, and `nextVerifiedFrame` exists because of
 *   it).
 * - At 0.13 ms there is no exposure for the write to hide behind, and the sensor's own minimum
 *   frame duration becomes the floor instead.
 *
 * So the honest answer needs both measured, which is what this does.
 *
 * ### The shape of the measurement
 *
 * Four phases, and the arithmetic falls out of comparing them:
 *
 * 1. **Steady long** — one `apply`, then time N frames. Gives the per-frame cost `L` with no
 *    switching in it.
 * 2. **Steady short** — the same at the short exposure, giving `S`. This is also the phase that
 *    answers "are short frames free?", because if the sensor floors at ~33 ms a frame then 120 of
 *    them cost 4 s rather than the 0.26 s the plan currently claims.
 * 3. **Alternating** — `apply` before every frame, so N frames carry N switches. Anything above
 *    `(N/2)(L + S)` is switching cost.
 * 4. **Blocks** — `apply` once per block of [DEFAULT_BLOCK], so the same N frames carry N/block
 *    switches. If the per-switch cost is the same as phase 3's, blocking works exactly as the plan
 *    assumes and the block size is a free parameter.
 *
 * Frames are received and closed, never written. That is deliberate: this measures the *sensor's*
 * response to a changed request, and mixing the DNG write into it would confound the two. The
 * write cost is already known separately — 0.042 s per 24 MiB file (OI-5) — and phase 2's floor
 * says whether it can still hide.
 *
 * ### Why it counts skipped frames as well as timing them
 *
 * A switch that costs one frame on a phone whose minimum frame duration is 33 ms costs 33 ms; the
 * same switch on a 2.5 s exposure costs 2.5 s. Reporting the *count* rather than only the
 * milliseconds is what makes the number transferable to hardware nobody here has seen — which is
 * the same rule §1.45 applies to the camera and to the drift rate.
 */
object ExposureSwitchCheck {

    /**
     * The block phase 4 actually shoots. Small on purpose: it has to fit several blocks inside
     * [DEFAULT_FRAMES] to carry more than one switch, and a 20-frame block of 2.5 s exposures
     * would be 50 s of probe for a single measurement.
     */
    const val DEFAULT_BLOCK = 4

    /** The interleave block §1.45 proposes. Phase 3's per-switch cost is extrapolated to it. */
    const val PLAN_BLOCK = 20

    /** Frames per phase. Small, because phase 1 pays the long exposure for every one of them. */
    const val DEFAULT_FRAMES = 12

    /**
     * Long enough to cover a ten-deep pipeline draining at the *previous* phase's exposure before
     * the burst's own frames appear. The first attempt used 40 s and timed out in the drain.
     */
    private const val BURST_TIMEOUT_MS = 120_000L

    private class Phase(
        val name: String,
        val frames: Int,
        val switches: Int,
        val elapsedNs: Long,
        val skipped: Int,
        val longFrames: Int,
        val shortFrames: Int,
        val settleWaitsNs: List<Long>,
    ) {
        val perFrameMs: Double get() = elapsedNs / 1e6 / frames.coerceAtLeast(1)
        val skippedPerSwitch: Double
            get() = if (switches == 0) 0.0 else skipped.toDouble() / switches
    }

    suspend fun run(
        access: CameraAccess,
        cameraId: String,
        iso: Int,
        shortNs: Long,
        longNs: Long,
        frames: Int = DEFAULT_FRAMES,
        block: Int = DEFAULT_BLOCK,
        log: (String) -> Unit,
    ) {
        val chars = access.characteristics(cameraId)

        // The advertised floor, and the sensor's own frame-duration floor for a full-size RAW.
        // Both matter: the first says whether the short exposure is even askable, the second says
        // what a "free" frame actually costs.
        val range: Range<Long>? = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val minFrameNs = rawMinFrameDurationNs(chars)
        val askedShort = shortNs.coerceAtLeast(range?.lower ?: shortNs)
        if (askedShort != shortNs) {
            log("short exposure raised to the advertised floor: ${us(askedShort)} (asked ${us(shortNs)})")
        }

        log("camera $cameraId · ISO $iso · short ${us(askedShort)} · long ${ms(longNs)}")
        log(
            "advertised exposure range: " +
                (range?.let { "${us(it.lower)} – ${ms(it.upper)}" } ?: "not reported"),
        )
        log(
            "min frame duration for full RAW: " +
                (minFrameNs?.let { ms(it) } ?: "not reported") +
                " — the floor a short exposure cannot go below",
        )

        SequenceSession.open(access, cameraId).use { session ->
            val steadyLong = steady(session, "steady long", iso, longNs, frames, log)
            val steadyShort = steady(session, "steady short", iso, askedShort, frames, log)
            val alternating = switching(
                session, "alternating", iso, askedShort, longNs, frames, blockSize = 1, log,
            )
            val blocked = switching(
                session, "blocks of $block", iso, askedShort, longNs, frames, blockSize = block, log,
            )
            val bursted = burst(
                session, "repeating burst", iso, askedShort, longNs, frames,
                minFrameNs ?: 0L, log,
            )
            val injected = injected(
                session, "injected burst", iso, askedShort, longNs, frames / 2,
                minFrameNs ?: 0L, log,
            )

            report(
                steadyLong, steadyShort, alternating, blocked, bursted, injected,
                block, minFrameNs, longNs, log,
            )
        }
    }

    /** One exposure, applied once, then N frames timed back to back. */
    private suspend fun steady(
        session: SequenceSession,
        name: String,
        iso: Int,
        exposureNs: Long,
        frames: Int,
        log: (String) -> Unit,
    ): Phase {
        val generation = session.currentGeneration + 1
        session.apply(iso, exposureNs, focusDiopters = null)

        // The settle is not part of the steady cost — it is a switch, and phases 3 and 4 are what
        // measure switches. So it is consumed and reported separately before the clock starts.
        val settle = awaitApplied(session, exposureNs, generation, log)

        var skipped = 0
        val started = System.nanoTime()
        repeat(frames) {
            val f = awaitApplied(session, exposureNs, generation, log)
            skipped += f.skipped
        }
        val elapsed = System.nanoTime() - started

        val phase = Phase(
            name = name,
            frames = frames,
            switches = 0,
            elapsedNs = elapsed,
            skipped = skipped,
            longFrames = frames,
            shortFrames = 0,
            settleWaitsNs = listOf(settle.waitedNs),
        )
        log(
            "$name: $frames frames in ${ms(elapsed)} = %.1f ms/frame · exposure ${ms(exposureNs)} · overhead %.1f ms/frame"
                .format(phase.perFrameMs, phase.perFrameMs - exposureNs / 1e6),
        )
        if (skipped > 0) log("  $skipped frame(s) arrived at the wrong exposure mid-run")
        return phase
    }

    /** Alternating exposures, one `apply` per [blockSize] frames. */
    private suspend fun switching(
        session: SequenceSession,
        name: String,
        iso: Int,
        shortNs: Long,
        longNs: Long,
        frames: Int,
        blockSize: Int,
        log: (String) -> Unit,
    ): Phase {
        var skipped = 0
        var switches = 0
        var longFrames = 0
        var shortFrames = 0
        val waits = mutableListOf<Long>()
        var useShort = true

        val started = System.nanoTime()
        var taken = 0
        while (taken < frames) {
            val exposureNs = if (useShort) shortNs else longNs
            val generation = session.currentGeneration + 1
            session.apply(iso, exposureNs, focusDiopters = null)
            switches++

            // Everything up to and including the first frame that actually carries the new
            // exposure is the switch. Its wall time minus the exposure itself is the settle.
            val first = awaitApplied(session, exposureNs, generation, log)
            skipped += first.skipped
            waits += first.waitedNs - exposureNs
            if (useShort) shortFrames++ else longFrames++
            taken++

            // The rest of the block runs at the exposure already applied, so it costs what the
            // steady phase costs and isolates the switch as the only difference.
            var inBlock = 1
            while (inBlock < blockSize && taken < frames) {
                val f = awaitApplied(session, exposureNs, generation, log)
                skipped += f.skipped
                if (useShort) shortFrames++ else longFrames++
                taken++
                inBlock++
            }
            useShort = !useShort
        }
        val elapsed = System.nanoTime() - started

        val phase = Phase(name, frames, switches, elapsed, skipped, longFrames, shortFrames, waits)
        log(
            "$name: $frames frames ($longFrames long, $shortFrames short), $switches switch(es) in " +
                "${ms(elapsed)} = %.1f ms/frame".format(phase.perFrameMs),
        )
        log(
            "  %d frame(s) discarded while settling = %.1f per switch · settle %s median"
                .format(skipped, phase.skippedPerSwitch, ms(median(waits))),
        )
        return phase
    }

    /**
     * The same alternating frames, submitted as one burst instead of a switch per frame.
     *
     * Timed from the *first* burst frame rather than from submission, deliberately: whatever was
     * already in flight when the burst went in has to drain first, and that drain is a one-off
     * belonging to the phase before it. What T-11.9 needs to know is the steady cadence once the
     * burst is running, so the clock covers the intervals between accepted frames.
     */
    private suspend fun burst(
        session: SequenceSession,
        name: String,
        iso: Int,
        shortNs: Long,
        longNs: Long,
        frames: Int,
        minFrameDurationNs: Long,
        log: (String) -> Unit,
    ): Phase {
        // Two requests, cycled: the whole interleave expressed once, with nothing to switch.
        val generation = session.burst(
            iso, listOf(shortNs, longNs), focusDiopters = null, minFrameDurationNs,
        )

        var skipped = 0
        var longFrames = 0
        var shortFrames = 0
        var started = 0L
        var accepted = 0
        var firstWasLong = false
        var previous = 0L

        // Whatever exposure a frame carries is what it carries: classifying by the metadata
        // rather than by position keeps one dropped frame from desynchronising the whole phase
        // against the pattern — which is a measurement bug, not a finding.
        while (accepted < frames) {
            val frame = session.nextFrame(BURST_TIMEOUT_MS)
            val gen = frame.generation
            val stale = gen < generation
            val applied = frame.appliedExposureNs
            frame.close()
            if (stale) {
                skipped++
                continue
            }
            val isLong = applied != null && ManualRequest.exposureMatches(applied, longNs)
            val now = System.nanoTime()
            if (accepted == 0) {
                started = now
                firstWasLong = isLong
            }
            // Traced per frame rather than summarised: the first run of this phase reported a
            // cadence that said "long" and a classification that said "short", and only the
            // frame-by-frame numbers can say which of the two is lying.
            log(
                "    #%02d gen %d · applied %s · %+.0f ms since previous"
                    .format(
                        accepted, gen,
                        applied?.let { "%.3f ms".format(it / 1e6) } ?: "none",
                        if (accepted == 0) 0.0 else (now - previous) / 1e6,
                    ),
            )
            previous = now
            if (isLong) longFrames++ else shortFrames++
            accepted++
        }
        val elapsed = System.nanoTime() - started
        session.stopRepeating()

        // frames - 1 intervals, because the clock starts at the first frame rather than before it.
        val intervals = (frames - 1).coerceAtLeast(1)
        val phase = Phase(
            name = name,
            frames = intervals,
            switches = frames - 1,
            elapsedNs = elapsed,
            skipped = skipped,
            // The first frame is outside the clock, so it is outside the steady comparison too.
            longFrames = if (firstWasLong) longFrames - 1 else longFrames,
            shortFrames = if (firstWasLong) shortFrames else shortFrames - 1,
            settleWaitsNs = emptyList(),
        )
        log(
            "$name: %d frames from a repeating 2-request cycle, %d interval(s) in %s = %.1f ms/frame"
                .format(frames, intervals, ms(elapsed), phase.perFrameMs),
        )
        // The discards here are the previous phase's pipeline draining, not a cost of the burst:
        // they arrive before its first frame and the clock starts after them.
        log("  $skipped stale frame(s) drained before the burst's own frames began")
        return phase
    }

    /**
     * Long frames injected as one-shots while the sensor keeps repeating at the short exposure.
     *
     * This is the shape T-11.9 would actually ship if it works: the moon frames stream
     * continuously and cheaply, and each ground frame is a one-shot dropped into the stream. What
     * it has to prove is that an injected request keeps *its own* exposure — which is precisely
     * what `setRepeatingBurst` failed to do.
     */
    private suspend fun injected(
        session: SequenceSession,
        name: String,
        iso: Int,
        shortNs: Long,
        longNs: Long,
        longFrameCount: Int,
        minFrameDurationNs: Long,
        log: (String) -> Unit,
    ): Phase {
        // The repeating request is the thing keeping RAW alive; it is deliberately the cheap one.
        val repeatGeneration = session.currentGeneration + 1
        session.apply(iso, shortNs, focusDiopters = null)
        awaitApplied(session, shortNs, repeatGeneration, log)

        val generation = session.injectBurst(
            iso, List(longFrameCount) { longNs }, focusDiopters = null, minFrameDurationNs,
        )

        var accepted = 0
        var shortFrames = 0
        var wrongExposure = 0
        var started = 0L
        var previous = 0L
        var lastAccepted = 0L
        val submitted = System.nanoTime()

        // A *deadline*, not a per-frame timeout. The repeating short request keeps delivering
        // every 33 ms throughout, so a per-frame timeout can never fire: a frame the reader
        // dropped under back pressure is never re-sent, and the loop would spin on stale short
        // frames forever waiting for an injected one that no longer exists. (It did.)
        val deadline = System.nanoTime() +
            (longFrameCount + 2) * longNs + BURST_TIMEOUT_MS * 1_000_000L
        while (accepted < longFrameCount) {
            if (System.nanoTime() > deadline) {
                log("  burst delivered $accepted of $longFrameCount before the phase deadline")
                break
            }
            val frame = runCatching { session.nextFrame(BURST_TIMEOUT_MS) }.getOrNull()
            if (frame == null) {
                log("  no frames at all for ${BURST_TIMEOUT_MS / 1000} s — the stream has stopped")
                break
            }
            val gen = frame.generation
            val applied = frame.appliedExposureNs
            frame.close()
            if (gen < generation) {
                // Repeating short frames still flowing between the injected ones. Expected, and
                // they are what keeps the stream alive rather than a cost.
                shortFrames++
                continue
            }
            val now = System.nanoTime()
            if (accepted == 0) {
                started = now
                previous = now
            }
            val isLong = applied != null && ManualRequest.exposureMatches(applied, longNs)
            if (!isLong) wrongExposure++
            log(
                "    L%02d gen %d · applied %s · %+.0f ms since previous · %d short between"
                    .format(
                        accepted, gen,
                        applied?.let { "%.3f ms".format(it / 1e6) } ?: "none",
                        (now - previous) / 1e6, shortFrames,
                    ),
            )
            previous = now
            lastAccepted = now
            shortFrames = 0
            accepted++
        }
        // First accepted to *last accepted*, not to the end of the loop: a phase that ends on the
        // deadline would otherwise charge the wait to the frames.
        val elapsed = lastAccepted - started
        val toFirst = started - submitted

        val intervals = (accepted - 1).coerceAtLeast(1)
        val phase = Phase(
            name = name,
            frames = intervals,
            switches = 1,
            elapsedNs = elapsed,
            skipped = wrongExposure,
            longFrames = intervals,
            shortFrames = 0,
            settleWaitsNs = listOf(toFirst),
        )
        log(
            "$name: %d of %d long frames injected over a short repeat, %d interval(s) in %s = %.1f ms/frame"
                .format(accepted, longFrameCount, intervals, ms(elapsed), phase.perFrameMs),
        )
        log(
            "  %s to the first injected frame · %d frame(s) came back at the wrong exposure"
                .format(ms(toFirst), wrongExposure),
        )
        return phase
    }

    private class Applied(val skipped: Int, val waitedNs: Long)

    /**
     * Waits for a frame whose own metadata carries [exposureNs], counting what it threw away.
     *
     * This is `SequenceSession.nextVerifiedFrame` with the discard *counted* rather than logged —
     * the count is the measurement, and it is the half of the result that transfers to other
     * hardware.
     */
    private suspend fun awaitApplied(
        session: SequenceSession,
        exposureNs: Long,
        minGeneration: Int,
        log: (String) -> Unit,
    ): Applied {
        val started = System.nanoTime()
        var skipped = 0
        // Generous: a settling sensor may hand back several frames at the *long* exposure before
        // the short one takes, so the budget has to cover the exposure it is leaving, not the one
        // it is going to.
        val timeoutMs = 30_000L + exposureNs / 1_000_000L * 4
        while (true) {
            val frame = session.nextFrame(timeoutMs)
            val matches = ManualRequest.exposureMatches(frame.appliedExposureNs, exposureNs)
            val current = frame.generation >= minGeneration
            frame.close()
            if (matches && current) return Applied(skipped, System.nanoTime() - started)
            skipped++
            if (skipped == 40) log("  still not settled after 40 frames — the sensor may be refusing")
        }
    }

    private fun report(
        steadyLong: Phase,
        steadyShort: Phase,
        alternating: Phase,
        blocked: Phase,
        bursted: Phase,
        injected: Phase,
        block: Int,
        minFrameNs: Long?,
        longNs: Long,
        log: (String) -> Unit,
    ) {
        log("--- OI-26 ---")

        // Are short frames free? The plan assumed 2 ms each. The floor says otherwise if the
        // sensor cannot read out faster than its minimum frame duration.
        val shortMs = steadyShort.perFrameMs
        log("short-frame cadence: %.1f ms/frame".format(shortMs))
        log(
            "  120 short frames therefore cost %.1f s — the plan assumed 0.26 s at 2 ms each"
                .format(120 * shortMs / 1000),
        )
        minFrameNs?.let {
            val floorMs = it / 1e6
            log(
                "  the sensor's own floor is %.1f ms, so %s"
                    .format(
                        floorMs,
                        if (shortMs <= floorMs * 1.25) "readout is the limit, not the exposure"
                        else "something beyond readout is pacing this",
                    ),
            )
        }

        // The switch cost: whatever a switching run spent above the same frames shot steadily.
        // Counted per exposure rather than assumed half-and-half, because a block of B puts B
        // frames at one exposure before turning over and the mix is never 50/50.
        fun steadyEquivalentNs(p: Phase): Long =
            ((p.longFrames * steadyLong.perFrameMs + p.shortFrames * steadyShort.perFrameMs) * 1e6)
                .toLong()

        val expectedNs = steadyEquivalentNs(alternating)
        val excessNs = alternating.elapsedNs - expectedNs
        val perSwitchMs = excessNs / 1e6 / alternating.switches.coerceAtLeast(1)
        log(
            "switch cost: %.1f ms per switch (%s above %s of steady frames, over %d switches)"
                .format(perSwitchMs, ms(excessNs), ms(expectedNs), alternating.switches),
        )
        log("  discarded %.1f frame(s) per switch".format(alternating.skippedPerSwitch))

        val blockedExcessNs = blocked.elapsedNs - steadyEquivalentNs(blocked)
        val blockedPerSwitchMs = blockedExcessNs / 1e6 / blocked.switches.coerceAtLeast(1)
        log(
            "blocks of %d: %s above steady over %d switch(es) = %.1f ms per switch"
                .format(block, ms(blockedExcessNs), blocked.switches, blockedPerSwitchMs),
        )
        // If the two disagree wildly the cost is not per-switch at all, and extrapolating from
        // either would be wrong — so say so rather than quietly averaging them.
        val agree = perSwitchMs > 0 && blockedPerSwitchMs > 0 &&
            maxOf(perSwitchMs, blockedPerSwitchMs) <= 2.5 * minOf(perSwitchMs, blockedPerSwitchMs)
        log(
            "  the two agree to within 2.5x: $agree" +
                if (agree) " — so the cost is per switch and the block size is a free parameter"
                else " — treat the extrapolation below as indicative only",
        )

        // What it means for a real session: 60 long frames is ~150 s of exposure, and the plan
        // wants to know what fraction the switching adds at each interleave granularity.
        val sessionMs = 60 * longNs / 1e6
        val strictOverheadMs = perSwitchMs * 120
        val blockOverheadMs = perSwitchMs * (120.0 / PLAN_BLOCK)
        log(
            ("on a 60-long-frame session (%.0f s): strict alternation adds %.1f s (%.1f%%), " +
                "blocks of %d add %.1f s (%.1f%%)")
                .format(
                    sessionMs / 1000, strictOverheadMs / 1000,
                    100 * strictOverheadMs / sessionMs, PLAN_BLOCK,
                    blockOverheadMs / 1000, 100 * blockOverheadMs / sessionMs,
                ),
        )

        // The burst is the phase that decides the design, so it is reported against the same
        // steady baseline as the others rather than on its own terms.
        val burstExcessNs = bursted.elapsedNs - steadyEquivalentNs(bursted)
        val burstPerFrameMs = burstExcessNs / 1e6 / bursted.frames.coerceAtLeast(1)
        log(
            "burst: %s above %s of steady frames over %d interval(s) = %.1f ms per frame"
                .format(
                    ms(burstExcessNs), ms(steadyEquivalentNs(bursted)), bursted.frames,
                    burstPerFrameMs,
                ),
        )
        val burstOverheadMs = burstPerFrameMs * 120
        log(
            "  on the same 60-long-frame session that is %.1f s (%.1f%%)"
                .format(burstOverheadMs / 1000, 100 * burstOverheadMs / sessionMs),
        )

        // The injected burst is the mechanism that can actually ship, so it is judged on the two
        // things T-11.9 needs from it: the long frames cost what a long frame costs, and they come
        // back at the exposure they were asked for.
        val injectedExcessNs = injected.elapsedNs - steadyEquivalentNs(injected)
        val injectedPerFrameMs = injectedExcessNs / 1e6 / injected.frames.coerceAtLeast(1)
        log(
            "injected burst: %s above %s of steady long frames = %.1f ms per frame, %d at the wrong exposure"
                .format(
                    ms(injectedExcessNs), ms(steadyEquivalentNs(injected)),
                    injectedPerFrameMs, injected.skipped,
                ),
        )
        val injectedOverheadMs = injectedPerFrameMs * 60
        log(
            "  over 60 long frames that is %.1f s (%.1f%%), plus %s once to the first frame of a submission"
                .format(
                    injectedOverheadMs / 1000, 100 * injectedOverheadMs / sessionMs,
                    ms(injected.settleWaitsNs.firstOrNull() ?: 0L),
                ),
        )

        val injectedHonoured = injected.skipped == 0
        val verdict = when {
            perSwitchMs < 0 ->
                "INCONCLUSIVE — the alternating run came in under two steady runs, so the phases " +
                    "are not comparable. Re-run with more frames."
            injectedHonoured && 100 * injectedOverheadMs / sessionMs < 10 ->
                ("RESOLVED — interleave by injecting one-shot requests over a cheap repeating one. " +
                    "Per-frame apply costs %.1f s a switch (a ten-deep pipeline draining at the old " +
                    "exposure, which no block size fixes) and setRepeatingBurst applies the cycle's " +
                    "first exposure to every frame. Injection costs %.1f ms a frame and every frame " +
                    "came back at the exposure it was asked for.")
                    .format(perSwitchMs / 1000, injectedPerFrameMs)
            !injectedHonoured ->
                ("RESOLVED AGAINST — injected requests came back at the wrong exposure %d time(s), " +
                    "so this HAL does not honour per-request sensor settings by any of the three " +
                    "mechanisms. T-11.9 cannot interleave within a session on this device; it needs " +
                    "two passes, and the drift budget has to carry the gap between them.")
                    .format(injected.skipped)
            100 * blockOverheadMs / sessionMs < 10 ->
                "RESOLVED — injection is not cheap enough but blocks of $PLAN_BLOCK on a repeating " +
                    "request are. Interleave in blocks, as the plan's default assumed."
            else ->
                "UNRESOLVED — no mechanism tried is cheap enough. T-11.9 needs rethinking rather " +
                    "than retuning."
        }
        log(verdict)
    }

    /** The minimum frame duration the sensor advertises for a full-size RAW stream. */
    private fun rawMinFrameDurationNs(chars: CameraCharacteristics): Long? {
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
        val largest: Size = map.getOutputSizes(ImageFormat.RAW_SENSOR)
            ?.maxByOrNull { it.width.toLong() * it.height } ?: return null
        return runCatching {
            map.getOutputMinFrameDuration(ImageFormat.RAW_SENSOR, largest)
        }.getOrNull()?.takeIf { it > 0 }
    }

    private fun median(values: List<Long>): Long {
        if (values.isEmpty()) return 0
        val sorted = values.sorted()
        return sorted[sorted.size / 2]
    }

    private fun us(ns: Long) = "%.3f ms".format(ns / 1e6)
    private fun ms(ns: Long) = if (ns >= 1_000_000_000) "%.2f s".format(ns / 1e9)
    else "%.1f ms".format(ns / 1e6)
}
