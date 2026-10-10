@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.npn.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import dev.npn.app.platform.FolderPick
import dev.npn.app.ui.theme.NpnTheme
import dev.npn.core.ai.AiProvider
import dev.npn.core.app.SettingsSnapshot
import dev.npn.core.model.FieldDef
import dev.npn.core.model.ReviewMode
import io.kotest.matchers.shouldBe
import java.util.Locale
import javax.swing.SwingUtilities
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Settings search finds a pane by the labels of the settings inside it ([SettingsCategory.keywords]).
 * Those lists are kept by hand, so this renders every pane with everything it can show switched on
 * and checks that each label a user can see there finds its own pane.
 */
class SettingsSearchCoverageTest {
    private var previousLocale: Locale? = null

    @BeforeTest
    fun pinEnglishLocale() {
        previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
    }

    @AfterTest
    fun restoreLocale() {
        previousLocale?.let { Locale.setDefault(it) }
    }

    @Test
    fun everyVisibleSettingLabelFindsItsPane() =
        onUiThread {
            val unsearchable =
                SettingsCategories.flatMap { category ->
                    val (labels, search) = render(category)
                    labels.filterNot { search.finds(it) }.map { "${category.id}: $it" }
                }
            unsearchable shouldBe emptyList()
        }

    /** The labels [category]'s pane shows, and what search knows of it. */
    private fun render(category: SettingsCategory): Pair<List<String>, SettingsCategoryLabels> {
        var search: SettingsCategoryLabels? = null
        val scene =
            ImageComposeScene(WIDTH, HEIGHT) {
                NpnTheme(darkTheme = false) {
                    search = settingsCategoryLabels().single { it.category.id == category.id }
                    Column { category.content(EVERYTHING_SHOWN, NoActions) }
                }
            }
        try {
            scene.render()
            return scene.labels() to checkNotNull(search)
        } finally {
            scene.close()
        }
    }

    /** Whether searching for [label] lists this pane; a label with a value in it is searched by its name. */
    private fun SettingsCategoryLabels.finds(label: String): Boolean =
        matches(label) || keywords.any { label.startsWith(it, ignoreCase = true) }

    /** Section headings, switches, buttons and text fields, by the text they show or announce. */
    private fun ImageComposeScene.labels(): List<String> =
        nodes()
            .mapNotNull { node ->
                val config = node.config
                val role = config.getOrNull(SemanticsProperties.Role)
                val isLabel =
                    config.getOrNull(SemanticsProperties.Heading) != null ||
                        role == Role.Switch ||
                        role == Role.Button ||
                        config.getOrNull(SemanticsProperties.EditableText) != null
                if (!isLabel) return@mapNotNull null
                config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text
                    ?: config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()
            }.filter { it.isNotBlank() && it !in NOT_SETTINGS }
            .distinct()

    private fun ImageComposeScene.nodes(): List<SemanticsNode> {
        fun descendants(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descendants)
        return semanticsOwners.flatMap { descendants(it.rootSemanticsNode) }
    }

    private fun onUiThread(block: () -> Unit) {
        var failure: Throwable? = null
        SwingUtilities.invokeAndWait { runCatching(block).onFailure { failure = it } }
        failure?.let { throw it }
    }

    private object NoActions : SettingsPaneActions {
        override fun edit(transform: (SettingsSnapshot) -> SettingsSnapshot) = Unit

        override fun setStoragePathTemplate(template: String) = Unit

        override fun setStoragePathEnabled(enabled: Boolean) = Unit

        override fun setAiProvider(provider: AiProvider) = Unit

        override fun loadAiModels() = Unit

        override fun runAiSelfTest() = Unit

        override fun clearThumbnailCache() = Unit

        override fun saveField(def: FieldDef) = Unit

        override fun removeField(
            key: String,
            clearValues: Boolean,
        ) = Unit

        override fun moveField(
            key: String,
            delta: Int,
        ) = Unit

        override fun setUntaggedOnlyMode() = Unit

        override fun setInboxWorkflowMode(migration: ReviewMigration) = Unit

        override fun watchedFolderPicked(pick: FolderPick) = Unit

        override fun changeLibrary() = Unit

        override fun openAutoMatchRules() = Unit
    }

    private companion object {
        const val WIDTH = 900
        const val HEIGHT = 4_000

        /** Every pane at its fullest: AI on with an editable endpoint, a watched folder, manual OCR languages. */
        val EVERYTHING_SHOWN =
            SettingsPaneState(
                settings =
                    SettingsUiState(
                        snapshot =
                            SettingsSnapshot(
                                installationId = "test",
                                libraryLocation = "/library",
                                watchedFolder = "/inbox",
                                ocrLanguage = "eng",
                                aiEnabled = true,
                                aiProvider = AiProvider.CUSTOM,
                            ),
                    ),
                aiModels = AiModelListState(),
                aiSelfTest = AiSelfTestState(),
                reviewMode = ReviewMode.INBOX_WORKFLOW,
                fields = emptyList(),
                fieldUsage = emptyMap(),
            )

        /** Shown, but not the name of a setting: the button that adds a typed OCR language code. */
        val NOT_SETTINGS = setOf("Add")
    }
}
