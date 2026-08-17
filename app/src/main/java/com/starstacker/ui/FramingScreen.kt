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
        item { PreviewPanel(controller) }

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

        item { Eyebrow("Focus · FR-6.3") }
        item { FocusCard(controller, pointing?.altitudeDeg) }

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
private fun PreviewPanel(controller: FramingController) {
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
        // At ~1 fps an unlabelled preview reads as a frozen app, so the rate is stated.
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
            Metric("Sky", frame?.background?.let { "%.0f".format(it) } ?: "—", unit = "ADU")
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

@Composable
private fun FocusCard(controller: FramingController, altitudeDeg: Double?) {
    val stored = controller.storedFocus
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    when {
                        stored == null -> "No stored focus"
                        stored.fixedFocus -> "Fixed focus"
                        else -> "Stored at %.3f dioptres".format(stored.diopters)
                    },
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Night.Txt,
                )
                Mono(
                    when {
                        stored == null -> "run a sweep once the preview is showing stars"
                        stored.fixedFocus -> "nothing to calibrate, nothing to drift"
                        else -> "HFR %.2f px · %d stars%s".format(
                            stored.hfr,
                            stored.starCount,
                            stored.altitudeDeg?.let { " · at %.0f° elevation".format(it) }.orEmpty(),
                        )
                    },
                    color = Night.Txt3,
                )
            }
            Badge(
                controller.focusStatus.name,
                when (controller.focusStatus) {
                    FocusStatus.LOCKED -> Night.Red
                    FocusStatus.DRIFTING, FocusStatus.LOST -> Night.Warn
                    FocusStatus.UNKNOWN -> Night.Dim
                },
            )
        }

        Spacer(Modifier.height(10.dp))
        ButtonRow {
            Box(Modifier.weight(1f)) {
                QuietButton(
                    text = "Sweep focus",
                    enabled = controller.running && controller.busy == null,
                    onClick = { controller.sweepFocus(altitudeDeg) },
                )
            }
            Box(Modifier.weight(1f)) {
                QuietButton(
                    text = "Verify",
                    enabled = controller.running && controller.busy == null && stored != null,
                    onClick = { controller.verifyFocus(altitudeDeg) },
                )
            }
        }

        controller.sweepProgress?.let {
            Spacer(Modifier.height(8.dp))
            Mono(it, color = Night.Hot, size = 11.sp)
        }

        controller.focusMessage?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, fontSize = 11.sp, color = Night.Txt2, lineHeight = 16.sp)
        }

        if (controller.sweepSamples.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Mono("HFR VS POSITION", color = Night.Dim, size = 8.5.sp)
            Spacer(Modifier.height(4.dp))
            HfrCurve(controller)
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
