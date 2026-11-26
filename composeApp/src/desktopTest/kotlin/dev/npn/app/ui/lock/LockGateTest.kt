@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.npn.app.ui.lock

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import dev.npn.app.EnglishLocaleTest
import dev.npn.app.FakeLibrarySession
import dev.npn.app.LibraryViewModelHost
import dev.npn.app.LocalizedApp
import dev.npn.app.NoCloudSetupActions
import dev.npn.app.ui.closing
import dev.npn.app.ui.has
import dev.npn.app.ui.hostedScene
import dev.npn.app.ui.onUiThread
import dev.npn.app.ui.settings.CloudSetupState
import dev.npn.app.ui.settings.CloudSetupWizard
import dev.npn.app.ui.text
import dev.npn.app.ui.themedScene
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * Locking takes the app's screens away and unlocking brings them back as they were: the library's
 * nav graph under [LockGate], as the app shell has it, keeps a detail screen's swiped-to document.
 */
class LockGateTest : EnglishLocaleTest() {
    private val scope = CoroutineScope(Job())
    private val session = FakeLibrarySession()
    private var locked by mutableStateOf(false)

    @AfterTest
    fun restore() {
        scope.cancel()
    }

    @Test
    fun theSwipedToDocumentIsStillShownAfterAnUnlock() =
        onUiThread {
            var swipe: (() -> Unit)? = null
            var openDetail: (() -> Unit)? = null
            var effects = 0
            hostedScene(WIDTH, HEIGHT) {
                LocalizedApp(null, session, scope) { library ->
                    if (library != null) {
                        LockGate(locked, lockScreen = { Text("Locked") }) {
                            LibraryViewModelHost(library) {
                                openDetail = { library.nav.navigate(DETAIL) }
                                NavHost(library.nav, startDestination = LIBRARY) {
                                    composable(LIBRARY) { Text("Library") }
                                    composable(DETAIL) {
                                        // As DocDetailScreen keeps the pager's document.
                                        var shown by rememberSaveable { mutableStateOf("doc-1") }
                                        // As the cloud sign-in opens its page once.
                                        var opened by rememberSaveable { mutableStateOf(false) }
                                        if (!opened) {
                                            effects++
                                            opened = true
                                        }
                                        swipe = { shown = "doc-2" }
                                        Text("Showing $shown")
                                    }
                                }
                            }
                        }
                    }
                }
            }.closing {
                settle()
                checkNotNull(openDetail)()
                settle()
                checkNotNull(swipe)()
                settle()
                has { it.text() == "Showing doc-2" } shouldBe true

                locked = true
                settle()
                has { it.text() == "Locked" } shouldBe true
                has { it.text() == "Showing doc-2" } shouldBe false

                locked = false
                settle()
                has { it.text() == "Showing doc-2" } shouldBe true
                effects shouldBe 1
            }
        }

    @Test
    fun theCloudSignInPageIsNotOpenedAgainAfterAnUnlock() =
        onUiThread {
            val opened = mutableListOf<String>()
            val browser =
                object : UriHandler {
                    override fun openUri(uri: String) {
                        opened += uri
                    }
                }
            val signingIn = CloudSetupState.SigningIn(providerName = "Drive", authUrl = "http://127.0.0.1:53682/auth")
            themedScene(WIDTH * 4, HEIGHT * 4, LocalUriHandler provides browser) {
                LockGate(locked, lockScreen = { Text("Locked") }) {
                    CloudSetupWizard(signingIn, NoCloudSetupActions)
                }
            }.closing {
                settle()
                opened shouldBe listOf(signingIn.authUrl)

                locked = true
                settle()
                locked = false
                settle()

                opened shouldBe listOf(signingIn.authUrl)
            }
        }

    private fun ImageComposeScene.settle() =
        repeat(FRAMES) {
            Snapshot.sendApplyNotifications()
            render()
        }

    private companion object {
        const val WIDTH = 200
        const val HEIGHT = 200
        const val FRAMES = 3
        const val LIBRARY = "library"
        const val DETAIL = "detail"
    }
}
