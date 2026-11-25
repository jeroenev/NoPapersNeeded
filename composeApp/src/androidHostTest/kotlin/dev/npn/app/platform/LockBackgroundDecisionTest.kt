package dev.npn.app.platform

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LockBackgroundDecisionTest {
    @Test
    fun backWithoutLeaveHintStartsLockTimeout() {
        assertTrue(lockOnStop(false, false, true, false))
    }

    @Test
    fun finishingAndScreenOffOverrideHandoff() {
        assertTrue(lockOnStop(false, true, true, true))
        assertTrue(lockOnStop(false, false, false, true))
    }

    @Test
    fun handoffAndRecreationDoNotStartTimeout() {
        assertFalse(lockOnStop(false, false, true, true))
        assertFalse(lockOnStop(true, true, false, false))
    }

    @Test
    fun handoffIsConsumedAndCanBeClearedAfterFailure() {
        LockActivityHandoff.clear()
        LockActivityHandoff.started()
        assertTrue(LockActivityHandoff.consume())
        assertFalse(LockActivityHandoff.consume())
        LockActivityHandoff.started()
        LockActivityHandoff.clear()
        assertFalse(LockActivityHandoff.consume())
    }
}
