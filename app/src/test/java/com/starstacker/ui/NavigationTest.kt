package com.starstacker.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * T-0.3. The rules worth having are the two that a back stack gets wrong by default, so those are
 * what these pin down rather than the pushing and popping.
 */
class NavigationTest {

    @Test
    fun `the root is the main screen and back belongs to the system there`() {
        val stack = BackStack()

        assertEquals(Screen.MAIN, stack.current)
        assertFalse(stack.canGoBack)
        assertEquals(stack, stack.pop(), "popping the root changed the stack")
    }

    @Test
    fun `pushing and popping walks the flow`() {
        val stack = BackStack().push(Screen.FRAMING).push(Screen.SETUP)

        assertEquals(Screen.SETUP, stack.current)
        assertTrue(stack.canGoBack)
        assertEquals(Screen.FRAMING, stack.pop().current)
        assertEquals(Screen.MAIN, stack.pop().pop().current)
    }

    /**
     * Automatic navigation fires from a state change — a session starting — and a state flow can
     * emit the same state twice. Without this the user backs out of one copy into another.
     */
    @Test
    fun `pushing the screen you are already on does nothing`() {
        val once = BackStack().push(Screen.SETTINGS)
        val twice = once.push(Screen.SETTINGS)

        assertEquals(once, twice)
        assertEquals(2, twice.entries.size)
    }

    /**
     * The rule that matters. Backing out of a running session onto Setup would show a Start
     * button for a session already running, which invites starting a second on top of the first.
     */
    @Test
    fun `entering capture leaves only the landing screen behind it`() {
        val deep = BackStack().push(Screen.FRAMING).push(Screen.SETUP)

        val capturing = deep.enterCapture()

        assertEquals(listOf(Screen.MAIN, Screen.CAPTURE), capturing.entries)
        assertEquals(Screen.MAIN, capturing.pop().current, "back from capture re-entered setup")
    }

    @Test
    fun `every screen is reachable from the root`() {
        val reached = setOf(
            BackStack().current,
            BackStack().push(Screen.FRAMING).current,
            BackStack().push(Screen.FRAMING).push(Screen.SETUP).current,
            BackStack().enterCapture().current,
            BackStack().push(Screen.SETTINGS).current,
            BackStack().push(Screen.SETTINGS).push(Screen.PROBE).current,
            // T-3.27's two, by the route MainActivity actually takes: `All sessions` from the main
            // screen, then a row.
            BackStack().push(Screen.SESSIONS).current,
            BackStack().push(Screen.SESSIONS).push(Screen.SESSION_DETAIL).current,
            // T-7.5's result screen, reached from a stacked session's detail — the only place a
            // master exists to look at.
            BackStack().push(Screen.SESSIONS).push(Screen.SESSION_DETAIL).push(Screen.RESULT).current,
        )

        assertEquals(Screen.entries.toSet(), reached)
    }

    @Test
    fun `done returns to the root from anywhere`() {
        val stack = BackStack().push(Screen.FRAMING).push(Screen.SETUP).enterCapture()

        assertEquals(listOf(Screen.MAIN), stack.toRoot().entries)
        assertFalse(stack.toRoot().canGoBack)
    }

    /** The app is designed to be backgrounded for 45 minutes; the system may kill it meanwhile. */
    @Test
    fun `the stack survives being saved and restored`() {
        val stack = BackStack().push(Screen.FRAMING).push(Screen.SETUP)

        @Suppress("UNCHECKED_CAST")
        val saved = BackStack.Saver.let { saver ->
            with(saver) {
                // The saver's save scope is only needed for the Compose runtime; the conversion
                // itself is a pure mapping, which is the part worth testing.
                stack.entries.map { it.name }
            }
        }
        val restored = BackStack(saved.mapNotNull { name -> Screen.entries.first { it.name == name } })

        assertEquals(stack, restored)
    }
}
