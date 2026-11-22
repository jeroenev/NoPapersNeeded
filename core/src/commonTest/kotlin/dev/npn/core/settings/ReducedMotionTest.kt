package dev.npn.core.settings

import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * The app's reduced-motion preference and the platform's animation setting both ask for less
 * motion: either one alone is enough, and only both being off leaves transitions animated. In
 * commonTest, so both targets run it.
 */
class ReducedMotionTest {
    @Test
    fun eitherReducedMotionPreferenceOrThePlatformTurnsMotionOff() {
        reducesMotion(preference = false, systemAnimationsEnabled = true) shouldBe false
        reducesMotion(preference = true, systemAnimationsEnabled = true) shouldBe true
        reducesMotion(preference = false, systemAnimationsEnabled = false) shouldBe true
        reducesMotion(preference = true, systemAnimationsEnabled = false) shouldBe true
    }
}
