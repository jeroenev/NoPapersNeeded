@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.npn.app.ui.settings

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import dev.npn.app.BuildInfo
import dev.npn.app.EnglishLocaleTest
import dev.npn.app.FakeLibrarySession
import dev.npn.app.FakeSettingsRepository
import dev.npn.app.settingsVm
import dev.npn.app.ui.click
import dev.npn.app.ui.closing
import dev.npn.app.ui.common.LocalWindowWidthClass
import dev.npn.app.ui.common.LocaleProvider
import dev.npn.app.ui.common.WindowWidthClass
import dev.npn.app.ui.contentDescription
import dev.npn.app.ui.detail.DocumentDrafts
import dev.npn.app.ui.detail.LocalDocumentDrafts
import dev.npn.app.ui.has
import dev.npn.app.ui.hostedScene
import dev.npn.app.ui.onUiThread
import dev.npn.app.ui.texts
import dev.npn.app.ui.theme.NpnTheme
import dev.npn.core.security.AppLock
import dev.npn.core.security.AppLockSettings
import dev.npn.core.testing.FakeMetadataWriter
import dev.npn.core.util.MonotonicClock
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
                            appLock = AppLock(AppLockSettings(), MonotonicClock { 0L }),
                            session = FakeLibrarySession(),
                            outliving = CoroutineScope(Dispatchers.Unconfined),
                            category = null,
                            onOpenCategory = { _, _ -> },
                            onBack = { leftSettings = true },
                            onOpenLibrary = {},
                            onResumeDraft = {},
                            onOpenDocument = {},
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

    /**
     * The Appearance pane changes the app's language, which rebuilds everything under it: on a wide
     * window the pane the user was on stays open rather than falling back to the first one.
     */
    @Test
    fun aLanguageChangeKeepsTheWidePaneOpen() =
        onUiThread {
            var language by mutableStateOf<String?>(null)
            val drafts = DocumentDrafts(FakeMetadataWriter(), CoroutineScope(Dispatchers.Unconfined))
            hostedScene(WIDTH, HEIGHT) {
                LocaleProvider(language) {
                    CompositionLocalProvider(
                        LocalWindowWidthClass provides WindowWidthClass.WIDE,
                        LocalDocumentDrafts provides drafts,
                    ) {
                        NpnTheme(darkTheme = false) {
                            SettingsScreen(
                                settings = FakeSettingsRepository(),
                                appLock = AppLock(AppLockSettings(), MonotonicClock { 0L }),
                                session = FakeLibrarySession(),
                                outliving = CoroutineScope(Dispatchers.Unconfined),
                                category = null,
                                onOpenCategory = { _, _ -> },
                                onBack = {},
                                onOpenLibrary = {},
                                onResumeDraft = {},
                                onOpenDocument = {},
                                onAutoMatchRules = {},
                            )
                        }
                    }
                }
            }.closing {
                render()
                click { "About" in it.texts() }
                render()
                has { node -> node.texts().any { BuildInfo.VERSION in it } } shouldBe true

                language = "nl"
                Snapshot.sendApplyNotifications()
                render()
                render()

                // Only the About pane shows the version, in any language.
                has { node -> node.texts().any { BuildInfo.VERSION in it } } shouldBe true
            }
        }

    /**
     * A search that finds a setting behind a pane's Advanced disclosure opens the pane with it
     * expanded: beside the list on a wide window, and through the route on a compact one.
     */
    @Test
    fun aSearchHitBehindAdvancedOpensThePaneExpanded() =
        onUiThread {
            var widthClass by mutableStateOf(WindowWidthClass.WIDE)
            val opened = mutableListOf<Pair<SettingsCategoryId, Boolean>>()
            val vm = settingsVm()
            val drafts = DocumentDrafts(FakeMetadataWriter(), CoroutineScope(Dispatchers.Unconfined))
            hostedScene(WIDTH, HEIGHT) {
                CompositionLocalProvider(
                    LocalWindowWidthClass provides widthClass,
                    LocalDocumentDrafts provides drafts,
                ) {
                    NpnTheme(darkTheme = false) {
                        SettingsScreen(
                            settings = FakeSettingsRepository(),
                            appLock = AppLock(AppLockSettings(), MonotonicClock { 0L }),
                            session = FakeLibrarySession(),
                            outliving = CoroutineScope(Dispatchers.Unconfined),
                            category = null,
                            onOpenCategory = { id, reveal -> opened += id to reveal },
                            onBack = {},
                            onOpenLibrary = {},
                            onResumeDraft = {},
                            onOpenDocument = {},
                            onAutoMatchRules = {},
                            vm = vm,
                        )
                    }
                }
            }.closing {
                vm.searchCategories("template")
                vm.pick(SettingsCategoryId.LIBRARY)
                render()
                has { "Clear thumbnail cache" in it.texts() } shouldBe false

                vm.searchCategories("thumbnail")
                render()
                render()
                has { "Clear thumbnail cache" in it.texts() } shouldBe true

                vm.pick(null)
                widthClass = WindowWidthClass.COMPACT
                Snapshot.sendApplyNotifications()
                render()
                click { "Library" in it.texts() }
                opened shouldBe listOf(SettingsCategoryId.LIBRARY to true)
            }
        }

    private companion object {
        const val WIDTH = 1000
        const val HEIGHT = 900
    }
}
