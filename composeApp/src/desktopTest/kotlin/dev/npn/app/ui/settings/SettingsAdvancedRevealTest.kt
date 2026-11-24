@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.npn.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import dev.npn.app.EnglishLocaleTest
import dev.npn.app.NoSettingsPaneActions
import dev.npn.app.paneState
import dev.npn.app.ui.closing
import dev.npn.app.ui.nodes
import dev.npn.app.ui.onUiThread
import dev.npn.app.ui.texts
import dev.npn.app.ui.theme.NpnTheme
import dev.npn.core.model.ReviewMode
import dev.npn.core.settings.SettingsSnapshot
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import kotlin.test.Test

/**
 * A settings search that finds a setting behind a pane's Advanced disclosure opens that disclosure
 * (the route hands [LocalRevealAdvanced] down); without a hit it stays closed.
 */
class SettingsAdvancedRevealTest : EnglishLocaleTest() {
    @Test
    fun advancedSettingsShowOnlyWhenASearchFoundThem() =
        onUiThread {
            labels(reveal = false) shouldNotContain CLEAR_THUMBNAILS
            labels(reveal = true) shouldContain CLEAR_THUMBNAILS
        }

    private fun labels(reveal: Boolean): List<String> {
        val scene =
            ImageComposeScene(WIDTH, HEIGHT) {
                NpnTheme(darkTheme = false) {
                    CompositionLocalProvider(LocalRevealAdvanced provides reveal) {
                        Column {
                            LibrarySettingsPane(
                                paneState(
                                    SettingsSnapshot(installationId = "test", libraryLocation = "/library"),
                                    reviewMode = ReviewMode.UNTAGGED_ONLY,
                                ),
                                NoSettingsPaneActions(),
                            )
                        }
                    }
                }
            }
        return scene.closing {
            render()
            render()
            nodes().flatMap { it.texts() }
        }
    }

    private companion object {
        const val WIDTH = 900
        const val HEIGHT = 3_000
        const val CLEAR_THUMBNAILS = "Clear thumbnail cache"
    }
}
