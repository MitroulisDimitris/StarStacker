package com.starstacker.session

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.TimeZone

/**
 * T-3.30 — a session is named at the start, and named for the day when it is not.
 *
 * The arithmetic is here rather than on a device because all of it is string work over a clock
 * reading, and the two properties worth pinning are properties a device cannot demonstrate quickly:
 * that the numbering comes from the folders (so a folder copied in from a PC shifts it, and a
 * deletion shifts it back), and that the date never lands in a folder name twice.
 */
class SessionNamingTest {

    private val utc = TimeZone.getTimeZone("UTC")

    /** 2026-08-18 21:15 UTC. */
    private val night = 1_787_087_700_000L

    private fun folder(label: String, at: Long = night) =
        SessionLayout.folderName(at, label, utc)

    @Test
    fun `the day is the local date`() {
        assertEquals("2026-08-18", SessionNaming.dayOf(night, utc))
    }

    @Test
    fun `the first session of a night carries no number`() {
        assertEquals("2026-08-18", SessionNaming.forDay(night, emptyList(), utc))
    }

    @Test
    fun `the second session of a night is told apart from the first`() {
        val existing = listOf("2026-08-18_2115")
        assertEquals("2026-08-18-2", SessionNaming.forDay(night, existing, utc))
    }

    @Test
    fun `the count is of every session that night, named or not`() {
        // `Orion` followed by a cancelled prompt gives -2, which is true: it was the second
        // session of the night, and that is what T-3.30 asks the number to say.
        val existing = listOf("2026-08-18_2115_Orion", "2026-08-18_2230")
        assertEquals("2026-08-18-3", SessionNaming.forDay(night, existing, utc))
    }

    @Test
    fun `sessions from other nights do not shift tonight's numbering`() {
        val existing = listOf(
            "2026-08-17_2300_Andromeda",
            "2026-08-19_0100",
            "2026-08-1_2000",
        )
        assertEquals("2026-08-18", SessionNaming.forDay(night, existing, utc))
    }

    @Test
    fun `a folder without a timestamp is ignored rather than guessed at`() {
        // Something a person made by hand, or a tool's leftovers. It is not a session of this
        // night and must not push the number along.
        val existing = listOf("my-stuff", "2026-08-18-notes", "")
        assertEquals("2026-08-18", SessionNaming.forDay(night, existing, utc))
    }

    @Test
    fun `deleting a session frees its number again`() {
        // The property a preferences counter cannot have, and the reason D-5 forbids one: the
        // number is derived, so removing the folder removes the reservation.
        val before = listOf("2026-08-18_2115", "2026-08-18_2230")
        assertEquals("2026-08-18-3", SessionNaming.forDay(night, before, utc))
        val after = before.drop(1)
        assertEquals("2026-08-18-2", SessionNaming.forDay(night, after, utc))
    }

    @Test
    fun `a typed name is kept`() {
        assertEquals("Orion", SessionNaming.labelFor("Orion", night, emptyList(), utc))
    }

    @Test
    fun `a typed name is sanitised the same way the folder will be`() {
        assertEquals(
            "M31-Andromeda",
            SessionNaming.labelFor("M31 / Andromeda!", night, emptyList(), utc),
        )
    }

    @Test
    fun `a blank name is the same answer as cancelling`() {
        val existing = listOf("2026-08-18_2115")
        assertEquals("2026-08-18-2", SessionNaming.labelFor("", night, existing, utc))
        assertEquals("2026-08-18-2", SessionNaming.labelFor("   ", night, existing, utc))
    }

    @Test
    fun `a name that cleans up to nothing is blank, not the literal 'session'`() {
        // `sanitise` answers "session" for anything that cleans to nothing, and "session" is
        // precisely the name this task exists to abolish — twelve nights of it named nothing.
        assertEquals("2026-08-18", SessionNaming.labelFor("///", night, emptyList(), utc))
        assertEquals("2026-08-18", SessionNaming.labelFor("session", night, emptyList(), utc))
    }

    @Test
    fun `a named session puts the name after the stamp`() {
        assertEquals("2026-08-18_2115_Orion", folder("Orion"))
    }

    @Test
    fun `a session named for the day does not carry the date twice`() {
        assertEquals("2026-08-18_2115", folder("2026-08-18"))
        assertEquals("2026-08-18_2115", folder("2026-08-18-2"))
    }

    @Test
    fun `a name that merely starts with a date is not mistaken for the default`() {
        // `2026-08-18-comet` is a name someone chose. Only the day itself, and the day with a
        // bare number, are the generated default.
        assertEquals("2026-08-18_2115_2026-08-18-comet", folder("2026-08-18-comet"))
    }

    @Test
    fun `a default name from another day still appears in the folder`() {
        // Nothing generates this, but if a label from a different date arrives the folder must not
        // silently drop it — the stamp would then be the only record and it disagrees.
        assertEquals("2026-08-18_2115_2026-08-17", folder("2026-08-17"))
    }

    @Test
    fun `the round trip a session actually takes is stable`() {
        // Cancel the prompt on the second session of the night, and the pieces agree: the label
        // says which session it was, the folder says when, and neither repeats the other.
        val existing = listOf("2026-08-18_2115_Orion")
        val label = SessionNaming.labelFor("", night, existing, utc)
        assertEquals("2026-08-18-2", label)
        assertEquals("2026-08-18_2115", SessionLayout.folderName(night, label, utc))
    }

    @Test
    fun `a plain child name is required before anything can be deleted`() {
        assertTrue(SessionLayout.isPlainChildName("2026-08-18_2115_Orion"))
        assertFalse(SessionLayout.isPlainChildName(""))
        assertFalse(SessionLayout.isPlainChildName("   "))
        assertFalse(SessionLayout.isPlainChildName("."))
        assertFalse(SessionLayout.isPlainChildName(".."))
        assertFalse(SessionLayout.isPlainChildName("../../DCIM"))
        assertFalse(SessionLayout.isPlainChildName("a/b"))
        assertFalse(SessionLayout.isPlainChildName("a\\b"))
    }
}
