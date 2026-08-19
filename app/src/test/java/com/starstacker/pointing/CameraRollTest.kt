package com.starstacker.pointing

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * T-4.1 — the roll angle, tested against phone positions anyone can picture.
 *
 * Worth testing on a laptop precisely because it is otherwise only exercised on a tripod under a
 * real sky, at night, by someone who cannot see the number. A sign error here would send the drift
 * seed the wrong way around the frame while producing a shift of exactly the right size — the kind
 * of defect that survives every check except an explicit one.
 */
class CameraRollTest {

    /**
     * A rotation matrix, row-major, mapping device axes to world axes (X east, Y north, Z up).
     * Columns are where the device's own X, Y and Z axes land in the world.
     */
    private fun matrix(
        deviceX: Triple<Double, Double, Double>,
        deviceY: Triple<Double, Double, Double>,
        deviceZ: Triple<Double, Double, Double>,
    ) = floatArrayOf(
        deviceX.first.toFloat(), deviceY.first.toFloat(), deviceZ.first.toFloat(),
        deviceX.second.toFloat(), deviceY.second.toFloat(), deviceZ.second.toFloat(),
        deviceX.third.toFloat(), deviceY.third.toFloat(), deviceZ.third.toFloat(),
    )

    @Test
    fun `a phone held upright facing north reads zero roll`() {
        // Device +Y (the top of the phone) points at the sky; the lens looks along -Z, which is
        // north. Sky-up and device-up coincide, so the roll is nothing.
        val r = matrix(
            deviceX = Triple(1.0, 0.0, 0.0),   // device right -> east
            deviceY = Triple(0.0, 0.0, 1.0),   // device up    -> world up
            deviceZ = Triple(0.0, -1.0, 0.0),  // device back  -> south, so the lens faces north
        )
        assertEquals(0.0, CameraRoll.degrees(r, 0.0, 1.0, 0.0)!!, 1e-6)
    }

    @Test
    fun `turning the phone on its side gives a quarter turn of roll`() {
        // Rotate the phone 90 degrees anticlockwise about the lens axis: the top of the phone now
        // points west while the lens still looks north.
        val r = matrix(
            deviceX = Triple(0.0, 0.0, 1.0),    // device right -> up
            deviceY = Triple(-1.0, 0.0, 0.0),   // device up    -> west
            deviceZ = Triple(0.0, -1.0, 0.0),   // lens still faces north
        )
        assertEquals(90.0, CameraRoll.degrees(r, 0.0, 1.0, 0.0)!!, 1e-6)
    }

    @Test
    fun `the other side gives the opposite sign`() {
        val r = matrix(
            deviceX = Triple(0.0, 0.0, -1.0),
            deviceY = Triple(1.0, 0.0, 0.0),    // device up -> east
            deviceZ = Triple(0.0, -1.0, 0.0),
        )
        assertEquals(-90.0, CameraRoll.degrees(r, 0.0, 1.0, 0.0)!!, 1e-6)
    }

    @Test
    fun `upside down is half a turn`() {
        val r = matrix(
            deviceX = Triple(-1.0, 0.0, 0.0),
            deviceY = Triple(0.0, 0.0, -1.0),   // device up -> world down
            deviceZ = Triple(0.0, -1.0, 0.0),
        )
        assertEquals(180.0, kotlin.math.abs(CameraRoll.degrees(r, 0.0, 1.0, 0.0)!!), 1e-6)
    }

    @Test
    fun `roll is measured about the lens, not about the horizon`() {
        // A phone tilted up at 45 degrees but not rolled still reads zero: the lens axis has moved,
        // and "up on the sky" moved with it. This is the case that separates roll from pitch, and
        // getting it wrong would make every tilted pointing report a phantom rotation.
        val s = sin(Math.PI / 4)
        val r = matrix(
            deviceX = Triple(1.0, 0.0, 0.0),        // right stays east
            deviceY = Triple(0.0, -s, s),           // top tips backwards as the lens rises
            deviceZ = Triple(0.0, -s, -s),          // lens looks north and 45 degrees up
        )
        assertEquals(0.0, CameraRoll.degrees(r, 0.0, s, s)!!, 1e-6)
    }

    @Test
    fun `pointing at the zenith has no defined roll`() {
        // Every direction in the frame is equally "up on the sky", so any number would be noise.
        val r = matrix(
            deviceX = Triple(1.0, 0.0, 0.0),
            deviceY = Triple(0.0, 1.0, 0.0),
            deviceZ = Triple(0.0, 0.0, -1.0),
        )
        assertNull(CameraRoll.degrees(r, 0.0, 0.0, 1.0))
        assertNull(CameraRoll.degrees(r, 0.0, 0.0, -1.0)) { "straight down is degenerate too" }
    }

    @Test
    fun `a short matrix is refused rather than read past its end`() {
        assertNull(CameraRoll.degrees(FloatArray(4), 0.0, 1.0, 0.0))
    }

    @Test
    fun `roll is continuous as the phone turns`() {
        // Sweeping the roll through a full turn must produce the angle back, with no jumps other
        // than the single wrap at +-180.
        listOf(0.0, 20.0, 60.0, 120.0, 175.0, -30.0, -100.0).forEach { expected ->
            val t = Math.toRadians(expected)
            val r = matrix(
                // The frame turning anticlockwise about a lens that looks north.
                deviceX = Triple(cos(t), 0.0, -sin(t)),
                deviceY = Triple(-sin(t), 0.0, cos(t)),
                deviceZ = Triple(0.0, -1.0, 0.0),
            )
            assertEquals(expected, CameraRoll.degrees(r, 0.0, 1.0, 0.0)!!, 1e-6) { "at $expected" }
        }
    }
}
