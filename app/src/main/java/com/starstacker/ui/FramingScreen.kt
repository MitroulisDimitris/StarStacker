package com.starstacker.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.starstacker.device.CameraOption
import com.starstacker.focus.FocusStatus
import com.starstacker.pointing.Astro
import com.starstacker.pointing.CompassAccuracy
import com.starstacker.pointing.PointingFix
import com.starstacker.ui.theme.Night

/**
 * Phase 1B — the part of shooting mode that happens before Start: point the phone, see stars,
 * lock focus.
 *
 * Read in the dark from a metre away, so numbers are large and monospaced and the palette never
 * leaves the red end. One full-intensity control only, and it is the one that starts and stops
 * the loop.
 */

/**
 * T-3.32 — what shooting without stored focus costs, in one sentence, authored **once**.
 *
 * It appears twice: under the focus state on the preview, and under `Continue to session setup`.
 * A constant rather than two string literals because of the trap T-3.24 recorded — the screen gave
 * two answers to one question, in two wordings, a scroll apart — and two copies of a sentence are
 * one edit away from being exactly that again.
 *
 * The number behind it: with no stored focus the capture request passes 0.0 dioptres, which this
 * HAL answers with the hyperfocal position (§1.11). Stars are then soft but present, so a whole
 * session can be shot slightly out of focus with nothing appearing to be wrong until the morning.
 */
private const val NO_FOCUS_CONSEQUENCE =
    "the session will shoot at hyperfocal — soft, not ruined"
@Composable
fun FramingScreen(
    options: List<CameraOption>,
    selectedCameraId: String?,
    controller: FramingController,
    pointing: PointingFix?,
    pointingAvailable: Boolean,
    locationGranted: Boolean,
    onSelectCamera: (String) -> Unit,
    onRequestLocation: () -> Unit,
    onBack: () -> Unit,
    onContinue: () -> Unit,
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
                Mono("FRAMING & FOCUS", color = Night.Dim, size = 9.5.sp)
            }
        }

        item { Eyebrow("Camera") }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                options.forEach { option ->
                    CameraRow(
                        option = option,
                        selected = option.id == selectedCameraId,
                        onClick = { onSelectCamera(option.id) },
                    )
                }
            }
        }

        item { Eyebrow("Night preview") }
        item { PreviewPanel(controller, pointing?.altitudeDeg) }

        item {
            HotButton(
                text = if (controller.running) "Stop preview" else "Start night preview",
                enabled = selectedCameraId != null && controller.busy == null,
                onClick = {
                    controller.touch()
                    if (controller.running) controller.stop() else controller.start()
                },
            )
        }

        item {
            ButtonRow {
                Box(Modifier.weight(1f)) {
                    QuietButton(
                        text = "1 s",
                        selected = !controller.boosted,
                        onClick = { controller.boost(false) },
                    )
                }
                Box(Modifier.weight(1f)) {
                    QuietButton(
                        text = "Boost · 4 s",
                        selected = controller.boosted,
                        onClick = { controller.boost(true) },
                    )
                }
            }
        }

        if (controller.stoppedForIdle) {
            item {
                Banner(
                    "Preview stopped after ${controller.idleTimeoutSeconds / 60} minutes idle. " +
                        "Framing at high ISO warms the sensor, and that heat comes out of the " +
                        "session you are about to shoot.",
                )
            }
        }

        controller.error?.let { message ->
            item { Banner("Preview failed — $message") }
        }

        item { Eyebrow("Frame") }
        item { MetricsCard(controller) }

        item { Eyebrow("Pointing · FR-5.1") }
        item {
            PointingCard(
                fix = pointing,
                available = pointingAvailable,
                locationGranted = locationGranted,
                onRequestLocation = onRequestLocation,
            )
        }

        // T-3.31: only when there is a sweep to look at. This was a permanent card holding the
        // by-hand controls, which have moved onto the preview; what is left is the measurement,
        // and a measurement nobody has taken needs no card.
        if (controller.sweepSamples.isNotEmpty()) {
            item { Eyebrow("Focus curve · FR-6.3") }
            item { FocusCurveCard(controller) }
        }

        // Quiet, not hot: the full-intensity control on this screen is the preview toggle, and a
        // screen with two of them has neither (T-0.2).
        item {
            Column {
                QuietButton(
                    text = "Continue to session setup →",
                    enabled = selectedCameraId != null,
                    onClick = onContinue,
                )
                // T-3.32 — the app walks to setup perfectly happily with no focus stored and used
                // to say nothing about it there. Deliberately **not a gate**: FR-3.1.1's
                // Functional tier shoots without calibration, and a beginner stopped at 1 a.m. by
                // a sweep that will not converge under thin cloud has nowhere to go. D-25: the
                // consequence stays, the justification does not.
                if (controller.storedFocus == null) {
                    Spacer(Modifier.height(6.dp))
                    Mono(
                        "No focus stored — $NO_FOCUS_CONSEQUENCE",
                        color = Night.Warn,
                        size = 10.5.sp,
                    )
                }
            }
        }

        controller.streamDetail?.let { detail ->
            item { Eyebrow("Streams · T-2.1") }
            item { Card { Mono(detail, color = Night.Txt3, size = 10.sp) } }
        }

        item { Spacer(Modifier.height(40.dp)) }
    }
}

@Composable
private fun CameraRow(option: CameraOption, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .border(
                1.dp,
                if (selected) Night.Red else Night.LineSoft,
                RoundedCornerShape(11.dp),
            )
            .background(
                if (selected) Night.Surface2 else Color.Transparent,
                RoundedCornerShape(11.dp),
            )
            .clickable(enabled = option.selectable, onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Mono(
            option.headline,
            color = if (option.selectable) Night.Txt else Night.Dim,
            size = 14.sp,
            modifier = Modifier.padding(end = 12.dp),
        )
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    option.name,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (option.selectable) Night.Txt else Night.Dim,
                )
                if (option.recommended) {
                    Spacer(Modifier.padding(horizontal = 4.dp))
                    Badge("BEST", Night.Red)
                }
            }
            Text(
                option.note,
                fontSize = 10.5.sp,
                color = Night.Txt3,
                lineHeight = 15.sp,
            )
            option.warning?.let {
                Text(it, fontSize = 10.sp, color = Night.Warn, lineHeight = 14.sp)
            }
        }
    }
}

@Composable
private fun PreviewPanel(controller: FramingController, altitudeDeg: Double?) {
    Column {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
                .border(1.dp, Night.LineSoft, RoundedCornerShape(12.dp))
                .background(Night.Surface, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            val image = controller.preview
            if (image != null) {
                Image(
                    bitmap = image,
                    contentDescription = "Night framing preview",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(1.dp),
                )
            } else {
                Mono(
                    controller.busy ?: "no frames yet",
                    color = Night.Dim,
                    size = 11.sp,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        // At ~1 fps an unlabelled preview reads as a frozen app, so the rate is stated. Directly
        // under the image, because it describes the image and nothing else.
        Mono(
            "%.1f s per frame · ISO %d · frame %d%s".format(
                controller.refreshSeconds,
                controller.iso,
                controller.frameCount,
                if (controller.frame?.settled == false) " · settling" else "",
            ),
            color = Night.Txt3,
            size = 10.sp,
        )
        Spacer(Modifier.height(10.dp))
        FocusBar(controller, altitudeDeg)
        // Whatever focus last said — a sweep's verdict, a verification, a refusal. One line, here,
        // where the state it comments on is (T-3.24's rule).
        (controller.sweepProgress ?: controller.focusMessage)?.let { message ->
            Spacer(Modifier.height(6.dp))
            Text(
                message,
                fontSize = 10.5.sp,
                color = if (controller.sweepProgress != null) Night.Hot else Night.Txt2,
                lineHeight = 15.sp,
            )
        }
        Spacer(Modifier.height(8.dp))
        ManualFocusDisclosure(controller, altitudeDeg)
    }
}

/**
 * T-3.31 — focus by hand, and stored-focus verification, as a disclosure under `Find focus`.
 *
 * **Both were permanently open, several scrolls below the preview.** That is wrong in both
 * directions at once: the controls are visible when nobody wants them, and out of sight at the one
 * moment they are wanted — a sweep that has just failed. So they open on exactly two events: the
 * sweep failing ([FramingController.sweepFocus] sets it), and being asked for.
 *
 * `Verify stored focus` joins them for the same reason. It is a thing you do occasionally, not a
 * thing you read every time, and it needs a stored focus to act on — so as a standing control it
 * was disabled most of the time it was on screen.
 *
 * *Unchanged:* the stepping itself — ±1 motor step of 0.0374 dioptres against the live HFR (§1.7).
 * The complaint answered here is the placement. If the control is wrong too, that is a separate
 * task and it wants a number: which step, and judged against what.
 */
@Composable
private fun ManualFocusDisclosure(controller: FramingController, altitudeDeg: Double?) {
    val open = controller.manualFocusOpen
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { controller.manualFocusOpen = !open }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Mono(
                if (controller.sweepFailed) "Focus by hand — the sweep found nothing"
                else "Focus by hand",
                color = if (controller.sweepFailed) Night.Warn else Night.Txt3,
                size = 10.5.sp,
                modifier = Modifier.weight(1f),
            )
            Mono(if (open) "▴" else "▾", color = Night.Txt3, size = 10.5.sp)
        }
        if (!open) return@Column

        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Mono(
                controller.activeFocus?.let { "%.3f dioptres".format(it) } ?: "lens at default",
                color = if (controller.manualFocus != null) Night.Txt else Night.Txt3,
                size = 11.sp,
            )
            Spacer(Modifier.weight(1f))
            Mono(
                controller.frame?.hfr?.let { "HFR %.2f".format(it) } ?: "HFR —",
                color = Night.Txt2,
                size = 11.sp,
            )
        }
        Spacer(Modifier.height(6.dp))
        ButtonRow {
            Box(Modifier.weight(1f)) {
                QuietButton(
                    text = "◀ Further",
                    enabled = controller.running && controller.busy == null,
                    onClick = { controller.nudgeFocus(-1) },
                )
            }
            Box(Modifier.weight(1f)) {
                QuietButton(
                    text = "Nearer ▶",
                    enabled = controller.running && controller.busy == null,
                    onClick = { controller.nudgeFocus(+1) },
                )
            }
        }
        if (controller.manualFocus != null) {
            Spacer(Modifier.height(6.dp))
            QuietButton(
                text = "Use this focus for the session",
                enabled = controller.busy == null,
                onClick = { controller.storeManualFocus(altitudeDeg) },
            )
        }
        Spacer(Modifier.height(6.dp))
        QuietButton(
            text = "Verify stored focus",
            enabled = controller.running && controller.busy == null &&
                controller.storedFocus != null,
            onClick = { controller.verifyFocus(altitudeDeg) },
        )
    }
}

@Composable
private fun MetricsCard(controller: FramingController) {
    val frame = controller.frame
    Card {
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Metric("Stars", frame?.starCount?.toString() ?: "—")
            Metric(
                "HFR",
                frame?.hfr?.let { "%.2f".format(it) } ?: "—",
                unit = "px",
                warn = controller.focusStatus == FocusStatus.DRIFTING ||
                    controller.focusStatus == FocusStatus.LOST,
            )
            Metric(
                "Ecc",
                frame?.eccentricity?.let { "%.2f".format(it) } ?: "—",
                warn = (frame?.eccentricity ?: 0.0) > 0.6,
            )
            Metric(
                "Sky",
                frame?.background?.let { "%.0f".format(it) } ?: "—",
                unit = "ADU",
                warn = frame?.saturated == true,
            )
        }
        // "Clipped" and "no stars" look identical in a star count, and they call for opposite
        // actions — turn the exposure down, or wait for the cloud to pass.
        if (frame?.saturated == true) {
            Spacer(Modifier.height(8.dp))
            Banner(
                "Frame is clipped — the sensor is saturated, so nothing can be measured. " +
                    "Drop the ISO or the framing exposure, or point away from the light.",
                color = Night.Warn,
            )
        }
        if (frame != null) {
            Spacer(Modifier.height(8.dp))
            Mono(
                "noise %.2f ADU · analysis %d ms · ISO %s · %s".format(
                    frame.noise,
                    frame.analysisMs,
                    frame.appliedIso?.toString() ?: "?",
                    frame.appliedExposureNs?.let { "%.2f s".format(it / 1e9) } ?: "? s",
                ),
                color = Night.Txt3,
                size = 10.sp,
            )
        }
    }
}

@Composable
private fun PointingCard(
    fix: PointingFix?,
    available: Boolean,
    locationGranted: Boolean,
    onRequestLocation: () -> Unit,
) {
    Card {
        if (!available) {
            Text(
                "This device has no accelerometer or magnetometer, so pointing is unavailable. " +
                    "The trailing limit falls back to the pole-agnostic form.",
                fontSize = 11.5.sp,
                color = Night.Warn,
                lineHeight = 17.sp,
            )
            return@Card
        }
        if (fix == null) {
            Mono("waiting for the compass…", color = Night.Dim, size = 11.sp)
            return@Card
        }

        val azimuth = fix.azimuthTrueDeg ?: fix.azimuthMagneticDeg
        KeyValue(
            "Altitude / azimuth",
            "%.1f° / %.0f° %s".format(fix.altitudeDeg, azimuth, Astro.compassPoint(azimuth)),
        )
        KeyValue(
            "Declination at centre",
            fix.declinationDeg?.let { Astro.formatDeclination(it) } ?: "needs location",
            warn = fix.declinationDeg == null,
        )
        KeyValue(
            "Field rotation",
            fix.fieldRotationArcsecPerSec?.let { "%.1f″/s".format(kotlin.math.abs(it)) }
                ?: "needs location",
            warn = fix.nearZenith,
        )
        KeyValue(
            "Right ascension",
            fix.rightAscensionHours?.let { Astro.formatHours(it) } ?: "needs location",
        )
        KeyValue(
            "Position",
            if (fix.hasLocation) {
                "%.3f, %.3f".format(fix.latitudeDeg, fix.longitudeDeg)
            } else {
                "unknown"
            },
            warn = !fix.hasLocation,
        )

        if (fix.nearZenith) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Near the zenith the field-rotation rate diverges — that is the sky, not a bug. " +
                    "An alt-az tripod cannot track through overhead; frame lower if you can.",
                fontSize = 10.5.sp,
                color = Night.Warn,
                lineHeight = 15.sp,
            )
        }

        if (fix.accuracy == CompassAccuracy.LOW || fix.accuracy == CompassAccuracy.UNRELIABLE) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Compass accuracy is ${fix.accuracy.name.lowercase()} — wave the phone in a " +
                    "figure of eight to recalibrate. Azimuth feeds the declination, which sets " +
                    "the trailing limit.",
                fontSize = 10.5.sp,
                color = Night.Warn,
                lineHeight = 15.sp,
            )
        }

        if (!locationGranted) {
            Spacer(Modifier.height(10.dp))
            QuietButton(text = "Allow location for declination", onClick = onRequestLocation)
        }
    }
}

/**
 * What is left of the focus card after T-3.31: **the measurement**.
 *
 * The by-hand controls and `Verify stored focus` moved onto the preview, where the thing they are
 * judged against is. A sweep's curve is a different kind of object — it is evidence, read once
 * after a sweep and then left alone — so it stays a card, and the card only exists when there is a
 * sweep behind it.
 *
 * FR-4.1.4 wants the curve visible, because a minimum you can see is the difference between "the
 * app says it focused" and "focus is bracketed".
 */
@Composable
private fun FocusCurveCard(controller: FramingController) {
    Card {
        Mono("HFR VS POSITION", color = Night.Dim, size = 8.5.sp)
        Spacer(Modifier.height(4.dp))
        HfrCurve(controller)
        controller.storedFocus?.takeIf { !it.fixedFocus }?.let { stored ->
            Spacer(Modifier.height(8.dp))
            Mono(
                "stored: %.3f dioptres · HFR %.2f px".format(stored.diopters, stored.hfr),
                color = Night.Txt2,
                size = 10.sp,
            )
        }
    }
}

/**
 * The sweep as a bar per position — FR-4.1.4 wants the curve visible, and a minimum you can see
 * is the difference between "the app says it focused" and "focus is bracketed".
 */
@Composable
private fun HfrCurve(controller: FramingController) {
    val samples = controller.sweepSamples
    val measured = samples.mapNotNull { it.hfr }
    val worst = measured.maxOrNull() ?: return
    val best = measured.minOrNull() ?: return

    samples.forEach { sample ->
        val hfr = sample.hfr
        val fraction = if (hfr == null || worst <= 0.0) 0f else (hfr / worst).toFloat()
        val isBest = hfr != null && hfr <= best + 1e-9
        Row(
            Modifier.fillMaxWidth().padding(vertical = 1.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Mono("%.3f".format(sample.diopters), color = Night.Txt3, size = 9.5.sp)
            Spacer(Modifier.padding(horizontal = 3.dp))
            Box(
                Modifier
                    .weight(1f)
                    .height(9.dp)
                    .background(Night.Ghost, RoundedCornerShape(2.dp)),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction.coerceIn(0.02f, 1f))
                        .fillMaxHeight()
                        .background(if (isBest) Night.Hot else Night.Mid, RoundedCornerShape(2.dp)),
                )
            }
            Spacer(Modifier.padding(horizontal = 3.dp))
            Mono(
                hfr?.let { "%.2f".format(it) } ?: "—",
                color = if (isBest) Night.Txt else Night.Txt3,
                size = 9.5.sp,
            )
        }
    }
}

/**
 * T-3.24 — focus, stated and started where the user is already looking.
 *
 * **Both halves were wrong before.** `Find focus` lived in a card far below the preview, so the
 * one action that needs the preview to judge it was the one furthest from it. And the stored state
 * was a word in a badge — `UNKNOWN` — which reads much like `LOCKED` at 3 a.m. and does not say
 * what to do about it.
 *
 * The distinction matters more than it looks: with no stored focus the capture request passes 0.0
 * dioptres, which this HAL answers with the hyperfocal position (§1.11). That is **soft but not
 * ruined**, so a whole session can be shot slightly out of focus without anything appearing to be
 * wrong until the morning.
 */
@Composable
private fun FocusBar(controller: FramingController, altitudeDeg: Double?) {
    val stored = controller.storedFocus
    val ready = stored != null
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                when {
                    stored == null -> "Focus not set"
                    stored.fixedFocus -> "Fixed focus"
                    controller.focusStatus == FocusStatus.DRIFTING -> "Focus drifting"
                    controller.focusStatus == FocusStatus.LOST -> "Focus lost"
                    else -> "Focus set"
                },
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = when {
                    !ready -> Night.Warn
                    controller.focusStatus == FocusStatus.DRIFTING ||
                        controller.focusStatus == FocusStatus.LOST -> Night.Warn
                    else -> Night.Txt
                },
            )
            Mono(
                when {
                    // The same sentence as the line under Continue, from the same constant.
                    stored == null -> NO_FOCUS_CONSEQUENCE
                    stored.fixedFocus -> "nothing to calibrate on this lens"
                    else -> "%.3f dioptres · HFR %.2f px".format(stored.diopters, stored.hfr)
                },
                color = Night.Txt3,
                size = 10.sp,
            )
        }
        Box(Modifier.width(132.dp)) {
            QuietButton(
                text = when {
                    controller.sweepProgress != null -> "Finding…"
                    ready -> "Refocus"
                    else -> "Find focus"
                },
                enabled = controller.running && controller.sweepProgress == null,
                selected = !ready,
                onClick = { controller.sweepFocus(altitudeDeg) },
            )
        }
    }
}
