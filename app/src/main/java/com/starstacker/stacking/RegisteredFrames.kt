package com.starstacker.stacking

import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile

/**
 * §1.38's fix — every frame warped into reference coordinates **once**, held on disk, and read back
 * by row range with no margin at all.
 *
 * ### Why this exists, measured rather than reasoned
 *
 * T-5.3's loop warped inside the tile: for each output tile, each frame's source band was read,
 * calibrated, debayered and warped. That band has to be taller than the tile, because under
 * rotation the rows feeding an output row are spread over `width × sin θ`. On the first real
 * session — 114 frames, 3.72° of field rotation — the sample budget gave an **8-row tile** against
 * a **220-row margin**, so every band did 328 rows of work to produce 8. **41× amplification, and
 * about 61 minutes for one stack**, of which a quarter was warping pixels that were discarded.
 *
 * The two numbers move the wrong way: the margin grows with session length, and the tile shrinks as
 * frames are added, so the waste is worst exactly when a session is best.
 *
 * Warping once breaks the coupling. After this pass every frame is in the **same** coordinate
 * system, so the combine reads row `y` from each frame and no margin exists to amplify.
 *
 * ### The cost, and why it is worth paying
 *
 * A registered frame is `width × height × 3` floats — 151 MB at 12.6 MP, so 114 frames is **17 GB**
 * of temporary files. That is a lot, and it buys turning `frames × tiles` warps into `frames`: on
 * the measured session, 43 776 band-warps become 114 frame-warps.
 *
 * **Float rather than 16-bit**, deliberately. Half the space is tempting and the data would survive
 * it, but T-5.2 keeps negative values on purpose — clamping them biases the background upward
 * non-uniformly — and an integer intermediate is exactly the clamp that FR-8.1 step 5 then has to
 * undo. The saving is about thirty seconds of I/O on an eight-minute job; the risk is a bias that
 * nothing downstream could detect.
 *
 * ### One file per frame
 *
 * Rather than one 17 GB file, because a single file that large is a bet on the filesystem, and
 * because a failed run can then delete what it wrote without understanding it. The files are raw:
 * no header, three interleaved channels, row-major, little-endian float. Nothing else reads them,
 * they never outlive the stack that made them, and a header would be a format to maintain.
 *
 * **They are deleted on [close], whatever happened.** 17 GB of orphaned scratch after a crash is
 * worse than a failed stack.
 */
class RegisteredFrames private constructor(
    private val directory: File,
    val width: Int,
    val height: Int,
    val count: Int,
) : Closeable {

    private val files = List(count) { File(directory, "%04d.reg".format(it)) }
    private val readers = arrayOfNulls<RandomAccessFile>(count)

    /** Bytes in one row of one frame: three interleaved channels of float. */
    private val rowBytes = width.toLong() * CHANNELS * 4

    /** Bytes one registered frame occupies. */
    val frameBytes: Long get() = rowBytes * height

    /** What the whole intermediate will take. Checked against free space before a stack starts. */
    val totalBytes: Long get() = frameBytes * count

    /**
     * Appends [rows] rows of frame [index], which must arrive in order from row 0.
     *
     * Sequential by contract rather than by seek, because the register pass produces bands top to
     * bottom and a buffered append is several times faster than a positioned write per band.
     */
    fun write(index: Int, out: BufferedOutputStream, data: FloatArray, rows: Int) {
        val samples = width * rows * CHANNELS
        val bytes = ByteArray((rowBytes * rows).toInt())
        // Through a FloatBuffer rather than shifting each float by hand. The hand-rolled loop is
        // four bytes per sample over 17 GB — 4.3 billion iterations for one stack — and it was
        // most of the gap between the combine's measured 950 s and the 370 s the profile said the
        // arithmetic costs. `put(FloatArray)` is a bulk copy the JIT turns into a memcpy.
        java.nio.ByteBuffer.wrap(bytes)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .asFloatBuffer()
            .put(data, 0, samples)
        out.write(bytes)
    }

    /** Opens frame [index] for appending. The caller closes it when the frame is complete. */
    fun writer(index: Int): BufferedOutputStream =
        BufferedOutputStream(files[index].outputStream(), WRITE_BUFFER)

    /**
     * Reads rows `[fromRow, fromRow + rowCount)` of registered frame [index] into [into].
     *
     * **No margin**, which is the whole point: the frame is already in reference coordinates, so
     * output row `y` is input row `y`.
     *
     * @return rows actually read, fewer at the bottom edge.
     */
    fun rows(index: Int, fromRow: Int, rowCount: Int, into: FloatArray): Int {
        val available = (height - fromRow).coerceAtLeast(0)
        val rows = minOf(rowCount, available)
        if (rows <= 0) return 0
        require(into.size >= width * rows * CHANNELS) { "buffer holds ${into.size}" }

        val file = readers[index] ?: RandomAccessFile(files[index], "r").also { readers[index] = it }
        val length = (rowBytes * rows).toInt()
        val bytes = scratchBytes(length)
        file.seek(fromRow * rowBytes)
        file.readFully(bytes, 0, length)

        // Bulk, for the same reason as [write]: this runs over every byte of a 17 GB intermediate.
        java.nio.ByteBuffer.wrap(bytes, 0, length)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .asFloatBuffer()
            .get(into, 0, width * rows * CHANNELS)
        return rows
    }

    /** Grown once and reused: a fresh 40 MB array per tile per frame is pure garbage. */
    private var scratchBytes = ByteArray(0)

    private fun scratchBytes(length: Int): ByteArray {
        if (scratchBytes.size < length) scratchBytes = ByteArray(length)
        return scratchBytes
    }

    /** Closes every reader and deletes the scratch. Safe to call twice. */
    override fun close() {
        readers.forEachIndexed { i, reader ->
            runCatching { reader?.close() }
            readers[i] = null
        }
        files.forEach { runCatching { it.delete() } }
        runCatching { directory.delete() }
    }

    companion object {
        const val CHANNELS = TiledStacker.CHANNELS
        private const val WRITE_BUFFER = 1 shl 20

        /**
         * Creates the scratch directory for one stack.
         *
         * @param parent where to put it — the session's own folder, so the space used is visible
         *   beside the frames it came from rather than hidden in app-private storage.
         * @return null if the space is not there, which is a refusal rather than a crash: 17 GB is
         *   enough that a phone can genuinely not have it, and finding out at frame 90 is worse
         *   than finding out at frame 0.
         */
        fun create(parent: File, width: Int, height: Int, count: Int): RegisteredFrames? {
            val dir = File(parent, SCRATCH_DIRECTORY)
            // A previous run that died without closing leaves this behind; it is scratch, so
            // clearing it is safe and is the only way a retry can succeed on a full disk.
            dir.listFiles()?.forEach { runCatching { it.delete() } }
            if (!dir.isDirectory && !dir.mkdirs()) return null

            val frames = RegisteredFrames(dir, width, height, count)
            val free = runCatching { dir.usableSpace }.getOrDefault(0L)
            if (free < frames.totalBytes + HEADROOM_BYTES) {
                dir.delete()
                return null
            }
            return frames
        }

        /** Named so it is obviously scratch if anyone finds one after a crash. */
        const val SCRATCH_DIRECTORY = "registered.tmp"

        /** Slack so the master itself still fits after the intermediates are written. */
        private const val HEADROOM_BYTES = 512L * 1024 * 1024
    }
}
