@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.npn.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.npn.app.EnglishLocaleTest
import dev.npn.app.NoSettingsPaneActions
import dev.npn.app.paneState
import dev.npn.app.ui.common.BackTopBar
import dev.npn.app.ui.common.ProvideReducedMotion
import dev.npn.app.ui.common.TagEditor
import dev.npn.app.ui.common.TooltipIconButton
import dev.npn.app.ui.common.reducedMotion
import dev.npn.app.ui.library.GridZoom
import dev.npn.app.ui.library.LibraryTopBar
import dev.npn.app.ui.settings.AppearanceSettingsPane
import dev.npn.core.app.ViewType
import dev.npn.core.settings.SettingsSnapshot
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * The accessibility pass's semantics, against a real composition: the icon-only actions of the bars
 * the main screens share announce a name, headings are headings, and the reduced-motion switch is
 * one switch whose state the user changes.
 *
 * The library, detail, settings, tags, trash and assistant screens all wear the two bars rendered
 * here (the library's own and [BackTopBar]), so a label added to a shared bar reaches every screen
 * that uses it. A screen with a bar of its own (Activity, onboarding) is not rendered here.
 */
class AccessibilitySemanticsTest : EnglishLocaleTest() {
    /** The library bar's three icon-only actions, and its title, are readable without seeing them. */
    @Test
    fun theLibraryBarNamesItsIconsAndHeadsItsTitle() =
        onUiThread {
            themedScene(WIDTH, HEIGHT) {
                LibraryTopBar(
                    viewType = ViewType.GRID,
                    gridZoom = GridZoom(),
                    extras = {},
                    onOpenFilter = {},
                    onSetView = {},
                    onSelectAll = {},
                    onRefresh = {},
                )
            }.closing {
                render()
                has { it.contentDescription() == "Filters" } shouldBe true
                has { it.contentDescription() == "View" } shouldBe true
                has { it.contentDescription() == "More actions" } shouldBe true
                node { it.text() == "Documents" }
                    .config
                    .getOrNull(SemanticsProperties.Heading) shouldBe Unit
            }
        }

    /** A pushed screen's bar names Back and its actions, and marks the screen's title as a heading. */
    @Test
    fun theBackBarNamesItsIconsAndHeadsItsTitle() =
        onUiThread {
            themedScene(WIDTH, HEIGHT) {
                BackTopBar(title = "A document", onBack = {}) {
                    TooltipIconButton(Icons.Filled.Refresh, label = "Refresh", onClick = {})
                }
            }.closing {
                render()
                has { it.contentDescription() == "Back" } shouldBe true
                has { it.contentDescription() == "Refresh" } shouldBe true
                node { it.text() == "A document" }
                    .config
                    .getOrNull(SemanticsProperties.Heading) shouldBe Unit
            }
        }

    /**
     * Reduce motion is one labelled switch, off by default; turning it on asks the settings to store
     * the new value. It is the preference the shell provides to every screen ([ProvideReducedMotion]).
     */
    @Test
    fun reduceMotionIsOneSwitchThatStoresTheChoice() =
        onUiThread {
            var snapshot by mutableStateOf(SettingsSnapshot(installationId = "test"))
            val actions =
                object : NoSettingsPaneActions() {
                    override fun edit(transform: (SettingsSnapshot) -> SettingsSnapshot) {
                        snapshot = transform(snapshot)
                    }
                }
            themedScene(WIDTH, HEIGHT) {
                Column { AppearanceSettingsPane(paneState(snapshot), actions) }
            }.closing {
                render()
                val motionSwitch =
                    node { node ->
                        node.config.getOrNull(SemanticsProperties.Role) == Role.Switch &&
                            node.text() == "Reduce motion"
                    }
                motionSwitch.config.getOrNull(SemanticsProperties.ToggleableState) shouldBe
                    androidx.compose.ui.state.ToggleableState.Off

                click { it.config.getOrNull(SemanticsProperties.Role) == Role.Switch && it.text() == "Reduce motion" }
                render()

                snapshot.reducedMotion shouldBe true
                node { node ->
                    node.config.getOrNull(SemanticsProperties.Role) == Role.Switch &&
                        node.text() == "Reduce motion"
                }.config.getOrNull(SemanticsProperties.ToggleableState) shouldBe
                    androidx.compose.ui.state.ToggleableState.On
            }
        }

    /**
     * A tag too long for a narrow panel wraps its label instead of taking the width the remove
     * button needs: the chip's one action keeps the size it has beside a short tag.
     */
    @Test
    fun aLongTagDoesNotSqueezeItsRemoveButton() =
        onUiThread {
            removeButtonWidth(NARROW, LONG_TAG) shouldBeGreaterThanOrEqualTo
                removeButtonWidth(WIDTH, "invoices")
        }

    /** Either source of the preference reaches a screen as one `reducedMotion` answer. */
    @Test
    fun thePreferenceReachesTheScreensThatReadIt() =
        onUiThread {
            val seen = mutableListOf<Boolean>()
            hostedScene(WIDTH, HEIGHT) {
                ProvideReducedMotion(preference = true) { seen += reducedMotion }
            }.closing { render() }
            seen shouldContain true

            // The platform's animation setting alone is a reason too, as Android's "Remove
            // animations" is; the seam stands in for a device that reports animations off.
            val platformOnly = mutableListOf<Boolean>()
            hostedScene(WIDTH, HEIGHT) {
                ProvideReducedMotion(preference = false, readSystemAnimations = { false }) {
                    platformOnly += reducedMotion
                }
            }.closing { render() }
            platformOnly shouldContain true
        }

    @Test
    fun systemMotionSettingIsReadAgainOnResume() =
        onUiThread {
            val owner = ScreenOwner()
            var animationsEnabled = true
            var observed = false
            themedScene(WIDTH, HEIGHT, LocalLifecycleOwner provides owner) {
                ProvideReducedMotion(preference = false, readSystemAnimations = { animationsEnabled }) {
                    observed = reducedMotion
                }
            }.closing {
                render()
                observed shouldBe false
                owner.lifecycle.currentState = Lifecycle.State.STARTED
                animationsEnabled = false
                owner.lifecycle.currentState = Lifecycle.State.RESUMED
                render()
                observed shouldBe true
            }
        }

    /** How wide the tag chip's × is in a panel [width] wide, showing [tag]. */
    private fun removeButtonWidth(
        width: Int,
        tag: String,
    ): Int =
        themedScene(width, HEIGHT) {
            TagEditor(
                tags = listOf(tag),
                allTagNames = emptyList(),
                tagColors = emptyMap(),
                onAdd = {},
                onRemove = {},
            )
        }.closing {
            render()
            node { it.contentDescription()?.startsWith("Remove ") == true }.size.width
        }

    private companion object {
        const val WIDTH = 900
        const val HEIGHT = 800

        /** A detail panel's width on the narrowest layout: too narrow for a long tag and its ×. */
        const val NARROW = 260

        /** A tag name that cannot fit a narrow panel beside its chip's button. */
        const val LONG_TAG = "finance.invoices.2026.a.very.long.tag.name.for.a.narrow.panel"
    }
}
