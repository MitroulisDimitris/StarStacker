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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.starstacker.exposure.ExposureCompensation
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
    /**
     * T-3.30 — what this session will be called if nobody types anything: the day, numbered if it
     * is not the first tonight. Computed by scanning the root ([com.starstacker.session.SessionNaming]),
     * so it is passed in rather than derived here.
     */
    defaultLabel: String,
    onBack: () -> Unit,
    onStart: (label: String) -> Unit,
) {
    // The prompt is a state of this screen rather than a dialog over it: a dialog at 2 a.m. arrives
    // at Material's own brightness, and the one thing every screen in this app agrees on is that
    // nothing does that.
    var naming by remember { mutableStateOf(false) }
    var typed by remember(defaultLabel) { mutableStateOf(defaultLabel) }
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
            if (naming) {
                NameThisSession(
                    typed = typed,
                    onTyped = { typed = it },
                    defaultLabel = defaultLabel,
                    onStart = { onStart(typed) },
                    onCancel = { naming = false; typed = defaultLabel },
                )
            } else {
                HotButton(
                    text = "Start session",
                    enabled = controller.plan?.let { !it.blocked } == true && controller.busy == null,
                    onClick = { naming = true },
                )
            }
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

/**
 * T-3.33 / **D-27** — the sky is measured **when asked**.
 *
 * This screen used to solve on arrival, from a `LaunchedEffect`, justified as "solving is what this
 * screen is *for*, so it does not wait to be asked". That is true of the screen and false of the
 * cost: the measurement opens the camera and spends frames the moment the screen appears, so a
 * mistaken tap cost a sky measurement, and the only feedback was a phone that got warm. The cost
 * is now stated first and paid on a press.
 */
@Composable
private fun ExposureCard(controller: SetupController) {
    val solution = controller.solution
    Card {
        if (solution == null) {
            if (controller.busy != null) {
                Mono(controller.busy!!, Night.Txt3, size = 11.5.sp)
                return@Card
            }
            Text(
                if (controller.error != null) "The sky could not be measured" else "Measure the sky",
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = if (controller.error != null) Night.Warn else Night.Txt,
            )
            Spacer(Modifier.height(4.dp))
            // The cost, before the button rather than after it. Moving a surprise one tap later
            // is not the same as removing it.
            Mono(controller.measurementCost(), color = Night.Txt3, size = 10.5.sp)
            Spacer(Modifier.height(10.dp))
            QuietButton(
                text = if (controller.measurementAsked) "Try again" else "Measure the sky",
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
        // "Solved from your sensor and this pointing" described the app's own working rather than
        // the reader's position. What the line has to carry is that these are a *suggestion* built
        // from something measured, and therefore both trustworthy and overridable — which is what
        // the compensation dial below it is for.
        Mono(
            if (controller.pinnedIso != null) {
                "ISO pinned to ${controller.pinnedIso} — re-solved around it"
            } else {
                "Suggested settings based on measurements."
            },
            color = Night.Txt3,
            size = 10.5.sp,
        )
        controller.histogram?.let { prediction ->
            Spacer(Modifier.height(12.dp))
            HistogramCard(prediction)
            Spacer(Modifier.height(10.dp))
            ExposureCompensationControl(controller)
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

/**
 * T-3.30 — the session is named at the start.
 *
 * **Every session from the UI was labelled `"session"`** — the literal string — so twelve nights of
 * shooting produced twelve folders distinguished only by their timestamps, and a list where every
 * row read the same word. It is asked for here, at Start, and not later: T-3.16 writes session
 * identity into every DNG's `ImageDescription`, so the name has to exist before the first exposure
 * or there is a rename to propagate across two hundred files that have already been written.
 *
 * **The field is pre-filled with the default rather than left empty behind a placeholder.** Three
 * things follow from that, and all three are the point:
 *
 * - the user can *see* what the session will be called if they change nothing, instead of finding
 *   out afterwards in the list;
 * - "cancelled or left blank, the session is named for the day" is true by construction — clearing
 *   the field and starting gives the day's name back, because that is what
 *   [com.starstacker.session.SessionNaming.labelFor] does with a blank;
 * - `Not now` can mean *do not start*, which a prompt needs to mean something. A naming step with no
 *   way back would make a mistaken Start unrecoverable, and turning an expensive action into a trap
 *   is the exact class of problem §1.17 is about.
 */
@Composable
private fun NameThisSession(
    typed: String,
    onTyped: (String) -> Unit,
    defaultLabel: String,
    onStart: () -> Unit,
    onCancel: () -> Unit,
) {
    Column {
        Eyebrow("Name this session")
        NightTextField(
            value = typed,
            onValueChange = onTyped,
            placeholder = defaultLabel,
        )
        Spacer(Modifier.height(6.dp))
        Mono(
            if (typed.isBlank()) {
                "Left blank it will be called $defaultLabel"
            } else {
                "The folder and the frames will carry this name"
            },
            color = Night.Txt3,
            size = 10.sp,
        )
        Spacer(Modifier.height(10.dp))
        HotButton(text = "Start session", onClick = onStart)
        Spacer(Modifier.height(8.dp))
        QuietButton(text = "Not now", onClick = onCancel)
    }
}

private fun clockOf(epochMs: Long): String =
    SimpleDateFormat("HH:mm", Locale.US).format(Date(epochMs))

/**
 * T-3.25 — the predicted histogram. **T-3.34 gave it a title.**
 *
 * Read left to right: the hump is the sky, and where it sits is the whole answer. Hard against the
 * left wall means read-noise limited — the sensor's own noise is louder than the sky. Hard against
 * the right means clipped. A little way in, with room to spare, is what "sky-limited" looks like.
 *
 * That is the one picture on the screen that makes "sky-limited" checkable, and it was drawn with
 * no title, no labelled wall and no axis — so it read as decoration, and the sentence under it did
 * all the work. Three things fix that and each answers a specific question a reader has:
 *
 * - **the title says it is a prediction**, not a measurement of frames already taken — there are
 *   none yet, and a histogram normally describes something that exists;
 * - **the wall is labelled `CLIPPED`**, because an unlabelled red line at one edge is a border;
 * - **the axis is named at both ends** — black on the left, full well on the right — which is what
 *   makes "the hump sits a little way in" a statement about the picture rather than a hint.
 */
@Composable
private fun HistogramCard(prediction: PredictedHistogram.Prediction) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Eyebrow("Predicted histogram", modifier = Modifier.weight(1f))
            Mono(
                if (prediction.clipped) "CLIPPED" else "%.1f stops spare".format(
                    prediction.headroomStops,
                ),
                color = if (prediction.clipped) Night.Warn else Night.Txt3,
                size = 9.sp,
            )
        }
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
            // The baseline, so the bars stand on something and the picture reads as a plot.
            drawLine(
                color = Night.LineSoft,
                start = Offset(0f, size.height),
                end = Offset(size.width, size.height),
                strokeWidth = 1.5f,
            )
            // The right wall. Everything past it is clipped and unrecoverable — labelled below,
            // because a red line at the edge of a box is indistinguishable from a border.
            drawLine(
                color = Night.Warn,
                start = Offset(size.width - 1f, 0f),
                end = Offset(size.width - 1f, size.height),
                strokeWidth = 2f,
            )
        }
        Spacer(Modifier.height(3.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Mono("black", color = Night.Dim, size = 8.5.sp)
            Spacer(Modifier.weight(1f))
            Mono("brightness of one pixel →", color = Night.Dim, size = 8.5.sp)
            Spacer(Modifier.weight(1f))
            Mono("full well", color = Night.Warn, size = 8.5.sp)
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
 * T-3.25's veto, rebuilt by **T-3.35** and **T-3.36** into a photographer's control.
 *
 * What it was: an unlabelled slider over ±2 stops in thirds, titled `Exposure`, reading
 * `as solved`. Three separate problems in one control.
 *
 * - **`Exposure` names the wrong thing.** The screen has an exposure — the solved sub, stated
 *   above. This is what compensates it, and the title now says so.
 * - **`as solved` is the app's bookkeeping**, not the user's number. T-3.36: at zero the solved
 *   sub is shown as a *time*, and moving the control shows the change rather than the destination —
 *   `3.2 s → 4.5 s per frame` — because the interesting thing about turning a dial is what it did.
 * - **±2 stops was a fiat.** The histogram sits directly above and shows the consequence, so the
 *   range can be as wide as a camera's is: ±4 stops, marked at whole stops, moving in sixths.
 *
 * The scale is drawn rather than left implicit. A slider with no marks is a slider whose value can
 * only be read from the number beside it, which defeats the point of a dial.
 */
@Composable
private fun ExposureCompensationControl(controller: SetupController) {
    val stops = controller.exposureStops
    val solved = controller.solution?.chosen?.exposureSeconds
    val effective = controller.effectiveSubSeconds

    Row(verticalAlignment = Alignment.CenterVertically) {
        Mono(
            "Exposure compensation",
            color = Night.Txt3,
            size = 10.5.sp,
            modifier = Modifier.weight(1f),
        )
        Mono(
            if (stops == 0.0) "0" else "${ExposureCompensation.format(stops)} stops",
            color = if (stops == 0.0) Night.Txt3 else Night.Txt,
            size = 10.5.sp,
        )
    }
    Spacer(Modifier.height(2.dp))
    // T-3.36 — the number the user is deciding about, and what it becomes.
    Text(
        when {
            solved == null || effective == null -> "—"
            stops == 0.0 -> "${ExposureSolver.formatSeconds(solved)} per frame"
            else -> "%s → %s per frame".format(
                ExposureSolver.formatSeconds(solved),
                ExposureSolver.formatSeconds(effective),
            )
        },
        fontFamily = NumFamily,
        fontSize = 15.sp,
        color = if (stops == 0.0) Night.Txt2 else Night.Txt,
    )
    Slider(
        value = stops.toFloat(),
        onValueChange = { controller.compensate(it.toDouble()) },
        valueRange = -ExposureCompensation.MAX_STOPS.toFloat()..
            ExposureCompensation.MAX_STOPS.toFloat(),
        steps = ExposureCompensation.SLIDER_STEPS,
        colors = SliderDefaults.colors(
            thumbColor = Night.Hot,
            activeTrackColor = Night.Red,
            inactiveTrackColor = Night.LineSoft,
        ),
    )
    StopScale(stops)
    // No note about the sensor's stated ceiling. It is advertised rather than enforced (§1.20), so
    // a line warning about crossing it would be warning about nothing — and the check that matters
    // is `SequenceSession`'s, which measures what the sensor did rather than predicting it.
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

/**
 * The dial's markings: −4 −3 −2 −1 0 +1 +2 +3 +4, evenly spaced under the track.
 *
 * `Row` with equal weights rather than absolute offsets, so the marks stay under the track on any
 * width. The whole stop nearest the current value is brightened — at sixths of a stop the thumb
 * rarely sits exactly on a mark, and the nearest one is what tells you where you are at a glance
 * in the dark.
 */
@Composable
private fun StopScale(stops: Double) {
    val nearest = kotlin.math.round(stops).toInt()
    Row(Modifier.fillMaxWidth()) {
        ExposureCompensation.MARKS.forEachIndexed { index, mark ->
            val text = when {
                mark == 0 -> "0"
                mark > 0 -> "+$mark"
                else -> "−${-mark}"
            }
            Box(
                Modifier.weight(1f),
                contentAlignment = when (index) {
                    0 -> Alignment.CenterStart
                    ExposureCompensation.MARKS.lastIndex -> Alignment.CenterEnd
                    else -> Alignment.Center
                },
            ) {
                Mono(
                    text,
                    color = if (mark == nearest) Night.Txt2 else Night.Dim,
                    size = 9.sp,
                )
            }
        }
    }
}
