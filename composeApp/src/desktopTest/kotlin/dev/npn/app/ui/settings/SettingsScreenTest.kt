@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.npn.app.ui.settings

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import dev.npn.app.EnglishLocaleTest
import dev.npn.app.FakeLibrarySession
import dev.npn.app.FakeSettingsRepository
import dev.npn.app.ui.click
import dev.npn.app.ui.closing
import dev.npn.app.ui.common.LocalWindowWidthClass
import dev.npn.app.ui.common.WindowWidthClass
import dev.npn.app.ui.contentDescription
import dev.npn.app.ui.detail.DocumentDrafts
import dev.npn.app.ui.detail.LocalDocumentDrafts
import dev.npn.app.ui.has
import dev.npn.app.ui.hostedScene
import dev.npn.app.ui.onUiThread
import dev.npn.app.ui.texts
import dev.npn.app.ui.theme.NpnTheme
import dev.npn.core.testing.FakeMetadataWriter
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test

class SettingsScreenTest : EnglishLocaleTest() {
    /**
     * A pane picked on a wide window stays open when the window turns compact, where it has no route
     * of its own: Back there closes it, back to the list, rather than leaving Settings.
     */
    @Test
    fun aPanePickedWideStaysOpenCompactUntilBack() =
        onUiThread {
            var widthClass by mutableStateOf(WindowWidthClass.WIDE)
            var leftSettings = false
            val drafts = DocumentDrafts(FakeMetadataWriter(), CoroutineScope(Dispatchers.Unconfined))
            hostedScene(WIDTH, HEIGHT) {
                CompositionLocalProvider(
                    LocalWindowWidthClass provides widthClass,
                    LocalDocumentDrafts provides drafts,
                ) {
                    NpnTheme(darkTheme = false) {
                        SettingsScreen(
                            settings = FakeSettingsRepository(),
                            session = FakeLibrarySession(),
                            category = null,
                            onOpenCategory = {},
                            onBack = { leftSettings = true },
                            onOpenLibrary = {},
                            onResumeDraft = {},
                            onAutoMatchRules = {},
                        )
                    }
                }
            }.closing {
                render()
                click { "About" in it.texts() }
                render()

                widthClass = WindowWidthClass.COMPACT
                Snapshot.sendApplyNotifications()
                render()
                has { it.contentDescription() == "Back" } shouldBe true

                click { it.contentDescription() == "Back" }
                render()
                has { it.contentDescription() == "Back" } shouldBe false
                leftSettings shouldBe false
            }
        }

    private companion object {
        const val WIDTH = 1000
        const val HEIGHT = 900
    }
}
