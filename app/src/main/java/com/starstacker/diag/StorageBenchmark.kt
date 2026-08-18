package com.starstacker.diag

import android.util.Log
import com.starstacker.session.SessionLayout
import com.starstacker.session.SessionStore
import kotlin.system.measureNanoTime

/**
 * T-0.5's acceptance measurement, feeding **OI-5**.
 *
 * Two numbers decide whether the SAF path is viable at all:
 *
 * 1. **Sustained write throughput for frame-sized files.** A sub is ~24 MiB (§1.6) and arrives
 *    every few seconds. If SAF cannot keep up, capture cadence becomes storage-bound and the
 *    planner's promised integration quietly shortens.
 * 2. **The cost of a full root scan.** FR-10.6.4 discovers sessions by scanning, and D-5 assumes
 *    a cached index is necessary. That assumption should be measured rather than inherited — if
 *    the scan is cheap, the index is complexity for nothing.
 *
 * Driven from adb rather than the UI because it writes gigabytes and nobody should reach it by
 * accident:
 *
 * ```
 * adb shell am start -n com.starstacker/.MainActivity \
 *   --es diag storage --ei files 200 --ei sizeMb 25
 * ```
 *
 * **It does not clean up.** The written session folder is left on disk and named in the log,
 * because a benchmark that deletes its own evidence cannot be checked afterwards with `ls`.
 * Delete it by hand when the numbers are recorded.
 */
object StorageBenchmark {

    private const val TAG = "StorageBenchmark"
    private const val MIB = 1024 * 1024

    data class Result(
        val store: String,
        val folder: String,
        val files: Int,
        val bytesEach: Int,
        val writeSeconds: Double,
        val scanSeconds: Double,
        val sessionsScanned: Int,
    ) {
        val megabytesPerSecond: Double
            get() = if (writeSeconds <= 0.0) 0.0
            else (files.toLong() * bytesEach) / MIB / writeSeconds

        val secondsPerFile: Double get() = if (files == 0) 0.0 else writeSeconds / files

        /** The line that goes into OI-5. */
        fun summary(): String =
            "%s · %d × %d MiB in %.1fs = %.1f MiB/s (%.3f s/file) · root scan of %d sessions in %.3fs · %s"
                .format(
                    store, files, bytesEach / MIB, writeSeconds, megabytesPerSecond,
                    secondsPerFile, sessionsScanned, scanSeconds, folder,
                )
    }

    /**
     * @param files how many frame-sized writes to perform
     * @param bytesEach size of each, defaulting to the measured 24 MiB of a real sub
     */
    fun run(store: SessionStore, files: Int = 200, bytesEach: Int = 24 * MIB): Result {
        val folderName = "_benchmark_${System.currentTimeMillis()}"
        val folder = store.createSession(folderName)

        // One buffer, reused. Allocating per file would measure the allocator as much as the
        // filesystem, and incompressible content stops any provider-side compression from
        // flattering the result.
        val payload = ByteArray(bytesEach).also { java.util.Random(1).nextBytes(it) }

        val writeNs = measureNanoTime {
            repeat(files) { index ->
                folder.createFrame(SessionLayout.LIGHTS, "bench_%04d.dng".format(index + 1))
                    .use { it.write(payload) }
            }
        }

        var scanned = 0
        val scanNs = measureNanoTime { scanned = store.listSessions().size }

        return Result(
            store = store::class.simpleName ?: "SessionStore",
            folder = folder.displayPath,
            files = files,
            bytesEach = bytesEach,
            writeSeconds = writeNs / 1e9,
            scanSeconds = scanNs / 1e9,
            sessionsScanned = scanned,
        ).also { Log.i(TAG, it.summary()) }
    }
}
