package dev.npn.app.ui.detail

import androidx.compose.runtime.mutableStateOf
import dev.npn.app.MainDispatcherTest
import dev.npn.core.model.FieldType
import dev.npn.core.testing.FakeMetadataWriter
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * The editor's tag edits write at once, as they always did, and once landed join the document's
 * Undo, the triage mode's included.
 */
class DraftedTagsTest : MainDispatcherTest() {
    /** Writes that land at once, as the detail viewmodel's do once the journal accepted them. */
    private val landing =
        object : DocumentWrites by NoWrites {
            val ran = mutableListOf<String>()

            override fun addTag(
                tag: String,
                onLanded: () -> Unit,
            ) {
                ran += "+$tag"
                onLanded()
            }

            override fun removeTag(
                tag: String,
                onLanded: () -> Unit,
            ) {
                ran += "-$tag"
                onLanded()
            }

            override fun addTagWithStyle(
                tag: String,
                color: String?,
                onLanded: () -> Unit,
            ) {
                ran += "+$tag:$color"
                onLanded()
            }
        }

    @Test
    fun tagEditsJoinTheDocumentsUndo() =
        runTest(dispatcher) {
            val writer = FakeMetadataWriter()
            val store = DocumentDrafts(writer, this)
            store.registerEditing("one")
            val actions =
                DraftedActions(
                    landing,
                    store,
                    "one",
                    persisted = mutableStateOf(emptyMap()),
                    types = mutableStateOf(emptyMap<String, FieldType>()),
                    title = mutableStateOf("Invoice"),
                    reviewed = mutableStateOf(false),
                )

            actions.addTag("invoice")
            actions.removeTag("draft")
            actions.addTagWithStyle("urgent", "#ff0000")

            landing.ran shouldContainExactly listOf("+invoice", "-draft", "+urgent:#ff0000")
            store.state
                .getValue("one")
                .undo
                .shouldNotBeNull()
                .title shouldBe "Invoice"
            store.undo("one")
            runCurrent()
            writer.removedTags shouldContainExactly listOf(listOf("one") to "invoice", listOf("one") to "urgent")
            writer.addedTags shouldContainExactly listOf(listOf("one") to "draft")
        }
}
