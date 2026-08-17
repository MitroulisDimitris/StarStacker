package com.starstacker.focus

import com.starstacker.json.Json
import com.starstacker.json.boolean
import com.starstacker.json.double
import com.starstacker.json.float
import com.starstacker.json.int
import com.starstacker.json.long
import com.starstacker.json.objects
import com.starstacker.json.string
import java.io.File

/**
 * A stored infinity-focus position, per camera (FR-4.1.4).
 *
 * [altitudeDeg] is not decoration. A phone's lens sags under gravity, so a position calibrated
 * at 20° elevation is not the same position at the zenith; the record carries the elevation it
 * was found at so the verification step (T-2.5) can say *why* the stored value missed instead of
 * silently re-sweeping every session.
 */
data class FocusRecord(
    val cameraId: String,
    /** True for a lens with no motor — nothing to sweep and nothing to drift (FR-4.1.4.1). */
    val fixedFocus: Boolean,
    val diopters: Float,
    val hfr: Double,
    val starCount: Int,
    val altitudeDeg: Double?,
    val exposureNs: Long,
    val iso: Int,
    val verdict: String,
    val capturedAtEpochMs: Long,
) {
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "cameraId" to cameraId,
        "fixedFocus" to fixedFocus,
        "diopters" to diopters,
        "hfr" to hfr,
        "starCount" to starCount,
        "altitudeDeg" to altitudeDeg,
        "exposureNs" to exposureNs,
        "iso" to iso,
        "verdict" to verdict,
        "capturedAtEpochMs" to capturedAtEpochMs,
    )

    companion object {
        fun fromMap(map: Map<String, Any?>): FocusRecord? {
            val cameraId = map.string("cameraId") ?: return null
            return FocusRecord(
                cameraId = cameraId,
                fixedFocus = map.boolean("fixedFocus") ?: false,
                diopters = map.float("diopters") ?: 0f,
                hfr = map.double("hfr") ?: Double.NaN,
                starCount = map.int("starCount") ?: 0,
                altitudeDeg = map.double("altitudeDeg"),
                exposureNs = map.long("exposureNs") ?: 0L,
                iso = map.int("iso") ?: 0,
                verdict = map.string("verdict").orEmpty(),
                capturedAtEpochMs = map.long("capturedAtEpochMs") ?: 0L,
            )
        }
    }
}

/**
 * Per-camera focus records on disk. One small file, rewritten whole — there are at most a
 * handful of cameras and this is written once per calibration, not once per frame.
 *
 * Takes a [File] rather than a Context so it is testable on the JVM with a temp directory.
 */
class FocusStore(private val file: File) {

    fun load(): Map<String, FocusRecord> = runCatching {
        if (!file.exists()) return emptyMap()
        val root = Json.parseObject(file.readText())
        root.objects("cameras")
            .mapNotNull { FocusRecord.fromMap(it) }
            .associateBy { it.cameraId }
    }.getOrElse { emptyMap() }

    fun get(cameraId: String): FocusRecord? = load()[cameraId]

    fun save(record: FocusRecord) {
        val merged = load().toMutableMap()
        merged[record.cameraId] = record
        write(merged.values.sortedBy { it.cameraId })
    }

    fun clear(cameraId: String) {
        val merged = load().toMutableMap()
        merged.remove(cameraId)
        write(merged.values.sortedBy { it.cameraId })
    }

    private fun write(records: Collection<FocusRecord>) {
        file.parentFile?.mkdirs()
        val body = Json.write(
            linkedMapOf(
                "schemaVersion" to SCHEMA_VERSION,
                "cameras" to records.map { it.toMap() },
            ),
        )
        // Write-then-rename: a session that dies mid-write must not leave a truncated file
        // where a stored focus used to be.
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(body)
        if (!temp.renameTo(file)) {
            file.writeText(body)
            temp.delete()
        }
    }

    private companion object {
        const val SCHEMA_VERSION = 1
    }
}
