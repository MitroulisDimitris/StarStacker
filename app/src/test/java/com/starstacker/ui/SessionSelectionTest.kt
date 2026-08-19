package com.starstacker.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** T-3.29 — the selection, tested rather than clicked (the same reason [BackStack] is). */
class SessionSelectionTest {

    private val a = "2026-08-18_2115_Orion"
    private val b = "2026-08-18_2230"
    private val c = "2026-08-17_2300_Andromeda"

    @Test
    fun `nothing is selected to begin with`() {
        val selection = SessionSelection()
        assertFalse(selection.isActive)
        assertEquals(0, selection.count)
    }

    @Test
    fun `a tap selects and a second tap deselects`() {
        val selection = SessionSelection().toggle(a)
        assertTrue(a in selection)
        assertEquals(1, selection.count)
        assertFalse(a in selection.toggle(a))
    }

    @Test
    fun `several sessions can be selected and the count is stated`() {
        val selection = SessionSelection().toggle(a).toggle(b).toggle(c)
        assertEquals(3, selection.count)
        assertEquals("3 selected", selection.describe())
    }

    @Test
    fun `clearing drops everything`() {
        assertFalse(SessionSelection().toggle(a).toggle(b).clear().isActive)
    }

    @Test
    fun `names that no longer exist are dropped`() {
        // The bug this exists to prevent: after a batch delete the vanished names stay selected,
        // so the count claims three sessions and a second Delete acts on nothing while saying it
        // acts on three.
        val selection = SessionSelection().toggle(a).toggle(b).toggle(c)
        val afterDelete = selection.retaining(listOf(c))
        assertEquals(1, afterDelete.count)
        assertTrue(c in afterDelete)
        assertFalse(a in afterDelete)
    }

    @Test
    fun `retaining an unchanged list returns the same selection`() {
        // Identity, not just equality: this runs on every rescan, and a new object each time
        // would restart the LaunchedEffect that calls it.
        val selection = SessionSelection().toggle(a).toggle(b)
        assertTrue(selection === selection.retaining(listOf(a, b, c)))
    }

    @Test
    fun `the selection survives being saved and restored`() {
        val selection = SessionSelection().toggle(a).toggle(b)
        // `save` needs a SaverScope that only the Compose runtime supplies; the mapping either
        // side of it is the part that can be wrong, and it is pure. Same approach as
        // NavigationTest takes with BackStack.Saver.
        val saved = SessionSelection(selection.names).names.toList()
        val restored = SessionSelection.Saver.restore(saved)
        assertEquals(selection, restored)
        assertEquals(2, restored?.count)
    }
}
