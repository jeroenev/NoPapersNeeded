@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.npn.app.ui.library

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigationevent.DirectNavigationEventInput
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import dev.npn.app.ui.closing
import dev.npn.app.ui.common.KeyedViewModelStores
import dev.npn.app.ui.common.ViewModelStoreLease
import dev.npn.app.ui.onUiThread
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class TriageOwnershipTest {
    private class Probe : ViewModel() {
        var cleared = false

        override fun onCleared() {
            cleared = true
        }
    }

    @Test
    fun previewAndTriageLeasesDoNotReleaseEachOthersDocuments() =
        onUiThread {
            val stores = KeyedViewModelStores<String>()
            val owner =
                object : ViewModelStoreOwner {
                    override val viewModelStore = ViewModelStore()
                }
            lateinit var preview: ViewModelStoreLease<String>
            lateinit var triage: ViewModelStoreLease<String>
            lateinit var probe: Probe
            val scene =
                ImageComposeScene(100, 100) {
                    CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                        preview = viewModel { ViewModelStoreLease(stores) }
                        triage = rememberTriageLease(stores)
                        probe = viewModel(viewModelStoreOwner = triage.of("doc")) { Probe() }
                    }
                }
            scene.closing {
                render()
                preview.keepOnly(null)
                probe.cleared shouldBe false
                triage.keepOnly(null)
                probe.cleared shouldBe true
            }
            owner.viewModelStore.clear()
        }

    @Test
    fun systemBackExitsTriage() =
        onUiThread {
            var exited = 0
            var fellThrough = 0
            val dispatcher = NavigationEventDispatcher(onBackCompletedFallback = { fellThrough++ })
            val back = DirectNavigationEventInput().also(dispatcher::addInput)
            val owner =
                object : NavigationEventDispatcherOwner {
                    override val navigationEventDispatcher = dispatcher
                }
            val scene =
                ImageComposeScene(100, 100) {
                    CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides owner) {
                        TriageBackHandler { exited++ }
                    }
                }
            scene.closing {
                render()
                back.backCompleted()
                exited shouldBe 1
                fellThrough shouldBe 0
            }
        }
}
