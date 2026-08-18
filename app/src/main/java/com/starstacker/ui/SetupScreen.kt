package com.starstacker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.starstacker.exposure.PredictedHistogram
import com.starstacker.exposure.ExposureSolver
import com.starstacker.exposure.SessionPlanner
import com.starstacker.pointing.Astro
import com.starstacker.pointing.PointingFix
import com.starstacker.ui.theme.Night
import com.starstacker.ui.theme.NumFamily
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Prototype screen 02 — session setup (T-3.4, T-3.5).
 *
 * The screen's whole argument is that **one line carries the solve** and `Show work` opens the
 * derivation underneath it, on the same screen, one tap deeper. There is no advanced mode: an
 * advanced mode splits the users into two groups and gives the wrong half the wrong screen, and
 * an expert standing in a field at 1 a.m. wanting to know why it picked ISO 800 is not a
 * different person from the beginner who does not.
 *
 * The budget warnings sit **above** Start and can disable it, because unlike the calibration
 * banner they are not advice — a session that will not fit on the volume is not a session.
 */
@Composable
fun SetupScreen(
    controller: SetupController,
    pointing: PointingFix?,
    onBack: () -> Unit,
    onStart: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Night.Void)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { Spacer(Modifier.height(16.dp)) }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "← Back",
                    fontSize = 13.sp,
                    color = Night.Txt3,
                    modifier = Modifier.clickable(onClick = onBack),
                )
                Spacer(Modifier.weight(1f))
                Mono("SESSION SETUP", color = Night.Dim, size = 9.5.sp)
            }
        }

        item { Eyebrow("Pointing") }
        item { PointingSummary(pointing) }

        item { Eyebrow("Exposure") }
        item { ExposureCard(controller) }

        controller.solution?.let { solution ->
            if (controller.showWork) {
                item { DerivationCard(solution, controller) }
            }
        }

        item { Eyebrow("Plan") }
        item { PlanCard(controller) }

        item {
            HotButton(
                text = "Start session",
                enabled = controller.plan?.let { !it.blocked } == true && controller.busy == null,
                onClick = onStart,
            )
        }

        // FR-4.0.4.2's placement rule, applied to the budgets: anything that is *advice* sits
        // below the start control so it cannot read as a gate. Anything that genuinely blocks
        // has already disabled the button above.
        controller.plan?.let { plan ->
            listOf(plan.storage, plan.battery, plan.rotation)
                .filter { it.severity != SessionPlanner.Severity.OK }
                .forEach { budget ->
                    item {
                        Banner(
                            "${budget.label}: ${budget.detail}",
                            color = if (budget.severity == SessionPlanner.Severity.BLOCK) {
                                Night.Red
                            } else {
                                Night.Warn
                            },
                        )
                    }
                }
        }

        controller.error?.let { item { Banner(it, color = Night.Red) } }
        item { Spacer(Modifier.height(28.dp)) }
    }
}

@Composable
private fun PointingSummary(pointing: PointingFix?) {
    Card {
        if (pointing == null) {
            Mono("no pointing fix — the trailing limit will assume the equator", Night.Txt3)
            return@Card
        }
        // True azimuth, not magnetic — the sky is measured from true north, and without a
        // location fix there is no declination correction and so no true bearing to show.
        val azimuth = pointing.azimuthTrueDeg
        KeyValue(
            "Altitude / azimuth",
            if (azimuth == null) {
                "%.1f° / %.0f° magnetic".format(pointing.altitudeDeg, pointing.azimuthMagneticDeg)
            } else {
                "%.1f° / %.0f° %s".format(
                    pointing.altitudeDeg, azimuth, Astro.compassPoint(azimuth),
                )
            },
        )
        KeyValue(
            "Declination at centre",
            pointing.declinationDeg?.let { Astro.formatDeclination(it) } ?: "needs location",
        )
        KeyValue(
            "Field rotation",
            pointing.fieldRotationArcsecPerSec?.let { "%.1f″/s".format(it) } ?: "needs location",
        )
    }
}

@Composable
private fun ExposureCard(controller: SetupController) {
    val solution = controller.solution
    Card {
        // T-3.25: solving is what this screen is *for*, so it does not wait to be asked. The
        // retry stays for the case where it failed, which is the only case a button helps.
        LaunchedEffect(controller.camera?.id) {
            if (controller.camera != null && controller.solution == null && controller.busy == null) {
                controller.measureAndSolve()
            }
        }
        if (solution == null) {
            Mono(controller.busy ?: "measuring the sky…", Night.Txt3, size = 11.5.sp)
            if (controller.busy == null && controller.error != null) {
                Spacer(Modifier.height(10.dp))
                QuietButton(text = "Try again", onClick = { controller.measureAndSolve() })
            }
            return@Card
        }

        // The one line. Everything above and below it exists to justify this.
        Text(
            controller.plan?.headline ?: solution.headline,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            color = Night.Txt,
        )
        Spacer(Modifier.height(4.dp))
        Mono(
            if (controller.pinnedIso != null) {
                "ISO pinned to ${controller.pinnedIso} — re-solved around it"
            } else {
                "Solved from your sensor and this pointing"
            },
            color = Night.Txt3,
            size = 10.5.sp,
        )
        controller.histogram?.let { prediction ->
            Spacer(Modifier.height(12.dp))
            HistogramCard(prediction)
            Spacer(Modifier.height(10.dp))
            ExposureCompensation(controller)
        }

        Spacer(Modifier.height(8.dp))
        Text(
            if (controller.showWork) "Hide work ▴" else "Show work ▾",
            fontSize = 12.sp,
            color = Night.Txt2,
            modifier = Modifier.clickable { controller.toggleWork() },
        )
    }
}

/**
 * FR-5.3's expansion. Rendered straight off the solution object, including **the candidates that
 * lost and why** — which is the part that makes it an audit trail rather than a summary.
 */
@Composable
private fun DerivationCard(solution: ExposureSolver.Solution, controller: SetupController) {
    Card {
        Eyebrow("Derivation")
        KeyValue(
            "Trailing limit (dec-corrected)",
            ExposureSolver.formatSeconds(solution.trailing.maxExposureSeconds),
        )
        KeyValue("Star elongation budget", "%.1f px".format(solution.trailing.tolerancePx))
        KeyValue("Sky background", "%.0f e⁻/s".format(solution.sky.electronsPerSecond))
        KeyValue("Dual-gain switch", solution.dualGainIso?.let { "ISO $it" } ?: "none on this sensor")
        solution.chosen?.let {
            KeyValue("Read noise at ISO ${it.iso}", "%.2f e⁻".format(it.readNoiseElectrons))
            KeyValue("Highlight headroom", "%.1f stops".format(it.clippingHeadroomStops))
            KeyValue("Sky vs read noise", "%.1f× in variance".format(it.skyToReadVariance))
        }

        Spacer(Modifier.height(10.dp))
        Mono(solution.advisory, color = Night.Txt2, size = 11.sp)

        Spacer(Modifier.height(12.dp))
        Eyebrow("Every ISO considered — tap to pin")
        solution.candidates.forEach { candidate ->
            CandidateRow(
                candidate = candidate,
                chosen = candidate.iso == solution.chosen?.iso,
                pinned = candidate.iso == controller.pinnedIso,
                onClick = { controller.pinIso(candidate.iso) },
            )
        }
    }
}

@Composable
private fun CandidateRow(
    candidate: ExposureSolver.Candidate,
    chosen: Boolean,
    pinned: Boolean,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 5.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Mono(
                "ISO %-5d".format(candidate.iso),
                color = if (chosen) Night.Txt else Night.Txt3,
                size = 11.5.sp,
            )
            Spacer(Modifier.weight(1f))
            if (pinned) Badge("PINNED", Night.Warn)
            else if (chosen) Badge("CHOSEN", Night.Red)
        }
        Mono(candidate.reason, color = Night.Txt3, size = 10.sp)
    }
}

@Composable
private fun PlanCard(controller: SetupController) {
    val plan = controller.plan
    Card {
        if (plan == null) {
            Mono("solve an exposure first", Night.Txt3, size = 11.5.sp)
            return@Card
        }

        KeyValue(
            "Lights",
            "%d × %s → %s".format(
                plan.lightCount,
                ExposureSolver.formatSeconds(plan.subSeconds),
                ExposureSolver.formatSeconds(plan.integrationSeconds),
            ),
        )
        KeyValue(
            "Darks (at end)",
            "%d × %s → %s".format(
                plan.darkCount,
                ExposureSolver.formatSeconds(plan.subSeconds),
                ExposureSolver.formatSeconds(plan.darkCount * plan.subSeconds),
            ),
        )
        KeyValue(
            "Storage",
            plan.storage.detail,
            warn = plan.storage.severity != SessionPlanner.Severity.OK,
        )
        KeyValue(
            "Battery",
            plan.battery.detail,
            warn = plan.battery.severity != SessionPlanner.Severity.OK,
        )
        KeyValue("Ends", clockOf(plan.endsAtEpochMs))
        KeyValue(
            "Frame left at end",
            plan.commonAreaFraction?.let { "%.0f%% common area".format(it * 100) }
                ?: "needs a pointing fix",
            warn = plan.rotation.severity != SessionPlanner.Severity.OK,
        )

        Spacer(Modifier.height(12.dp))
        Eyebrow("Session length")
        // T-3.26: a continuous drag in *frames*. Presets made four arbitrary answers look like
        // the only ones; the quantum is the frame, and the time follows from it.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${plan.lightCount} frames",
                fontFamily = NumFamily,
                fontSize = 15.sp,
                color = Night.Txt,
                modifier = Modifier.weight(1f),
            )
            // The headline above states *integration* — light only. This is wall clock, darks
            // included, and the two differ by minutes. Saying which is which is the difference
            // between a plan and two contradictory plans.
            Text(
                "${ExposureSolver.formatSeconds(plan.totalSeconds)} total",
                fontFamily = NumFamily,
                fontSize = 15.sp,
                color = Night.Txt2,
            )
        }
        Slider(
            value = controller.frameCount.toFloat(),
            onValueChange = { controller.chooseFrameCount(it.toInt()) },
            valueRange = 1f..controller.maxFrames.toFloat(),
            colors = SliderDefaults.colors(
                thumbColor = Night.Hot,
                activeTrackColor = Night.Red,
                inactiveTrackColor = Night.LineSoft,
            ),
        )
        Mono(
            "1 frame to ${ExposureSolver.formatSeconds(controller.maxFrames * plan.subSeconds)} " +
                "· ${plan.darkCount} darks are inside the total, not added to it",
            color = Night.Txt3,
            size = 10.sp,
        )
    }
}

private fun clockOf(epochMs: Long): String =
    SimpleDateFormat("HH:mm", Locale.US).format(Date(epochMs))

/**
 * T-3.25 — the predicted histogram.
 *
 * Read left to right: the hump is the sky, and where it sits is the whole answer. Hard against the
 * left wall means read-noise limited — the sensor's own noise is louder than the sky. Hard against
 * the right means clipped. A little way in, with room to spare, is what "sky-limited" looks like.
 */
@Composable
private fun HistogramCard(prediction: PredictedHistogram.Prediction) {
    Column {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(64.dp),
        ) {
            val binWidth = size.width / prediction.bins.size
            prediction.bins.forEachIndexed { i, height ->
                val h = (height * size.height).toFloat().coerceAtLeast(1f)
                drawRect(
                    color = if (prediction.clipped) Night.Warn else Night.Red,
                    topLeft = Offset(i * binWidth, size.height - h),
                    size = Size(binWidth * 0.85f, h),
                )
            }
            // The right wall. Everything past it is clipped and unrecoverable.
            drawLine(
                color = Night.Warn,
                start = Offset(size.width - 1f, 0f),
                end = Offset(size.width - 1f, size.height),
                strokeWidth = 2f,
            )
        }
        Spacer(Modifier.height(6.dp))
        Mono(
            when {
                prediction.clipped -> "clipped — the sky is off the right of the frame"
                prediction.readNoiseLimited ->
                    "read-noise limited · %.1f stops of headroom".format(prediction.headroomStops)
                else -> "sky-limited · %.1f stops of headroom".format(prediction.headroomStops)
            },
            color = if (prediction.clipped || prediction.readNoiseLimited) Night.Warn else Night.Txt3,
            size = 10.5.sp,
        )
    }
}

/**
 * T-3.25's veto. The solve is the recommendation; this is the disagreement, with its cost shown
 * rather than described — the histogram above moves as the value does.
 */
@Composable
private fun ExposureCompensation(controller: SetupController) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Mono("Exposure", color = Night.Txt3, size = 10.5.sp, modifier = Modifier.weight(1f))
        Mono(
            if (controller.exposureStops == 0.0) {
                "as solved"
            } else {
                "%+.2f stops · %s".format(
                    controller.exposureStops,
                    ExposureSolver.formatSeconds(controller.effectiveSubSeconds ?: 0.0),
                )
            },
            color = if (controller.exposureStops == 0.0) Night.Txt3 else Night.Txt,
            size = 10.5.sp,
        )
    }
    Slider(
        value = controller.exposureStops.toFloat(),
        onValueChange = { controller.compensate(it.toDouble()) },
        valueRange = -SetupController.MAX_STOPS.toFloat()..SetupController.MAX_STOPS.toFloat(),
        steps = 11,
        colors = SliderDefaults.colors(
            thumbColor = Night.Hot,
            activeTrackColor = Night.Red,
            inactiveTrackColor = Night.LineSoft,
        ),
    )
    controller.compensatedTrailPx?.takeIf { it > controller.solution!!.trailing.tolerancePx * 1.05 }
        ?.let {
            Mono(
                "stars will trail about %.1f px — the budget is %.1f".format(
                    it, controller.solution!!.trailing.tolerancePx,
                ),
                color = Night.Warn,
                size = 10.sp,
            )
        }
}
