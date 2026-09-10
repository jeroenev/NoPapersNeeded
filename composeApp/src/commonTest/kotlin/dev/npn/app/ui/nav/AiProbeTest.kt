package dev.npn.app.ui.nav

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The AI configuration probe is a tri-state precisely so the shell can tell "not answered yet"
 * apart from "answered no": while it is unresolved the AI slots stay reserved so the compact bar
 * does not shift under the user, and only a resolved-unavailable answer drops them. These pin that
 * distinction, which a plain boolean could not carry.
 */
class AiProbeTest {
    @Test
    fun onlyAConfiguredProbeLetsTheAiEntriesAct() {
        assertTrue(AiProbe.CONFIGURED.isConfigured)
        assertFalse(AiProbe.PROBING.isConfigured)
        assertFalse(AiProbe.UNAVAILABLE.isConfigured)
    }

    @Test
    fun probingReservesTheSlotWithoutActivatingIt() {
        // The whole point of the third state: hold space, but do not act until the answer lands.
        assertTrue(AiProbe.PROBING.reservesSlot)
        assertFalse(AiProbe.PROBING.isConfigured)
    }

    @Test
    fun onlyAnUnavailableProbeGivesUpTheSlot() {
        assertTrue(AiProbe.CONFIGURED.reservesSlot)
        assertTrue(AiProbe.PROBING.reservesSlot)
        assertFalse(AiProbe.UNAVAILABLE.reservesSlot)
    }

    /** A reserved-but-inactive slot is exactly the probing state, and nothing else. */
    @Test
    fun reservedButInactiveIsUniquelyTheProbingState() {
        val reservedButInactive = AiProbe.entries.filter { it.reservesSlot && !it.isConfigured }
        assertEquals(listOf(AiProbe.PROBING), reservedButInactive)
    }
}
