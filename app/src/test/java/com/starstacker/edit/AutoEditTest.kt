package com.starstacker.edit

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.random.Random

/**
 * T-7.1–T-7.4 — the auto-edit.
 *
 * The pipeline is judged on what it does to *synthetic* frames with known faults, because "does it
 * look right" is not a test. Each fixture has one thing wrong with it and the assertion is that the
 * one thing is fixed and nothing else is broken.
 */
class AutoEditTest {

    private val w = 96
    private val h = 72

    // ------------------------------------------------------------------------ gradient removal

    /** A flat sky with a linear ramp across it, plus noise. */
    private fun ramped(
        base: Double = 100.0,
        acrossX: Double = 40.0,
        acrossY: Double = 20.0,
        seed: Int = 7,
    ): FloatArray {
        val random = Random(seed)
        return FloatArray(w * h) { i ->
            val x = i % w
            val y = i / w
            (base + acrossX * x / w + acrossY * y / h + random.nextDouble(-1.0, 1.0)).toFloat()
        }
    }

    @Test
    fun `a linear ramp is flattened`() {
        val plane = ramped()
        val model = Gradient.fit(plane, w, h, degree = 1)
        assertNotNull(model)
        Gradient.subtract(plane, w, h, model!!)

        // Compare the mean of the left and right thirds: the ramp put 40 ADU between them.
        val left = columnMean(plane, 0, w / 3)
        val right = columnMean(plane, 2 * w / 3, w)
        assertTrue(abs(left - right) < 2.0, "ramp survived: left $left, right $right")
    }

    @Test
    fun `flattening preserves the overall level rather than dragging it to zero`() {
        // The stretch that follows works from the background level, so a gradient removal that
        // lands everything on zero would break it.
        val plane = ramped()
        val before = plane.average()
        val model = Gradient.fit(plane, w, h, degree = 1)!!
        Gradient.subtract(plane, w, h, model)

        assertEquals(before, plane.average(), 1.0)
    }

    @Test
    fun `a curved gradient needs the second degree`() {
        val plane = FloatArray(w * h) { i ->
            val x = (i % w).toDouble() / w - 0.5
            val y = (i / w).toDouble() / h - 0.5
            (100.0 + 80.0 * (x * x + y * y)).toFloat()
        }
        val flat = plane.copyOf()
        Gradient.subtract(flat, w, h, Gradient.fit(flat, w, h, degree = 1)!!)
        val curved = plane.copyOf()
        Gradient.subtract(curved, w, h, Gradient.fit(curved, w, h, degree = 2)!!)

        assertTrue(spread(curved) < spread(flat), "degree 2 should flatten a bowl better than degree 1")
        assertTrue(spread(curved) < 3.0, "residual ${spread(curved)}")
    }

    @Test
    fun `a bright object is not subtracted away`() {
        // The failure that matters: a model flexible enough to follow the sky will follow a galaxy
        // if the fit lets it. The tile percentile and the one-sided rejection are what stop it.
        val plane = ramped()
        val cx = w / 2
        val cy = h / 2
        for (y in cy - 8 until cy + 8) {
            for (x in cx - 8 until cx + 8) plane[y * w + x] += 300f
        }
        val before = plane[cy * w + cx]

        val model = Gradient.fit(plane, w, h, degree = 2)!!
        Gradient.subtract(plane, w, h, model)

        // The object must still stand well above its surroundings.
        val around = plane[(cy - 20) * w + cx]
        assertTrue(plane[cy * w + cx] - around > 250.0, "the object was eaten: ${plane[cy * w + cx] - around}")
        assertTrue(before > 0)
    }

    @Test
    fun `a frame with nothing to fit is refused rather than modelled`() {
        // Refusing beats extrapolating: a model that has lost its footing invents a surface.
        assertNull(Gradient.fit(FloatArray(4), 2, 2, degree = 2))
        assertNull(Gradient.fit(FloatArray(0), 0, 0))
    }

    @Test
    fun `the model reports how much of the frame it kept`() {
        val model = Gradient.fit(ramped(), w, h, degree = 2)!!
        assertTrue(model.used > 0 && model.used <= model.offered)
        assertTrue(model.describe().contains("degree 2"))
    }

    // ------------------------------------------------------------------------ the whole pipeline

    /** Three channels: an orange light-pollution sky with a ramp, and a few white stars. */
    private fun master(seed: Int = 11): FloatArray {
        val random = Random(seed)
        val out = FloatArray(w * h * 3)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val ramp = 30.0 * x / w
                val i = (y * w + x) * 3
                // Light pollution is orange: red well above blue.
                out[i] = (120.0 + ramp + random.nextDouble(-2.0, 2.0)).toFloat()
                out[i + 1] = (90.0 + ramp * 0.8 + random.nextDouble(-2.0, 2.0)).toFloat()
                out[i + 2] = (60.0 + ramp * 0.6 + random.nextDouble(-2.0, 2.0)).toFloat()
            }
        }
        // White stars, equal in all three channels.
        for (s in 0 until 40) {
            val x = random.nextInt(4, w - 4)
            val y = random.nextInt(4, h - 4)
            val i = (y * w + x) * 3
            val flux = random.nextDouble(400.0, 3000.0).toFloat()
            for (c in 0 until 3) out[i + c] += flux
        }
        return out
    }

    @Test
    fun `the linear master is never modified`() {
        // FR-8.2. Every step of this is destructive, so the thing it derives from has to survive.
        val original = master()
        val copy = original.copyOf()
        AutoEdit.render(original, w, h)
        assertTrue(original.contentEquals(copy), "the auto-edit wrote to the linear master")
    }

    @Test
    fun `an orange sky comes out grey`() {
        // Neutralisation is what makes the sky grey; without it the stretch lifts an orange fog.
        val (rgb, _) = AutoEdit.render(master(), w, h)
        val (r, g, b) = channelMedians(rgb)
        assertTrue(abs(r - g) < 12 && abs(g - b) < 12, "sky is not neutral: $r/$g/$b")
    }

    @Test
    fun `the sky is lifted off black without being washed out`() {
        val (rgb, report) = AutoEdit.render(master(), w, h)
        val (r, g, b) = channelMedians(rgb)
        val sky = (r + g + b) / 3
        assertTrue(sky > 10, "the sky is still black at $sky")
        assertTrue(sky < 140, "the sky is washed out at $sky")
        assertNotNull(report.stretch)
    }

    @Test
    fun `stars survive the stretch as the brightest thing in the frame`() {
        val (rgb, _) = AutoEdit.render(master(), w, h)
        val values = ByteArray(rgb.size) { rgb[it] }.map { it.toInt() and 0xFF }
        assertTrue(values.max() > 200, "no highlights survived: max ${values.max()}")
    }

    @Test
    fun `strength moves the background and nothing crashes at either end`() {
        val dark = AutoEdit.render(master(), w, h, AutoEdit.Settings(strength = 0.0)).first
        val bright = AutoEdit.render(master(), w, h, AutoEdit.Settings(strength = 1.0)).first

        val darkSky = channelMedians(dark).let { (r, g, b) -> (r + g + b) / 3 }
        val brightSky = channelMedians(bright).let { (r, g, b) -> (r + g + b) / 3 }
        assertTrue(brightSky > darkSky, "strength should lift the sky: $darkSky -> $brightSky")
    }

    @Test
    fun `the gradient step can be turned off`() {
        val (_, report) = AutoEdit.render(master(), w, h, AutoEdit.Settings(gradientDegree = 0))
        assertTrue(report.gradient.isEmpty())
        assertTrue(report.describe().contains("no gradient"))
    }

    @Test
    fun `saturation of one leaves a grey frame grey`() {
        // A boost applied to a neutral frame must not manufacture colour out of rounding.
        val flat = FloatArray(w * h * 3) { 100f }
        val (rgb, _) = AutoEdit.render(flat, w, h, AutoEdit.Settings(saturation = 1.0))
        val (r, g, b) = channelMedians(rgb)
        assertEquals(r, g)
        assertEquals(g, b)
    }

    @Test
    fun `a featureless frame renders without dividing by zero`() {
        val flat = FloatArray(w * h * 3) { 50f }
        val (rgb, _) = AutoEdit.render(flat, w, h)
        assertEquals(w * h * 3, rgb.size)
        assertTrue(rgb.all { it.toInt() and 0xFF <= 255 })
    }

    @Test
    fun `the report says what it did`() {
        val (_, report) = AutoEdit.render(master(), w, h)
        val text = report.describe()
        assertTrue(text.contains("strength"), text)
        assertTrue(text.contains("gradient"), text)
        assertTrue(text.contains("ADU"), text)
    }

    // --------------------------------------------------------------------------------- helpers

    private fun columnMean(plane: FloatArray, from: Int, until: Int): Double {
        var sum = 0.0
        var n = 0
        for (y in 0 until h) {
            for (x in from until until) { sum += plane[y * w + x]; n++ }
        }
        return sum / n
    }

    private fun spread(plane: FloatArray): Double {
        val mean = plane.average()
        return kotlin.math.sqrt(plane.sumOf { (it - mean) * (it - mean) } / plane.size)
    }

    private fun channelMedians(rgb: ByteArray): Triple<Int, Int, Int> {
        fun median(c: Int): Int {
            val values = IntArray(w * h) { rgb[it * 3 + c].toInt() and 0xFF }
            values.sort()
            return values[values.size / 2]
        }
        return Triple(median(0), median(1), median(2))
    }
}

/**
 * The session list telling the truth about its own contents — noticed on the phone, where a
 * session with a master in it still read `Captured`.
 */
class StackedBadgeTest {

    private fun log(state: com.starstacker.session.SessionState, stacking: Map<String, String>) =
        com.starstacker.session.SessionLog(
            com.starstacker.session.SessionInfo(
                sessionId = "s",
                startedAtEpochMs = 1L,
                deviceModel = "d",
                cameraId = "0",
                plannedIso = 3200,
                plannedExposureNs = 1L,
                plannedLightCount = 1,
                plannedDarkCount = 0,
                state = state,
                stacking = stacking,
            ),
        )

    @Test
    fun `a captured session invites the action the prototype asked for`() {
        val summary = com.starstacker.session.SessionSummary.of(
            "f", log(com.starstacker.session.SessionState.DONE, emptyMap()),
        )
        assertTrue(summary.badge == "Stack now", summary.badge)
        assertTrue(!summary.stacked)
    }

    @Test
    fun `a stacked session says so`() {
        val summary = com.starstacker.session.SessionSummary.of(
            "f", log(com.starstacker.session.SessionState.DONE, mapOf("master" to "stack_linear.tif")),
        )
        assertTrue(summary.badge == "Stacked", summary.badge)
        assertTrue(summary.stacked)
    }

    @Test
    fun `an unfinished session is not offered a stack`() {
        val summary = com.starstacker.session.SessionSummary.of(
            "f", log(com.starstacker.session.SessionState.PAUSED, emptyMap()),
        )
        assertTrue(summary.badge == "Unfinished", summary.badge)
    }
}
