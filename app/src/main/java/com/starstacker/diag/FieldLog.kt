package com.starstacker.diag

import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * T-0.6 — the log that survives the night.
 *
 * **Why this exists, stated as the thing that actually happened.** On 2026-08-18 three separate
 * defects were found in the bump detector, and *not one of them crashed or showed a symptom*: the
 * exposure window sat in the future, the gyro delivered at 400 Hz rather than the ~50 Hz its
 * constant implied, and the zero-rate estimate was seeded from a moving sample. All three were
 * caught by reading `logcat` over USB. A session that fails at 2 a.m. in a field has no USB and no
 * logcat — that ring buffer is a few MB shared across every process on the device, and it is long
 * gone by morning. Without a file on disk the report is "it stopped" and nothing else.
 *
 * ### Three sources, because each misses what the others catch
 *
 * 1. **A `logcat` tee** filtered to this process. Every existing `Log.i/w/e` call in the app lands
 *    in the file with no call site touched — including the runtime's own `FATAL EXCEPTION` dump,
 *    which the framework writes and which never passes through any handler of ours.
 * 2. **[write]**, for deliberate entries that should be there whether or not the tee is running.
 * 3. **An uncaught-exception handler**, writing the trace directly and flushed before delegating.
 *    The tee is a pipe between two processes and a crash can outrun it. This is the belt to its
 *    braces, and it guards the one event that only happens once.
 *
 * Reading its own logs needs no permission: an app has been able to read exactly its own process
 * and nothing else since Android 4.1, which is precisely the scope wanted here.
 *
 * ### Rolling, because unbounded is its own failure
 *
 * Two files of [MAX_BYTES] each. A 45-minute session at a few lines per frame sits far inside one,
 * and the rolled file is kept so a crash that restarts the app cannot erase the reason for it.
 */
object FieldLog {

    private const val TAG = "FieldLog"
    private const val DIRECTORY = "logs"
    private const val CURRENT = "field.log"
    private const val PREVIOUS = "field.1.log"

    /** 1 MiB each. Two of them is a rounding error beside one 24 MiB sub. */
    private const val MAX_BYTES = 1L shl 20

    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.ROOT)
    private val lock = Any()

    @Volatile
    private var directory: File? = null

    @Volatile
    private var started = false

    private var reader: java.lang.Process? = null

    /** Idempotent — safe from both the Application and a service that may outlive it. */
    fun start(context: Context) {
        synchronized(lock) {
            if (started) return
            started = true
            directory = File(context.getExternalFilesDir(null) ?: context.filesDir, DIRECTORY)
                .apply { mkdirs() }
        }

        write("I", TAG, banner(context))
        installCrashHandler()
        startTee()
    }

    private fun banner(context: Context) = buildString {
        append("--- StarStacker log opened ")
        append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date()))
        append(" - ${Build.MANUFACTURER} ${Build.MODEL}")
        append(" - Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        append(" - pid ${Process.myPid()}")
        append(" - ${context.packageName} ---")
    }

    /**
     * The runtime writes its own `FATAL EXCEPTION` to logcat, but a crash can outrun a pipe
     * between processes, so the trace is written here directly too and flushed before the previous
     * handler is given the exception. Delegating matters: swallowing it would leave the process
     * alive in an undefined state rather than dying honestly.
     */
    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val trace = StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }
                write("E", TAG, "CRASH on thread ${thread.name}\n$trace")
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    /**
     * `-T 1` starts from the newest line rather than replaying the whole buffer, which would
     * otherwise copy megabytes of unrelated history into the file on every launch.
     */
    private fun startTee() {
        runCatching {
            val process = ProcessBuilder(
                "logcat", "--pid=${Process.myPid()}", "-v", "threadtime", "-T", "1",
            ).redirectErrorStream(true).start()
            reader = process
            thread(isDaemon = true, name = "field-log-tee") {
                runCatching {
                    process.inputStream.bufferedReader().forEachLine { append(it + "\n") }
                }.onFailure { Log.w(TAG, "log tee ended", it) }
            }
        }.onFailure {
            // Not fatal: [write] and the crash handler still produce a usable log.
            Log.w(TAG, "could not start the logcat tee", it)
            write("W", TAG, "logcat tee unavailable (${it.message}); deliberate entries only")
        }
    }

    /** Writes an entry to the file *and* to logcat, so nothing is lost by preferring this. */
    fun write(level: String, tag: String, message: String) {
        when (level) {
            "E" -> Log.e(tag, message)
            "W" -> Log.w(tag, message)
            else -> Log.i(tag, message)
        }
        // The tee will duplicate this line. It is written directly anyway, because the tee may
        // not be running at all, and a duplicated line is a far smaller problem than a missing one.
        append("${stamp.format(Date())} $level/$tag: $message\n")
    }

    private fun append(text: String) {
        val dir = directory ?: return
        synchronized(lock) {
            runCatching {
                val current = File(dir, CURRENT)
                if (current.length() > MAX_BYTES) {
                    val rolled = File(dir, PREVIOUS)
                    rolled.delete()
                    current.renameTo(rolled)
                }
                File(dir, CURRENT).appendText(text)
            }
        }
    }

    /** Newest first, for the viewer. Reaches into the rolled file when the current one is short. */
    fun tail(lines: Int = 200): List<String> {
        val dir = directory ?: return emptyList()
        return synchronized(lock) {
            runCatching {
                val current = File(dir, CURRENT).takeIf { it.isFile }?.readLines().orEmpty()
                val all = if (current.size >= lines) {
                    current
                } else {
                    File(dir, PREVIOUS).takeIf { it.isFile }?.readLines().orEmpty() + current
                }
                all.takeLast(lines).asReversed()
            }.getOrDefault(emptyList())
        }
    }

    /** The file to share. Null before [start], or if nothing has been written yet. */
    fun currentFile(): File? = directory?.let { File(it, CURRENT) }?.takeIf { it.isFile }

    fun sizeBytes(): Long {
        val dir = directory ?: return 0L
        return File(dir, CURRENT).length() + File(dir, PREVIOUS).length()
    }

    fun stop() {
        runCatching { reader?.destroy() }
        reader = null
    }
}
