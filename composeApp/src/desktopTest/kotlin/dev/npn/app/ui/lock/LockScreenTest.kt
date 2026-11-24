@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.npn.app.ui.lock

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import dev.npn.app.EnglishLocaleTest
import dev.npn.app.platform.AppLockAuthenticator
import dev.npn.app.platform.LocalAppLockAuthenticator
import dev.npn.app.ui.click
import dev.npn.app.ui.closing
import dev.npn.app.ui.onUiThread
import dev.npn.app.ui.texts
import dev.npn.app.ui.themedScene
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class LockScreenTest : EnglishLocaleTest() {
    @Test
    fun deviceButtonRequiresSuccessfulAuthentication() =
        onUiThread {
            var result: ((Boolean) -> Unit)? = null
            var prompts = 0
            var unlocks = 0
            var state by mutableStateOf(LockUiState())
            val authenticator =
                object : AppLockAuthenticator {
                    override fun isAvailable() = true

                    override fun authenticate(
                        title: String,
                        subtitle: String,
                        onResult: (Boolean) -> Unit,
                    ) {
                        prompts++
                        result = onResult
                    }
                }
            val actions =
                object : LockActions {
                    override fun unlock(secret: String) = Unit

                    override fun unlockWithDevice() {
                        unlocks++
                    }

                    override fun retryRemainingMillis() = 0L
                }
            themedScene(800, 600, LocalAppLockAuthenticator provides authenticator) {
                LockScreen(state, actions)
            }.closing {
                render()
                prompts shouldBe 1
                result!!(false)
                state = state.copy(checking = true)
                Snapshot.sendApplyNotifications()
                render()
                prompts shouldBe 1
                state = state.copy(checking = false)
                Snapshot.sendApplyNotifications()
                render()
                unlocks shouldBe 0
                click { "Unlock with device" in it.texts() }
                prompts shouldBe 2
                unlocks shouldBe 0
                result(true)
                unlocks shouldBe 1
            }
        }
}
