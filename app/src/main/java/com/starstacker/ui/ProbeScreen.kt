package com.starstacker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
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
import com.starstacker.session.SessionRecovery
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
    val captureLines: List<String> = emptyList(),
    val cameraPermissionGranted: Boolean = false,
)

@Composable
fun ProbeScreen(
    profile: DeviceProfile,
    qualification: DeviceQualification,
    diagnostics: DiagnosticsState,
    onCaptureRaw: (Long) -> Unit,
    onOpenFraming: () -> Unit,
    /** An interrupted session found on disk at launch (T-3.13), or null. */
    resumable: SessionRecovery.Resumable? = null,
    onResumeSession: () -> Unit = {},
    onDiscardResumable: () -> Unit = {},
    /** T-3.18: the probe is a diagnostic now, reached from Settings, and this goes back there. */
    onOpenSettings: () -> Unit = {},
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

        // FR-6.4 / T-3.13. Offered on the landing screen because the situation it exists for is
        // finding the phone in the morning after something killed the session at 03:40 — at
        // which point walking back through framing and setup to reach a resume would mean
        // re-deriving decisions that were already made correctly under a sky that has now set.
        if (resumable != null) {
            item { Eyebrow("Unfinished session") }
            item {
                Card {
                    Mono(resumable.folderName, color = Night.Txt, size = 12.sp)
                    Spacer(Modifier.height(4.dp))
                    Mono(resumable.describe(), color = Night.Txt3, size = 10.5.sp)
                    Spacer(Modifier.height(10.dp))
                    QuietButton(
                        text = "Resume — ${resumable.lightsRemaining} frames left",
                        onClick = onResumeSession,
                    )
                    Spacer(Modifier.height(6.dp))
                    QuietButton(text = "Leave it", onClick = onDiscardResumable)
                    Spacer(Modifier.height(8.dp))
                    Mono(
                        "Leaving it keeps every frame on disk — it only stops the offer.",
                        color = Night.Txt3,
                        size = 10.sp,
                    )
                }
            }
        }

        item {
            HotButton(
                text = "Framing & focus",
                enabled = diagnostics.cameraPermissionGranted && qualification.bestTier != Tier.UNSUPPORTED,
                onClick = onOpenFraming,
            )
        }

        item { Eyebrow("Camera checks") }
        item { DiagnosticsPanel(diagnostics, onCaptureRaw) }

        item { QuietButton(text = "Back", onClick = onOpenSettings) }

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

        // T-3.23: the openability test is gone. It answered OI-18 in §1 — all five cameras open,
        // including the three the system does not publish — and a question that has been answered
        // does not need a permanent button on a screen someone reads in the dark. It survives as
        // `runOpenabilityTest`, which the `--ez autodiag true` path still runs and logs.
        ButtonRow {
            Box(Modifier.weight(1f)) {
                QuietButton(
                    text = "RAW · 100 ms",
                    enabled = state.busy == null && state.cameraPermissionGranted,
                    onClick = { onCaptureRaw(100_000_000L) },
                )
            }
            Box(Modifier.weight(1f)) {
                QuietButton(
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
    Badge(
        text = tier.name,
        color = when (tier) {
            Tier.FULL, Tier.FUNCTIONAL -> Night.Red
            Tier.DEGRADED, Tier.UNSUPPORTED -> Night.Warn
        },
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

// Card, Eyebrow, Mono, HotButton and QuietButton now live in Components.kt (T-0.2).
