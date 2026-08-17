package com.starstacker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.starstacker.ui.theme.Night
import com.starstacker.ui.theme.NumFamily

/**
 * The shared night-theme components (part of T-0.2).
 *
 * The rule the prototype enforces and this file has to keep enforceable: **exactly one
 * full-intensity element per screen**. [HotButton] is that element. Everything else is drawn in
 * the dimmer register, because dark adaptation takes twenty-five minutes and one bright control
 * ends it.
 */

@Composable
fun Card(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .border(1.dp, Night.LineSoft, RoundedCornerShape(12.dp))
            .background(Night.Surface2, RoundedCornerShape(12.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(13.dp),
    ) { content() }
}

@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        fontFamily = NumFamily,
        fontSize = 9.5.sp,
        letterSpacing = 2.2.sp,
        color = Night.Dim,
        modifier = modifier.padding(bottom = 6.dp),
    )
}

@Composable
fun Mono(
    text: String,
    color: Color,
    size: TextUnit = 10.5.sp,
    modifier: Modifier = Modifier,
) {
    Text(text, fontFamily = NumFamily, fontSize = size, color = color, modifier = modifier)
}

/** The one full-intensity control on a screen. If a screen needs two, one of them is wrong. */
@Composable
fun HotButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
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
        textAlign = TextAlign.Center,
    )
}

@Composable
fun QuietButton(
    text: String,
    enabled: Boolean = true,
    selected: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val tint = when {
        !enabled -> Night.Dim
        selected -> Night.Txt
        else -> Night.Txt2
    }
    Text(
        text,
        fontSize = 13.sp,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
        color = tint,
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, if (selected) Night.Red else Night.Line, RoundedCornerShape(11.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 12.dp),
        textAlign = TextAlign.Center,
    )
}

/** A label/value pair — the prototype's `plan` grid, which every readout card is built from. */
@Composable
fun KeyValue(label: String, value: String, warn: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 12.sp, color = Night.Txt3, modifier = Modifier.weight(1f))
        Mono(value, color = if (warn) Night.Warn else Night.Txt, size = 11.5.sp)
    }
}

/** A big number with a small unit — the live metrics grid. */
@Composable
fun Metric(label: String, value: String, unit: String? = null, warn: Boolean = false) {
    Column {
        Mono(label.uppercase(), color = Night.Dim, size = 8.5.sp)
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                value,
                fontFamily = NumFamily,
                fontSize = 19.sp,
                fontWeight = FontWeight.Medium,
                color = if (warn) Night.Warn else Night.Txt,
            )
            if (unit != null) {
                Mono(unit, color = Night.Txt3, size = 9.5.sp, modifier = Modifier.padding(start = 2.dp))
            }
        }
    }
}

@Composable
fun Badge(text: String, color: Color) {
    Text(
        text,
        fontFamily = NumFamily,
        fontSize = 8.5.sp,
        letterSpacing = 1.4.sp,
        color = color,
        modifier = Modifier
            .border(1.dp, color, RoundedCornerShape(5.dp))
            .padding(horizontal = 7.dp, vertical = 4.dp),
    )
}

/** A non-blocking note. It sits *below* whatever it comments on, so it can never read as a gate. */
@Composable
fun Banner(text: String, color: Color = Night.Warn) {
    Text(
        text,
        fontSize = 11.5.sp,
        color = color,
        lineHeight = 17.sp,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, color.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
            .padding(11.dp),
    )
}

@Composable
fun ButtonRow(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}
