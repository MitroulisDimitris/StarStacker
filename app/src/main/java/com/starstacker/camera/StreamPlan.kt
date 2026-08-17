package com.starstacker.camera

import com.starstacker.device.SizePx
import kotlin.math.abs

/**
 * T-2.1 — which streams a session configures, decided from sizes alone so it can be tested
 * without a camera.
 *
 * Two streams, always:
 *
 * - **RAW at maximum size**, which is the data. Everything the app measures and everything it
 *   writes comes from here (D-9): the analysis plane is this frame binned, so the numbers on
 *   screen describe the frame on disk rather than something the ISP made alongside it.
 * - **A second stream that is never looked at.** D-20: this HAL delivers nothing at all on a
 *   RAW-only session, in any request profile. A second output is the price of the first one
 *   working, so the plan treats it as mandatory rather than optional.
 *
 * The second stream is a YUV `ImageReader`, not a display surface. That makes the whole
 * screen-off question in T-2.1 disappear rather than get handled: no part of the capture session
 * belongs to the display, so there is nothing to lose when the screen goes off, nothing to
 * reconfigure on wake, and the framing preview — which is drawn from the RAW frames themselves —
 * is unaffected either way. It also happens to be the YUV analysis fallback OI-3 identified,
 * already configured if it is ever needed.
 */
data class StreamPlan(
    val raw: SizePx,
    val secondary: SizePx,
    /** Downsample applied to the RAW frame before analysis. Even, per [CfaBinner]'s contract. */
    val binFactor: Int,
    val analysis: SizePx,
    val reason: String,
)

object StreamPlanner {

    /** The platform's PREVIEW bound: 1080p, or the display, whichever is smaller. */
    const val MAX_SECONDARY_WIDTH = 1920
    const val MAX_SECONDARY_HEIGHT = 1080

    /** FR-7.2 puts star detection on a ~1 MP frame. */
    const val TARGET_ANALYSIS_PIXELS = 1_000_000

    fun choose(
        rawSizes: List<SizePx>,
        secondaryCandidates: List<SizePx>,
        targetAnalysisPixels: Int = TARGET_ANALYSIS_PIXELS,
    ): StreamPlan {
        val raw = rawSizes.maxByOrNull { it.width.toLong() * it.height }
            ?: error("camera offers no RAW sizes — it should not have qualified (FR-3.1)")
        val binFactor = binFactorFor(raw, targetAnalysisPixels)
        val secondary = chooseSecondary(raw, secondaryCandidates)

        return StreamPlan(
            raw = raw,
            secondary = secondary,
            binFactor = binFactor,
            analysis = SizePx(raw.width / binFactor, raw.height / binFactor),
            reason = "RAW $raw + YUV $secondary; analysis at " +
                "${raw.width / binFactor}x${raw.height / binFactor} (bin ${binFactor}x)",
        )
    }

    /**
     * The smallest even bin factor that brings the frame to the analysis budget.
     *
     * Even because the CFA cell is 2x2 and a factor that straddles it would mix colours
     * (see `CfaBinner`); smallest because binning throws away the sub-pixel detail the centroid
     * fit is built on, so it should be done exactly as much as the budget demands and no more.
     */
    fun binFactorFor(raw: SizePx, targetPixels: Int = TARGET_ANALYSIS_PIXELS): Int {
        val pixels = raw.width.toLong() * raw.height
        var factor = 2
        while (factor < MAX_BIN_FACTOR && pixels / (factor.toLong() * factor) > targetPixels) {
            factor += 2
        }
        return factor
    }

    /**
     * Prefers a secondary size that matches the RAW aspect ratio. Nothing reads this stream, but
     * a mismatched aspect is a sign the HAL is cropping somewhere, and matching costs nothing.
     */
    private fun chooseSecondary(raw: SizePx, candidates: List<SizePx>): SizePx {
        val withinPreview = candidates.filter {
            it.width <= MAX_SECONDARY_WIDTH && it.height <= MAX_SECONDARY_HEIGHT
        }
        if (withinPreview.isEmpty()) {
            return candidates.minByOrNull { it.width.toLong() * it.height }
                ?: SizePx(FALLBACK_SECONDARY_WIDTH, FALLBACK_SECONDARY_HEIGHT)
        }

        val rawAspect = raw.width.toDouble() / raw.height
        val matching = withinPreview.filter {
            abs(it.width.toDouble() / it.height - rawAspect) < ASPECT_TOLERANCE
        }
        val pool = matching.ifEmpty { withinPreview }
        return pool.maxByOrNull { it.width.toLong() * it.height }!!
    }

    private const val MAX_BIN_FACTOR = 16
    private const val ASPECT_TOLERANCE = 0.02
    private const val FALLBACK_SECONDARY_WIDTH = 640
    private const val FALLBACK_SECONDARY_HEIGHT = 480
}
