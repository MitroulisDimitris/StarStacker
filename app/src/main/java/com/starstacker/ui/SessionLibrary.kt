package com.starstacker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.starstacker.calibration.Staleness
import com.starstacker.session.SessionFilter
import com.starstacker.session.SessionSummary
import com.starstacker.session.StoragePlan
import com.starstacker.stacking.MasterVersions
import com.starstacker.stacking.MultiNight
import com.starstacker.ui.theme.Night

/**
 * Phase 4's session-library controls, kept out of `SessionsScreen` because that file is already
 * the longest in the app and these are seven independent pieces rather than one screen.
 *
 * Everything here draws state the controller already holds and calls back; none of it decides
 * anything, which is what keeps the decisions in the tested pure code where they can be argued
 * with.
 */

// ---------------------------------------------------------------------------- T-6.2

/**
 * The sort and filter row.
 *
 * A horizontal strip of chips rather than a dialog: on a phone at 2 a.m. the whole point is that
 * narrowing the list costs one tap, and a dialog costs three. The active state is drawn rather
 * than implied, because a list that is quietly filtered is a list that appears to have lost
 * sessions.
 */
@Composable
fun SessionFilterBar(
    filter: SessionFilter,
    cameras: List<String>,
    onChange: (SessionFilter) -> Unit,
) {
    Column {
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Sort cycles rather than opening a menu. Six options is few enough that tapping
            // through them is faster than choosing from a list.
            Chip(
                text = filter.sort.label,
                active = filter.sort != SessionFilter.Sort.NEWEST,
                onClick = {
                    val order = SessionFilter.Sort.entries
                    val next = order[(order.indexOf(filter.sort) + 1) % order.size]
                    onChange(filter.copy(sort = next))
                },
            )
            Chip(
                text = "Stacked",
                active = filter.onlyStacked,
                onClick = { onChange(filter.copy(onlyStacked = !filter.onlyStacked)) },
            )
            Chip(
                text = "Needs attention",
                active = filter.onlyNeedingAttention,
                onClick = {
                    onChange(filter.copy(onlyNeedingAttention = !filter.onlyNeedingAttention))
                },
            )
            // Only the cameras actually present — a chip for a lens this phone has never used
            // would be a control that can only ever empty the list.
            cameras.forEach { camera ->
                Chip(
                    text = "Cam $camera",
                    active = camera in filter.cameras,
                    onClick = {
                        val next = if (camera in filter.cameras) {
                            filter.cameras - camera
                        } else {
                            filter.cameras + camera
                        }
                        onChange(filter.copy(cameras = next))
                    },
                )
            }
            if (!filter.isDefault) {
                Chip(text = "Clear", active = false, onClick = { onChange(SessionFilter()) })
            }
        }
        if (!filter.isDefault) {
            Box(Modifier.padding(top = 4.dp)) {
                Mono(filter.describe(), color = Night.Txt3, size = 9.5.sp)
            }
        }
    }
}

@Composable
private fun Chip(text: String, active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (active) Night.Txt3.copy(alpha = 0.22f) else Night.Surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Mono(text, color = if (active) Night.Txt else Night.Txt3, size = 9.5.sp)
    }
}

// ---------------------------------------------------------------------------- T-6.5

/**
 * The versions of a master, and which one is being shown.
 *
 * Drawn even when there is one, because the *count* is the thing that tells someone restacking is
 * non-destructive. A control that only appears after the second stack teaches nobody that the
 * first one is safe.
 */
@Composable
fun VersionsCard(
    index: MasterVersions.Index,
    onSelect: (Int) -> Unit,
    onDelete: (Int) -> Unit,
) {
    if (index.versions.isEmpty()) return
    val current = index.current?.id
    Card {
        index.versions.sortedByDescending { it.id }.forEach { version ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(version.id) }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Mono(
                    if (version.id == current) "●" else "○",
                    color = if (version.id == current) Night.Txt else Night.Dim,
                    size = 10.sp,
                )
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f)) {
                    Mono(
                        "v${version.id} · ${version.label}",
                        color = if (version.id == current) Night.Txt2 else Night.Txt3,
                        size = 10.sp,
                    )
                    Mono(
                        "${version.frames} frames · ${version.region}",
                        color = Night.Dim,
                        size = 9.sp,
                    )
                }
                // The last one is not offered for deletion — see MasterVersions.delete.
                if (index.versions.size > 1) {
                    Mono(
                        "delete",
                        color = Night.Warn,
                        size = 9.sp,
                        modifier = Modifier.clickable { onDelete(version.id) },
                    )
                }
            }
        }
    }
}

/** What differs between the two newest versions — FR-10.4's comparison, in words. */
@Composable
fun VersionComparison(pair: Pair<MasterVersions.Version, MasterVersions.Version>?) {
    if (pair == null) return
    val (older, newer) = pair
    Card {
        Mono("v${older.id} → v${newer.id}", color = Night.Txt2, size = 10.sp)
        Spacer(Modifier.height(3.dp))
        MasterVersions.differences(older, newer).forEach {
            Mono(it, color = Night.Txt3, size = 9.5.sp, lineHeight = 13.sp)
        }
    }
}

// ---------------------------------------------------------------------------- T-6.6

/**
 * The staleness banner.
 *
 * Says nothing when the calibration is unchanged, which is the common case and does not need a
 * row. **Never offers to restack automatically** (FR-10.4.2): a newer flat is only better on
 * average, and one shot through a smeared lens is worse than the one it replaced.
 */
@Composable
fun StalenessBanner(report: Staleness.Report) {
    when (report.verdict) {
        Staleness.Verdict.FRESH -> Unit
        Staleness.Verdict.UNKNOWN -> Unit
        Staleness.Verdict.STALE -> Banner(report.describe(), color = Night.Warn)
    }
}

// ---------------------------------------------------------------------------- T-6.7

/**
 * Per-session storage, and the actions that would reduce it.
 *
 * Every action states its size and whether it can be undone, and an action that cannot be offered
 * is drawn **with its reason** rather than greyed out — "there is no master yet, this would delete
 * the whole session" is the answer to the question a disabled button provokes.
 */
@Composable
fun StorageCard(plan: StoragePlan.Plan, onAsk: (StoragePlan.Action) -> Unit) {
    Card {
        KeyValue("Lights", SessionSummary.formatBytes(plan.lightBytes))
        KeyValue("Darks", SessionSummary.formatBytes(plan.darkBytes))
        KeyValue("Masters", SessionSummary.formatBytes(plan.masterBytes))
        Spacer(Modifier.height(8.dp))
        plan.actions.forEach { action ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .then(if (action.available) Modifier.clickable { onAsk(action) } else Modifier)
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Mono(
                        action.label,
                        color = if (action.available) Night.Txt2 else Night.Dim,
                        size = 10.sp,
                    )
                    action.blockedBecause?.let {
                        Mono(it, color = Night.Dim, size = 9.sp, lineHeight = 12.sp)
                    }
                }
                Mono(
                    SessionSummary.formatBytes(action.bytes),
                    color = if (action.available) Night.Txt3 else Night.Dim,
                    size = 9.5.sp,
                )
                if (action.available && !action.reversible) {
                    Spacer(Modifier.height(6.dp))
                    Mono("one-way", color = Night.Warn, size = 8.5.sp)
                }
            }
        }
    }
}

/** The confirmation, worded like T-3.28's so the two cannot describe a loss differently. */
@Composable
fun StorageConfirmation(
    action: StoragePlan.Action,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    Card {
        Mono(action.label, color = Night.Txt, size = 11.sp)
        Spacer(Modifier.height(4.dp))
        Mono(
            buildString {
                append("Frees ${SessionSummary.formatBytes(action.bytes)}. ")
                append(
                    if (action.reversible) {
                        "This can be rebuilt from the subs."
                    } else {
                        "This cannot be undone — the frames are a night that happened."
                    },
                )
            },
            color = Night.Txt3,
            size = 10.sp,
            lineHeight = 14.sp,
        )
        Spacer(Modifier.height(10.dp))
        ButtonRow {
            Box(Modifier.weight(1f)) { QuietButton(text = "Cancel", onClick = onCancel) }
            Box(Modifier.weight(1f)) { QuietButton(text = "Delete", onClick = onConfirm) }
        }
    }
}

// ---------------------------------------------------------------------------- T-6.8

/**
 * Whether a multi-session selection could be combined.
 *
 * A **preview, not an action** — the composite stack itself is not built, and the card says so
 * rather than offering a button that does nothing. It earns its place anyway: whether two nights
 * can go together is answerable from their logs alone, the answer is often no, and the reason is
 * worth knowing before anyone plans a second night around it.
 */
@Composable
fun CombinePreview(plan: MultiNight.Plan?) {
    if (plan == null) return
    Card {
        Mono("Combined, these would give", color = Night.Txt3, size = 9.5.sp)
        Spacer(Modifier.height(3.dp))
        Mono(plan.describe(), color = Night.Txt2, size = 11.sp)
        if (plan.rejected.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            plan.rejected.forEach { (night, reason) ->
                Mono("${night.label}: $reason", color = Night.Warn, size = 9.5.sp, lineHeight = 13.sp)
            }
        }
        plan.warnings.forEach {
            Mono(it, color = Night.Txt3, size = 9.5.sp, lineHeight = 13.sp)
        }
        Spacer(Modifier.height(6.dp))
        Mono(
            "Combining is not built yet — this is what it would do.",
            color = Night.Dim,
            size = 9.sp,
        )
    }
}
