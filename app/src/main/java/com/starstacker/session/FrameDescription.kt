package com.starstacker.session

import java.time.Instant
import java.util.Locale

/**
 * T-3.16 / §1.12 — the one line of text that makes a DNG able to say what it is.
 *
 * `DngCreator` writes `ImageDescription` (tag 270) and leaves it **empty** — measured, not
 * assumed: a real frame from session `2026-08-18_0050` carries 54 tags, all of them the sensor's
 * own description of itself, and tag 270 present with a zero-length value. That tag is the only
 * place an astro frame's *session* identity can go, because DNG has no vocabulary for frame kind,
 * sensor temperature or sub number; FITS does, and these are not FITS files.
 *
 * ### What is deliberately not here
 *
 * HFR, star count, eccentricity, background and the gate verdict are **absent, and cannot be
 * present**. The write ordering (§6) puts the bytes on disk *before* the pixels are analysed —
 * deliberately, so a 25 MB frame is safe before anything can go wrong measuring it — so at the
 * moment this string is built those numbers do not exist yet. Adding them would mean either
 * rewriting the file afterwards (the value lives in the IFD's value area; changing its length
 * moves every one of the 3072 `StripOffsets`) or analysing before writing, which trades a
 * guarantee for a nicety. `session.json` keeps them, and keeps them per frame.
 *
 * So this carries **identity and intent**: which session, which frame, what was asked of the
 * sensor and what the sensor was like at the time. That is the half that matters when a frame is
 * separated from its folder — a light that has wandered into `darks/` can still say it is a light.
 *
 * Format is one line of `key=value` in a stable order, which greps and diffs. It is read by
 * exiftool, Siril and PixInsight alike.
 */
object FrameDescription {

    /** Leading token so a frame is identifiable as this app's without parsing the rest. */
    const val MARKER = "StarStacker"

    fun of(
        sessionId: String,
        index: Int,
        kind: FrameKind,
        iso: Int,
        exposureNs: Long,
        capturedAtEpochMs: Long,
        temperatureC: Double? = null,
        thermalHeadroom: Double? = null,
        batteryPercent: Int? = null,
        focusDiopters: Float? = null,
    ): String {
        val parts = buildList {
            add(MARKER)
            add("session=$sessionId")
            add("frame=$index")
            add("kind=$kind")
            add("iso=$iso")
            add("exposure=${format("%.4f", exposureNs / 1e9)}s")
            add("utc=${Instant.ofEpochMilli(capturedAtEpochMs)}")
            temperatureC?.let { add("batteryTempC=${format("%.1f", it)}") }
            thermalHeadroom?.let { add("thermalHeadroom=${format("%.3f", it)}") }
            batteryPercent?.let { add("battery=$it%") }
            focusDiopters?.let { add("focusDiopters=${format("%.4f", it.toDouble())}") }
        }
        return parts.joinToString(" ")
    }

    /** Root locale so a comma-decimal device does not emit `7,3993` and break every parser. */
    private fun format(pattern: String, value: Double) = String.format(Locale.ROOT, pattern, value)
}
