package com.starstacker.stars

/**
 * T-4.6 — where a preview pixel comes from in the frame being stacked.
 *
 * Six numbers, applied as `sourceX = a·px + b·py + tx` and `sourceY = c·px + d·py + ty`. An affine
 * form rather than a rotation and a shift because it is what the sampling loop actually wants: the
 * inner loop runs once per preview pixel — nearly two hundred thousand times a frame — and it
 * should be two multiplies and an add, not a trigonometric call and a change of origin.
 *
 * It lives here, in `stars`, and carries no astronomy. [PreviewStack] must not depend on the
 * registration package: preview is a display concern that happens to be told where things go, and
 * a dependency the other way would put a rigid-body transform inside a downsampler.
 */
data class PlaneMapping(
    val a: Double,
    val b: Double,
    val tx: Double,
    val c: Double,
    val d: Double,
    val ty: Double,
) {
    fun sourceX(px: Int, py: Int): Double = a * px + b * py + tx
    fun sourceY(px: Int, py: Int): Double = c * px + d * py + ty

    companion object {
        /**
         * The mapping for a frame that needs no alignment beyond the downsample — the reference
         * frame, or a preview with no registration behind it.
         */
        fun scaling(scaleX: Double, scaleY: Double): PlaneMapping =
            PlaneMapping(scaleX, 0.0, 0.0, 0.0, scaleY, 0.0)

        /** Downsample plus a whole-plane shift: what the preview did before registration existed. */
        fun shifted(scaleX: Double, scaleY: Double, dx: Double, dy: Double): PlaneMapping =
            PlaneMapping(scaleX, 0.0, dx, 0.0, scaleY, dy)

        /**
         * Composes a **sensor-space** affine transform down onto the preview grid.
         *
         * Three coordinate systems meet here and getting the conversion wrong is invisible in the
         * result — a preview that is merely a little soft, which is also what bad focus, bad
         * seeing and a bad stack all look like. Written out rather than folded into a one-liner
         * for that reason:
         *
         * - a preview pixel covers `scale` plane pixels;
         * - a plane pixel covers `binFactor` sensor pixels, offset by `binOffset`, which is
         *   [BinnedPlane.toSensorCoordinate]'s convention for where a binned sample sits;
         * - [sensorMatrix] is [com.starstacker.registration.RigidTransform.toMatrix]'s six
         *   numbers, mapping reference sensor coordinates to this frame's.
         *
         * Substituting `sensor = plane · bin + offset` and `plane = preview · scale` into the
         * sensor transform and collecting terms gives the six below. The translation carries the
         * `binOffset · (a + b − 1)` correction that a naive `tx / binFactor` would drop — small,
         * constant, and exactly the sort of half-pixel bias that would smear a hundred-frame stack
         * without ever looking like an error.
         */
        fun fromSensorMatrix(
            sensorMatrix: List<Double>,
            scaleX: Double,
            scaleY: Double,
            binFactor: Int,
            binOffset: Double,
        ): PlaneMapping {
            require(sensorMatrix.size == 6) { "expected six affine terms" }
            val (a, b, c, d) = listOf(
                sensorMatrix[0], sensorMatrix[1], sensorMatrix[2], sensorMatrix[3],
            )
            val tx = sensorMatrix[4]
            val ty = sensorMatrix[5]
            val bin = binFactor.toDouble()
            return PlaneMapping(
                a = a * scaleX,
                b = b * scaleY,
                tx = (binOffset * (a + b - 1.0) + tx) / bin,
                c = c * scaleX,
                d = d * scaleY,
                ty = (binOffset * (c + d - 1.0) + ty) / bin,
            )
        }
    }
}

private operator fun <T> List<T>.component4(): T = this[3]
