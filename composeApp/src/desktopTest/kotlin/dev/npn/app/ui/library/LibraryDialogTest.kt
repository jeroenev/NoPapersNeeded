@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.npn.app.ui.library

import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import dev.npn.app.ui.theme.NpnTheme
import dev.npn.core.app.LibraryFilter
import dev.npn.core.app.LibrarySort
import dev.npn.core.app.SavedView
import dev.npn.core.app.SortField
import dev.npn.core.model.ReviewMode
import io.kotest.matchers.shouldBe
import java.util.Locale
import javax.swing.SwingUtilities
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/** The library's open dialog, and the filter draft inside it, survive the screen being recreated. */
class LibraryDialogTest {
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
    fun everyDialogSavesAndRestores() {
        val view = SavedView("v1", "Invoices", LibraryFilter(tagsAll = listOf("invoice")), LibrarySort(SortField.Name))
        val dialogs =
            listOf(
                LibraryDialog.Filter,
                LibraryDialog.SaveView,
                LibraryDialog.RenameView(view),
                LibraryDialog.AddTag,
                LibraryDialog.RemoveTag(listOf("invoice", "tax")),
                LibraryDialog.ConfirmTrash,
            )
        val scope = SaverScope { true }
        dialogs.forEach { dialog ->
            val saved = with(LibraryDialogSaver) { scope.save(dialog) }
            LibraryDialogSaver.restore(saved!!) shouldBe dialog
        }
        with(LibraryDialogSaver) { scope.save(null) } shouldBe null
    }

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
            val before = scene(first) { dialog = it }
            val saved =
                try {
                    before.render()
                    dialog.value = LibraryDialog.Filter
                    before.render()
                    before.untaggedSwitch()?.config?.get(SemanticsActions.OnClick)?.action?.invoke()
                    before.render()
                    before.untaggedSwitch()?.config?.getOrNull(SemanticsProperties.ToggleableState) shouldBe
                        ToggleableState.On
                    first.performSave()
                } finally {
                    before.close()
                }

            val after = scene(SaveableStateRegistry(restoredValues = saved) { true }) { dialog = it }
            try {
                after.render()
                dialog.value shouldBe LibraryDialog.Filter
                after.untaggedSwitch()?.config?.getOrNull(SemanticsProperties.ToggleableState) shouldBe
                    ToggleableState.On
            } finally {
                after.close()
            }
        }

    /** The part of the library screen under test: its dialog value and, when open, the filter panel. */
    private fun scene(
        registry: SaveableStateRegistry,
        onDialog: (MutableState<LibraryDialog?>) -> Unit,
    ) = ImageComposeScene(WIDTH, HEIGHT) {
        CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
            NpnTheme(darkTheme = false) {
                Surface {
                    val dialog = rememberLibraryDialog()
                    onDialog(dialog)
                    if (dialog.value == LibraryDialog.Filter) {
                        FilterPanel(
                            filter = LibraryFilter(),
                            tags = emptyList(),
                            fields = FieldFilterOptions(),
                            reviewMode = ReviewMode.DEFAULT,
                            onApply = {},
                        )
                    }
                }
            }
        }
    }

    private fun ImageComposeScene.untaggedSwitch(): SemanticsNode? =
        nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.ToggleableState) != null }

    private fun ImageComposeScene.nodes(): List<SemanticsNode> {
        fun descendants(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descendants)
        return semanticsOwners.flatMap { descendants(it.rootSemanticsNode) }
    }

    private fun onUiThread(block: () -> Unit) {
        var failure: Throwable? = null
        SwingUtilities.invokeAndWait { runCatching(block).onFailure { failure = it } }
        failure?.let { throw it }
    }

    private companion object {
        const val WIDTH = 480
        const val HEIGHT = 900
    }
}
