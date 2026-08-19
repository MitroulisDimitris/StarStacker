package com.starstacker.pointing

import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * T-4.1 — which way up the camera is, relative to the sky.
 *
 * [SkyDrift][com.starstacker.registration.SkyDrift] predicts how far the sky moves and along which
 * horizon direction. Turning that into a shift *in the picture* needs one more angle: how the frame
 * is rotated about the lens axis. Altitude and azimuth say where the camera looks; this says which
 * way up it is, and without it a seed knows the size of the drift but not its sign.
 *
 * ### The definition
 *
 * "Up on the sky" at a given pointing is the direction of increasing altitude — world **up** with
 * the part along the optical axis removed, which is what makes it perpendicular to the view. Roll
 * is the signed angle from that to the device's own up axis, measured about the optical axis and
 * positive anticlockwise as seen looking out along the lens.
 *
 * Separated from `PointingSource` because it is pure vector arithmetic over a rotation matrix, and
 * a sign convention that is only exercised on a tripod under a real sky is a sign convention nobody
 * ever checks.
 */
object CameraRoll {

    /**
     * @param rotation the 3×3 row-major matrix from `SensorManager.getRotationMatrix`, mapping
     *   device coordinates into the world frame (X east, Y north, Z up).
     * @param dx , [dy], [dz] the unit optical-axis direction in world coordinates — the smoothed
     *   one the caller already maintains, rather than a second copy read back out of the matrix.
     * @return degrees, or null when the camera points close enough to straight up or down that
     *   "up on the sky" degenerates and any answer would be noise.
     */
    fun degrees(rotation: FloatArray, dx: Double, dy: Double, dz: Double): Double? {
        if (rotation.size < 9) return null

        // World up, with its component along the view removed: the direction a star climbs in.
        var ux = -dx * dz
        var uy = -dy * dz
        var uz = 1.0 - dz * dz
        val length = sqrt(ux * ux + uy * uy + uz * uz)
        // Straight up or straight down: every direction in the frame is equally "up on the sky",
        // so roll is undefined rather than merely imprecise. Null is the honest answer, and
        // SkyDrift already refuses to seed near the zenith for the related reason.
        if (length < DEGENERATE) return null
        ux /= length
        uy /= length
        uz /= length

        // The device's own up axis (+Y) in world coordinates: column 1 of the rotation matrix.
        val vx = rotation[1].toDouble()
        val vy = rotation[4].toDouble()
        val vz = rotation[7].toDouble()

        // Signed angle from sky-up to device-up, measured about the optical axis.
        //
        // The cross product (skyUp x deviceUp) . opticalAxis is positive for the handedness of
        // someone standing *in front of* the lens looking back at it. The useful convention is the
        // opposite one — anticlockwise **in the image**, which is what a person behind the phone
        // sees and what SkyDrift consumes — so the sine is negated. Pinned by
        // CameraRollTest: lens north, top of the phone west, is a left turn from behind and
        // therefore +90.
        val cosine = ux * vx + uy * vy + uz * vz
        val sine = -(
            (uy * vz - uz * vy) * dx +
                (uz * vx - ux * vz) * dy +
                (ux * vy - uy * vx) * dz
            )
        if (hypot(sine, cosine) < DEGENERATE) return null
        return Math.toDegrees(atan2(sine, cosine))
    }

    /** Below this the two vectors are too nearly parallel for the angle to mean anything. */
    private const val DEGENERATE = 1e-6
}
