package com.starstacker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.starstacker.device.CameraProfile
import com.starstacker.device.CameraQualification
import com.starstacker.device.Check
import com.starstacker.device.DeviceProfile
import com.starstacker.device.DeviceQualification
import com.starstacker.device.Discovery
import com.starstacker.device.Tier
import com.starstacker.device.Verdict
import com.starstacker.ui.theme.Night
import com.starstacker.ui.theme.NumFamily

/**
 * T-1.1 / T-1.2 — the qualification screen.
 *
 * Answers, in this order: does this device work at all, which camera is the astro camera,
 * and what did the probe actually see. FR-3.1 requires the failure case to name the
 * specific missing capability, so the verdict card leads with that rather than a generic
 * "unsupported".
 */
/** Results of the on-device checks that only running the camera can answer (T-1.3, T-1.4). */
data class DiagnosticsState(
    val busy: String? = null,
    val openResults: List<String> = emptyList(),
    val captureLines: List<String> = emptyList(),
    val cameraPermissionGranted: Boolean = false,
)

@Composable
fun ProbeScreen(
    profile: DeviceProfile,
    qualification: DeviceQualification,
    onExport: () -> Unit,
    exportedPath: String?,
    diagnostics: DiagnosticsState,
    onOpenabilityTest: () -> Unit,
    onCaptureRaw: (Long) -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Night.Void)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { Spacer(Modifier.height(20.dp)) }

        item {
            Eyebrow("Capability probe · FR-3.2")
            Text(
                "${profile.manufacturer} ${profile.model}",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = Night.Txt,
            )
            Text(
                "Android ${profile.androidRelease} (API ${profile.sdkInt}) · " +
                    profile.supportedAbis.firstOrNull().orEmpty(),
                fontFamily = NumFamily,
                fontSize = 11.sp,
                color = Night.Txt3,
            )
        }

        item { VerdictCard(qualification) }

        item { Eyebrow("Cameras") }

        items(profile.cameras, key = { it.id }) { cam ->
            val q = qualification.cameras.first { it.cameraId == cam.id }
            CameraCard(cam, q)
        }

        item { Eyebrow("Sensors") }
        item {
            Card {
                qualification.sensors.forEach { CheckRow(it) }
            }
        }

        if (profile.concurrentCameraIdSets.isNotEmpty()) {
            item { Eyebrow("Concurrent camera sets") }
            item {
                Card {
                    profile.concurrentCameraIdSets.forEach {
                        Mono(it.joinToString(" + "), color = Night.Txt2)
                    }
                }
            }
        }

        item { Eyebrow("Camera checks") }
        item { DiagnosticsPanel(diagnostics, onOpenabilityTest, onCaptureRaw) }

        item {
            Column {
                QuietButton(text = "Export device profile (JSON)", onClick = onExport)
                if (exportedPath != null) {
                    Spacer(Modifier.height(8.dp))
                    Mono("Written to $exportedPath", color = Night.Txt3, size = 10.sp)
                }
            }
        }

        item { Spacer(Modifier.height(40.dp)) }
    }
}

@Composable
private fun VerdictCard(q: DeviceQualification) {
    val accent = when (q.bestTier) {
        Tier.UNSUPPORTED -> Night.Warn
        Tier.DEGRADED -> Night.Warn
        else -> Night.Hot
    }
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, accent, RoundedCornerShape(12.dp))
            .background(Color(0x14FF5A2B), RoundedCornerShape(12.dp))
            .padding(14.dp),
    ) {
        Text(
            q.headline,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            color = accent,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Best tier: ${q.bestTier.name} — " + when (q.bestTier) {
                Tier.FULL -> "calibrated"
                Tier.FUNCTIONAL -> "capture, registration, stacking and auto-edit all work; " +
                    "calibration improves results but is never a gate (FR-3.1.1)"
                Tier.DEGRADED -> "reduced frame rate or resolution"
                Tier.UNSUPPORTED -> "see the blocking reason on each camera below"
            },
            fontSize = 11.5.sp,
            color = Night.Txt3,
            lineHeight = 17.sp,
        )
    }
}

@Composable
private fun CameraCard(cam: CameraProfile, q: CameraQualification) {
    var expanded by remember { mutableStateOf(false) }

    Card(onClick = { expanded = !expanded }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    buildString {
                        append("Camera ${cam.id}")
                        append(
                            when (cam.discovery) {
                                Discovery.LISTED -> ""
                                Discovery.PHYSICAL_CHILD -> " · sub-camera"
                                Discovery.HIDDEN -> " · hidden"
                            },
                        )
                        if (cam.isLogical) append(" · logical")
                    },
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Night.Txt,
                )
                Mono(
                    listOfNotNull(
                        cam.facing,
                        cam.focalLengthsMm.firstOrNull()?.let { "%.1fmm".format(it) },
                        cam.aperturesF.firstOrNull()?.let { "f/%.1f".format(it) },
                        cam.maxRawSize?.let { "%.1fMP raw".format(it.megapixels) },
                    ).joinToString(" · "),
                    color = Night.Txt3,
                )
            }
            TierBadge(q.tier)
        }

        Spacer(Modifier.height(8.dp))
        q.checks.forEach { CheckRow(it, showNote = expanded) }

        if (q.blockingReason != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Blocked: ${q.blockingReason}",
                fontSize = 11.5.sp,
                color = Night.Warn,
                lineHeight = 17.sp,
            )
        }

        if (expanded) {
            Spacer(Modifier.height(10.dp))
            Detail("Capabilities", cam.capabilities.joinToString(", "))
            Detail("Pixel array", cam.pixelArray?.toString())
            Detail("Active array", cam.activeArray?.toString())
            Detail("Physical size", cam.physicalSizeMm?.toString())
            Detail("Max RAW", cam.maxRawSize?.toString())
            Detail("Binning factor", cam.binningFactor?.toString())
            Detail("Naive pitch", cam.naivePixelPitchUm?.let { "%.3f um".format(it) })
            Detail("Effective pitch", cam.effectivePixelPitchUm?.let { "%.3f um".format(it) })
            Detail("Binning ratio", cam.rawBinningRatio?.let { "%.2fx".format(it) })
            Detail("Sensor bins internally", if (cam.sensorBinsInternally) "yes" else "no")
            Detail("Physical children", cam.physicalChildIds.joinToString(", "))
            Detail("AF modes", cam.afAvailableModes.joinToString(", ").ifEmpty { "none" })
            Detail("CFA", cam.cfaArrangement)
            Detail("White level", cam.whiteLevel?.toString())
            Detail("Black level", cam.blackLevelPattern?.joinToString(", "))
            Detail("ISO range", "${cam.isoMin ?: "?"}–${cam.isoMax ?: "?"}")
            Detail(
                "Exposure range",
                "${cam.exposureMinNs ?: "?"}ns – ${cam.exposureMaxSeconds?.let { "%.2fs".format(it) } ?: "?"}",
            )
            Detail("Max frame duration", cam.maxFrameDurationNs?.let { "%.2fs".format(it / 1e9) })
            Detail("Timestamp source", cam.timestampSource)
            Detail("Focus calibration", cam.focusDistanceCalibration)
            Detail("Min focus", cam.minimumFocusDistanceDiopters?.let { "$it dioptres" })
            Detail("Hyperfocal", cam.hyperfocalDistanceDiopters?.let { "$it dioptres" })
            Detail("OIS modes", cam.oisModes.joinToString(", ").ifEmpty { "none" })
            Detail("EIS modes", cam.eisModes.joinToString(", ").ifEmpty { "none" })
            Detail("FOV", cam.focalLengthsMm.mapNotNull { f ->
                cam.horizontalFovDegrees(f)?.let { "%.1f°".format(it) }
            }.joinToString(", "))

            if (cam.mandatoryStreamCombinations.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Mono(
                    "Guaranteed stream combinations (${cam.mandatoryStreamCombinations.size})",
                    color = Night.Dim, size = 9.5.sp,
                )
                cam.mandatoryStreamCombinations.take(40).forEach {
                    Mono(it, color = Night.Txt3, size = 9.5.sp)
                }
            }
        } else {
            Spacer(Modifier.height(6.dp))
            Mono("Tap for the full dump", color = Night.Dim, size = 9.5.sp)
        }
    }
}

@Composable
private fun DiagnosticsPanel(
    state: DiagnosticsState,
    onOpenabilityTest: () -> Unit,
    onCaptureRaw: (Long) -> Unit,
) {
    Card {
        if (!state.cameraPermissionGranted) {
            Text(
                "Camera permission not granted — opening and capture are unavailable, and " +
                    "some lens characteristics read as null.",
                fontSize = 11.5.sp,
                color = Night.Warn,
                lineHeight = 17.sp,
            )
            Spacer(Modifier.height(10.dp))
        }

        QuietButton(
            text = "Test which cameras will open",
            enabled = state.busy == null && state.cameraPermissionGranted,
            onClick = onOpenabilityTest,
        )
        state.openResults.forEach { Mono(it, color = Night.Txt2, size = 10.sp) }

        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) {
                QuietButton(
                    text = "RAW · 100 ms",
                    enabled = state.busy == null && state.cameraPermissionGranted,
                    onClick = { onCaptureRaw(100_000_000L) },
                )
            }
            Box(Modifier.weight(1f)) {
                HotButton(
                    text = "RAW · 10 s",
                    enabled = state.busy == null && state.cameraPermissionGranted,
                    onClick = { onCaptureRaw(10_000_000_000L) },
                )
            }
        }
        state.captureLines.forEach { Mono(it, color = Night.Txt2, size = 10.sp) }

        if (state.busy != null) {
            Spacer(Modifier.height(8.dp))
            Mono("${state.busy}…", color = Night.Hot, size = 11.sp)
        }
    }
}

@Composable
private fun TierBadge(tier: Tier) {
    val color = when (tier) {
        Tier.FULL, Tier.FUNCTIONAL -> Night.Red
        Tier.DEGRADED -> Night.Warn
        Tier.UNSUPPORTED -> Night.Warn
    }
    Text(
        tier.name,
        fontFamily = NumFamily,
        fontSize = 8.5.sp,
        letterSpacing = 1.4.sp,
        color = color,
        modifier = Modifier
            .border(1.dp, color, RoundedCornerShape(5.dp))
            .padding(horizontal = 7.dp, vertical = 4.dp),
    )
}

@Composable
private fun CheckRow(check: Check, showNote: Boolean = true) {
    val color = when (check.verdict) {
        Verdict.PASS -> Night.Red
        Verdict.WARN -> Night.Warn
        Verdict.FAIL -> Night.Warn
    }
    Column(Modifier.padding(vertical = 3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(
                Modifier
                    .size(8.dp)
                    .background(
                        if (check.verdict == Verdict.PASS) color else Color.Transparent,
                        CircleShape,
                    )
                    .border(1.dp, color, CircleShape),
            )
            Spacer(Modifier.size(9.dp))
            Text(
                check.label,
                fontSize = 12.sp,
                color = Night.Txt2,
                modifier = Modifier.weight(1f),
            )
            Mono(check.value, color = if (check.verdict == Verdict.FAIL) Night.Warn else Night.Txt)
        }
        if (showNote && check.note.isNotEmpty()) {
            Text(
                check.note,
                fontSize = 10.5.sp,
                color = Night.Txt3,
                lineHeight = 15.sp,
                modifier = Modifier.padding(start = 17.dp, top = 2.dp),
            )
        }
    }
}

@Composable
private fun Detail(label: String, value: String?) {
    if (value.isNullOrEmpty()) return
    Row(Modifier.padding(vertical = 2.dp)) {
        Mono(label, color = Night.Dim, size = 10.sp, modifier = Modifier.weight(1f))
        Mono(value, color = Night.Txt2, size = 10.sp)
    }
}

// ---- shared bits (promoted to a component file in T-0.2) ---------------------------

@Composable
private fun Card(
    onClick: (() -> Unit)? = null,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, Night.LineSoft, RoundedCornerShape(12.dp))
            .background(Night.Surface2, RoundedCornerShape(12.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(13.dp),
    ) { content() }
}

@Composable
private fun Eyebrow(text: String) {
    Text(
        text.uppercase(),
        fontFamily = NumFamily,
        fontSize = 9.5.sp,
        letterSpacing = 2.2.sp,
        color = Night.Dim,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}

@Composable
private fun Mono(
    text: String,
    color: Color,
    size: androidx.compose.ui.unit.TextUnit = 10.5.sp,
    modifier: Modifier = Modifier,
) {
    Text(text, fontFamily = NumFamily, fontSize = size, color = color, modifier = modifier)
}

@Composable
private fun HotButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    val tint = if (enabled) Night.Hot else Night.Dim
    Text(
        text,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        color = tint,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, tint, RoundedCornerShape(11.dp))
            .background(
                if (enabled) Color(0x22FF5A2B) else Color.Transparent,
                RoundedCornerShape(11.dp),
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 14.dp),
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
    )
}

@Composable
private fun QuietButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    val tint = if (enabled) Night.Txt2 else Night.Dim
    Text(
        text,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        color = tint,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Night.Line, RoundedCornerShape(11.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 12.dp),
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
    )
}
