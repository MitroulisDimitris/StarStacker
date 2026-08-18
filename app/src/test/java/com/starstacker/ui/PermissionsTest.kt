package com.starstacker.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * T-0.4. FR-3.1's rule is that denial is survivable, and survivable means the app keeps working
 * *and says what it is now doing differently* — so these test the wording as much as the logic.
 */
class PermissionsTest {

    private val everything = Permissions.all.map { it.id }.toSet()

    @Test
    fun `only the camera is required`() {
        val required = Permissions.all.filter { it.required }
        assertEquals(listOf(Permissions.CAMERA), required.map { it.id })
    }

    @Test
    fun `capture needs the camera and nothing else`() {
        assertTrue(Permissions.canCapture(setOf(Permissions.CAMERA)))
        assertTrue(Permissions.canCapture(everything))
        assertFalse(Permissions.canCapture(emptySet()))
        assertFalse(
            Permissions.canCapture(setOf(Permissions.FINE_LOCATION, Permissions.NOTIFICATIONS)),
        )
    }

    /** Install-time permissions cannot be refused, so asking for them would be a dead prompt. */
    @Test
    fun `install-time permissions are never requested`() {
        val outstanding = Permissions.outstanding(emptySet())
        assertTrue(outstanding.none { it.installTime }, "an unrefusable permission was queued")
        assertEquals(3, outstanding.size)
    }

    @Test
    fun `granting everything leaves nothing outstanding`() {
        assertTrue(Permissions.outstanding(everything).isEmpty())
    }

    /**
     * The summary leads with what works whenever anything works. A screen that opens by
     * complaining about an optional permission reads as broken rather than degraded.
     */
    @Test
    fun `the summary leads with capability once the camera is granted`() {
        val summary = Permissions.summary(setOf(Permissions.CAMERA))
        assertTrue(summary.startsWith("Ready to capture"), summary)
    }

    @Test
    fun `without the camera the summary says so first`() {
        assertTrue(Permissions.summary(emptySet()).contains("required"))
        assertFalse(Permissions.summary(emptySet()).contains("Ready to capture"))
    }

    @Test
    fun `granting everything is stated plainly rather than left blank`() {
        assertEquals("Everything the app asks for has been granted.", Permissions.summary(everything))
    }

    /**
     * Each consequence has to be concrete enough to decide on. "Reduced functionality" is not a
     * sentence anyone can act on; naming the thing that stops happening is.
     */
    @Test
    fun `every optional permission names what is actually lost`() {
        Permissions.runtime.filterNot { it.required }.forEach {
            assertTrue(it.ifDenied.length > 40, "${it.label} explains too little: ${it.ifDenied}")
            assertFalse(
                it.ifDenied.contains("functionality", ignoreCase = true),
                "${it.label} hides behind a vague word",
            )
        }
    }

    /** The measured consequence from T-0.6's work: no notification means no darks prompt. */
    @Test
    fun `refusing notifications is described as costing the darks`() {
        val notifications = Permissions.all.first { it.id == Permissions.NOTIFICATIONS }
        assertTrue(notifications.ifDenied.contains("darks"), notifications.ifDenied)
        assertTrue(notifications.ifDenied.contains("Lights are unaffected"), notifications.ifDenied)
    }

    /** Location denial must promise the equator fallback, which is what the solver actually does. */
    @Test
    fun `refusing location is described as the equator fallback`() {
        val location = Permissions.all.first { it.id == Permissions.FINE_LOCATION }
        assertTrue(location.ifDenied.contains("equator"), location.ifDenied)
    }

    @Test
    fun `every permission has a reason and a consequence`() {
        Permissions.all.forEach {
            assertTrue(it.why.isNotBlank(), "${it.label} has no reason")
            assertTrue(it.ifDenied.isNotBlank(), "${it.label} has no consequence")
            assertTrue(it.label.isNotBlank())
        }
    }
}
