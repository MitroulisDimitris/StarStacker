package com.starstacker.registration

import com.starstacker.stars.BinnedPlane
import com.starstacker.stars.Star
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin

/**
 * T-4.7, end to end through [LiveRegistration] — a session with no stars in it.
 *
 * ### What this reproduces
 *
 * Session `2026-09-06_0118`: a crescent moon over a harbour, where `AsterismMatcher` rejected
 * **34 of 67 frames** on a scene that was aligned to the pixel. There are no point sources to build
 * a triangle from — the detector's brightest returns all sit inside one small blob, and most of the
 * rest are shimmering water — so the star path has nothing to work with and never will.
 *
 * The frames here carry **no detections at all**, which is the honest form of that: whatever the
 * detector returns on such a scene is noise, and a matcher handed noise fails. What matters is that
 * the frame is still placed.
 */
class StarlessRegistrationTest {

    private val binFactor = 2
    private val planeWidth = 640
    private val planeHeight = 480
    private val sensorWidth = planeWidth * binFactor
    private val sensorHeight = planeHeight * binFactor

    /** Broadband detail, so the landscape dominates the correlation — see `PhaseCorrelationTest`. */
    private fun texture(x: Double, y: Double): Double {
        fun at(ix: Int, iy: Int): Double {
            var h = ix * 374761393 + iy * 668265263
            h = (h xor (h shr 13)) * 1274126177
            return (((h xor (h shr 16)) and 0x7fffffff).toDouble() / 0x7fffffff) - 0.5
        }
        val x0 = floor(x).toInt()
        val y0 = floor(y).toInt()
        val fx = x - x0
        val fy = y - y0
        val a = at(x0, y0) * (1 - fx) + at(x0 + 1, y0) * fx
        val b = at(x0, y0 + 1) * (1 - fx) + at(x0 + 1, y0 + 1) * fx
        return a * (1 - fy) + b * fy
    }

    /** A harbour: a textured shore, a horizon, and one bright object that moves on its own. */
    private fun plane(shiftX: Double = 0.0, shiftY: Double = 0.0, moonOffsetX: Double = 0.0): BinnedPlane {
        val data = FloatArray(planeWidth * planeHeight)
        for (y in 0 until planeHeight) {
            for (x in 0 until planeWidth) {
                val sx = x - shiftX
                val sy = y - shiftY
                var v = 40.0 + 20.0 * sin(sx * 0.017) * cos(sy * 0.013) + 70.0 * texture(sx, sy)
                if (sy > planeHeight * 0.55) v += 60.0
                val r = hypot(sx - moonOffsetX - planeWidth * 0.3, sy - planeHeight * 0.22)
                if (r < 6) v += 700.0 * (1.0 - r / 6.0)
                data[y * planeWidth + x] = v.toFloat()
            }
        }
        return BinnedPlane(data, planeWidth, planeHeight, binFactor)
    }

    private fun register(
        live: LiveRegistration,
        plane: BinnedPlane,
        stars: List<Star> = emptyList(),
    ) = live.register(stars, plane, sensorWidth, sensorHeight)

    /** Enough detections to become a reference, so the fallback path is what is being tested. */
    private fun referenceStars(): List<Star> = (0 until 40).map {
        val a = it * 0.37
        Star(
            x = planeWidth / 2.0 + 180 * cos(a),
            y = planeHeight / 2.0 + 130 * sin(a),
            flux = 1000.0 + it,
            peak = 500.0,
            hfr = 2.0,
            eccentricity = 0.1,
            pixelCount = 9,
            saturated = false,
        )
    }

    @Test
    fun `a starless frame is placed instead of being rejected`() {
        val live = LiveRegistration()
        assertTrue(register(live, plane(), referenceStars()).isReference)

        // No detections at all: the star path cannot even be attempted.
        val outcome = register(live, plane())

        assertTrue(!outcome.failed, "the frame should have been placed: ${live.describe(outcome)}")
        assertEquals(AsterismMatcher.Method.PHASE_CORRELATION, outcome.method)
        checkNotNull(outcome.transform)
    }

    @Test
    fun `the shift it finds is the one the frame actually has`() {
        val live = LiveRegistration()
        register(live, plane(), referenceStars())

        val outcome = register(live, plane(shiftX = 7.0, shiftY = -4.0))
        val transform = checkNotNull(outcome.transform)

        // Reported in *sensor* pixels, so the analysis plane's binning has to be undone.
        assertEquals(7.0 * binFactor, transform.dx, 3.0)
        assertEquals(-4.0 * binFactor, transform.dy, 3.0)
    }

    /**
     * The measurement that motivated the task: the landscape is static and the moon is not, and
     * the session is aligned to the pixel however far the moon has moved.
     */
    @Test
    fun `a moving moon over a static shore still registers at zero`() {
        val live = LiveRegistration()
        register(live, plane(), referenceStars())

        val outcome = register(live, plane(moonOffsetX = 13.0))
        val transform = checkNotNull(outcome.transform)

        assertEquals(0.0, transform.dx, 3.0, "the shore did not move; the moon did")
        assertEquals(0.0, transform.dy, 3.0)
    }

    /** Translation only, by construction — and the log has to say so. */
    @Test
    fun `the fallback reports no rotation and says which method placed the frame`() {
        val live = LiveRegistration()
        register(live, plane(), referenceStars())
        val outcome = register(live, plane(shiftX = 5.0))

        assertEquals(0.0, checkNotNull(outcome.transform).rotationDeg)

        val line = live.describe(outcome)
        assertTrue(line.contains("translation only"), line)
        assertTrue(line.contains("correlation"), line)
    }

    /**
     * The residual monitor tracks a *star* residual. Feeding it a frame with none would poison the
     * baseline every later frame is judged against.
     */
    @Test
    fun `a correlated frame does not disturb the residual baseline`() {
        val live = LiveRegistration()
        register(live, plane(), referenceStars())
        val before = live.baselineResidualPx

        val outcome = register(live, plane(shiftX = 4.0))
        assertEquals(ResidualMonitor.Verdict.UNKNOWN, outcome.verdict)
        assertTrue(outcome.residualRmsPx.isNaN(), "no stars were fitted, so there is no residual")
        assertEquals(before, live.baselineResidualPx)
    }

    /** Nothing in common between the frames is still a failure, not a guess. */
    @Test
    fun `an unrelated frame is still rejected`() {
        val live = LiveRegistration()
        register(live, plane(), referenceStars())

        val noise = FloatArray(planeWidth * planeHeight).also { arr ->
            val rng = kotlin.random.Random(3)
            for (i in arr.indices) arr[i] = rng.nextDouble(0.0, 100.0).toFloat()
        }
        val outcome = register(live, BinnedPlane(noise, planeWidth, planeHeight, binFactor))

        assertTrue(outcome.failed, "unrelated frames must not be rescued")
        assertNull(outcome.transform)
    }

    /**
     * The first frame of a session still cannot be a registration failure (§1.29): there is nothing
     * to correlate against yet, and the fallback must not change that.
     */
    @Test
    fun `a starved first frame is still starved, not correlated`() {
        val live = LiveRegistration()
        val outcome = register(live, plane())

        assertTrue(outcome.tooFewStars)
        assertTrue(!outcome.failed)
        assertNull(outcome.transform)
    }
}
