package com.starstacker.ui

/**
 * T-0.4 — what the app asks for, why, and **what is lost when the answer is no**.
 *
 * The third of those is the point. FR-3.1's rule is that denial is survivable, and survivable
 * means the app keeps working *and says what it is now doing differently*. A permission screen
 * that lists names and toggles teaches nobody anything; the consequence is the only part a user
 * can make a decision with.
 *
 * Pure data, no Android types, so the wording is testable without a device. The permission ids are
 * the platform's own strings, which are stable public constants.
 */
data class PermissionNeed(
    val id: String,
    val label: String,
    /** Plain language. What the app does with it. */
    val why: String,
    /** Plain language. What stops working, stated concretely. */
    val ifDenied: String,
    /** True when the app cannot function at all without it. Exactly one of these is true. */
    val required: Boolean,
    /**
     * Granted at install and impossible to refuse, so it is listed for completeness and never
     * asked for. Showing it keeps the screen an honest account of what the app holds.
     */
    val installTime: Boolean = false,
)

object Permissions {

    const val CAMERA = "android.permission.CAMERA"
    const val FINE_LOCATION = "android.permission.ACCESS_FINE_LOCATION"
    const val NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"
    const val FOREGROUND_SERVICE = "android.permission.FOREGROUND_SERVICE"
    const val FOREGROUND_SERVICE_CAMERA = "android.permission.FOREGROUND_SERVICE_CAMERA"

    /** In the order they matter, which is the order they are asked for. */
    val all: List<PermissionNeed> = listOf(
        PermissionNeed(
            id = CAMERA,
            label = "Camera",
            why = "",
            ifDenied = "Nothing can be captured.",
            required = true,
        ),
        PermissionNeed(
            id = NOTIFICATIONS,
            label = "Notifications",
            why = "",
            // Measured consequence, not a guess: the darks prompt is delivered only as a
            // notification, and the wait behind it times out after 15 minutes.
            ifDenied = "The prompt to cover the lens never appears, so the session waits 15 " +
                "minutes and then finishes without darks. Lights are unaffected.",
            required = false,
        ),
        PermissionNeed(
            id = FINE_LOCATION,
            label = "Location",
            why = "",
            ifDenied = "Subs are limited as if pointed at the celestial equator, so shorter " +
                "than a field near the pole allows. No GPS tags in the DNGs.",
            required = false,
        ),
        PermissionNeed(
            id = FOREGROUND_SERVICE,
            label = "Foreground service",
            why = "",
            ifDenied = "",
            required = false,
            installTime = true,
        ),
        PermissionNeed(
            id = FOREGROUND_SERVICE_CAMERA,
            label = "Camera foreground service",
            why = "",
            ifDenied = "",
            required = false,
            installTime = true,
        ),
    )

    /** The ones worth asking for at runtime, in order. */
    val runtime: List<PermissionNeed> get() = all.filterNot { it.installTime }

    /** The runtime permissions still outstanding, in the order they should be requested. */
    fun outstanding(granted: Set<String>): List<PermissionNeed> =
        runtime.filterNot { it.id in granted }

    /** True when the app can capture at all. */
    fun canCapture(granted: Set<String>): Boolean =
        all.filter { it.required }.all { it.id in granted }

    /**
     * One line for the top of the screen. **D-25:** a state, not an explanation.
     *
     * Leads with what works whenever anything does — a screen that opens by complaining about an
     * optional permission reads as broken rather than degraded.
     */
    fun summary(granted: Set<String>): String {
        if (!canCapture(granted)) return "Camera access needed."
        val missing = outstanding(granted)
        if (missing.isEmpty()) return "All granted."
        return "Ready. Missing: ${missing.joinToString(", ") { it.label.lowercase() }}."
    }
}
