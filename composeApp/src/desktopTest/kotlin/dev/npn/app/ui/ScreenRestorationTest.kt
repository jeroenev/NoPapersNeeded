@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.npn.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.lifecycle.ViewModelStoreOwner
import dev.npn.app.EnglishLocaleTest
import dev.npn.app.FakeLibrarySession
import dev.npn.app.ui.detail.OcrEngineRow
import dev.npn.app.ui.rules.AutoMatchRulesScreen
import dev.npn.app.ui.settings.FieldsSettingsPane
import dev.npn.app.ui.tags.TagManagementScreen
import dev.npn.app.ui.theme.NpnTheme
import dev.npn.app.ui.trash.TrashScreen
import dev.npn.core.app.OcrVersion
import dev.npn.core.app.TagInfo
import dev.npn.core.model.FieldDef
import dev.npn.core.model.FieldType
import dev.npn.core.ocr.OcrEngineId
import dev.npn.core.ocr.OcrEngineRef
import dev.npn.core.testing.doc
import dev.npn.core.testing.listItem
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * What the screens are in the middle of comes back when their state is saved and restored: a trip
 * to another tab and back, or the process being restored. A tag selection is the viewmodel's, as
 * the library's is, so it comes back where the viewmodel outlives the screen, and from the
 * viewmodel's saved state where it does not.
 */
class ScreenRestorationTest : EnglishLocaleTest() {
    @Test
    fun aTagSelectionSurvivesRecreation() =
        onUiThread {
            val session = tagSession()
            restored(
                content = { TagManagementScreen(session) },
                viewModels = viewModelStoreOwner(),
                before = { scene ->
                    scene.longClick { it.text() == "tax" }
                    scene.longClick { it.text() == "invoice" }
                    scene.has { it.text() == "2 selected" } shouldBe true
                },
                after = { scene -> scene.has { it.text() == "2 selected" } shouldBe true },
            )
        }

    @Test
    fun aTagSelectionSurvivesAProcessRestore() =
        onUiThread {
            val session = tagSession()
            restored(
                content = { TagManagementScreen(session) },
                before = { scene ->
                    scene.longClick { it.text() == "tax" }
                    scene.longClick { it.text() == "invoice" }
                    scene.has { it.text() == "2 selected" } shouldBe true
                },
                after = { scene -> scene.has { it.text() == "2 selected" } shouldBe true },
            )
        }

    @Test
    fun anOpenTagRenameKeepsItsTypedName() =
        onUiThread {
            val session = tagSession()
            restored(
                content = { TagManagementScreen(session) },
                before = { scene ->
                    scene.tap { it.text() == "tax" }
                    scene.tap { it.text() == "Rename" }
                    scene.type("taxes")
                },
                after = { scene ->
                    scene.has { it.text() == "Rename tag" } shouldBe true
                    scene.editableField()?.editableText() shouldBe "taxes"
                },
            )
        }

    @Test
    fun anOpenRuleFormKeepsWhatWasTyped() =
        onUiThread {
            val session = FakeLibrarySession()
            restored(
                content = { AutoMatchRulesScreen(session, onBack = {}) },
                before = { scene ->
                    scene.tap { it.contentDescription() == "Add rule" }
                    scene.type("Utilities")
                },
                after = { scene ->
                    scene.has { it.text() == "New rule" } shouldBe true
                    scene.editableField()?.editableText() shouldBe "Utilities"
                },
            )
        }

    @Test
    fun anOpenFieldDeletionComesBack() =
        onUiThread {
            restored(
                content = { FieldsPane(listOf(field("invoiceNo", "Invoice number"))) },
                before = { scene ->
                    scene.tap { it.contentDescription() == "Delete" }
                    scene.has { it.text() == "Delete field" } shouldBe true
                },
                after = { scene -> scene.has { it.text() == "Delete field" } shouldBe true },
            )
        }

    @Test
    fun anOpenFieldEditKeepsWhatWasTyped() =
        onUiThread {
            restored(
                content = { FieldsPane(listOf(field("invoiceNo", "Invoice number"))) },
                before = { scene ->
                    scene.tap { it.text() == "Invoice number" }
                    scene.type("Invoice no.")
                },
                after = { scene ->
                    scene.has { it.text() == "Edit field" } shouldBe true
                    scene.editableField()?.editableText() shouldBe "Invoice no."
                },
            )
        }

    @Test
    fun aFieldDialogClosesWhenItsFieldIsGone() =
        onUiThread {
            val fields = mutableStateOf(listOf(field("invoiceNo", "Invoice number")))
            scene(
                SaveableStateRegistry(restoredValues = null) {
                    true
                },
                viewModelStoreOwner(),
            ) { FieldsPane(fields.value) }.closing {
                render()
                tap { it.text() == "Invoice number" }
                has { it.text() == "Edit field" } shouldBe true

                fields.value = listOf(field("orderNo", "Order number"))
                render()
                render()
                has { it.text() == "Edit field" } shouldBe false
            }
        }

    /**
     * A restored process's trash has not loaded when the confirmation comes back: not loaded yet is
     * not "this document left the trash", so the confirmation stays and shows once the trash is in.
     */
    @Test
    fun anOpenDeleteForeverConfirmationComesBack() =
        onUiThread {
            val session =
                FakeLibrarySession().apply {
                    documents.trashed.value = listOf(listItem(doc("doc-1", trashed = true)))
                }
            restored(
                content = { TrashScreen(session) },
                before = { scene ->
                    scene.tap { it.text() == "Delete" }
                    scene.has { it.text() == "Delete forever" } shouldBe true
                },
                after = { scene -> scene.has { it.text() == "Delete forever" } shouldBe true },
            )
        }

    /** The OCR engine picker comes back open, with a choice of automatic over the pinned engine. */
    @Test
    fun anOpenOcrEnginePickerKeepsItsChoice() =
        onUiThread {
            val tesseract = OcrEngineRef.Known(OcrEngineId.TESSERACT)
            restored(
                content = {
                    OcrEngineRow(
                        indexedEngine = tesseract,
                        pinned = true,
                        versions = listOf(OcrVersion(tesseract, "desktop", "eng", createdAt = 0L, active = true)),
                        onSetEngine = {},
                    )
                },
                before = { scene ->
                    scene.tap { "Tesseract" in it.texts() }
                    scene.tap { "Automatic (best available)" in it.texts() }
                },
                after = { scene ->
                    scene.has { it.text() == "Text source for search" } shouldBe true
                    scene.node { "Automatic (best available)" in it.texts() }.isSelectedRadio() shouldBe true
                },
            )
        }

    /** Whether [this] node, or the radio row it sits in, is selected. */
    private fun SemanticsNode.isSelectedRadio(): Boolean =
        generateSequence(this) { it.parent }.any { it.config.getOrNull(SemanticsProperties.Selected) == true }

    private fun field(
        key: String,
        label: String,
    ) = FieldDef(key, label, FieldType.TEXT, showByDefault = false, aiEnabled = false, position = 0)

    @Composable
    private fun FieldsPane(fields: List<FieldDef>) {
        Column {
            FieldsSettingsPane(
                fields = fields,
                usage = emptyMap(),
                onSave = {},
                onRemove = { _, _ -> },
                onMove = { _, _ -> },
            )
        }
    }

    private fun tagSession() =
        FakeLibrarySession().apply {
            tags.tags.value = listOf(TagInfo("invoice", null, null, 2), TagInfo("tax", null, null, 1))
        }

    /**
     * Run [before] on [content], save the screen's state, compose [content] again from it and run
     * [after] on that. Each composition gets its own viewmodels, as after a process restore, which
     * get back only what they saved, unless both share [viewModels], as across a configuration
     * change.
     */
    private fun restored(
        content: @Composable () -> Unit,
        before: (ImageComposeScene) -> Unit,
        after: (ImageComposeScene) -> Unit,
        viewModels: ViewModelStoreOwner? = null,
    ) {
        val first = SaveableStateRegistry(restoredValues = null) { true }
        val firstViewModels = viewModels ?: viewModelStoreOwner()
        val saved =
            scene(first, firstViewModels, content).closing {
                render()
                before(this)
                first.performSave()
            }
        scene(
            SaveableStateRegistry(restoredValues = saved) { true },
            viewModels ?: restored(firstViewModels as ScreenOwner),
            content,
        ).closing {
            render()
            render()
            after(this)
        }
    }

    private fun scene(
        registry: SaveableStateRegistry,
        viewModels: ViewModelStoreOwner,
        content: @Composable () -> Unit,
    ): ImageComposeScene =
        hostedScene(WIDTH, HEIGHT, LocalSaveableStateRegistry provides registry, viewModels = viewModels) {
            NpnTheme(darkTheme = false) { content() }
        }

    /** Click as [click] does, and render what the click opened. */
    private fun ImageComposeScene.tap(match: (SemanticsNode) -> Boolean) {
        click(match)
        render()
    }

    /**
     * Long-click the nearest long-clickable node at or above the first node [match] finds, and render
     * twice: once to collect the viewmodel's new selection, once to show it.
     */
    private fun ImageComposeScene.longClick(match: (SemanticsNode) -> Boolean) {
        generateSequence(nodes().first(match)) { it.parent }
            .first { it.config.getOrNull(SemanticsActions.OnLongClick) != null }
            .config[SemanticsActions.OnLongClick]
            .action
            ?.invoke()
        render()
        render()
    }

    private companion object {
        const val WIDTH = 900
        const val HEIGHT = 900
    }
}
