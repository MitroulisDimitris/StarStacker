package com.starstacker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.starstacker.exposure.ExposureSolver
import com.starstacker.session.FrameKind
import com.starstacker.session.SessionState
import com.starstacker.session.SessionSummary
import com.starstacker.stacking.Combine
import com.starstacker.stacking.LinearMaster
import com.starstacker.stacking.StackJob
import com.starstacker.stacking.StackSettings
import com.starstacker.stacking.StackingService
import com.starstacker.ui.theme.Night
import com.starstacker.ui.theme.NumFamily
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * T-3.27 — the sessions, at last.
 *
 * **What it replaces.** `All sessions · N` called `openSessionFolder()` — the same file-manager
 * route as the folder icon two centimetres away — so the app had two controls doing one thing and
 * no screen at all for the thing they were named after. A file manager is where the *folders* are;
 * this is where the *sessions* are, which is a different question: how many frames were kept, how
 * much disk it cost, whether the night finished.
 *
 * This is **T-6.1 and T-6.3 arriving early**, out of Phase 4, because the button that needs them
 * already existed and currently lies about what it does. What stays in Phase 4 is what genuinely
 * cannot come early: the cached index (which waits on **OI-5** saying the scan is slow), thumbnails
 * (which want a stacked master from Phase 3), and manual frame include/exclude (which is
 * meaningless until the stacking queue reads the flags).
 *
 * **No `Stack selected` button.** T-3.29 builds the selection and stops there. Stacking one session
 * is Phase 3's T-5.x and stacking several is T-6.8; a visible control that silently does nothing is
 * worse than no control, which is the same rule that keeps the prototype's `Stack now` badge off
 * the main screen until it can act.
 */
@Composable
fun SessionsScreen(
    controller: SessionsController,
    selection: SessionSelection,
    onSelectionChange: (SessionSelection) -> Unit,
    onOpen: (SessionSummary) -> Unit,
    onOpenFolder: () -> Unit,
    onBack: () -> Unit,
) {
    val sessions = controller.sessions
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Night.Void)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
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
                Mono("SESSIONS", color = Night.Dim, size = 9.5.sp)
            }
        }

        // The confirmation, first, where it cannot be scrolled past. Nothing is deleted until the
        // button inside it is pressed, and it states what is about to go (T-3.28 / D-26).
        controller.pending?.let { pending ->
            item {
                DeleteConfirmation(
                    describe = pending.describe(),
                    onConfirm = { controller.confirmDelete { onSelectionChange(selection.clear()) } },
                    onCancel = { controller.cancelDelete() },
                )
            }
        }

        controller.lastAction?.let { item { Banner(it, color = Night.Txt3) } }
        controller.error?.let { item { Banner(it, color = Night.Red) } }

        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    Eyebrow(
                        when {
                            selection.isActive -> selection.describe()
                            controller.loading && sessions.isEmpty() -> "Reading the root"
                            else -> "${sessions.size} on this phone"
                        },
                    )
                }
                // T-3.21's folder route stays. When the answer really is "give me the files", a
                // file manager is the right tool and this screen is not it.
                FolderButton(onOpenFolder)
            }
        }

        // T-3.29's controls, present only while something is selected — a Delete button standing
        // over an empty selection is an invitation to find out what it does.
        if (selection.isActive) {
            item {
                ButtonRow {
                    Box(Modifier.weight(1f)) {
                        QuietButton(
                            text = "Delete ${selection.count}",
                            onClick = {
                                controller.askDelete(
                                    sessions.filter { it.folderName in selection },
                                )
                            },
                        )
                    }
                    Box(Modifier.weight(1f)) {
                        QuietButton(
                            text = "Clear",
                            onClick = { onSelectionChange(selection.clear()) },
                        )
                    }
                }
            }
        }

        if (sessions.isEmpty() && !controller.loading) {
            // "With no sessions it says so rather than presenting an empty box" — and it says the
            // one useful thing, which is that nothing is broken.
            item {
                Card {
                    Text(
                        "No sessions here yet.",
                        fontSize = 13.sp,
                        color = Night.Txt2,
                    )
                    Spacer(Modifier.height(4.dp))
                    Mono(
                        "Sessions appear as soon as one has been shot. A folder copied in from a " +
                            "computer counts too — this list is a scan of the storage root, not a " +
                            "record the app keeps.",
                        color = Night.Txt3,
                        size = 10.5.sp,
                    )
                }
            }
        }

        items(sessions, key = { it.folderName }) { session ->
            SessionPaneRow(
                session = session,
                selected = session.folderName in selection,
                selecting = selection.isActive,
                onOpen = { onOpen(session) },
                onToggle = { onSelectionChange(selection.toggle(session.folderName)) },
                onDelete = { controller.askDelete(listOf(session)) },
            )
        }

        // Folders that hold a session.json this app could not parse. Listed rather than hidden:
        // the DNGs beside a damaged log are still there and still worth having, and the one screen
        // built to find sessions is the worst place to make one invisible.
        controller.scanResult?.unreadable?.takeIf { it.isNotEmpty() }?.let { names ->
            item { Eyebrow("Could not be read") }
            item {
                Card {
                    names.forEach { Mono(it, color = Night.Txt3, size = 10.5.sp) }
                    Spacer(Modifier.height(6.dp))
                    Mono(
                        "The log in these folders would not parse. The frames are untouched — open " +
                            "the folder to recover them by hand.",
                        color = Night.Txt3,
                        size = 10.sp,
                    )
                }
            }
        }

        controller.scanNote()?.let { item { Mono(it, color = Night.Dim, size = 9.5.sp) } }
        item { Spacer(Modifier.height(28.dp)) }
    }
}

/**
 * One row. A tap opens the session; a long press starts selecting.
 *
 * Long press rather than a permanent checkbox, because the common act is opening one session and
 * the rare one is picking several to delete — and a column of checkboxes down the left makes the
 * screen about deletion. Once a selection exists, a plain tap toggles instead of opening, which is
 * the behaviour every photo gallery on the phone already has.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionPaneRow(
    session: SessionSummary,
    selected: Boolean,
    selecting: Boolean,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .border(
                1.dp,
                if (selected) Night.Red else Night.LineSoft,
                RoundedCornerShape(12.dp),
            )
            .background(Night.Surface2, RoundedCornerShape(12.dp))
            .combinedClickable(
                // A selection in progress changes what a tap means, which is what every photo
                // gallery on the phone already does. Long press is how one starts.
                onClick = if (selecting) onToggle else onOpen,
                onLongClick = onToggle,
            )
            .padding(13.dp),
    ) {
        // Top, not centre: the description wraps to two lines on a long session, and a centred
        // badge then floats halfway down the row looking like it belongs to neither line.
        Row(verticalAlignment = Alignment.Top) {
            // The thumbnail slot the prototype fills from a stacked master. There is no stacking
            // until Phase 3, so it says so rather than showing an empty square that reads as a
            // failed image load. Doubles as the selection mark, which needs no second control.
            Box(
                Modifier
                    .size(44.dp)
                    .border(
                        1.dp,
                        if (selected) Night.Red else Night.LineSoft,
                        RoundedCornerShape(8.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) {
                    Text("✓", fontSize = 18.sp, color = Night.Hot)
                } else {
                    Text(
                        "NO\nSTACK",
                        fontFamily = NumFamily,
                        fontSize = 6.5.sp,
                        lineHeight = 8.sp,
                        color = Night.Dim,
                        textAlign = TextAlign.Center,
                    )
                }
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
                Mono(
                    session.describeWithSize(),
                    color = Night.Txt3,
                    size = 10.sp,
                    lineHeight = 15.sp,
                )
                // Only when it adds something. An unnamed session is *named* for its start time,
                // so printing `started 20:39` under a row titled `20:39` states one fact twice.
                if (!session.labelIsStartTime) {
                    Spacer(Modifier.height(1.dp))
                    Mono("started ${session.startedAtClock()}", color = Night.Dim, size = 9.5.sp)
                }
            }
            Spacer(Modifier.size(8.dp))
            Badge(session.badge, if (session.needsAttention) Night.Warn else Night.Txt3)
        }
        // The per-row delete, offered where the session is rather than only behind a selection —
        // deleting one is the common case and should not require learning the selection first.
        if (!selecting) {
            Spacer(Modifier.height(9.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Mono("Open ▸", color = Night.Txt2, size = 10.5.sp)
                Spacer(Modifier.weight(1f))
                Text(
                    "Delete",
                    fontSize = 11.sp,
                    color = Night.Txt3,
                    modifier = Modifier
                        .border(1.dp, Night.Line, RoundedCornerShape(8.dp))
                        .clickable(onClick = onDelete)
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                )
            }
        }
    }
}

/**
 * T-3.28's confirmation. **No deletion ever happens without one**, and it names what is about to be
 * lost — the frame counts and the size on disk, not "this session".
 *
 * Drawn in the app's own palette rather than as a system dialog: everything else here is read with
 * dark-adapted eyes, and a Material dialog arrives at full brightness at 2 a.m. The destructive
 * button is the only red thing on the screen.
 */
@Composable
private fun DeleteConfirmation(describe: String, onConfirm: () -> Unit, onCancel: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, Night.Red, RoundedCornerShape(12.dp))
            .background(Night.Surface2, RoundedCornerShape(12.dp))
            .padding(13.dp),
    ) {
        Text("Delete for good?", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Night.Txt)
        Spacer(Modifier.height(4.dp))
        Mono(describe, color = Night.Warn, size = 11.sp)
        Spacer(Modifier.height(4.dp))
        Mono(
            "The folder and every frame in it are removed from the storage. There is no undo and " +
                "nothing is moved to a bin.",
            color = Night.Txt3,
            size = 10.sp,
        )
        Spacer(Modifier.height(11.dp))
        ButtonRow {
            Box(Modifier.weight(1f)) {
                Text(
                    "Delete",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Night.Red,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, Night.Red, RoundedCornerShape(11.dp))
                        .clickable(onClick = onConfirm)
                        .padding(vertical = 12.dp),
                )
            }
            Box(Modifier.weight(1f)) { QuietButton(text = "Keep it", onClick = onCancel) }
        }
    }
}

/**
 * T-6.4 / FR-10.3.1 — the **Stack** action, its progress, and the expert panel behind it.
 *
 * ### Never unprompted, and the button says what it will cost
 *
 * FR-10.3.3 and **D-27** agree here: stacking is minutes of a warm phone on battery, so it starts
 * because someone pressed a button and never because a screen appeared. The button names the frame
 * count it is about to work through, so the cost is legible before it is spent rather than
 * discovered from the fan.
 *
 * ### The advanced panel is collapsed, and that is the whole design
 *
 * The defaults are answers, not placeholders — sigma clipping and trimming to the overlap are what
 * this app thinks is right, argued out in 1.33 and 1.35. Someone who does not know what a
 * kappa-sigma clip is should never have to find out, so the choices sit behind one disclosure and
 * the collapsed state names what is currently selected. Expanding is opting in to an argument.
 */
@Composable
private fun StackingSection(
    folderName: String,
    accepted: Int,
    stacked: Map<String, String>,
    progress: StackingService.Progress,
    settings: StackSettings,
    advanced: Boolean,
    onToggleAdvanced: () -> Unit,
    onSettingsChange: (StackSettings) -> Unit,
    onStack: () -> Unit,
    onCancel: () -> Unit,
) {
    // Only this session's progress. The service is a singleton and may be working on another one.
    val mine = progress.active && progress.sessionName == folderName

    Column {
        if (mine) {
            Card {
                Mono(progress.message, color = Night.Txt2, size = 11.sp)
                Spacer(Modifier.height(8.dp))
                ProgressBar(progress.percent)
                if (progress.queued > 0) {
                    Spacer(Modifier.height(6.dp))
                    Mono("${progress.queued} more queued", color = Night.Txt3, size = 10.sp)
                }
            }
            Spacer(Modifier.height(8.dp))
            QuietButton(text = "Cancel", onClick = onCancel)
            return@Column
        }

        if (accepted == 0) {
            Card {
                Mono(
                    "Nothing to stack \u2014 no frames were accepted.",
                    color = Night.Txt3,
                    size = 11.sp,
                )
            }
            return@Column
        }

        if (stacked.isNotEmpty()) {
            // FR-10.4: a session can be restacked at any time, so what produced the existing
            // master has to be visible before anyone decides to replace it.
            Card {
                stacked["region"]?.let { KeyValue("Master", it) }
                KeyValue("Method", StackSettings.fromMap(stacked).describe())
                stacked["rejection"]?.let { KeyValue("Rejected", it) }
            }
            Spacer(Modifier.height(8.dp))
        }

        HotButton(
            text = if (stacked.isEmpty()) "Stack $accepted frames" else "Restack $accepted frames",
            onClick = onStack,
        )

        Spacer(Modifier.height(8.dp))
        Text(
            if (advanced) "Hide advanced" else "Advanced \u00b7 ${settings.describe()}",
            fontSize = 12.sp,
            color = Night.Txt3,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggleAdvanced)
                .padding(vertical = 8.dp),
            textAlign = TextAlign.Center,
        )

        if (advanced) {
            Eyebrow("Combination")
            Spacer(Modifier.height(6.dp))
            Combine.Method.entries.forEach { method ->
                QuietButton(
                    text = method.label,
                    selected = method == settings.method,
                    onClick = { onSettingsChange(settings.copy(method = method)) },
                )
                Spacer(Modifier.height(6.dp))
            }

            Spacer(Modifier.height(6.dp))
            Eyebrow("Edges")
            Spacer(Modifier.height(6.dp))
            ButtonRow {
                LinearMaster.Crop.entries.forEach { option ->
                    QuietButton(
                        text = option.label,
                        selected = option == settings.crop,
                        modifier = Modifier.weight(1f),
                        onClick = { onSettingsChange(settings.copy(crop = option)) },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Card { Mono(settings.crop.summary, color = Night.Txt3, size = 10.5.sp) }
        }
    }
}

/** A bar, in the night palette. Material's has its own opinions about colour and animation. */
@Composable
private fun ProgressBar(percent: Int) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(4.dp)
            .background(Night.Line, RoundedCornerShape(2.dp)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(percent.coerceIn(0, 100) / 100f)
                .height(4.dp)
                .background(Night.Hot, RoundedCornerShape(2.dp)),
        )
    }
}

/**
 * T-3.27 — one session, in full.
 *
 * The frame log is the point. FR-10.2.2 wants every frame listed with the reason it was rejected,
 * because a rejection is a judgement and **D-10** says the user is entitled to disagree with it —
 * which they cannot do if they cannot see it. Manual include/exclude stays in Phase 4 (T-6.3),
 * where something finally reads the flags.
 */
@Composable
fun SessionDetailScreen(
    detail: SessionsController.Detail,
    stacking: StackingService.Progress,
    stackDefaults: StackSettings,
    onStack: (StackSettings) -> Unit,
    onCancelStack: () -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit,
) {
    val log = detail.log
    val info = log.info
    // Per-run, seeded from the Settings default and deliberately not written back: changing your
    // mind about one session is not changing your mind about all of them.
    var settings by remember(detail.summary.folderName) { mutableStateOf(stackDefaults) }
    var advanced by remember(detail.summary.folderName) { mutableStateOf(false) }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Night.Void)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Spacer(Modifier.height(16.dp)) }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "← Sessions",
                    fontSize = 13.sp,
                    color = Night.Txt3,
                    modifier = Modifier.clickable(onClick = onBack),
                )
                Spacer(Modifier.weight(1f))
                Badge(
                    detail.summary.badge,
                    if (detail.summary.needsAttention) Night.Warn else Night.Txt3,
                )
            }
        }

        item {
            Column {
                Text(
                    detail.summary.label,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Night.Txt,
                )
                Spacer(Modifier.height(3.dp))
                Mono(
                    "${FULL_DATE.format(Date(info.startedAtEpochMs))} · " +
                        detail.summary.describeCountsWithSize(),
                    color = Night.Txt3,
                    size = 10.5.sp,
                    lineHeight = 15.sp,
                )
            }
        }

        item { Eyebrow("Result") }
        item {
            Card {
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Metric("Kept", "${detail.summary.accepted}")
                    Metric("Shot", "${detail.summary.lights}")
                    Metric("Darks", "${detail.summary.darks}")
                    Metric(
                        "Integration",
                        SessionSummary.formatDuration(detail.summary.integrationSeconds),
                    )
                }
            }
        }

        item { Eyebrow("As planned") }
        item {
            Card {
                KeyValue("Camera", info.cameraId)
                KeyValue("ISO", "${info.plannedIso}")
                KeyValue(
                    "Sub exposure",
                    ExposureSolver.formatSeconds(info.plannedExposureNs / 1e9),
                )
                KeyValue("Lights asked for", "${info.plannedLightCount}")
                KeyValue("Darks asked for", "${info.plannedDarkCount}")
                info.focusDiopters?.let {
                    KeyValue(
                        "Focus",
                        "%.3f dioptres%s".format(
                            it,
                            info.focusHfr?.let { hfr -> " · HFR %.2f px".format(hfr) }.orEmpty(),
                        ),
                    )
                }
                KeyValue("State", info.state.name.lowercase(), warn = info.state == SessionState.FAILED)
                KeyValue("Device", info.deviceModel)
            }
        }

        if (info.declinationDeg != null || info.latitudeDeg != null) {
            item { Eyebrow("Pointing, frozen at start") }
            item {
                Card {
                    info.altitudeDeg?.let { KeyValue("Altitude", "%.1f°".format(it)) }
                    info.azimuthDeg?.let { KeyValue("Azimuth", "%.0f°".format(it)) }
                    info.declinationDeg?.let { KeyValue("Declination", "%.1f°".format(it)) }
                    info.fieldRotationArcsecPerSec?.let {
                        KeyValue("Field rotation", "%.1f″/s".format(it))
                    }
                    if (info.latitudeDeg != null && info.longitudeDeg != null) {
                        KeyValue(
                            "Position",
                            "%.3f, %.3f".format(info.latitudeDeg, info.longitudeDeg),
                        )
                    }
                    info.compassAccuracy?.let { KeyValue("Compass", it.lowercase()) }
                }
            }
        }

        if (info.exposureDerivation.isNotEmpty()) {
            item { Eyebrow("Why this exposure") }
            item {
                Card {
                    info.exposureDerivation.forEach { line ->
                        Mono(line, color = Night.Txt3, size = 10.sp)
                        Spacer(Modifier.height(3.dp))
                    }
                }
            }
        }

        item { Eyebrow("Stacking · FR-10.3") }
        item {
            StackingSection(
                folderName = detail.summary.folderName,
                accepted = detail.summary.accepted,
                stacked = info.stacking,
                progress = stacking,
                settings = settings,
                advanced = advanced,
                onToggleAdvanced = { advanced = !advanced },
                onSettingsChange = { settings = it },
                onStack = { onStack(settings) },
                onCancel = onCancelStack,
            )
        }

        item { Eyebrow("Frame log · ${log.frames.size} frames") }
        if (log.frames.isEmpty()) {
            item {
                Card {
                    Mono(
                        "No frames were recorded. The session was created and never exposed.",
                        color = Night.Txt3,
                        size = 10.5.sp,
                    )
                }
            }
        } else {
            items(log.frames, key = { "${it.kind}-${it.index}-${it.fileName}" }) { frame ->
                FrameRow(frame)
            }
        }

        item { Eyebrow("On disk") }
        item {
            Card {
                Mono(detail.displayPath ?: detail.summary.folderName, color = Night.Txt3, size = 10.sp)
            }
        }

        item {
            QuietButton(text = "Delete this session", onClick = onDelete)
        }
        item { Spacer(Modifier.height(28.dp)) }
    }
}

/**
 * The moment between a tap and the log being read.
 *
 * A screen rather than nothing, because the alternative is what this replaced: the detail screen
 * composing with no log, concluding it could not be read, and popping itself back off the stack
 * before the read finished. On a two-session root that is a tap that appears to do nothing.
 */
@Composable
fun SessionDetailLoading(folderName: String, onBack: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Night.Void)
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(16.dp))
        Text(
            "← Sessions",
            fontSize = 13.sp,
            color = Night.Txt3,
            modifier = Modifier.clickable(onClick = onBack),
        )
        Spacer(Modifier.height(20.dp))
        Card { Mono("reading $folderName…", color = Night.Txt3, size = 11.sp) }
    }
}

/**
 * One frame of the log.
 *
 * A rejected frame states **the reason and the numbers behind it**, which is the whole reason the
 * gate records `rejectDetail` — "TRAILED" is a verdict, "elongation 2.4 px, budget 1.5" is a
 * measurement someone can argue with.
 */
@Composable
private fun FrameRow(frame: com.starstacker.session.FrameRecord) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Mono(
            "%04d".format(frame.index),
            color = if (frame.accepted) Night.Txt2 else Night.Dim,
            size = 10.sp,
        )
        Spacer(Modifier.size(9.dp))
        Column(Modifier.weight(1f)) {
            Mono(
                buildString {
                    append(if (frame.kind == FrameKind.DARK) "dark" else "light")
                    frame.hfr?.let { append(" · HFR %.2f".format(it)) }
                    frame.starCount?.let { append(" · $it stars") }
                    frame.temperatureC?.let { append(" · %.1f°C".format(it)) }
                },
                color = if (frame.accepted) Night.Txt2 else Night.Txt3,
                size = 10.sp,
            )
            if (!frame.accepted) {
                Mono(
                    listOfNotNull(frame.rejectReason?.name?.lowercase(), frame.rejectDetail)
                        .joinToString(" — "),
                    color = Night.Warn,
                    size = 9.5.sp,
                )
            }
        }
        Mono(
            if (frame.accepted) "kept" else "cut",
            color = if (frame.accepted) Night.Txt3 else Night.Warn,
            size = 9.5.sp,
        )
    }
}

private val FULL_DATE = SimpleDateFormat("EEE d MMM yyyy, HH:mm", Locale.getDefault())
