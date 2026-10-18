package dev.npn.app.ui.library

import dev.npn.core.app.LibraryFilter
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/** How the filter editor reads the draft's included tags for its all/any toggle. */
class FilterEditorStateTest {
    private fun editor(
        draft: LibraryFilter,
        matchAll: Boolean = true,
    ) = FilterEditorState(draft = draft, live = draft, tagQuery = "", matchAll = matchAll)

    @Test
    fun includedTagsSayHowTheyMatch() {
        editor(LibraryFilter(tagsAll = listOf("a")), matchAll = false).matchesAllTags shouldBe true
        editor(LibraryFilter(tagsAny = listOf("a")), matchAll = true).matchesAllTags shouldBe false
    }

    @Test
    fun withNoTagsIncludedTheToggleSays() {
        editor(LibraryFilter(), matchAll = true).matchesAllTags shouldBe true
        editor(LibraryFilter(), matchAll = false).matchesAllTags shouldBe false
    }

    @Test
    fun tagsIncludedBothWaysAreMixed() {
        editor(LibraryFilter(tagsAll = listOf("a"), tagsAny = listOf("b"))).mixedTagMatch shouldBe true
        editor(LibraryFilter(tagsAll = listOf("a"))).mixedTagMatch shouldBe false
        editor(LibraryFilter()).mixedTagMatch shouldBe false
    }

    /** The draft follows a filter applied elsewhere, keeping the edits that filter did not touch. */
    @Test
    fun bringingUpToAFilterKeepsUntouchedEdits() {
        val editor = editor(LibraryFilter())
        editor.draft = LibraryFilter(untaggedOnly = true)

        editor.bringUpTo(LibraryFilter(query = "tax"))

        editor.draft shouldBe LibraryFilter(query = "tax", untaggedOnly = true)
        editor.live shouldBe LibraryFilter(query = "tax")
    }
}
