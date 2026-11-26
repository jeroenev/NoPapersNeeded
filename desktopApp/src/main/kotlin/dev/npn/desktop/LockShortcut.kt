package dev.npn.desktop

import java.awt.event.InputEvent
import java.awt.event.KeyEvent

/**
 * Ctrl+Shift+L, the desktop's "lock now": a window has no Home button to leave with. It is read by
 * the window's key dispatcher, which sees every key whatever holds focus (and when nothing does), so
 * it works on any screen, a text field included. [keyCode] and [modifiersEx] are a key press's.
 */
internal fun isLockNowKey(
    keyCode: Int,
    modifiersEx: Int,
): Boolean {
    val wanted = InputEvent.CTRL_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK
    val held = modifiersEx and (wanted or InputEvent.ALT_DOWN_MASK or InputEvent.META_DOWN_MASK)
    return keyCode == KeyEvent.VK_L && held == wanted
}
