package com.starstacker.moon

import com.starstacker.stacking.TiledStacker
import kotlin.math.hypot

/**
 * T-11.11 — merging the moon master into the ground master, and handing back both anyway.
 *
 * ### Why the layers matter more than the merge
 *
 * The blend is a **taste decision**. How hard the moon sits against the sky, whether the limb is
 * feathered or crisp, how much of the earthshine survives — none of these have a right answer, and
 * an app that flattens them into one opinionated JPEG has thrown away the thing the user actually
 * wanted. This workflow ends in Photoshop.
 *
 * So [merge] produces a starting point, and the two **registered masters ship alongside it**.
 * `LinearMaster`'s writer already takes 1 or 3 channels, so a layer costs nothing new: it is
 * another master, written the same way, and both are in the same coordinate frame because T-11.10
 * put them there.
 *
 * ### The mask, and the one thing it must not do
 *
 * The moon is pasted through a **radial feather** centred on the disc, not through a luminance
 * threshold. A luminance mask sounds more principled and is worse here: the ground master's blob
 * is *clipped and bloomed*, so thresholding on its brightness selects a region larger than the moon
 * and cuts a halo of sky out with it. A radius measured from the properly exposed moon master —
 * the one that knows how big the moon actually is — does not have that problem.
 *
 * The feather runs from the limb outward over [FEATHER_FRACTION] of the radius. Hard enough that
 * the disc stays sharp, soft enough that the join does not read as a cut-out.
 *
 * ### What is deliberately not done
 *
 * No brightness matching between the layers. They were exposed 14 stops apart on purpose, and
 * "correcting" that would undo the entire point of bracketing — the moon is *supposed* to be the
 * bright thing. Scaling is left to the blend, where it is a choice rather than a silent step.
 */
object Composite {

    /** How far past the limb the mask fades, as a fraction of the disc radius. */
    const val FEATHER_FRACTION = 0.12

    /** Below this the feather is a single pixel and the join reads as a cut-out. */
    const val MIN_FEATHER_PX = 1.5

    data class Report(
        val discRadiusPx: Double,
        val featherPx: Double,
        val centreX: Double,
        val centreY: Double,
        val pixelsReplaced: Int,
        val note: String,
    ) {
        fun describe(): String =
            "moon merged at (%.1f, %.1f), r=%.1f px, feather %.1f px, %d pixels replaced".format(
                centreX, centreY, discRadiusPx, featherPx, pixelsReplaced,
            )
    }

    /**
     * Merges [moon] into [ground], in place on [ground].
     *
     * Both are interleaved linear float, same dimensions, already registered — [shiftX] and
     * [shiftY] are T-11.10's answer, applied here by sampling rather than by moving pixels first,
     * so no intermediate the size of a master is allocated. At 12.6 MP a master is 151 MB and a
     * spare copy is what failed the first stacking run (§1.41).
     *
     * @param moonDisc the disc as found in [moon], which is where the radius comes from
     * @param shiftX how far the moon master must move to land where the ground master says
     * @param scale multiplies the moon layer as it goes in. 1.0 leaves it alone, which is the
     *   default because the exposure difference is the point rather than an error.
     */
    fun merge(
        ground: FloatArray,
        moon: FloatArray,
        width: Int,
        height: Int,
        moonDisc: Disc.Found,
        shiftX: Double,
        shiftY: Double,
        scale: Double = 1.0,
        channels: Int = TiledStacker.CHANNELS,
    ): Report {
        val count = width.toLong() * height * channels
        require(ground.size >= count) { "ground master is smaller than ${width}x$height" }
        require(moon.size >= count) { "moon master is smaller than ${width}x$height" }

        val radius = moonDisc.equivalentDiameterPx / 2.0
        val feather = maxOf(MIN_FEATHER_PX, radius * FEATHER_FRACTION)

        // Where the disc ends up in the ground master's frame.
        val centreX = moonDisc.centroidX + shiftX
        val centreY = moonDisc.centroidY + shiftY

        val outer = radius + feather
        val left = (centreX - outer).toInt().coerceIn(0, width - 1)
        val right = (centreX + outer).toInt().coerceIn(0, width - 1)
        val top = (centreY - outer).toInt().coerceIn(0, height - 1)
        val bottom = (centreY + outer).toInt().coerceIn(0, height - 1)

        var replaced = 0
        for (y in top..bottom) {
            for (x in left..right) {
                val d = hypot(x - centreX, y - centreY)
                if (d > outer) continue
                // 1 inside the limb, falling to 0 at the outer edge of the feather.
                val alpha = when {
                    d <= radius -> 1.0
                    else -> 1.0 - (d - radius) / feather
                }
                if (alpha <= 0.0) continue

                // Sample the moon master at the *unshifted* position, which is where this output
                // pixel's light came from. Nearest neighbour: the shift is sub-pixel only in the
                // sense that it was fitted, and interpolating a linear master's disc against a
                // black sky would soften the limb the whole mode exists to keep sharp.
                val sx = (x - shiftX).toInt()
                val sy = (y - shiftY).toInt()
                if (sx < 0 || sy < 0 || sx >= width || sy >= height) continue

                val di = (y.toLong() * width + x) * channels
                val si = (sy.toLong() * width + sx) * channels
                for (c in 0 until channels) {
                    val moonValue = moon[(si + c).toInt()]
                    if (moonValue.isNaN()) continue
                    val groundValue = ground[(di + c).toInt()]
                    val base = if (groundValue.isNaN()) 0.0 else groundValue.toDouble()
                    ground[(di + c).toInt()] =
                        (base * (1.0 - alpha) + moonValue * scale * alpha).toFloat()
                }
                replaced++
            }
        }

        return Report(
            discRadiusPx = radius,
            featherPx = feather,
            centreX = centreX,
            centreY = centreY,
            pixelsReplaced = replaced,
            note = "Layers are written alongside this merge: the blend is a taste decision, and " +
                "two registered masters are worth more in an editor than one flatten.",
        )
    }

    /**
     * The moon master resampled into the ground master's frame, as a layer to write out.
     *
     * Separate from [merge] because it is the thing that actually gets handed over — a full-frame
     * layer, aligned, that an editor can blend however it likes. Uncovered pixels carry `NaN`,
     * which is the same sentinel `LinearMaster` already writes for "no frame reached here", so the
     * existing writer and reader need no changes.
     */
    fun alignedLayer(
        moon: FloatArray,
        width: Int,
        height: Int,
        shiftX: Double,
        shiftY: Double,
        channels: Int = TiledStacker.CHANNELS,
    ): FloatArray {
        val count = width * height * channels
        require(moon.size >= count) { "moon master is smaller than ${width}x$height" }

        val out = FloatArray(count) { Float.NaN }
        val dx = shiftX.toInt()
        val dy = shiftY.toInt()

        for (y in 0 until height) {
            val sy = y - dy
            if (sy < 0 || sy >= height) continue
            for (x in 0 until width) {
                val sx = x - dx
                if (sx < 0 || sx >= width) continue
                val di = (y * width + x) * channels
                val si = (sy * width + sx) * channels
                for (c in 0 until channels) out[di + c] = moon[si + c]
            }
        }
        return out
    }
}
