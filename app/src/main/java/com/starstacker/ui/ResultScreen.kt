package com.starstacker.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.starstacker.edit.Gradient
import com.starstacker.ui.theme.Night

/**
 * T-7.5 / FR-8.3 — the auto-edit screen: the picture, one slider, before/after, and the expert
 * controls a tap deeper.
 *
 * ### The first screen in this app that shows an image
 *
 * Everything up to now has been numbers about a photograph. FR-9.4 says the user must never have to
 * hunt for the output, and until this existed the only way to see a night's work was to plug the
 * phone into a computer.
 *
 * ### One slider, and it is not a toy
 *
 * The requirements ask for a single **strength** control and they are right to: the four numbers
 * underneath it — the polynomial degree, the two stretch points, the saturation — are meaningless
 * to someone who has just come in from a field, and the one thing they *do* want to say is
 * "brighter" or "less shouty". Everything else is behind one disclosure, which is where §1.36's
 * argument about the stacking panel applies again: the defaults are answers, and expanding is
 * opting into an argument rather than being handed one.
 *
 * ### Before/after is a press, not a second image on screen
 *
 * Side by side at phone width gives two images too small to judge. Holding the picture shows the
 * linear data as it came off the stack, releasing returns — the comparison is in the same pixels,
 * which is the only way to see what the edit actually did to them.
 */
@Composable
fun ResultScreen(
    state: ResultController.State,
    onStrength: (Double) -> Unit,
    onGradientDegree: (Int) -> Unit,
    onSaturation: (Double?) -> Unit,
    onToggleBefore: () -> Unit,
    onToggleAdvanced: () -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Night.Void)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Spacer(Modifier.height(16.dp)) }
        item {
            Text(
                "← ${state.folderName}",
                fontSize = 13.sp,
                color = Night.Txt3,
                modifier = Modifier.clickable(onClick = onBack),
            )
        }

        item {
            when {
                state.loading -> Card { Mono("Reading the linear master…", color = Night.Txt2, size = 11.sp) }
                state.error != null -> Card { Mono(state.error, color = Night.Warn, size = 11.sp) }
                state.ready -> Preview(state, onToggleBefore)
                else -> Card { Mono("Rendering…", color = Night.Txt2, size = 11.sp) }
            }
        }

        if (state.ready) {
            item { Eyebrow("Strength · FR-8.3") }
            item {
                Column {
                    NightSlider(state.settings.strength) { onStrength(it) }
                    Spacer(Modifier.height(6.dp))
                    Mono(
                        "%.0f%% · the sky sits at %.2f, colour ×%.2f".format(
                            state.settings.strength * 100,
                            state.settings.background,
                            state.settings.saturationBoost,
                        ),
                        color = Night.Txt3,
                        size = 10.5.sp,
                    )
                }
            }

            item {
                Text(
                    if (state.advanced) "Hide advanced" else "Advanced",
                    fontSize = 12.sp,
                    color = Night.Txt3,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onToggleAdvanced)
                        .padding(vertical = 8.dp),
                    textAlign = TextAlign.Center,
                )
            }

            if (state.advanced) {
                item { Eyebrow("Background model") }
                item {
                    Column {
                        ButtonRow {
                            (0..Gradient.MAX_DEGREE).forEach { degree ->
                                QuietButton(
                                    text = if (degree == 0) "Off" else "$degree",
                                    selected = degree == state.settings.gradientDegree,
                                    modifier = Modifier.weight(1f),
                                    onClick = { onGradientDegree(degree) },
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Card {
                            Mono(
                                "The lens darkens the corners, and that falloff follows a fourth " +
                                    "power — measured on this camera at 21 ADU in the middle " +
                                    "against 5 in the corners. Lower orders leave it behind; a " +
                                    "flat field (Phase 6) is the real answer.",
                                color = Night.Txt3,
                                size = 10.5.sp,
                                lineHeight = 15.sp,
                            )
                        }
                    }
                }

                item { Eyebrow("Saturation") }
                item {
                    ButtonRow {
                        listOf(null to "Auto", 1.0 to "None", 1.3 to "1.3×", 1.6 to "1.6×")
                            .forEach { (value, label) ->
                                QuietButton(
                                    text = label,
                                    selected = state.settings.saturation == value,
                                    modifier = Modifier.weight(1f),
                                    onClick = { onSaturation(value) },
                                )
                            }
                    }
                }

                state.report?.let {
                    item { Eyebrow("What it did") }
                    item { Card { Mono(it, color = Night.Txt3, size = 10.sp, lineHeight = 14.sp) } }
                }
            }

            item { Eyebrow("Save · FR-9.3") }
            item {
                Column {
                    HotButton(
                        text = if (state.busy != null) state.busy!! else "Save at full size",
                        enabled = state.busy == null,
                        onClick = onSave,
                    )
                    state.savedTo?.let {
                        Spacer(Modifier.height(8.dp))
                        Card { Mono("Saved to $it", color = Night.Txt2, size = 11.sp) }
                    }
                    Spacer(Modifier.height(6.dp))
                    Mono(
                        "The slider works on a reduced copy so it keeps up. Saving renders the " +
                            "full frame and puts it in your gallery.",
                        color = Night.Txt3,
                        size = 10.sp,
                        lineHeight = 14.sp,
                    )
                }
            }
        }

        item { Spacer(Modifier.height(40.dp)) }
    }
}

/** The picture. Press and hold to see the linear data it was made from. */
@Composable
private fun Preview(state: ResultController.State, onToggleBefore: () -> Unit) {
    val shown = if (state.showingBefore) state.linear ?: state.preview else state.preview
    Column {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(
                    (shown?.width?.toFloat() ?: 4f) / (shown?.height?.toFloat() ?: 3f),
                )
                .clip(RoundedCornerShape(10.dp))
                .background(Night.Void)
                .clickable(onClick = onToggleBefore),
            contentAlignment = Alignment.Center,
        ) {
            shown?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = "The stacked image",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Mono(
            if (state.showingBefore) {
                "Before — the linear stack, unstretched. Tap to return."
            } else {
                "Tap the image to see it before the edit."
            },
            color = Night.Txt3,
            size = 10.5.sp,
        )
    }
}

/**
 * A slider in the night palette.
 *
 * Hand-drawn for the same reason `NightTextField` is: Material's slider arrives with its own
 * container colours, ripple and thumb elevation, none of which can be talked down to one
 * full-intensity element per screen without overriding more of it than is left.
 */
@Composable
private fun NightSlider(value: Double, onChange: (Double) -> Unit) {
    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp),
    ) {
        val track = maxWidth
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectHorizontalDragGestures { change, _ ->
                        onChange((change.position.x / size.width).toDouble().coerceIn(0.0, 1.0))
                    }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(Night.Line, RoundedCornerShape(2.dp)),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth(value.toFloat().coerceIn(0f, 1f))
                    .height(3.dp)
                    .background(Night.Hot, RoundedCornerShape(2.dp)),
            )
            Box(
                modifier = Modifier
                    .padding(start = (track - THUMB) * value.toFloat().coerceIn(0f, 1f))
                    .height(THUMB)
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(THUMB / 2))
                    .background(Night.Hot),
            )
        }
    }
}

private val THUMB = 18.dp
