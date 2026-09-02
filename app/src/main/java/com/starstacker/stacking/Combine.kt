package com.starstacker.stacking

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * T-5.4 / FR-8.1 step 4 — how N samples of one pixel become one number.
 *
 * ### The mean is not the job
 *
 * Averaging is what makes a stack deep; **rejection is what makes it clean**, and they are separate
 * problems. A satellite crossing one frame of 150 survives the mean at 1/150th of its brightness —
 * which sounds like nothing and is not: the trail is hundreds of ADU above a background the stretch
 * is about to lift by two orders of magnitude, so it lands in the finished image as a faint, sharp,
 * unmistakably artificial line. Aircraft, cosmic rays and a car's headlights on a distant hill all
 * behave the same way. Every one of them is present in *one* frame and absent from the rest, which
 * is precisely what makes them removable, and the combiner is the only place that knows it.
 *
 * ### Why the clip is seeded from the median, not the mean
 *
 * The obvious implementation computes a mean and a standard deviation and rejects what lies more
 * than κ standard deviations away. It does not work at the frame counts this app actually shoots.
 * One satellite in twenty samples **inflates the standard deviation enough to keep itself**: the
 * outlier is part of the spread it is being measured against, so κ = 3 comfortably admits it and
 * the rejection quietly does nothing. The estimator has to survive the contaminant it is looking
 * for, which means the median and the MAD — a single wild value moves neither.
 *
 * **After the first pass the roles reverse.** Once the outliers are gone the surviving samples are
 * the Gaussian noise the mean is the best estimator of, and the standard deviation is both a
 * tighter description of them and cheaper to compute than another median. So the seed is robust and
 * the iterations are not, which is not an inconsistency — robustness is only needed while the thing
 * it defends against is still in the set.
 *
 * ### Two edge cases that are ordinary here, not pathological
 *
 * **Fewer than [SigmaClip.minSamples] samples.** Rejection needs enough data to say what normal
 * looks like; below a handful the spread estimate is less trustworthy than the outlier it is
 * judging, and clipping becomes a coin toss that discards real signal. Below the floor the combiner
 * returns the **median** instead — which at one or two samples *is* the mean, so the behaviour
 * degrades continuously rather than switching character at a threshold. This is not a rare corner:
 * it is every pixel near the edge of the common area, where only the frames that happened to point
 * that way contributed at all.
 *
 * **A MAD of exactly zero.** Calibrated data is quantised, and in a dark patch of background more
 * than half the samples are commonly the *same* value — at which point the median absolute
 * deviation is 0, κσ is 0, and a naive implementation rejects every sample that is not exactly the
 * median. That is not a clip, it is a mode filter, and it would show up as a flat, dead background
 * with the noise removed and the faint signal along with it. σ then comes from the *mean* absolute
 * deviation instead — and specifically not from the standard deviation, for the reason worked
 * through on [SigmaClip.meanDeviationSigma]. If that is zero too then the samples really are
 * identical and any answer is that value.
 *
 * ### Preconditions, because this runs 37.8 million times per master
 *
 * A 12.6 MP master in three channels is 37.8 M calls to [TiledStacker.Combiner.combine], so nothing
 * here validates what the caller already guarantees and nothing here allocates:
 *
 * - **`samples[0, count)` must be finite.** [TiledStacker] filters non-finite values and the
 *   uncovered sentinel at the single point where samples enter, so a check here would be the same
 *   test in a hotter loop. Uncovered pixels arrive as a *smaller count*, never as NaN — which
 *   matters, because §1.32's trap is that Kotlin sorts NaN above every number, and a NaN reaching
 *   the selection below would silently become the "brightest" sample.
 * - **The array is scratch and will be reordered.** Selecting a median partitions it in place. The
 *   caller refills the prefix for every pixel, so this costs nothing and saves a copy per pixel.
 * - **One instance per stack, not shared between threads.** [SigmaClip] carries a scratch buffer
 *   and its own counters.
 */
object Combine {

    /**
     * The choices behind the expert affordance, and the value recorded in `session.json` so a
     * restack can reproduce a master rather than approximate it (FR-9.2).
     */
    enum class Method(val label: String) {
        /** The default. Median/MAD seeded, then iterated on the survivors. */
        SIGMA_CLIP("Sigma-clipped mean"),

        /**
         * The textbook form, seeded from the mean and standard deviation of *all* samples. Offered
         * because it is what the desktop tools mean by the name, so a stack compared against Siril
         * in T-5.7 can be compared like for like — not because it is the better estimator here.
         */
        KAPPA_SIGMA("Kappa-sigma clipped mean"),

        /**
         * Rejects everything transient and costs about a quarter of the depth to do it: at large N
         * the median of Gaussian samples is √(π/2) noisier than their mean. Worth it only when the
         * frame count is low enough that clipping cannot be trusted, and the honest choice for a
         * short session under a flight path.
         */
        MEDIAN("Median"),

        /** No rejection at all. The deepest result, and the one with the satellites still in it. */
        MEAN("Mean"),
    }

    /**
     * T-5.5 — a combiner that can take frame weights.
     *
     * Separate from [TiledStacker.Combiner] rather than folded into it, for two reasons. That one
     * is a `fun interface` and adding a second method would stop it being one — the lambda form is
     * how half the tests declare a combiner. And the weighted path costs a parallel index array
     * per sample and a branch in the partition loop, so a stack with nothing to weight should not
     * pay for it: [TiledStacker] uses this only when the weights are not all equal.
     *
     * The contract adds one clause to [TiledStacker.Combiner]'s: **`frames[i]` names the frame
     * `samples[i]` came from and must be permuted with it.** Every reorder in here carries both.
     */
    interface Weighted : TiledStacker.Combiner {
        /**
         * @param frames `frames[i]` is the frame index of `samples[i]`; reordered alongside.
         * @param weights indexed by frame, not by sample — the same array for every pixel.
         */
        fun combineWeighted(
            samples: FloatArray,
            frames: IntArray,
            count: Int,
            weights: FloatArray,
        ): Float
    }

    /**
     * The weighted mean of `samples[0, count)`, each weighted by its own frame's weight.
     *
     * Falls back to the plain mean if the weights sum to nothing, which cannot happen through
     * [FrameQuality] — its floor is well above zero — but would silently produce NaN if it did.
     */
    fun weightedMeanOf(
        samples: FloatArray,
        frames: IntArray,
        count: Int,
        weights: FloatArray,
    ): Double {
        if (count <= 0) return Double.NaN
        var sum = 0.0
        var total = 0.0
        for (i in 0 until count) {
            val w = weights.getOrElse(frames[i]) { 1f }.toDouble()
            sum += samples[i] * w
            total += w
        }
        return if (total > 0.0) sum / total else meanOf(samples, count)
    }

    /** A fresh combiner for [method]. Fresh because [SigmaClip] is stateful — one per stack. */
    fun of(method: Method): TiledStacker.Combiner = when (method) {
        Method.SIGMA_CLIP -> SigmaClip()
        Method.KAPPA_SIGMA -> SigmaClip(robustSeed = false)
        Method.MEDIAN -> Median
        Method.MEAN -> WeightedMean
    }

    /** Whether [method] does anything with frame weights — see [Median]. */
    fun supportsWeights(method: Method): Boolean = method != Method.MEDIAN

    /**
     * No rejection logic and no state, so a single instance serves every caller.
     *
     * **Not weighted, and that is a real gap rather than an omission.** A weighted median exists —
     * the value where the cumulative weight crosses half — but it is a different estimator with
     * different behaviour at small n, and it is not what any desktop tool means by "median". A
     * session stacked this way ignores the weights; the UI says so rather than implying otherwise.
     */
    val Median = TiledStacker.Combiner { samples, count ->
        if (count <= 0) Float.NaN else medianOf(samples, count).toFloat()
    }

    /** The plain mean, weighted. [TiledStacker.Combiner.Mean]'s counterpart for T-5.5. */
    val WeightedMean = object : Weighted {
        override fun combine(samples: FloatArray, count: Int): Float =
            TiledStacker.Combiner.Mean.combine(samples, count)

        override fun combineWeighted(
            samples: FloatArray,
            frames: IntArray,
            count: Int,
            weights: FloatArray,
        ): Float =
            if (count <= 0) Float.NaN else weightedMeanOf(samples, frames, count, weights).toFloat()
    }

    /**
     * The default combiner: clip against a robust estimate of the spread, then average what is left.
     *
     * @param kappaLow how many σ below the centre a sample may sit before it is rejected.
     * @param kappaHigh the same above. Separate from [kappaLow] because the outliers this exists to
     *   remove are **positive** — satellites, aircraft, cosmic rays and meteors are all things that
     *   added light to one frame. Nothing symmetrical is going on physically, and a session under a
     *   flight path can be told so. Both default to [DEFAULT_KAPPA]; no asymmetric default is
     *   invented here, because the number that suits a given sky is exactly what the expert
     *   affordance is for.
     * @param iterations passes after the seed. Each narrows the bounds around a cleaner set; they
     *   stop the moment a pass rejects nothing, which on clean data is the first one.
     * @param minSamples the floor below which rejection is not attempted at all.
     * @param minSurvivors the floor a *pass* may not cut below. Deliberately a different and much
     *   smaller number than [minSamples], because the two guard different things: [minSamples] asks
     *   whether there is enough data to estimate a spread from, and this asks only whether a
     *   runaway is under way. Conflating them makes the combiner *worse* exactly where it should be
     *   best — six frames with two satellites in them would have a pass that correctly identifies
     *   both declined for leaving only four survivors, and the master would keep both.
     * @param robustSeed median/MAD when true, mean/SD when false — the difference between
     *   [Method.SIGMA_CLIP] and [Method.KAPPA_SIGMA].
     */
    class SigmaClip(
        val kappaLow: Float = DEFAULT_KAPPA,
        val kappaHigh: Float = DEFAULT_KAPPA,
        val iterations: Int = DEFAULT_ITERATIONS,
        val minSamples: Int = DEFAULT_MIN_SAMPLES,
        val minSurvivors: Int = DEFAULT_MIN_SURVIVORS,
        val robustSeed: Boolean = true,
    ) : Weighted {

        /** What the rejection actually did, which is the only way to know it did anything. */
        val stats = Stats()

        /** Deviations for the MAD. Grown once to the frame count, then reused for every pixel. */
        private var deviations = FloatArray(0)

        override fun combine(samples: FloatArray, count: Int): Float =
            clip(samples, count, null, null)

        override fun combineWeighted(
            samples: FloatArray,
            frames: IntArray,
            count: Int,
            weights: FloatArray,
        ): Float = clip(samples, count, frames, weights)

        /**
         * One implementation for both paths, because two would diverge.
         *
         * The rejection itself is **unweighted, deliberately**. A satellite is an outlier whatever
         * the quality of the frame it crossed, and letting a good frame's opinion count for more
         * while deciding what is an outlier would make the clip depend on which frames happened to
         * be sharp. Quality belongs in the average, not in the judgement of what to average — so
         * the weights are consulted exactly once, on the last line.
         *
         * @param frames null on the unweighted path, which then costs nothing: the branches below
         *   are on a value that is constant for the whole stack.
         */
        private fun clip(
            samples: FloatArray,
            count: Int,
            frames: IntArray?,
            weights: FloatArray?,
        ): Float {
            stats.pixels++
            if (count <= 0) {
                stats.uncovered++
                return Float.NaN
            }
            stats.samples += count.toLong()
            if (count < minSamples) {
                stats.belowFloor++
                return medianOf(samples, count, frames).toFloat()
            }

            var centre: Double
            var sigma: Double
            if (robustSeed) {
                centre = medianOf(samples, count, frames)
                sigma = madSigma(samples, count, centre)
                if (sigma <= 0.0) {
                    // Quantised data with a repeated mode. Not a pathology — see the class note.
                    stats.degenerateSpread++
                    sigma = meanDeviationSigma(samples, count, centre)
                }
            } else {
                centre = meanOf(samples, count)
                sigma = deviationAbout(samples, count, centre)
            }

            var n = count
            var pass = 0
            while (pass < iterations && sigma > 0.0) {
                val lo = centre - kappaLow * sigma
                val hi = centre + kappaHigh * sigma

                // Counted before it is applied, because compacting in place destroys the set it
                // replaces — and the floor check below has to be able to decline the new one.
                var keep = 0
                for (i in 0 until n) {
                    val v = samples[i]
                    if (v >= lo && v <= hi) keep++
                }
                if (keep == n) break
                if (keep < minSurvivors) {
                    // A pass this aggressive has stopped describing outliers and started describing
                    // the noise. Keep the set we have rather than average two survivors and call it
                    // a measurement.
                    stats.floorHit++
                    break
                }

                var w = 0
                for (i in 0 until n) {
                    val v = samples[i]
                    if (v >= lo && v <= hi) {
                        // The frame index moves with its sample, or the weights below would be
                        // applied to whichever frame happened to land at that slot.
                        if (frames != null) frames[w] = frames[i]
                        samples[w++] = v
                    }
                }
                n = keep

                centre = meanOf(samples, n)
                sigma = deviationAbout(samples, n, centre)
                pass++
            }

            stats.rejected += (count - n).toLong()
            // Recomputed rather than carried out of the loop: `centre` is only the mean of the
            // survivors on the paths that finished a pass, and is still the median seed on the two
            // that break early. One extra pass over at most a few hundred floats buys the guarantee
            // that what comes back is always the mean of what survived.
            return if (frames != null && weights != null) {
                weightedMeanOf(samples, frames, n, weights).toFloat()
            } else {
                meanOf(samples, n).toFloat()
            }
        }

        /**
         * σ estimated from the median absolute deviation, scaled to match the standard deviation
         * for Gaussian data — the same 1.4826 the preview's background estimate uses.
         */
        private fun madSigma(samples: FloatArray, count: Int, centre: Double): Double {
            val dev = deviationBuffer(count)
            val c = centre.toFloat()
            for (i in 0 until count) dev[i] = abs(samples[i] - c)
            return MAD_TO_SIGMA * medianOf(dev, count)
        }

        /**
         * The zero-MAD fallback: σ from the **mean** absolute deviation about the median.
         *
         * The obvious fallback is the standard deviation, and it is the wrong one — it inherits
         * precisely the weakness the MAD was chosen to avoid. Work it through for `n − 1` samples
         * at the median and one outlier `d` away: the SD is `d/√(n−1)`, so the outlier is rejected
         * only when `d > 3d/√(n−1)`, which is to say **only when there are more than ten samples**,
         * whatever `d` is. A fallback that cannot reject a satellite from a nine-frame stack no
         * matter how bright it is has not fallen back to anything.
         *
         * The mean absolute deviation is `d/n` for the same case, and `1.2533 × d/n` after scaling
         * to σ — so the outlier goes at any `n` above four. It is not robust the way the MAD is (a
         * single wild value moves it, and past roughly a quarter contamination it stops working),
         * which is exactly why it is second in line rather than first: the most robust estimator
         * gets asked, and this one only answers when that one had nothing to say.
         */
        private fun meanDeviationSigma(samples: FloatArray, count: Int, centre: Double): Double {
            var sum = 0.0
            for (i in 0 until count) sum += abs(samples[i] - centre)
            return MEAN_DEVIATION_TO_SIGMA * (sum / count)
        }

        private fun deviationBuffer(count: Int): FloatArray {
            if (deviations.size < count) deviations = FloatArray(count)
            return deviations
        }

        /** Counters, so a stack can say what its rejection did instead of being trusted. */
        class Stats {
            /** Pixels combined, including the uncovered ones. */
            var pixels = 0L
                internal set

            /** Samples offered across every pixel — the denominator of [rejectionRate]. */
            var samples = 0L
                internal set

            /** Samples clipped away. */
            var rejected = 0L
                internal set

            /** Pixels no frame covered, which came back as NaN. */
            var uncovered = 0L
                internal set

            /** Pixels with too few samples to clip, combined by median instead. */
            var belowFloor = 0L
                internal set

            /** Pixels where a pass would have cut below [SigmaClip.minSurvivors] and was declined. */
            var floorHit = 0L
                internal set

            /** Pixels whose MAD was zero, so σ came from the mean absolute deviation instead. */
            var degenerateSpread = 0L
                internal set

            /**
             * The one number worth reading. A few tenths of a percent is a clean sky with the odd
             * satellite; several percent means κ is eating the noise, and therefore the signal.
             */
            val rejectionRate: Double
                get() = if (samples == 0L) 0.0 else rejected.toDouble() / samples

            /** For `session.json`, in the shape [Calibration.Masters.describe] uses. */
            fun describe(): String = buildString {
                append("%.3f%% rejected".format(rejectionRate * 100))
                append(" · $pixels px")
                if (uncovered > 0) append(" · $uncovered uncovered")
                if (belowFloor > 0) append(" · $belowFloor below floor")
                if (floorHit > 0) append(" · $floorHit floor-limited")
                if (degenerateSpread > 0) append(" · $degenerateSpread zero-MAD")
            }
        }
    }

    // ---------------------------------------------------------------- statistics, allocation-free

    /**
     * The median of `a[0, count)`, **reordering `a` in the process**.
     *
     * Selection rather than a sort: this is the hot path and the rank is the only thing wanted.
     * Sorting 150 floats to read the middle one costs `n log n` compares where `n` buys the same
     * answer, 37.8 million times over.
     */
    fun medianOf(a: FloatArray, count: Int, ids: IntArray? = null): Double {
        if (count <= 0) return Double.NaN
        if (count == 1) return a[0].toDouble()
        val mid = count / 2
        selectKth(a, 0, count - 1, mid, ids)
        if (count % 2 == 1) return a[mid].toDouble()
        // Selection leaves everything below `mid` no greater than `a[mid]`, so the largest of them
        // is the lower of the two middle values. A second selection would be the obvious way, and
        // twice the work.
        var lower = a[0]
        for (i in 1 until mid) if (a[i] > lower) lower = a[i]
        return (lower.toDouble() + a[mid].toDouble()) / 2.0
    }

    fun meanOf(a: FloatArray, count: Int): Double {
        if (count <= 0) return Double.NaN
        // Accumulated in Double: 150 floats of a few thousand ADU each is nowhere near Float's
        // precision limit, but this sum is the number the whole stack exists to produce.
        var sum = 0.0
        for (i in 0 until count) sum += a[i]
        return sum / count
    }

    /**
     * The standard deviation about a *given* centre rather than about the sample mean, because the
     * seed pass needs the spread around the median. Divides by `n − 1`: with 20 frames the
     * difference from `n` is 2.5% of σ, which is 2.5% of the clip threshold.
     */
    fun deviationAbout(a: FloatArray, count: Int, centre: Double): Double {
        if (count < 2) return 0.0
        var sum = 0.0
        for (i in 0 until count) {
            val d = a[i] - centre
            sum += d * d
        }
        return sqrt(sum / (count - 1))
    }

    /**
     * Quickselect, Hoare partition, middle pivot.
     *
     * Hoare rather than Lomuto specifically because of **duplicates**: Lomuto degenerates to O(n²)
     * on an all-equal range, and an all-equal range is what a quantised patch of background sky
     * *is*. Hoare's scans both step over equal values, so the partition stays balanced.
     *
     * Assumes finite values. NaN compares false against everything and would stop both scans dead —
     * which terminates, but leaves the range unordered. The precondition documented on [Combine] is
     * what keeps that out.
     */
    private fun selectKth(a: FloatArray, from: Int, to: Int, k: Int, ids: IntArray?) {
        var lo = from
        var hi = to
        while (lo < hi) {
            val pivot = a[lo + (hi - lo) / 2]
            var i = lo - 1
            var j = hi + 1
            while (true) {
                do { i++ } while (a[i] < pivot)
                do { j-- } while (a[j] > pivot)
                if (i >= j) break
                val t = a[i]
                a[i] = a[j]
                a[j] = t
                // T-5.5: the weights live per *frame*, so a sample that moves has to take its
                // frame with it. Nothing else in this file knows which frame a sample came from,
                // and after one partition nothing could work it out.
                if (ids != null) {
                    val id = ids[i]
                    ids[i] = ids[j]
                    ids[j] = id
                }
            }
            if (k <= j) hi = j else lo = j + 1
        }
    }

    /** κ = 3: a Gaussian puts 99.73% of its samples inside, so a clean stack loses almost nothing. */
    const val DEFAULT_KAPPA = 3.0f

    /**
     * Three passes. The first removes the obvious, the second removes what the first's inflated σ
     * admitted, and a third almost never changes anything — the loop stops early when a pass
     * rejects nothing, so this is a ceiling rather than a cost.
     */
    const val DEFAULT_ITERATIONS = 3

    /**
     * Five samples. Below this the spread estimate is worse than the outlier it would judge: with
     * four samples a single satellite is a quarter of the data, and a κσ built from it rejects a
     * real sample about as readily as the intruder.
     */
    const val DEFAULT_MIN_SAMPLES = 5

    /**
     * Three survivors. Not a second opinion on [DEFAULT_MIN_SAMPLES] — this one exists solely to
     * stop a runaway, where each pass tightens σ around the survivors of the last until two samples
     * are left and the "measurement" is whichever pair happened to agree. Three is where a mean
     * still means something; anything above it starts declining passes that were right.
     */
    const val DEFAULT_MIN_SURVIVORS = 3

    /** MAD → σ for a Gaussian: `1 / Φ⁻¹(0.75)`. */
    const val MAD_TO_SIGMA = 1.4826

    /** Mean absolute deviation → σ for a Gaussian: `√(π/2)`. */
    const val MEAN_DEVIATION_TO_SIGMA = 1.2533
}
