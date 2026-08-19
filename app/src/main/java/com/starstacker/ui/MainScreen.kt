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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.starstacker.session.SessionSummary
import com.starstacker.ui.theme.Night
import com.starstacker.ui.theme.NumFamily

/**
 * T-3.18 — the front door, built to the prototype at last.
 *
 * **What it replaces.** The capability probe sat here from Phase 1A, when the only question was
 * whether the device worked at all. It answered that and was never moved, so the app's first
 * screen was a diagnostic: device model, qualification verdict, per-camera capability tables,
 * sensor lists. §1.15 has the full account. The probe is now behind Settings, where a diagnostic
 * belongs.
 *
 * The prototype's shape, and the reasons it has that shape:
 *
 * - **One full-intensity element.** `Start a session` is the only thing at full brightness. The
 *   palette has no hue axis to work with — dark adaptation forbids it — so state is carried by
 *   brightness and weight, and rationing the brightest value to one element per screen is what
 *   makes it mean anything.
 * - **Anything wrong sits *below* Start**, never above it, so a warning can never read as a gate.
 * - **Sessions are the second thing**, because the two questions someone opens this app with are
 *   "what do I do now" and "what did I shoot".
 *
 * Per **D-25** there is no explanatory prose here. The strip states free space, device temperature
 * and moon phase because each changes what you do tonight; none of them is annotated.
 */
@Composable
fun MainScreen(
    /** One line under Start: the camera and whether focus is ready. */
    readiness: String,
    /** Non-null when something is worth saying. Rendered under Start, never above it. */
    warning: MainWarning?,
    sessions: List<SessionSummary>,
    totalSessions: Int,
    freeBytes: Long,
    deviceTempC: Double?,
    moonPercent: Int,
    canStart: Boolean,
    onStart: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSessionFolder: () -> Unit,
    onAllSessions: () -> Unit,
    onDismissWarning: () -> Unit,
    /** T-3.13's resume offer, which outranks everything else on the screen when it exists. */
    resumable: String? = null,
    onResume: () -> Unit = {},
    onDiscardResumable: () -> Unit = {},
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Night.Void)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { TopBar(onOpenSettings) }

        if (resumable != null) {
            item { ResumeCard(resumable, onResume, onDiscardResumable) }
        }

        item {
            Column {
                Eyebrow("Tonight")
                HotButton(text = "Start a session", enabled = canStart, onClick = onStart)
                Spacer(Modifier.height(8.dp))
                Mono(readiness, color = Night.Txt3, size = 10.5.sp)
            }
        }

        if (warning != null) {
            item { WarningBanner(warning, onDismissWarning) }
        }

        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { Eyebrow("Sessions") }
                FolderButton(onOpenSessionFolder)
            }
        }

        if (sessions.isEmpty()) {
            item { Card { Mono("Nothing shot yet.", color = Night.Txt3, size = 11.sp) } }
        } else {
            items(sessions, key = { it.folderName }) { SessionRow(it) }
        }

        if (totalSessions > 0) {
            item {
                QuietButton(text = "All sessions · $totalSessions", onClick = onAllSessions)
            }
        }

        item { ConditionsStrip(freeBytes, deviceTempC, moonPercent) }
        item { Spacer(Modifier.height(28.dp)) }
    }
}

/** What the main screen is allowed to complain about. */
data class MainWarning(val title: String, val detail: String, val action: String?)

/**
 * T-3.19 — the status bar, with settings at the top right where the prototype puts it.
 *
 * The gear is the only way in, which is the point: everything that is not tonight's session lives
 * behind it.
 */
@Composable
private fun TopBar(onOpenSettings: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 14.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "ASTRO CAPTURE",
            fontFamily = NumFamily,
            fontSize = 10.sp,
            letterSpacing = 3.sp,
            color = Night.Dim,
            modifier = Modifier.weight(1f),
        )
        Box(
            Modifier
                .size(38.dp)
                .border(1.dp, Night.Line, RoundedCornerShape(10.dp))
                .clickable(onClick = onOpenSettings),
            contentAlignment = Alignment.Center,
        ) {
            // A glyph rather than a vector asset: the icon set is not a dependency this app has,
            // and one character in the app's own mono face matches everything around it.
            Text("⚙", fontSize = 16.sp, color = Night.Txt2)
        }
    }
}

@Composable
private fun ResumeCard(describe: String, onResume: () -> Unit, onDiscard: () -> Unit) {
    Card {
        Eyebrow("Unfinished session")
        Mono(describe, color = Night.Txt2, size = 11.sp)
        Spacer(Modifier.height(10.dp))
        ButtonRow {
            Box(Modifier.weight(1f)) { QuietButton(text = "Resume", onClick = onResume) }
            Box(Modifier.weight(1f)) { QuietButton(text = "Leave it", onClick = onDiscard) }
        }
    }
}

/**
 * Below Start, always. FR-4.0.4.2's shape: name the thing, the consequence, and the cost of
 * fixing it — and give it a dismiss, so it is information rather than an obstacle.
 */
@Composable
private fun WarningBanner(warning: MainWarning, onDismiss: () -> Unit) {
    Card {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(warning.title, fontSize = 13.sp, color = Night.Warn)
                Spacer(Modifier.height(4.dp))
                Mono(warning.detail, color = Night.Txt3, size = 10.5.sp)
            }
            Text(
                "×",
                fontSize = 18.sp,
                color = Night.Txt3,
                modifier = Modifier
                    .padding(start = 10.dp)
                    .clickable(onClick = onDismiss),
            )
        }
    }
}

@Composable
private fun SessionRow(session: SessionSummary) {
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // The thumbnail slot the prototype fills from a stacked master. There is no stacking
            // until Phase 3, so it says so rather than showing an empty square that reads as a
            // failed image load.
            Box(
                Modifier
                    .size(44.dp)
                    .border(1.dp, Night.LineSoft, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "NO\nSTACK",
                    fontFamily = NumFamily,
                    fontSize = 6.5.sp,
                    lineHeight = 8.sp,
                    color = Night.Dim,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.size(11.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    session.label,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = Night.Txt,
                )
                Spacer(Modifier.height(2.dp))
                Mono(session.describe(), color = Night.Txt3, size = 10.sp)
            }
            Badge(session.badge, if (session.needsAttention) Night.Warn else Night.Txt3)
        }
    }
}

/**
 * The three numbers that change what you do tonight: whether there is room, whether the phone is
 * already warm, and whether the moon has taken the sky.
 */
@Composable
private fun ConditionsStrip(freeBytes: Long, deviceTempC: Double?, moonPercent: Int) {
    Card {
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Metric("Free", formatFreeSpace(freeBytes))
            Metric("Device", deviceTempC?.let { "%.0f".format(it) } ?: "—", unit = "°C")
            Metric("Moon", "$moonPercent", unit = "%", warn = moonPercent >= 60)
        }
    }
}

/**
 * Free space, which is a different question from a session's size and wants a different precision —
 * 116 GB of headroom is a coarse fact, 3.6 GB of frames is not. [SessionSummary.formatBytes] is the
 * other one; the names say which is which so a later edit does not merge them by accident.
 */
private fun formatFreeSpace(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.0f GB".format(bytes.toDouble() / (1L shl 30))
    bytes >= 1L shl 20 -> "%.0f MB".format(bytes.toDouble() / (1L shl 20))
    else -> "$bytes B"
}
