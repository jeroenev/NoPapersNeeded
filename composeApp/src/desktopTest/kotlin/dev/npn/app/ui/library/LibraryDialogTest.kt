@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.npn.app.ui.library

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import dev.npn.app.EnglishLocaleTest
import dev.npn.app.ui.click
import dev.npn.app.ui.closing
import dev.npn.app.ui.editableField
import dev.npn.app.ui.editableText
import dev.npn.app.ui.isEnabled
import dev.npn.app.ui.node
import dev.npn.app.ui.nodes
import dev.npn.app.ui.onUiThread
import dev.npn.app.ui.texts
import dev.npn.app.ui.themedScene
import dev.npn.app.ui.type
import dev.npn.core.app.LibraryFilter
import dev.npn.core.app.LibrarySort
import dev.npn.core.app.SavedView
import dev.npn.core.app.SortField
import dev.npn.core.model.ReviewMode
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/** The library's open dialog, and the filter draft inside it, survive the screen being recreated. */
class LibraryDialogTest : EnglishLocaleTest() {
    @Test
    fun everyDialogSavesAndRestores() {
        val view = SavedView("v1", "Invoices", LibraryFilter(tagsAll = listOf("invoice")), LibrarySort(SortField.Name))
        val dialogs =
            listOf(
                LibraryDialog.Filter,
                LibraryDialog.SaveView,
                LibraryDialog.RenameView(view),
                LibraryDialog.AddTag,
                LibraryDialog.RemoveTag,
                LibraryDialog.ConfirmTrash,
            )
        val scope = SaverScope { true }
        dialogs.forEach { dialog ->
            val saved = with(LibraryDialogSaver) { scope.save(dialog) }
            LibraryDialogSaver.restore(saved.shouldNotBeNull()) shouldBe dialog
        }
        with(LibraryDialogSaver) { scope.save(null) } shouldBe null
    }

    /** Renaming a saved view starts from its name, and confirms only a changed, non-blank one. */
    @Test
    fun renamingAViewStartsFromItsName() =
        onUiThread {
            val view = SavedView("v1", "Invoices")
            var renamed: String? = null
            themedScene(WIDTH, HEIGHT) {
                LibraryDialogHost(
                    dialog = LibraryDialog.RenameView(view),
                    selectedCount = 0,
                    tagOptions = emptyList(),
                    onSaveView = {},
                    onRenameView = { _, name -> renamed = name },
                    onAddTag = {},
                    onRemoveTag = {},
                    onTrash = {},
                    onDismiss = {},
                )
            }.closing {
                render()
                editableField()?.editableText() shouldBe "Invoices"
                renameButton().isEnabled() shouldBe false

                type("  ")
                renameButton().isEnabled() shouldBe false

                type("Bills ")
                renameButton().isEnabled() shouldBe true
                click { "Rename" in it.texts() && it.config.contains(SemanticsActions.OnClick) }
                renamed shouldBe "Bills"
            }
        }

    private fun ImageComposeScene.renameButton(): SemanticsNode =
        node { "Rename" in it.texts() && it.config.contains(SemanticsActions.OnClick) }

    /**
     * An open filter editor with an unapplied change is still open, with the change, after the
     * screen's state is saved and restored. With the editor's visibility kept outside saved state,
     * the draft's own saver never had anything to restore into.
     */
    @Test
    fun anOpenFilterKeepsItsDraftAcrossRecreation() =
        onUiThread {
            val first = SaveableStateRegistry(restoredValues = null) { true }
            lateinit var dialog: MutableState<LibraryDialog?>
            val saved =
                scene(first) { dialog = it }.closing {
                    render()
                    dialog.value = LibraryDialog.Filter
                    render()
                    untaggedSwitch()
                        ?.config
                        ?.get(SemanticsActions.OnClick)
                        ?.action
                        ?.invoke()
                    render()
                    untaggedSwitch()?.config?.getOrNull(SemanticsProperties.ToggleableState) shouldBe
                        ToggleableState.On
                    first.performSave()
                }

            scene(SaveableStateRegistry(restoredValues = saved) { true }) { dialog = it }.closing {
                render()
                dialog.value shouldBe LibraryDialog.Filter
                untaggedSwitch()?.config?.getOrNull(SemanticsProperties.ToggleableState) shouldBe
                    ToggleableState.On
            }
        }

    /**
     * An open filter editor keeps its unapplied draft when it moves between the side panel's call
     * site and the sheet's: the window crossing the wide/compact breakpoint disposes one subtree and
     * composes the other, and the draft lives above that split.
     */
    @Test
    fun anOpenFilterKeepsItsDraftWhenTheEditorMovesCallSites() =
        onUiThread {
            val sidePanel = mutableStateOf(true)
            lateinit var dialog: MutableState<LibraryDialog?>
            scene(SaveableStateRegistry(restoredValues = null) { true }, sidePanel) { dialog = it }.closing {
                render()
                dialog.value = LibraryDialog.Filter
                render()
                untaggedSwitch()
                    ?.config
                    ?.get(SemanticsActions.OnClick)
                    ?.action
                    ?.invoke()
                render()
                untaggedSwitch()?.config?.getOrNull(SemanticsProperties.ToggleableState) shouldBe
                    ToggleableState.On

                // The window crosses the breakpoint: the editor moves to its other call site.
                sidePanel.value = false
                render()
                untaggedSwitch()?.config?.getOrNull(SemanticsProperties.ToggleableState) shouldBe
                    ToggleableState.On
            }
        }

    /** The part of the library screen under test: its dialog value and, when open, the filter panel. */
    private fun scene(
        registry: SaveableStateRegistry,
        sidePanel: MutableState<Boolean> = mutableStateOf(true),
        onDialog: (MutableState<LibraryDialog?>) -> Unit,
    ) = themedScene(WIDTH, HEIGHT, LocalSaveableStateRegistry provides registry) {
        val dialog = rememberLibraryDialog()
        onDialog(dialog)
        if (dialog.value == LibraryDialog.Filter) {
            val editor = rememberFilterEditorState(LibraryFilter())
            if (sidePanel.value) {
                FilterPanel(
                    editor = editor,
                    tags = emptyList(),
                    fields = FieldFilterOptions(),
                    reviewMode = ReviewMode.DEFAULT,
                    onApply = {},
                    onDismiss = {},
                    fillHeight = false,
                )
            } else {
                FilterPanel(
                    editor = editor,
                    tags = emptyList(),
                    fields = FieldFilterOptions(),
                    reviewMode = ReviewMode.DEFAULT,
                    onApply = {},
                    onDismiss = {},
                    fillHeight = true,
                )
            }
        }
    }

    private fun ImageComposeScene.untaggedSwitch(): SemanticsNode? =
        nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.ToggleableState) != null }

    private companion object {
        const val WIDTH = 480
        const val HEIGHT = 900
    }
}
