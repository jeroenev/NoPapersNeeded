@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.npn.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import dev.npn.app.platform.FolderPick
import dev.npn.app.ui.detail.nodes
import dev.npn.app.ui.detail.onUiThread
import dev.npn.app.ui.theme.NpnTheme
import dev.npn.core.ai.AiProvider
import dev.npn.core.model.FieldDef
import dev.npn.core.model.ReviewMode
import dev.npn.core.settings.SettingsSnapshot
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * The AI endpoint's text fields against a real composition, with saved settings that come back on
 * their own schedule, the way a settings file written out whole does: what is typed shows at once
 * and is what gets saved, whatever the saved value says meanwhile.
 */
class AiEndpointFieldsTest {
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
    fun keysTypedBeforeTheSavedValueCatchesUpAreKept() =
        onUiThread {
            for (field in Field.entries) {
                pane {
                    type(field, "a")
                    type(field, "b")
                    withClue(field) {
                        if (field != Field.API_KEY) shown(field) shouldBe field.start + "ab"
                        typed(field).last() shouldBe field.start + "ab"
                    }
                }
            }
        }

    /** A keystroke's own value coming back late is no edit; a value changed elsewhere replaces the text. */
    @Test
    fun onlyAValueChangedElsewhereReplacesWhatIsTyped() =
        onUiThread {
            pane {
                type(Field.BASE_URL, "a")
                type(Field.BASE_URL, "b")
                persisted = persisted.copy(aiBaseUrl = Field.BASE_URL.start + "a")
                scene.render()
                shown(Field.BASE_URL) shouldBe Field.BASE_URL.start + "ab"
                persisted = persisted.copy(aiBaseUrl = Field.BASE_URL.start + "ab")
                scene.render()
                shown(Field.BASE_URL) shouldBe Field.BASE_URL.start + "ab"

                // Another provider drops the base URL of the one before.
                persisted = persisted.copy(aiBaseUrl = "")
                scene.render()
                shown(Field.BASE_URL) shouldBe ""
            }
        }

    /** Leaving a field saves what was typed in it, without waiting for the pause. */
    @Test
    fun leavingAFieldSavesIt() =
        onUiThread {
            pane {
                type(Field.BASE_URL, "a")
                done shouldBe emptyList()
                type(Field.MODEL, "b")
                done shouldBe listOf(TextSetting.AI_BASE_URL)
            }
        }

    /** The three fields, by label, with what the saved settings hold in each. */
    private enum class Field(
        val label: String,
        val start: String,
        val setting: TextSetting,
    ) {
        BASE_URL("Server URL", "http://192.168.1.20:11434", TextSetting.AI_BASE_URL),
        API_KEY("API key", "sk-", TextSetting.AI_API_KEY),
        MODEL("Model", "qwen3:8", TextSetting.AI_MODEL),
    }

    private inner class Pane : SettingsPaneActions {
        /** The settings as saved; never caught up here, as a slow write would not be yet. */
        var persisted by mutableStateOf(
            SettingsSnapshot(
                installationId = "test",
                aiEnabled = true,
                aiProvider = AiProvider.CUSTOM,
                aiBaseUrl = Field.BASE_URL.start,
                aiApiKey = Field.API_KEY.start,
                aiModel = Field.MODEL.start,
            ),
        )
        private val values = mutableListOf<Pair<TextSetting, String>>()
        val done = mutableListOf<TextSetting>()
        val scene =
            ImageComposeScene(WIDTH, HEIGHT) {
                NpnTheme(darkTheme = false) {
                    Column { AiSettingsPane(state(persisted), this@Pane) }
                }
            }

        /** Every value handed on as typed into [field]. */
        fun typed(field: Field): List<String> = values.filter { it.first == field.setting }.map { it.second }

        fun field(field: Field): SemanticsNode =
            scene.nodes().first { node ->
                node.config.getOrNull(SemanticsProperties.EditableText) != null &&
                    node.config
                        .getOrNull(SemanticsProperties.Text)
                        .orEmpty()
                        .any { it.text == field.label }
            }

        fun shown(field: Field): String? = field(field).config.getOrNull(SemanticsProperties.EditableText)?.text

        /** Type [key] at the end of what [field] shows, as a keyboard does, and let the screen draw it. */
        fun type(
            field: Field,
            key: String,
        ) {
            field(field).config[SemanticsActions.RequestFocus].action?.invoke()
            scene.render()
            val end = field(field).config[SemanticsProperties.EditableText].length
            field(field).config[SemanticsActions.SetSelection].action?.invoke(end, end, false)
            scene.render()
            field(field).config[SemanticsActions.InsertTextAtCursor].action?.invoke(AnnotatedString(key))
            scene.render()
        }

        override fun type(
            setting: TextSetting,
            value: String,
        ) {
            values += setting to value
        }

        override fun doneTyping(setting: TextSetting) {
            done += setting
        }

        override fun edit(transform: (SettingsSnapshot) -> SettingsSnapshot) = Unit

        override fun setStoragePathTemplate(template: String) = Unit

        override fun setStoragePathEnabled(enabled: Boolean) = Unit

        override fun setTrashRetentionDays(days: Int) = Unit

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

    private fun pane(block: Pane.() -> Unit) {
        val pane = Pane()
        try {
            pane.scene.render()
            pane.block()
        } finally {
            pane.scene.close()
        }
    }

    private companion object {
        const val WIDTH = 900
        const val HEIGHT = 3_000

        fun state(snapshot: SettingsSnapshot) =
            SettingsPaneState(
                settings = SettingsUiState(snapshot = snapshot),
                aiModels = AiModelListState(),
                aiSelfTest = AiSelfTestState(),
                reviewMode = ReviewMode.DEFAULT,
                fields = emptyList(),
                fieldUsage = emptyMap(),
            )
    }
}
