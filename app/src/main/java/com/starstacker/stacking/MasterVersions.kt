package com.starstacker.stacking

import com.starstacker.json.Json
import com.starstacker.session.SessionLayout
import java.io.File

/**
 * T-6.5 / FR-10.4 — restacking without destroying what was there before.
 *
 * ### Why versioning at all
 *
 * FR-10.4 lets any session be restacked with different settings, and FR-10.4.1 makes that
 * non-destructive. That second clause is the whole feature: the point of trying `MEDIAN` against
 * `SIGMA_CLIP`, or a 90% cut against 95%, is to **look at both**, and a restack that overwrote the
 * first would leave nothing to compare against. It would also make the feature dangerous — twenty
 * minutes of CPU spent to find out the old settings were better, and the old master gone.
 *
 * ### The layout, and why the old one still works
 *
 * A version is a directory under `master/`:
 *
 * ```
 * master/
 *   versions.json     the index, and which version is current
 *   v1/stack_linear.tif, stack_stretched.jpg
 *   v2/...
 * ```
 *
 * **Sessions stacked before this existed have their files directly in `master/`**, and they keep
 * working: [currentDir] falls back to `master/` when there is no index, so nothing that reads a
 * master needs to know versioning happened. That fallback is not politeness — every session
 * already on the phone is in that state.
 *
 * ### What a version records
 *
 * The settings that produced it, in full ([StackSettings.toMap]), because FR-10.4's rule is that a
 * restack must **reproduce** a master rather than approximate it. The app's current preferences
 * are no use for that: they can have been changed since, which is exactly why the settings travel
 * with the output instead of being looked up.
 */
object MasterVersions {

    const val INDEX_FILE = "versions.json"

    /** Directory name for a version. `v1`, `v2`, … — ordered by name as well as by number. */
    fun dirName(id: Int): String = "v$id"

    data class Version(
        val id: Int,
        val createdAtEpochMs: Long,
        /** [StackSettings.toMap] — everything that decides what the pixels look like. */
        val settings: Map<String, String>,
        /** One line describing the settings, for a list that has no room for a map. */
        val label: String,
        val frames: Int,
        val region: String,
        val notes: List<String> = emptyList(),
    ) {
        fun dirName(): String = MasterVersions.dirName(id)

        fun describe(): String = "v$id · $label · $frames frames · $region"

        fun toJson(): Map<String, Any?> = linkedMapOf(
            "id" to id,
            "createdAt" to createdAtEpochMs,
            "settings" to settings,
            "label" to label,
            "frames" to frames,
            "region" to region,
            "notes" to notes,
        )

        companion object {
            fun fromJson(map: Map<*, *>): Version? {
                val id = (map["id"] as? Number)?.toInt() ?: return null
                @Suppress("UNCHECKED_CAST")
                val settings = (map["settings"] as? Map<*, *>)
                    ?.entries?.associate { (k, v) -> k.toString() to v.toString() }
                    ?: emptyMap()
                return Version(
                    id = id,
                    createdAtEpochMs = (map["createdAt"] as? Number)?.toLong() ?: 0L,
                    settings = settings,
                    label = map["label"]?.toString().orEmpty(),
                    frames = (map["frames"] as? Number)?.toInt() ?: 0,
                    region = map["region"]?.toString().orEmpty(),
                    notes = (map["notes"] as? List<*>)?.filterIsInstance<String>().orEmpty(),
                )
            }
        }
    }

    data class Index(
        val versions: List<Version> = emptyList(),
        /** Which version a reader should show. Null means the newest. */
        val currentId: Int? = null,
    ) {
        val current: Version? get() = versions.firstOrNull { it.id == currentId } ?: versions.maxByOrNull { it.id }

        fun nextId(): Int = (versions.maxOfOrNull { it.id } ?: 0) + 1

        fun encode(): String = Json.write(
            linkedMapOf(
                "current" to currentId,
                "versions" to versions.map { it.toJson() },
            ),
        )

        companion object {
            fun decode(text: String): Index {
                val root = runCatching { Json.parseObject(text) }.getOrNull() ?: return Index()
                val versions = (root["versions"] as? List<*>)
                    ?.filterIsInstance<Map<*, *>>()
                    ?.mapNotNull { Version.fromJson(it) }
                    .orEmpty()
                return Index(
                    versions = versions.sortedBy { it.id },
                    currentId = (root["current"] as? Number)?.toInt(),
                )
            }
        }
    }

    /** The index, or an empty one for a session that has never been versioned. */
    fun read(sessionDir: File): Index {
        val file = File(File(sessionDir, SessionLayout.MASTER), INDEX_FILE)
        if (!file.isFile) return Index()
        return runCatching { Index.decode(file.readText()) }.getOrDefault(Index())
    }

    fun write(sessionDir: File, index: Index) {
        val dir = File(sessionDir, SessionLayout.MASTER).apply { mkdirs() }
        File(dir, INDEX_FILE).writeText(index.encode())
    }

    /**
     * Where a reader should look for the current master.
     *
     * Falls back to `master/` itself, which is where every session stacked before versioning keeps
     * its files — see the class note.
     */
    fun currentDir(sessionDir: File): File {
        val master = File(sessionDir, SessionLayout.MASTER)
        val current = read(sessionDir).current ?: return master
        val dir = File(master, current.dirName())
        return if (dir.isDirectory) dir else master
    }

    /** Where a *new* stack should write, and the version it will become. */
    fun allocate(sessionDir: File): Pair<File, Int> {
        val index = read(sessionDir)
        val id = index.nextId()
        val dir = File(File(sessionDir, SessionLayout.MASTER), dirName(id)).apply { mkdirs() }
        return dir to id
    }

    /** Records a finished version and makes it current. */
    fun record(sessionDir: File, version: Version): Index {
        val index = read(sessionDir)
        val updated = Index(
            versions = (index.versions.filterNot { it.id == version.id } + version).sortedBy { it.id },
            currentId = version.id,
        )
        write(sessionDir, updated)
        return updated
    }

    /** Switches which version a reader sees, without restacking anything. */
    fun makeCurrent(sessionDir: File, id: Int): Index {
        val index = read(sessionDir)
        if (index.versions.none { it.id == id }) return index
        val updated = index.copy(currentId = id)
        write(sessionDir, updated)
        return updated
    }

    /**
     * Deletes a version's files and its entry.
     *
     * **Refuses to delete the last one**, because a session with an index and no versions reads as
     * a session that has never been stacked, and the fallback in [currentDir] would then point at
     * an empty `master/`. Someone who wants no masters at all should delete the masters, which is
     * [com.starstacker.session.StoragePlan]'s business.
     */
    fun delete(sessionDir: File, id: Int): Index {
        val index = read(sessionDir)
        if (index.versions.size <= 1) return index
        val version = index.versions.firstOrNull { it.id == id } ?: return index

        File(File(sessionDir, SessionLayout.MASTER), version.dirName()).deleteRecursively()
        val remaining = index.versions.filterNot { it.id == id }
        val updated = Index(
            versions = remaining,
            currentId = if (index.currentId == id) remaining.maxOfOrNull { it.id } else index.currentId,
        )
        write(sessionDir, updated)
        return updated
    }

    /**
     * What differs between two versions — the substance of FR-10.4's side-by-side comparison.
     *
     * Settings only. The *pixels* are what the screen shows side by side; what a person needs in
     * words is why they differ, and "sigma clip against median, best 95% against 100%" is that.
     */
    fun differences(a: Version, b: Version): List<String> {
        val keys = (a.settings.keys + b.settings.keys).sorted()
        val changes = keys.mapNotNull { key ->
            val left = a.settings[key]
            val right = b.settings[key]
            if (left == right) null else "$key: ${left ?: "—"} → ${right ?: "—"}"
        }
        return when {
            changes.isNotEmpty() -> changes
            a.frames != b.frames -> listOf("frames: ${a.frames} → ${b.frames}")
            // Same settings, same frame count: the difference is the calibration or the code, and
            // saying "identical" would be a claim this cannot support.
            else -> listOf("same settings — the inputs or the pipeline must have changed")
        }
    }
}
