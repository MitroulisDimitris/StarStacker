package com.starstacker.moon

import com.starstacker.synth.SyntheticMoon
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.hypot

/**
 * T-11.5's detector, and the three things every other lunar task depends on it getting right:
 * *where* the disc is, *how big* it is, and *whether it clipped*.
 */
class DiscTest {

    private fun find(frame: SyntheticMoon.Frame) = Disc.find(
        frame.pixels, frame.width, frame.height, SyntheticMoon.WHITE_LEVEL.toDouble(),
    )

    @Test
    fun `a full disc is found at the centre it was drawn at`() {
        val frame = SyntheticMoon.render(centreX = 128.0, centreY = 96.0, radiusPx = 40.0)
        val disc = checkNotNull(find(frame))

        // A full moon is symmetric, so the intensity centroid is the geometric centre. Craters
        // pull it around a little, which is why this is a pixel rather than a fraction of one.
        assertEquals(128.0, disc.centroidX, 1.5)
        assertEquals(96.0, disc.centroidY, 1.5)

        // The equivalent diameter should recover the drawn diameter. The threshold sits 25% up
        // from background so it cuts slightly inside the limb, hence the tolerance.
        assertEquals(80.0, disc.equivalentDiameterPx, 6.0)
        assertEquals(0, disc.clippedPixels, "a 700 ADU peak is nowhere near white")
    }

    @Test
    fun `an empty frame yields no disc rather than a spurious one`() {
        // Nothing but noise. A detector that returns *something* here would give the aligner a
        // random point to align on, which is worse than admitting it found nothing.
        val frame = SyntheticMoon.render(radiusPx = 0.0, craters = 0, noiseAdu = 3.0)
        assertNull(find(frame))
    }

    @Test
    fun `clipping is counted, which is what T-11_1 meters on`() {
        val frame = SyntheticMoon.render(peakAdu = 4000.0)
        val disc = checkNotNull(find(frame))
        assertTrue(disc.clippedPixels > 100, "a 4000 ADU peak against white 1023 must clip")
        assertEquals(SyntheticMoon.WHITE_LEVEL.toDouble(), disc.peakAdu, 0.5)
    }

    /**
     * The property the aligner actually relies on: the centroid must *move with* the disc.
     *
     * Absolute accuracy matters much less than this — an aligner only needs the same point
     * measured the same way in every frame.
     */
    @Test
    fun `the centroid tracks a translated disc`() {
        val a = SyntheticMoon.render(centreX = 100.0, centreY = 90.0, seed = 3)
        val b = SyntheticMoon.render(centreX = 117.0, centreY = 83.0, seed = 3)

        val da = checkNotNull(find(a))
        val db = checkNotNull(find(b))

        assertEquals(17.0, db.centroidX - da.centroidX, 0.5)
        assertEquals(-7.0, db.centroidY - da.centroidY, 0.5)
    }

    /**
     * A crescent's centroid is *not* the centre of the moon, and the detector should not pretend
     * otherwise — but it must still be stable, because that is all alignment needs.
     */
    @Test
    fun `a crescent centroid sits in the lit horn and is still repeatable`() {
        val crescent = SyntheticMoon.render(centreX = 128.0, phase = 0.18, seed = 11)
        val disc = checkNotNull(find(crescent))

        // Lit side is +x by the generator's construction, so the centroid must sit right of centre.
        assertTrue(
            disc.centroidX > 128.0 + 5,
            "a crescent's centroid should be well into the lit horn, was ${disc.centroidX}",
        )

        // Same phase, shifted: the offset must reproduce exactly.
        val moved = SyntheticMoon.render(centreX = 140.0, phase = 0.18, seed = 11)
        val movedDisc = checkNotNull(find(moved))
        assertEquals(12.0, movedDisc.centroidX - disc.centroidX, 0.5)
    }

    @Test
    fun `the bounding box contains the disc and the margin stays inside the frame`() {
        val frame = SyntheticMoon.render(centreX = 20.0, centreY = 20.0, radiusPx = 15.0)
        val disc = checkNotNull(find(frame))

        val box = disc.boxWithMargin(30, frame.width, frame.height)
        assertTrue(box[0] >= 0 && box[1] >= 0, "margin must clamp at the frame edge")
        assertTrue(box[2] < frame.width && box[3] < frame.height)
        assertTrue(box[2] > box[0] && box[3] > box[1], "box must be non-empty")
    }

    /** Blur moves light around; it must not move the *centre* of it. */
    @Test
    fun `blur does not shift the centroid`() {
        val sharp = SyntheticMoon.render(seed = 5, blurSigma = 0.0, noiseAdu = 0.0)
        val soft = SyntheticMoon.render(seed = 5, blurSigma = 3.0, noiseAdu = 0.0)

        val a = checkNotNull(find(sharp))
        val b = checkNotNull(find(soft))
        assertTrue(
            hypot(a.centroidX - b.centroidX, a.centroidY - b.centroidY) < 1.0,
            "blur shifted the centroid from (${a.centroidX}, ${a.centroidY}) to " +
                "(${b.centroidX}, ${b.centroidY})",
        )
    }

    @Test
    fun `a fully clipped disc still yields a usable centroid`() {
        // Every lit pixel at white: the intensity weighting degenerates to a plain area centroid,
        // which is the correct answer when there is no intensity information left.
        val frame = SyntheticMoon.render(centreX = 110.0, centreY = 88.0, peakAdu = 50_000.0)
        val disc = checkNotNull(find(frame))
        assertTrue(abs(disc.centroidX - 110.0) < 3.0, "centroid ${disc.centroidX}")
        assertTrue(abs(disc.centroidY - 88.0) < 3.0, "centroid ${disc.centroidY}")
    }
}
