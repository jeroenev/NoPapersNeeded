package dev.npn.desktop

import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LockShortcutTest {
    private val ctrlShift = InputEvent.CTRL_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK

    @Test
    fun ctrlShiftLLocks() {
        assertTrue(isLockNowKey(KeyEvent.VK_L, ctrlShift))
    }

    @Test
    fun otherKeysAndChordsDoNot() {
        assertFalse(isLockNowKey(KeyEvent.VK_L, InputEvent.CTRL_DOWN_MASK))
        assertFalse(isLockNowKey(KeyEvent.VK_L, InputEvent.SHIFT_DOWN_MASK))
        assertFalse(isLockNowKey(KeyEvent.VK_K, ctrlShift))
        assertFalse(isLockNowKey(KeyEvent.VK_L, ctrlShift or InputEvent.ALT_DOWN_MASK))
    }
}
