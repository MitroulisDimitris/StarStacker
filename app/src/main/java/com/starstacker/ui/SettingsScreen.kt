package com.starstacker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.starstacker.ui.theme.Night
import com.starstacker.ui.theme.NumFamily

/**
 * T-0.9 — the settings shell, and the home for everything that is *about* the app rather than
 * about tonight's session.
 *
 * It exists partly as tidying with a deadline. The session-root card arrived on the landing screen
 * with T-0.5 and the field-log card with T-0.6, because each needed somewhere to live and there
 * was nowhere. Two more of those and the capability probe becomes a junk drawer, which is how a
 * screen that is read in the dark stops being readable.
 *
 * T-0.4's permission flow lives here too rather than in a modal at first launch. A permission
 * prompt fired at install is answered before the user knows what the app does; the same question
 * asked where the consequence is written next to it can actually be answered.
 */
@Composable
fun SettingsScreen(
    sessionRoot: String,
    onPickSessionRoot: () -> Unit,
    grantedPermissions: Set<String>,
    onRequestPermission: (String) -> Unit,
    onOpenSystemSettings: () -> Unit,
    logTail: List<String>,
    logSizeBytes: Long,
    onRefreshLog: () -> Unit,
    onShareLog: () -> Unit,
    onExportProfile: () -> Unit,
    exportedPath: String?,
    onBack: () -> Unit,
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
            Text(
                "Settings",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = Night.Txt,
            )
        }

        item { Eyebrow("Permissions · FR-3.1") }
        item {
            Card {
                Mono(Permissions.summary(grantedPermissions), color = Night.Txt2, size = 11.sp)
            }
        }
        items@ for (need in Permissions.all) {
            item(key = need.id) {
                PermissionCard(
                    need = need,
                    granted = need.installTime || need.id in grantedPermissions,
                    onRequest = { onRequestPermission(need.id) },
                    onOpenSystemSettings = onOpenSystemSettings,
                )
            }
        }

        item { Eyebrow("Session folder · FR-9.1") }
        item { SessionRootSetting(sessionRoot, onPickSessionRoot) }

        item { Eyebrow("Reading this in the dark") }
        item { NightModeNote() }

        item { Eyebrow("Calibration") }
        item { CalibrationStub() }

        item { Eyebrow("Diagnostics · T-0.6") }
        item {
            FieldLogSetting(logTail, logSizeBytes, onRefreshLog, onShareLog)
        }

        item {
            Column {
                QuietButton(text = "Export device profile (JSON)", onClick = onExportProfile)
                if (exportedPath != null) {
                    Spacer(Modifier.height(8.dp))
                    Mono("Written to $exportedPath", color = Night.Txt3, size = 10.sp)
                }
            }
        }

        item { QuietButton(text = "Back", onClick = onBack) }
        item { Spacer(Modifier.height(40.dp)) }
    }
}

/**
 * One permission, with the consequence of refusing it written underneath.
 *
 * The consequence is shown whether or not it is granted. Someone deciding needs to know what they
 * are giving up; someone who already refused needs to know what they are currently missing, and
 * finding that out should not require revoking anything to see the warning appear.
 */
@Composable
private fun PermissionCard(
    need: PermissionNeed,
    granted: Boolean,
    onRequest: () -> Unit,
    onOpenSystemSettings: () -> Unit,
) {
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(need.label, fontSize = 14.sp, color = Night.Txt)
            }
            Badge(
                when {
                    need.installTime -> "Automatic"
                    granted -> "Granted"
                    need.required -> "Required"
                    else -> "Optional"
                },
                when {
                    granted || need.installTime -> Night.Txt3
                    need.required -> Night.Warn
                    else -> Night.Warn
                },
            )
        }
        Spacer(Modifier.height(6.dp))
        Mono(need.why, color = Night.Txt2, size = 10.5.sp)

        if (!granted && !need.installTime) {
            Spacer(Modifier.height(6.dp))
            Mono("Without it: ${need.ifDenied}", color = Night.Warn, size = 10.5.sp)
            Spacer(Modifier.height(10.dp))
            ButtonRow {
                Box(Modifier.weight(1f)) {
                    QuietButton(text = "Allow", onClick = onRequest)
                }
                // Android stops showing the system prompt after two refusals, and from then on
                // the only route is Settings. A button that silently does nothing would look
                // like a bug in this app rather than a decision already made.
                Box(Modifier.weight(1f)) {
                    QuietButton(text = "App settings", onClick = onOpenSystemSettings)
                }
            }
        }
    }
}

@Composable
private fun SessionRootSetting(sessionRoot: String, onPick: () -> Unit) {
    val atRisk = sessionRoot.contains("uninstall")
    Card {
        Mono(sessionRoot, color = if (atRisk) Night.Warn else Night.Txt2, size = 11.sp)
        Spacer(Modifier.height(9.dp))
        QuietButton(if (atRisk) "Choose a folder" else "Change folder", onClick = onPick)
    }
}

/**
 * Not a setting, deliberately: a note.
 *
 * Dark adaptation takes about 25 minutes and one white screen ends it. The app is already dark
 * everywhere, which leaves screen brightness — and that belongs to the system, so the honest thing
 * is to say so rather than to offer a control that only dims the app's own pixels.
 */
@Composable
private fun NightModeNote() {
    Card {
        Mono(
            "Every screen is dark by design. Dark adaptation takes about 25 minutes and a " +
                "single bright screen ends it, so turn the system brightness down before you " +
                "start rather than after your eyes have adjusted. The app deliberately has no " +
                "brightness control of its own — it would only dim its own pixels and leave the " +
                "notification shade and the system UI at full blast.",
            color = Night.Txt2,
            size = 10.5.sp,
        )
    }
}

/** Phase 6 fills this in. Stated as absent rather than hidden — see T-8.7. */
@Composable
private fun CalibrationStub() {
    Card {
        Mono("No calibration library yet — Phase 6.", color = Night.Txt2, size = 11.sp)
        Spacer(Modifier.height(6.dp))
        Mono(
            "Darks are captured per session at matched ISO, exposure and temperature (D-14), " +
                "so a session is self-sufficient without one. Flats, a hot-pixel map and a " +
                "measured noise model are what a library would add.",
            color = Night.Txt3,
            size = 10.sp,
        )
    }
}

@Composable
private fun FieldLogSetting(
    tail: List<String>,
    sizeBytes: Long,
    onRefresh: () -> Unit,
    onShare: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Card {
        Mono(
            if (sizeBytes > 0) {
                "%.1f KB — survives the session, the app being killed, and a crash"
                    .format(sizeBytes / 1024.0)
            } else {
                "empty"
            },
            color = Night.Txt3,
            size = 11.sp,
        )
        if (expanded && tail.isNotEmpty()) {
            Spacer(Modifier.height(9.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 260.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                tail.forEach { line ->
                    Text(
                        line,
                        fontFamily = NumFamily,
                        fontSize = 9.sp,
                        lineHeight = 12.sp,
                        color = if (line.contains(" E/") || line.contains("CRASH")) {
                            Night.Warn
                        } else {
                            Night.Txt3
                        },
                    )
                }
            }
        }
        Spacer(Modifier.height(9.dp))
        ButtonRow {
            Box(Modifier.weight(1f)) {
                QuietButton(if (expanded) "Hide" else "Show recent") {
                    if (!expanded) onRefresh()
                    expanded = !expanded
                }
            }
            Box(Modifier.weight(1f)) {
                QuietButton("Share log", onClick = onShare)
            }
        }
    }
}
