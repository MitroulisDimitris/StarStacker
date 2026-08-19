package com.starstacker.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.starstacker.stars.PreviewStack
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.starstacker.capture.CaptureEngine
import com.starstacker.exposure.ExposureSolver
import com.starstacker.session.FrameKind
import com.starstacker.session.FrameRecord
import com.starstacker.session.SessionLog
import com.starstacker.session.SessionState
import com.starstacker.ui.theme.Night
import com.starstacker.ui.theme.NumFamily

/**
 * Prototype screen 03 — capturing (T-3.11), and the completion summary (T-3.15) when it is done.
 *
 * The constraint that shapes everything: **readable from two metres away, in the dark, by someone
 * who has walked back to the tripod to see whether it is still working.** That question has to be
 * answerable at a glance, so the frame count is the largest thing on the screen and the ring is a
 * shape rather than a number — you can read a shape at a distance you cannot read a percentage.
 *
 * The recent-frame log shows rejections with the reason attached, because a rejection the user
 * cannot see is a rejection they cannot disagree with. Frames are on disk either way (D-10), and
 * the note says so — a session losing frames to cloud is still a session worth leaving running.
 */
@Composable
fun CaptureScreen(
    progress: CaptureEngine.Progress,
    log: SessionLog?,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onEndAndTakeDarks: () -> Unit,
    onConfirmDarks: () -> Unit,
    onSkipDarks: () -> Unit,
    onDone: () -> Unit,
) {
    if (progress.state == SessionState.DONE || progress.state == SessionState.FAILED) {
        CompletionScreen(progress, log, onDone)
        return
    }

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
                Mono(headline(progress), color = Night.Txt2, size = 11.sp)
                Spacer(Modifier.weight(1f))
                Badge(progress.state.name, stateColor(progress.state))
            }
        }

        // FR-4.2.1. Nothing else on the screen matters while this is up: a dark taken through an
        // uncovered lens is a light frame in the darks folder, and no later step can tell.
        if (progress.state == SessionState.AWAITING_DARKS) {
            item { Spacer(Modifier.height(8.dp)) }
            item {
                Text(
                    "Cover the lens",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = Night.Txt,
                )
            }
            item {
                Card {
                    Mono(
                        "Lights are done. Put the cap on, or press the lens flat against " +
                            "something opaque, then continue. Darks are matched to this " +
                            "session's ISO, exposure and temperature — they only work if the " +
                            "sensor sees nothing.",
                        color = Night.Txt2,
                        size = 11.5.sp,
                    )
                }
            }
            item { HotButton("Lens is covered — take darks", onClick = onConfirmDarks) }
            item {
                QuietButton("Skip darks", onClick = onSkipDarks)
            }
            item {
                Banner(
                    "Skipping costs dark-current and amp-glow correction: hot pixels and a " +
                        "warm glow in the corners stay in the stack. You can shoot darks later " +
                        "at the same ISO, exposure and temperature and add them by hand.",
                    color = Night.Warn,
                )
            }
            item { Spacer(Modifier.height(28.dp)) }
            return@LazyColumn
        }

        item { ProgressRing(progress) }

        // Below the stack, because it is diagnosis rather than progress — but present whenever
        // there is one, including when the stack above is absent, which is the case it exists for.
        if (progress.rejectedPreview != null) {
            item { Eyebrow("Last rejected frame") }
            item { RejectedFrameCard(progress.rejectedPreview!!, progress.rejectedNote) }
        }

        if (progress.preview != null) {
            item { Eyebrow("Live stack - FR-7.4") }
            item { PreviewStackCard(progress.preview, progress.previewDepth) }
        }

        item { Eyebrow("This frame") }
        item {
            Card {
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Metric("HFR", progress.lastHfr?.let { "%.2f".format(it) } ?: "—", unit = "px")
                    Metric("Stars", progress.lastStarCount?.toString() ?: "—")
                    Metric(
                        "Sky",
                        progress.lastBackground?.let { "%.0f".format(it) } ?: "—",
                        unit = "ADU",
                    )
                    Metric(
                        "Kept",
                        "${progress.framesAccepted}",
                        warn = progress.framesCaptured > 0 &&
                            progress.framesAccepted < progress.framesCaptured / 2,
                    )
                    // T-4.5 / FR-7.5 — how much of the frame all the kept frames still share. It
                    // only falls, and how fast is the one live signal that the tripod is drifting
                    // or the field rotating faster than the plan assumed. Warned below 80 %, which
                    // is where SessionPlanner's own rotation budget starts complaining.
                    Metric(
                        "Common",
                        progress.commonAreaFraction
                            ?.let { "%.0f".format(it * 100) } ?: "—",
                        unit = "%",
                        warn = (progress.commonAreaFraction ?: 1.0) < 0.8,
                    )
                }
                progress.thermalNote?.let {
                    Spacer(Modifier.height(8.dp))
                    Mono(it, color = if (progress.cooling) Night.Warn else Night.Txt3, size = 10.sp)
                }
            }
        }

        if (log != null && log.frames.isNotEmpty()) {
            item { Eyebrow("Recent frames") }
            item {
                Card {
                    log.frames.takeLast(RECENT_FRAMES).reversed().forEach { FrameRow(it) }
                }
            }
        }

        // A non-blocking note: it reports, it does not gate. The session is still running.
        rejectionNote(log)?.let { item { Banner(it, color = Night.Warn) } }

        item {
            ButtonRow {
                Box(Modifier.weight(1f)) {
                    QuietButton(
                        text = if (progress.state == SessionState.PAUSED) "Resume" else "Pause",
                        onClick = if (progress.state == SessionState.PAUSED) onResume else onPause,
                    )
                }
                Box(Modifier.weight(1f)) {
                    QuietButton(
                        text = "End & take darks",
                        enabled = progress.state != SessionState.DARKS,
                        onClick = onEndAndTakeDarks,
                    )
                }
            }
        }
        item { Spacer(Modifier.height(28.dp)) }
    }
}

/**
 * The one full-intensity element on this screen, and the thing you can read from two metres.
 * Drawn rather than laid out, because a ring communicates "most of the way through" without
 * being read.
 */
@Composable
private fun ProgressRing(progress: CaptureEngine.Progress) {
    val target = progress.target.coerceAtLeast(1)
    val lights = progress.log?.lights.orEmpty()
    val done = when (progress.state) {
        SessionState.DARKS -> progress.darksCaptured
        else -> progress.framesCaptured
    }

    // T-3.22's inner ring. The engine publishes when the exposure began and how long it runs;
    // everything animated happens here, so nothing ticks while the screen is off.
    val exposureFraction = inFlightFraction(progress)

    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1.35f),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val outer = minOf(size.width, size.height) / 2f - 6.dp.toPx()
            val tickInner = outer - 14.dp.toPx()
            val centre = Offset(size.width / 2f, size.height / 2f)

            // One tick per frame, so the ring shows *where* the rejections fell rather than only
            // how many there were — the prototype's shape, and the reason it is not an arc.
            for (i in 0 until target) {
                val angle = (i.toFloat() / target) * 2f * PI.toFloat() - PI.toFloat() / 2f
                val shot = lights.getOrNull(i)
                val colour = when {
                    i >= done -> Night.Ghost
                    shot == null -> Night.Red
                    shot.accepted -> Night.Red
                    else -> Night.Warn
                }
                drawLine(
                    color = colour,
                    start = centre + Offset(cos(angle) * tickInner, sin(angle) * tickInner),
                    end = centre + Offset(cos(angle) * outer, sin(angle) * outer),
                    strokeWidth = if (colour == Night.Ghost) 2.dp.toPx() else 2.6.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }

            // Leading edge: the frame being worked on right now.
            if (done in 1 until target) {
                val angle = (done.toFloat() / target) * 2f * PI.toFloat() - PI.toFloat() / 2f
                val r = (tickInner + outer) / 2f
                drawCircle(
                    color = Night.Hot,
                    radius = 4.dp.toPx(),
                    center = centre + Offset(cos(angle) * r, sin(angle) * r),
                )
            }

            // The inner ring: this exposure, 0 to 1.
            val innerRadius = tickInner - 12.dp.toPx()
            val innerStroke = 4.dp.toPx()
            drawArc(
                color = Night.LineSoft,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = centre - Offset(innerRadius, innerRadius),
                size = Size(innerRadius * 2, innerRadius * 2),
                style = Stroke(width = innerStroke),
            )
            if (exposureFraction != null) {
                drawArc(
                    color = Night.Hot,
                    startAngle = -90f,
                    sweepAngle = 360f * exposureFraction,
                    useCenter = false,
                    topLeft = centre - Offset(innerRadius, innerRadius),
                    size = Size(innerRadius * 2, innerRadius * 2),
                    style = Stroke(width = innerStroke, cap = StrokeCap.Round),
                )
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "$done",
                fontFamily = NumFamily,
                fontSize = 46.sp,
                color = Night.Txt,
            )
            Mono("of $target", color = Night.Txt3, size = 12.sp)
            Spacer(Modifier.height(6.dp))
            Mono(inFlightLabel(progress, exposureFraction), color = Night.Dim, size = 10.sp)
        }
    }
}

/**
 * How far through the current exposure we are, or null when nothing is exposing.
 *
 * Recomposes on a timer only while a frame is actually in flight — a session spends a third of
 * every cycle in readout and write (§1.14), and animating through that would be a lie as well as
 * a waste.
 */
@Composable
private fun inFlightFraction(progress: CaptureEngine.Progress): Float? {
    val started = progress.frameStartedElapsedNs ?: return null
    val exposure = progress.frameExposureNs
    if (exposure <= 0L) return null
    if (progress.state != SessionState.CAPTURING && progress.state != SessionState.DARKS) return null

    var fraction by remember(started) { mutableStateOf(0f) }
    LaunchedEffect(started) {
        while (true) {
            val elapsed = android.os.SystemClock.elapsedRealtimeNanos() - started
            fraction = (elapsed.toFloat() / exposure).coerceIn(0f, 1f)
            if (fraction >= 1f) break
            delay(FRAME_TICK_MS)
        }
    }
    return fraction
}

/**
 * The second state the ring needs.
 *
 * §1.14 measured ~3.4 s of readout and DNG write after a 7.4 s sub. A ring that only knows about
 * exposure would sit full and apparently stuck for a third of every cycle, which reads as a hang.
 */
private fun inFlightLabel(progress: CaptureEngine.Progress, fraction: Float?): String = when {
    progress.state == SessionState.PAUSED -> "paused"
    progress.state == SessionState.AWAITING_DARKS -> "cover the lens"
    fraction == null -> ""
    fraction >= 1f -> "reading out"
    else -> "exposing ${"%.0f".format(progress.frameExposureNs / 1e9 * (1f - fraction))}s left"
}

/** Fast enough to look continuous, slow enough to be invisible against a multi-second sub. */
private const val FRAME_TICK_MS = 120L

@Composable
private fun FrameRow(frame: FrameRecord) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Mono("%03d".format(frame.index), color = Night.Dim, size = 10.sp)
        Spacer(Modifier.size(10.dp))
        Mono(
            buildString {
                frame.hfr?.let { append("HFR %.1f".format(it)) }
                frame.eccentricity?.let {
                    if (isNotEmpty()) append(" · ")
                    append("ecc %.2f".format(it))
                }
                frame.starCount?.let {
                    if (isNotEmpty()) append(" · ")
                    append("$it★")
                }
                if (isEmpty()) append(frame.kind.name.lowercase())
            },
            color = if (frame.accepted) Night.Txt2 else Night.Txt3,
            size = 10.5.sp,
        )
        Spacer(Modifier.weight(1f))
        frame.rejectReason?.let {
            Badge(it.name.lowercase().replaceFirstChar { c -> c.uppercase() }, Night.Warn)
        }
    }
}

@Composable
private fun CompletionScreen(
    progress: CaptureEngine.Progress,
    log: SessionLog?,
    onDone: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Night.Void)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { Spacer(Modifier.height(28.dp)) }
        item {
            Text(
                if (progress.state == SessionState.FAILED) "Session failed" else "Session complete",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = Night.Txt,
            )
        }
        progress.message?.let { item { Mono(it, color = Night.Txt2, size = 11.5.sp) } }

        item { Eyebrow("Result") }
        item {
            Card {
                KeyValue("Frames kept", "${progress.framesAccepted} of ${progress.framesCaptured}")
                KeyValue("Darks", "${progress.darksCaptured}")
                log?.let {
                    KeyValue(
                        "Integration",
                        ExposureSolver.formatSeconds(it.acceptedIntegrationSeconds),
                    )
                    KeyValue("ISO / sub", "${it.info.plannedIso} · ${
                        ExposureSolver.formatSeconds(it.info.plannedExposureNs / 1e9)
                    }")
                }
                rejectionBreakdown(log)?.let {
                    Spacer(Modifier.height(8.dp))
                    Mono(it, color = Night.Txt3, size = 10.5.sp)
                }
            }
        }

        // FR-9.4: the user must never have to hunt for where the output went.
        item { Eyebrow("Where it is") }
        item {
            Card {
                Mono(
                    progress.sessionPath ?: "—",
                    color = Night.Txt2,
                    size = 10.5.sp,
                )
                Spacer(Modifier.height(6.dp))
                Mono(
                    "lights/ · darks/ · session.json — ready for Siril or DSS as they are",
                    color = Night.Txt3,
                    size = 10.sp,
                )
            }
        }

        item { HotButton("Done", onClick = onDone) }
        item { Spacer(Modifier.height(28.dp)) }
    }
}

private const val RECENT_FRAMES = 6

private fun headline(progress: CaptureEngine.Progress): String = buildString {
    append(
        when (progress.state) {
            SessionState.AWAITING_DARKS -> "COVER THE LENS"
            SessionState.DARKS -> "TAKING DARKS"
            SessionState.PAUSED -> "PAUSED"
            else -> "CAPTURING"
        },
    )
}

private fun stateColor(state: SessionState) = when (state) {
    SessionState.AWAITING_DARKS -> Night.Warn
    SessionState.PAUSED -> Night.Warn
    SessionState.FAILED -> Night.Red
    else -> Night.Mid
}

/**
 * The prototype's event note. It names what happened and, crucially, says the frames are still on
 * disk — otherwise "6 rejected" reads as "6 lost", and the user shortens a session that is fine.
 */
private fun rejectionNote(log: SessionLog?): String? {
    val rejected = log?.lights?.filter { !it.accepted }.orEmpty()
    if (rejected.isEmpty()) return null
    val commonest = rejected.groupingBy { it.rejectReason }.eachCount()
        .maxByOrNull { it.value } ?: return null
    val reason = commonest.key?.name?.lowercase() ?: "quality"
    return "${rejected.size} frame${if (rejected.size == 1) "" else "s"} rejected, mostly " +
        "$reason. Still capturing — they are kept on disk and you can include them later."
}

private fun rejectionBreakdown(log: SessionLog?): String? {
    val rejected = log?.lights?.filter { !it.accepted }.orEmpty()
    if (rejected.isEmpty()) return null
    return rejected.groupingBy { it.rejectReason?.name?.lowercase() ?: "other" }
        .eachCount()
        .entries
        .joinToString(" · ") { "${it.value} ${it.key}" }
        .let { "rejected: $it — all kept on disk" }
}

/**
 * T-3.14 / D-18 — the live preview stack.
 *
 * The caption states the depth *and* that this is not the result, because a stretched running mean
 * of a dozen subs looks enough like an astrophoto to be mistaken for one, and the real stack is a
 * full-resolution job that happens after the session (FR-7.4). Someone who thinks this is the
 * output will stop the session early.
 *
 * The bitmap is rebuilt from the engine's buffer on each change: the engine owns that array and
 * overwrites it in place, so holding on to it would show a frame that no longer exists.
 */
@Composable
private fun PreviewStackCard(argb: IntArray, depth: Int) {
    val bitmap = remember(argb, depth) {
        Bitmap.createBitmap(
            argb, PreviewStack.WIDTH, PreviewStack.HEIGHT, Bitmap.Config.ARGB_8888,
        ).asImageBitmap()
    }
    Card {
        Image(
            bitmap = bitmap,
            contentDescription = "Live stack of $depth frames",
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(PreviewStack.WIDTH.toFloat() / PreviewStack.HEIGHT),
            contentScale = ContentScale.Fit,
        )
        Spacer(Modifier.height(8.dp))
        Mono(
            "$depth frames, aligned on the measured transform (T-4.6). A preview at a fraction " +
                "of full resolution — not the stack.",
            color = Night.Txt3,
            size = 10.sp,
        )
    }
}

/**
 * The most recently rejected frame, so a session that is rejecting everything is not a blank
 * screen.
 *
 * Until this existed the capture screen showed **nothing** when nothing was accepted, because the
 * stack above only ever holds accepted frames. Cloud, a capped lens, the phone pointed at the
 * ground and twilight that has not faded are all identical from a rejection count and all obvious
 * from one look at the frame.
 */
@Composable
private fun RejectedFrameCard(argb: IntArray, note: String?) {
    val bitmap = remember(argb, note) {
        Bitmap.createBitmap(
            argb, PreviewStack.WIDTH, PreviewStack.HEIGHT, Bitmap.Config.ARGB_8888,
        ).asImageBitmap()
    }
    Card {
        Image(
            bitmap = bitmap,
            contentDescription = "The most recently rejected frame",
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(PreviewStack.WIDTH.toFloat() / PreviewStack.HEIGHT),
            contentScale = ContentScale.Fit,
        )
        note?.let {
            Spacer(Modifier.height(8.dp))
            Mono(it, color = Night.Warn, size = 10.sp, lineHeight = 15.sp)
        }
        Spacer(Modifier.height(6.dp))
        Mono(
            "Kept on disk regardless — nothing is deleted (D-10).",
            color = Night.Txt3,
            size = 9.5.sp,
        )
    }
}
