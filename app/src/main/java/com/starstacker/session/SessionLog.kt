package com.starstacker.session

import com.starstacker.json.Json
import com.starstacker.json.boolean
import com.starstacker.json.double
import com.starstacker.json.int
import com.starstacker.json.long
import com.starstacker.json.objects
import com.starstacker.json.string

/**
 * FR-9.2 — `session.json`, which D-5 makes **the source of truth**. There is no database; a
 * session is whatever its folder says it is, including a folder copied back from a PC (FR-10.6.4).
 *
 * That makes this file two things at once, and the requirements say so plainly: *"both the
 * debugging tool and the expert's audit trail"*. Two consequences follow and neither is optional.
 *
 * **It is written incrementally, not at the end.** A session that dies at 03:40 must still leave a
 * log describing every frame written before it died — otherwise the 200 DNGs on disk are
 * unattributed and the night is only half-recovered. T-3.7's acceptance is exactly this: kill the
 * process mid-session and the log still describes everything.
 *
 * **Nothing is ever deleted or hidden.** Rejected frames are recorded with the reason they were
 * rejected and stay on disk beside the accepted ones (D-10, FR-7.5), because a rejection rule is
 * a judgement and the user is entitled to disagree with it later.
 */

enum class RejectReason {
    TRAILED,
    CLOUD,
    BUMPED,
    SATURATED,
    /** Registration failed or the residual was too large — Phase 2 fills this in. */
    REGISTRATION,
    METADATA_MISMATCH,
}

enum class FrameKind { LIGHT, DARK }

/**
 * T-11.8 — what the session is pointed at, which decides how the app meters and stacks.
 *
 * **Not a camera choice.** The camera picker is untouched by this: the target type changes how the
 * app *meters and stacks*, never what the user is allowed to point (§1.45). Someone shooting a
 * moon-in-scene on an ultrawide is making a framing decision, and the mode's job is to expose it
 * correctly, not to argue.
 *
 * **Why three and not two.** [MOON_DISC] and [MOON_SCENE] differ by more than framing: one shoots
 * a single metered set where a black background is the *correct* answer, and the other brackets two
 * interleaved sets because a black background would be a failure. The scene is 14.2 stops wide
 * against a sensor spanning 10, so no single exposure serves both — which makes this a choice the
 * app cannot infer from a viewfinder and must therefore ask.
 *
 * Recorded in `session.json` because FR-10.4 requires a restack to *reproduce* a master rather than
 * approximate it, and a moon session run back through the deep-sky pipeline would be stretched,
 * gradient-corrected and ranked on star counts that do not exist.
 */
enum class TargetType(val label: String, val summary: String) {
    DEEP_SKY(
        "Deep sky",
        "Faint extended targets. Long subs, gradient removal, HFR-ranked frames.",
    ),
    MOON_DISC(
        "Moon: disc",
        "The moon filling the frame. One metered set; a black background is correct.",
    ),
    MOON_SCENE(
        "Moon in scene",
        "The moon and a landscape together. Two interleaved sets, because one exposure " +
            "cannot hold both.",
    ),
    ;

    val isLunar: Boolean get() = this != DEEP_SKY

    /** True when the session shoots two exposures rather than one — T-11.9. */
    val isBracketed: Boolean get() = this == MOON_SCENE
}

/**
 * Which half of a bracketed session a frame belongs to — T-11.9.
 *
 * A separate axis from [FrameKind], deliberately: light-versus-dark and moon-versus-ground are
 * independent questions, and a bracketed session has darks for *both* exposures. Null on an
 * unbracketed session, where there is only one set and naming it would be noise.
 */
enum class ExposureSet(val label: String) {
    /** Short, metered on the disc. */
    MOON("moon"),

    /** Long, metered on the ground — an ordinary deep-sky exposure pointed at a landscape. */
    GROUND("ground"),
}

/** One frame, exactly as FR-9.2 enumerates it. */
data class FrameRecord(
    val index: Int,
    val fileName: String,
    val kind: FrameKind,
    val capturedAtEpochMs: Long,
    val iso: Int,
    val exposureNs: Long,
    /** Battery temperature in °C — D-16's dark-matching key. Null when unavailable. */
    val temperatureC: Double?,
    val hfr: Double?,
    val starCount: Int?,
    val eccentricity: Double?,
    val backgroundAdu: Double?,
    /**
     * T-11.6 — normalised sharpness over the lunar disc, for moon mode's keep-best cut.
     *
     * Null on a deep-sky frame, where [hfr] is the sharpness measure and this would be meaningless:
     * measured over a star field rather than an extended object, the number would be dominated by
     * sensor noise, which is high-frequency and would rank the *noisiest* frame as the sharpest.
     */
    val sharpness: Double? = null,
    /** T-11.9 — which half of a bracketed session this frame belongs to. Null when unbracketed. */
    val exposureSet: ExposureSet? = null,
    val accepted: Boolean,
    val rejectReason: RejectReason? = null,
    /** Free text alongside the reason — the actual numbers that tripped it. */
    val rejectDetail: String? = null,
    /** Phase 2 (T-4.x) fills this: [a, b, c, d, tx, ty] relative to the reference frame. */
    val transform: List<Double>? = null,
    /** Thermal headroom at capture time, 0–1, if the platform reported it. */
    val thermalHeadroom: Double? = null,
    val batteryPercent: Int? = null,
) {
    fun toJson(): Map<String, Any?> = linkedMapOf(
        "index" to index,
        "file" to fileName,
        "kind" to kind.name,
        "capturedAt" to capturedAtEpochMs,
        "iso" to iso,
        "exposureNs" to exposureNs,
        "temperatureC" to temperatureC,
        "hfr" to hfr,
        "starCount" to starCount,
        "eccentricity" to eccentricity,
        "backgroundAdu" to backgroundAdu,
        "sharpness" to sharpness,
        "exposureSet" to exposureSet?.name,
        "accepted" to accepted,
        "rejectReason" to rejectReason?.name,
        "rejectDetail" to rejectDetail,
        "transform" to transform,
        "thermalHeadroom" to thermalHeadroom,
        "batteryPercent" to batteryPercent,
    )

    companion object {
        fun fromJson(map: Map<String, Any?>): FrameRecord = FrameRecord(
            index = map.int("index") ?: 0,
            fileName = map.string("file").orEmpty(),
            kind = map.string("kind")?.let { runCatching { FrameKind.valueOf(it) }.getOrNull() }
                ?: FrameKind.LIGHT,
            capturedAtEpochMs = map.long("capturedAt") ?: 0L,
            iso = map.int("iso") ?: 0,
            exposureNs = map.long("exposureNs") ?: 0L,
            temperatureC = map.double("temperatureC"),
            hfr = map.double("hfr"),
            starCount = map.int("starCount"),
            eccentricity = map.double("eccentricity"),
            backgroundAdu = map.double("backgroundAdu"),
            sharpness = map.double("sharpness"),
            exposureSet = map.string("exposureSet")
                ?.let { runCatching { ExposureSet.valueOf(it) }.getOrNull() },
            accepted = map.boolean("accepted") ?: true,
            rejectReason = map.string("rejectReason")
                ?.let { runCatching { RejectReason.valueOf(it) }.getOrNull() },
            rejectDetail = map.string("rejectDetail"),
            transform = (map["transform"] as? List<*>)?.mapNotNull { (it as? Number)?.toDouble() },
            thermalHeadroom = map.double("thermalHeadroom"),
            batteryPercent = map.int("batteryPercent"),
        )
    }
}

/** The session-level half of FR-9.2 — everything needed to reproduce or audit the run. */
data class SessionInfo(
    val schemaVersion: Int = SCHEMA_VERSION,
    val sessionId: String,
    /**
     * T-3.30 — what the user called this session, kept in full.
     *
     * Separate from [sessionId] because the folder deliberately does not always carry it: a
     * session named for the day would otherwise put the date in its own name twice
     * ([SessionLayout.folderName]). The label is what the list shows and what the user typed, so
     * it is stored rather than re-derived from a folder name that may have dropped it.
     */
    val label: String = "",
    val startedAtEpochMs: Long,
    val finishedAtEpochMs: Long? = null,
    val deviceModel: String,
    val cameraId: String,
    val plannedIso: Int,
    val plannedExposureNs: Long,
    val plannedLightCount: Int,
    val plannedDarkCount: Int,
    /**
     * T-11.8 — what this session was pointed at, and therefore which pipeline made its master.
     *
     * Defaulted rather than nullable: every session written before this existed was a deep-sky
     * session, so the default is the truth about them rather than a guess.
     */
    val targetType: TargetType = TargetType.DEEP_SKY,
    /**
     * T-11.9 — the *second* exposure of a bracketed session, metered on the ground.
     *
     * [plannedIso] and [plannedExposureNs] describe the moon set, which is the one the session is
     * named for. Null unless [targetType] is bracketed.
     */
    val groundIso: Int? = null,
    val groundExposureNs: Long? = null,
    val groundLightCount: Int? = null,
    /** FR-5.3's derivation, flattened to lines so the audit trail keeps the reasoning. */
    val exposureDerivation: List<String> = emptyList(),
    val latitudeDeg: Double? = null,
    val longitudeDeg: Double? = null,
    val altitudeDeg: Double? = null,
    val azimuthDeg: Double? = null,
    val declinationDeg: Double? = null,
    val fieldRotationArcsecPerSec: Double? = null,
    /**
     * Compass accuracy at the moment the pointing was taken. Recorded because a declination
     * cannot be judged afterwards without it — see [SessionPointing].
     */
    val compassAccuracy: String? = null,
    val focusDiopters: Float? = null,
    val focusHfr: Double? = null,
    /** Null until calibration exists (Phase 6) — recorded so a restack knows what was applied. */
    val calibrationVersions: Map<String, String> = emptyMap(),
    /**
     * T-5.6 — how the master was produced: combiner method, crop mode, the region written.
     *
     * Written when a stack completes rather than when the session is captured, and separate from
     * [calibrationVersions] because it describes an *output* and not an input. FR-9.2's rule is
     * that a restack must reproduce a master rather than approximate it, so every choice that
     * changes the pixels goes here — the app's current settings are no use, since they can have
     * been changed since.
     */
    val stacking: Map<String, String> = emptyMap(),
    val state: SessionState = SessionState.CAPTURING,
) {
    companion object {
        const val SCHEMA_VERSION = 1
    }
}

/**
 * T-3.6's state machine, and also what T-3.13 reads on restart to decide whether to offer a
 * resume. It is written into the log rather than held in memory only, because the process that
 * knew the state is precisely the one that may not exist any more.
 */
enum class SessionState {
    IDLE,
    FOCUSING,
    CAPTURING,
    PAUSED,

    /**
     * Lights are done and the app is waiting for the lens to be covered (FR-4.2.1). A real state
     * rather than a UI flag: the session can be killed while waiting, and on restart it has to
     * come back knowing it owes darks and not lights.
     */
    AWAITING_DARKS,
    DARKS,
    FINALISING,
    DONE,
    FAILED,
}

/**
 * The whole document. Immutable — [withFrame] returns a new log — so the writer can serialise a
 * consistent snapshot while capture carries on.
 */
data class SessionLog(
    val info: SessionInfo,
    val frames: List<FrameRecord> = emptyList(),
) {
    val lights: List<FrameRecord> get() = frames.filter { it.kind == FrameKind.LIGHT }
    val darks: List<FrameRecord> get() = frames.filter { it.kind == FrameKind.DARK }
    val accepted: List<FrameRecord> get() = lights.filter { it.accepted }

    /** Integration actually banked, seconds — accepted lights only. */
    val acceptedIntegrationSeconds: Double
        get() = accepted.sumOf { it.exposureNs.toDouble() } / 1e9

    fun withFrame(frame: FrameRecord): SessionLog = copy(frames = frames + frame)

    fun withState(state: SessionState, finishedAtEpochMs: Long? = null): SessionLog =
        copy(info = info.copy(state = state, finishedAtEpochMs = finishedAtEpochMs ?: info.finishedAtEpochMs))

    fun encode(): String = Json.write(
        linkedMapOf(
            "schemaVersion" to info.schemaVersion,
            "sessionId" to info.sessionId,
            "label" to info.label,
            "state" to info.state.name,
            "startedAt" to info.startedAtEpochMs,
            "finishedAt" to info.finishedAtEpochMs,
            "device" to info.deviceModel,
            "cameraId" to info.cameraId,
            "plan" to linkedMapOf(
                "iso" to info.plannedIso,
                "exposureNs" to info.plannedExposureNs,
                "lightCount" to info.plannedLightCount,
                "darkCount" to info.plannedDarkCount,
                "exposureDerivation" to info.exposureDerivation,
                "targetType" to info.targetType.name,
                "groundIso" to info.groundIso,
                "groundExposureNs" to info.groundExposureNs,
                "groundLightCount" to info.groundLightCount,
            ),
            "pointing" to linkedMapOf(
                "latitude" to info.latitudeDeg,
                "longitude" to info.longitudeDeg,
                "altitude" to info.altitudeDeg,
                "azimuth" to info.azimuthDeg,
                "declination" to info.declinationDeg,
                "compassAccuracy" to info.compassAccuracy,
                "fieldRotationArcsecPerSec" to info.fieldRotationArcsecPerSec,
            ),
            "focus" to linkedMapOf(
                "diopters" to info.focusDiopters,
                "hfr" to info.focusHfr,
            ),
            "calibration" to info.calibrationVersions,
            "stacking" to info.stacking,
            "summary" to linkedMapOf(
                "lights" to lights.size,
                "accepted" to accepted.size,
                "darks" to darks.size,
                "integrationSeconds" to acceptedIntegrationSeconds,
            ),
            "frames" to frames.map { it.toJson() },
        ),
    )

    companion object {

        /**
         * Reads a log back. Tolerant by design: this is the path that opens a folder copied back
         * from a PC, or one left behind by a process that died mid-write, and a log that will not
         * parse strands every frame beside it.
         */
        fun decode(text: String): SessionLog {
            val root = Json.parseObject(text)
            val plan = (root["plan"] as? Map<*, *>)?.filterKeys { it is String }
                ?.mapKeys { it.key as String } ?: emptyMap()
            val pointing = (root["pointing"] as? Map<*, *>)?.filterKeys { it is String }
                ?.mapKeys { it.key as String } ?: emptyMap()
            val focus = (root["focus"] as? Map<*, *>)?.filterKeys { it is String }
                ?.mapKeys { it.key as String } ?: emptyMap()

            val info = SessionInfo(
                schemaVersion = root.int("schemaVersion") ?: 1,
                sessionId = root.string("sessionId").orEmpty(),
                // Absent in schema 1 logs written before T-3.30, and in any folder that has been
                // to a PC and back through a tool that does not know the field. Blank, not
                // guessed: SessionSummary falls back to the folder name, which is where the only
                // other copy of the name is.
                label = root.string("label").orEmpty(),
                startedAtEpochMs = root.long("startedAt") ?: 0L,
                finishedAtEpochMs = root.long("finishedAt"),
                deviceModel = root.string("device").orEmpty(),
                cameraId = root.string("cameraId").orEmpty(),
                plannedIso = plan.int("iso") ?: 0,
                plannedExposureNs = plan.long("exposureNs") ?: 0L,
                plannedLightCount = plan.int("lightCount") ?: 0,
                plannedDarkCount = plan.int("darkCount") ?: 0,
                exposureDerivation = (plan["exposureDerivation"] as? List<*>)
                    ?.filterIsInstance<String>().orEmpty(),
                // Absent in every log written before T-11.8, and those were all deep sky.
                targetType = plan.string("targetType")
                    ?.let { runCatching { TargetType.valueOf(it) }.getOrNull() }
                    ?: TargetType.DEEP_SKY,
                groundIso = plan.int("groundIso"),
                groundExposureNs = plan.long("groundExposureNs"),
                groundLightCount = plan.int("groundLightCount"),
                latitudeDeg = pointing.double("latitude"),
                longitudeDeg = pointing.double("longitude"),
                altitudeDeg = pointing.double("altitude"),
                azimuthDeg = pointing.double("azimuth"),
                declinationDeg = pointing.double("declination"),
                compassAccuracy = pointing.string("compassAccuracy"),
                fieldRotationArcsecPerSec = pointing.double("fieldRotationArcsecPerSec"),
                focusDiopters = focus.float("diopters"),
                focusHfr = focus.double("hfr"),
                calibrationVersions = (root["calibration"] as? Map<*, *>)
                    ?.entries?.mapNotNull { (k, v) ->
                        if (k is String && v is String) k to v else null
                    }?.toMap().orEmpty(),
                stacking = (root["stacking"] as? Map<*, *>)
                    ?.entries?.mapNotNull { (k, v) ->
                        if (k is String && v is String) k to v else null
                    }?.toMap().orEmpty(),
                state = root.string("state")
                    ?.let { runCatching { SessionState.valueOf(it) }.getOrNull() }
                    ?: SessionState.CAPTURING,
            )

            return SessionLog(info, root.objects("frames").map { FrameRecord.fromJson(it) })
        }
    }
}

private fun Map<String, Any?>.float(key: String): Float? = (this[key] as? Number)?.toFloat()
