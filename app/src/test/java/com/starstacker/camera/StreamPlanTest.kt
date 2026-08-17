package com.starstacker.camera

import com.starstacker.device.SizePx
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * T-2.1's decisions, made from sizes alone. The reference device's numbers are used throughout
 * (4096x3072 RAW), because that is the configuration §1.6 measured and D-20 was found on.
 */
class StreamPlanTest {

    private val referenceRaw = listOf(SizePx(4096, 3072), SizePx(2048, 1536))
    private val referenceYuv = listOf(
        SizePx(4096, 3072), SizePx(1920, 1080), SizePx(1440, 1080),
        SizePx(1280, 720), SizePx(640, 480),
    )

    @Test
    fun `RAW is taken at maximum size — the data is the point`() {
        val plan = StreamPlanner.choose(referenceRaw, referenceYuv)
        assertEquals(SizePx(4096, 3072), plan.raw)
    }

    @Test
    fun `the analysis plane lands near the one megapixel budget`() {
        val plan = StreamPlanner.choose(referenceRaw, referenceYuv)

        assertEquals(4, plan.binFactor)
        assertEquals(SizePx(1024, 768), plan.analysis)
        val pixels = plan.analysis.width.toLong() * plan.analysis.height
        assertTrue(pixels <= StreamPlanner.TARGET_ANALYSIS_PIXELS, "analysis plane is $pixels px")
    }

    @Test
    fun `the bin factor is always even, because the CFA cell is two by two`() {
        val sizes = listOf(
            SizePx(4096, 3072), SizePx(8000, 6000), SizePx(1920, 1080), SizePx(640, 480),
        )
        for (size in sizes) {
            val factor = StreamPlanner.binFactorFor(size)
            assertEquals(0, factor % 2, "bin factor $factor for $size straddles the CFA cell")
            assertTrue(factor >= 2)
        }
    }

    @Test
    fun `a smaller sensor is binned less, not more`() {
        assertEquals(2, StreamPlanner.binFactorFor(SizePx(1920, 1080)))
        assertEquals(4, StreamPlanner.binFactorFor(SizePx(4096, 3072)))
        assertEquals(8, StreamPlanner.binFactorFor(SizePx(8000, 6000)))
    }

    @Test
    fun `the second stream matches the RAW aspect ratio and stays within preview bounds`() {
        val plan = StreamPlanner.choose(referenceRaw, referenceYuv)

        // 1440x1080 is 4:3 like the sensor; 1920x1080 is 16:9 and would imply a crop.
        assertEquals(SizePx(1440, 1080), plan.secondary)
        assertTrue(plan.secondary.width <= StreamPlanner.MAX_SECONDARY_WIDTH)
        assertTrue(plan.secondary.height <= StreamPlanner.MAX_SECONDARY_HEIGHT)
    }

    @Test
    fun `when nothing matches the aspect ratio the largest preview size is used anyway`() {
        val plan = StreamPlanner.choose(
            referenceRaw,
            listOf(SizePx(1920, 1080), SizePx(1280, 720)),
        )
        assertEquals(SizePx(1920, 1080), plan.secondary)
    }

    @Test
    fun `a device offering only large secondary sizes still gets a plan`() {
        val plan = StreamPlanner.choose(referenceRaw, listOf(SizePx(4096, 3072)))
        assertEquals(SizePx(4096, 3072), plan.secondary)
    }

    @Test
    fun `the reason names both streams, because it is what gets logged when a session fails`() {
        val reason = StreamPlanner.choose(referenceRaw, referenceYuv).reason
        assertTrue(reason.contains("4096x3072"), reason)
        assertTrue(reason.contains("1440x1080"), reason)
        assertTrue(reason.contains("1024x768"), reason)
    }
}
