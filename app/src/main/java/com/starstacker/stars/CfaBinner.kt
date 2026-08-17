package com.starstacker.stars

/**
 * Turns a raw CFA frame into the mono plane [StarDetector] works on (plan D-9).
 *
 * Green only, because it carries ~twice the samples of red or blue in a Bayer pattern and is
 * where most of a star's signal lands. Averaging all three channels would demand a demosaic —
 * which invents data between samples and would put the centroids on interpolated pixels rather
 * than measured ones.
 *
 * FR-7.2 specifies detection on a downsampled ~1 MP frame: a 4096x3072 CFA binned by 4 gives
 * 1024x768, and the binning also lifts faint stars out of the noise.
 */
object CfaBinner {

    /** The fallback when the arrangement is unknown — GRBG, as on the reference device. */
    val DEFAULT_CFA_CODES = listOf(1, 0, 2, 1)

    /**
     * CFA codes from the arrangement's name, for the paths that have a [DeviceProfile] rather
     * than live `CameraCharacteristics` — the exposure engine reads the profile, not the camera.
     */
    fun codesFor(arrangement: String?): List<Int> = when (arrangement?.uppercase()) {
        "RGGB" -> listOf(0, 1, 1, 2)
        "GRBG" -> listOf(1, 0, 2, 1)
        "GBRG" -> listOf(1, 2, 0, 1)
        "BGGR" -> listOf(2, 1, 1, 0)
        else -> DEFAULT_CFA_CODES
    }

    /**
     * @param cfaCodes the CFAPattern tag, row-major over a 2x2 cell: 0 = red, 1 = green, 2 = blue
     * @param factor total downsample factor, in raw pixels. Must be an even multiple of the 2x2
     *   Bayer cell, so 2, 4, 8 …
     */
    fun binGreen(
        pixels: ShortArray,
        width: Int,
        height: Int,
        cfaCodes: List<Int>,
        factor: Int = 4,
    ): BinnedPlane {
        require(factor >= 2 && factor % 2 == 0) { "factor must be even and >= 2, was $factor" }
        require(cfaCodes.size == 4) { "expected a 2x2 CFA pattern, got ${cfaCodes.size} codes" }

        // Offsets of the green samples within the 2x2 cell, as primitive arrays. A
        // List<Pair<Int, Int>> here boxes two Integers per sample and costs more than the
        // arithmetic it carries — FR-12.2's rule applied to a loop that runs 6 million times.
        var greenCount = 0
        for (i in 0..3) if (cfaCodes[i] == GREEN) greenCount++
        require(greenCount > 0) { "CFA pattern $cfaCodes contains no green samples" }
        val greenDx = IntArray(greenCount)
        val greenDy = IntArray(greenCount)
        var g = 0
        for (i in 0..3) {
            if (cfaCodes[i] == GREEN) {
                greenDx[g] = i % 2
                greenDy[g] = i / 2
                g++
            }
        }

        val outWidth = width / factor
        val outHeight = height / factor
        val out = FloatArray(outWidth * outHeight)
        val cellsPerBin = factor / 2

        for (oy in 0 until outHeight) {
            val baseY = oy * factor
            val outRow = oy * outWidth
            for (ox in 0 until outWidth) {
                var sum = 0
                var n = 0
                val baseX = ox * factor
                for (cy in 0 until cellsPerBin) {
                    val cellY = baseY + cy * 2
                    for (cx in 0 until cellsPerBin) {
                        val cellX = baseX + cx * 2
                        for (i in 0 until greenCount) {
                            val x = cellX + greenDx[i]
                            val y = cellY + greenDy[i]
                            if (x < width && y < height) {
                                sum += pixels[y * width + x].toInt() and 0xFFFF
                                n++
                            }
                        }
                    }
                }
                out[outRow + ox] = if (n > 0) sum.toFloat() / n else 0f
            }
        }

        return BinnedPlane(out, outWidth, outHeight, factor)
    }

    private const val GREEN = 1
}

/**
 * A downsampled mono plane plus the factor that produced it, so measurements taken here can be
 * expressed back in full-resolution pixels — the trailing limit and the registration transform
 * both live in sensor coordinates, not analysis coordinates.
 */
class BinnedPlane(
    val data: FloatArray,
    val width: Int,
    val height: Int,
    val binFactor: Int,
) {
    /** Converts an analysis-plane distance (e.g. HFR) to full-resolution sensor pixels. */
    fun toSensorPixels(value: Double): Double = value * binFactor

    /** Converts an analysis-plane coordinate to a full-resolution sensor coordinate. */
    fun toSensorCoordinate(value: Double): Double = value * binFactor + (binFactor - 1) / 2.0
}
