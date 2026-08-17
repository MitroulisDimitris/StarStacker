package com.starstacker.exposure

import android.util.Log
import com.starstacker.camera.CameraAccess
import com.starstacker.camera.FramingRequest
import com.starstacker.camera.FramingSession
import com.starstacker.device.CameraProfile
import com.starstacker.device.NoiseProfileEntry
import com.starstacker.stars.CfaBinner

/**
 * The measuring half of T-3.1 — the part that needs a camera.
 *
 * Two things come off the sensor here and they are gathered in one pass because they come from
 * the same frames. **Read noise per ISO** is metadata: `SENSOR_NOISE_PROFILE` is a capture-result
 * key, so a frame of any length at a given ISO reports it, and the whole ladder costs a short
 * burst rather than a series of real exposures. **The sky rate** is pixels, and needs one frame
 * that is neither clipped nor empty.
 *
 * Shared between the setup screen and the `--es diag solve` diagnostic deliberately: the solve a
 * user sees and the solve that answers OI-9 must be the same solve, or the diagnostic is
 * measuring something the app does not do.
 */
class SkyProbe(
    private val access: CameraAccess,
    private val camera: CameraProfile,
) {

    data class Measurement(
        val model: OemNoiseModel,
        val sky: SkyMeasurement,
        val dualGainIso: Int?,
        /** ISO of the frame the sky was measured from. */
        val measuredAtIso: Int,
        val backgroundAdu: Double,
        val starCount: Int,
        /** ISOs whose frames were clipped, so the caller can say why they were not used. */
        val clippedIsos: List<Int>,
    )

    class ProbeFailure(message: String) : Exception(message)

    data class Progress(val iso: Int, val index: Int, val total: Int)

    /**
     * @param isos the ladder to characterise, typically full stops across the sensor's range.
     * @param exposureNs the test exposure. Short on purpose — this is a measurement, not a sub,
     *   and a long one spends heat the session has not started yet (FR-6.2).
     */
    suspend fun measure(
        isos: List<Int>,
        exposureNs: Long,
        onProgress: (Progress) -> Unit = {},
    ): Measurement {
        val profiles = LinkedHashMap<Int, List<NoiseProfileEntry>>()
        val clipped = mutableListOf<Int>()
        var best: FrameSample? = null

        FramingSession.open(access, camera.id).use { session ->
            isos.forEachIndexed { i, iso ->
                onProgress(Progress(iso, i + 1, isos.size))
                session.apply(FramingRequest(iso, exposureNs, null))
                val frame = runCatching {
                    session.awaitSettledFrame(timeoutFor(exposureNs))
                }.getOrNull() ?: run {
                    Log.w(TAG, "no settled frame at ISO $iso")
                    return@forEachIndexed
                }

                frame.noiseProfile?.let { profiles[iso] = it }

                val appliedIso = frame.appliedIso ?: iso
                if (frame.stars.saturatedFrame) {
                    clipped += appliedIso
                    return@forEachIndexed
                }
                // The brightest frame that is *not* clipped carries the most signal above the
                // noise, so it is the one the sky rate should be read from. Taking whichever
                // frame came last would take the highest ISO of the ladder — the one likeliest
                // to be saturated, which is exactly how the first run of this measured the sky
                // from a frame pinned at the white level.
                if (best == null || appliedIso > best!!.iso) {
                    best = FrameSample(
                        iso = appliedIso,
                        backgroundAdu = frame.stars.background,
                        starCount = frame.stars.count,
                        exposureSeconds = (frame.appliedExposureNs ?: exposureNs) / 1e9,
                    )
                }
            }
        }

        if (profiles.isEmpty()) {
            throw ProbeFailure(
                "the camera reported no noise profile at any ISO — the exposure engine has " +
                    "nothing to work from on this device",
            )
        }
        val sample = best ?: throw ProbeFailure(
            "every test frame was clipped, even at ISO ${isos.minOrNull()}. Point away from " +
                "direct light — the sky cannot be measured through saturation.",
        )

        val whiteLevel = camera.whiteLevel ?: DEFAULT_WHITE_LEVEL
        val blackLevel = camera.blackLevelPattern?.average() ?: 0.0
        val model = OemNoiseModel.from(
            profilesByIso = profiles,
            cfaCodes = CfaBinner.codesFor(camera.cfaArrangement),
            whiteLevel = whiteLevel,
            blackLevel = blackLevel,
        )
        val noise = model.at(sample.iso)
            ?: throw ProbeFailure("no noise data at ISO ${sample.iso}")

        return Measurement(
            model = model,
            sky = SkyMeasurement.from(
                backgroundAdu = sample.backgroundAdu,
                blackLevelAdu = blackLevel,
                whiteLevelAdu = whiteLevel,
                iso = sample.iso,
                exposureSeconds = sample.exposureSeconds,
                noise = noise,
            ),
            dualGainIso = model.dualGainIso(),
            measuredAtIso = sample.iso,
            backgroundAdu = sample.backgroundAdu,
            starCount = sample.starCount,
            clippedIsos = clipped,
        )
    }

    private class FrameSample(
        val iso: Int,
        val backgroundAdu: Double,
        val starCount: Int,
        val exposureSeconds: Double,
    )

    private fun timeoutFor(exposureNs: Long): Long =
        exposureNs / 1_000_000 * (FramingSession.PIPELINE_DEPTH_FRAMES + 6) + 8_000L

    companion object {
        private const val TAG = "SkyProbe"
        private const val DEFAULT_WHITE_LEVEL = 1023

        /** Short: this is a measurement, and heat spent here is heat the session cannot use. */
        const val DEFAULT_TEST_EXPOSURE_NS = 250_000_000L

        /** Full stops across whatever range the sensor reports. */
        fun isoLadder(min: Int?, max: Int?): List<Int> {
            val lo = (min ?: 50).coerceAtLeast(25)
            val hi = (max ?: 3200).coerceAtLeast(lo)
            val stops = mutableListOf<Int>()
            var iso = lo
            while (iso <= hi) {
                stops += iso
                iso *= 2
            }
            return stops
        }
    }
}
