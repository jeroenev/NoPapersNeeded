@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.npn.app.ui.ai

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.navigationevent.DirectNavigationEventInput
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import dev.npn.app.ui.closing
import dev.npn.app.ui.onUiThread
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * [LeaveGuard] against a real composition and back dispatcher: the system gesture and desktop Esc
 * both arrive as a completed back on that dispatcher.
 */
class LeaveGuardTest {
    @Test
    fun backIsTakenOnlyWhileGuarding() =
        onUiThread {
            var fellThrough = 0
            val dispatcher = NavigationEventDispatcher(onBackCompletedFallback = { fellThrough++ })
            val back = DirectNavigationEventInput().also(dispatcher::addInput)
            val owner =
                object : NavigationEventDispatcherOwner {
                    override val navigationEventDispatcher = dispatcher
                }
            var guarding by mutableStateOf(true)
            var guarded = 0
            val scene =
                ImageComposeScene(WIDTH, HEIGHT) {
                    CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides owner) {
                        LeaveGuard(guarding = guarding, onBack = { guarded++ })
                    }
                }
            scene.closing {
                render()
                back.backCompleted()
                guarded shouldBe 1
                fellThrough shouldBe 0

                guarding = false
                render()
                back.backCompleted()
                guarded shouldBe 1
                fellThrough shouldBe 1
            }
        }

    private companion object {
        const val WIDTH = 100
        const val HEIGHT = 100
    }
}
