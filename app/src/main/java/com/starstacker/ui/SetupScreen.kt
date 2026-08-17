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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.starstacker.exposure.ExposureSolver
import com.starstacker.exposure.SessionPlanner
import com.starstacker.pointing.Astro
import com.starstacker.pointing.PointingFix
import com.starstacker.ui.theme.Night
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
        if (solution == null) {
            Mono(
                controller.busy ?: "the sky has not been measured yet",
                Night.Txt3,
                size = 11.5.sp,
            )
            Spacer(Modifier.height(10.dp))
            QuietButton(
                text = if (controller.busy != null) "Measuring…" else "Measure the sky",
                enabled = controller.busy == null && controller.camera != null,
                onClick = { controller.measureAndSolve() },
            )
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
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(15, 30, 60, 120).forEach { minutes ->
                Box(Modifier.weight(1f)) {
                    QuietButton(
                        text = "${minutes}m",
                        selected = controller.sessionMinutes == minutes,
                        onClick = { controller.chooseSessionMinutes(minutes) },
                    )
                }
            }
        }
    }
}

private fun clockOf(epochMs: Long): String =
    SimpleDateFormat("HH:mm", Locale.US).format(Date(epochMs))
