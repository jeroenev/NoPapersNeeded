package dev.npn.core.app

import dev.npn.core.model.FieldKeys
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * Pure tests for the filter edits the library's filter editor makes: tag-bucket placement (the
 * strip-from-all-buckets-first rule, the whole-group match mode) and a draft rebased onto a live
 * filter that changed under it.
 */
class LibraryFilterEditsTest {
    @Test
    fun explicitExclusionRemovesTheTagFromIncludedGroups() {
        val filter = LibraryFilter(tagsAll = listOf("a"), tagsAny = listOf("b"))
        filter.withTag("a", TagBucket.NONE) shouldBe LibraryFilter(tagsAny = listOf("b"), tagsNone = listOf("a"))
    }

    @Test
    fun changingMatchModePreservesExclusionsAndOtherFacets() {
        val filter =
            LibraryFilter(tagsAll = listOf("a"), tagsAny = listOf("b"), tagsNone = listOf("c"), query = "invoice")
        filter.matchIncludedTags(false) shouldBe filter.copy(tagsAll = emptyList(), tagsAny = listOf("a", "b"))
        filter.matchIncludedTags(true) shouldBe filter.copy(tagsAll = listOf("a", "b"), tagsAny = emptyList())
    }

    @Test
    fun tagBucketReadsWhicheverBucketTheTagIsIn() {
        val filter =
            LibraryFilter(
                tagsAll = listOf("a"),
                tagsAny = listOf("b"),
                tagsNone = listOf("c"),
            )
        tagBucket(filter, "a") shouldBe TagBucket.ALL
        tagBucket(filter, "b") shouldBe TagBucket.ANY
        tagBucket(filter, "c") shouldBe TagBucket.NONE
        tagBucket(filter, "absent") shouldBe TagBucket.OFF
    }

    @Test
    fun everyBucketPlacementLeavesTheTagInExactlyThatBucket() {
        val tag = "finance"
        var filter = LibraryFilter()
        // Each placement must be absolute, whatever the tag's previous bucket was: the tag is
        // never present in more than one of the three bucket lists at once.
        listOf(TagBucket.ALL, TagBucket.ANY, TagBucket.NONE, TagBucket.OFF).forEach { bucket ->
            filter = filter.withTag(tag, bucket)
            tagBucket(filter, tag) shouldBe bucket
            val presentIn = listOf(filter.tagsAll, filter.tagsAny, filter.tagsNone).count { tag in it }
            presentIn shouldBe if (bucket == TagBucket.OFF) 0 else 1
        }
        // Clearing a tag leaves nothing behind in any of the three lists.
        filter shouldBe LibraryFilter()
    }

    @Test
    fun placingATagStripsItFromAllBucketsFirst() {
        // A hand-corrupted filter with the SAME tag in two buckets at once. Placing it must not
        // leave a stale copy behind: afterwards the tag lives in exactly one bucket.
        val corrupt = LibraryFilter(tagsAll = listOf("dup"), tagsNone = listOf("dup"))
        val placed = corrupt.withTag("dup", TagBucket.ANY)
        placed.tagsAll shouldContainExactly emptyList()
        placed.tagsNone shouldContainExactly emptyList()
        placed.tagsAny shouldContainExactly listOf("dup")
    }

    @Test
    fun placingOneTagLeavesOtherTagsInTheirBucketsUntouched() {
        val filter =
            LibraryFilter(
                tagsAll = listOf("keep-all"),
                tagsAny = listOf("keep-any"),
                tagsNone = listOf("keep-none"),
            )
        val placed = filter.withTag("new", TagBucket.ALL)
        placed.tagsAll shouldContainExactly listOf("keep-all", "new")
        placed.tagsAny shouldContainExactly listOf("keep-any")
        placed.tagsNone shouldContainExactly listOf("keep-none")
    }

    @Test
    fun aDraftRebasedOnANewSearchKeepsItsEdits() {
        val live = LibraryFilter(tagsAll = listOf("bills"))
        val draft =
            live.copy(
                tagsAll = listOf("bills", "2024"),
                fieldRanges = mapOf(FieldKeys.DOC_DATE to FieldRange(from = "2024-01-01")),
            )

        draft.rebased(live, live.copy(query = "electricity")) shouldBe draft.copy(query = "electricity")
    }

    @Test
    fun aDraftRebasedOnARemovedChipLosesOnlyThatPart() {
        val live =
            LibraryFilter(
                tagsAll = listOf("bills"),
                fieldRanges = mapOf("amount" to FieldRange(from = "10")),
            )
        val draft =
            live.copy(
                tagsAll = listOf("bills", "2024"),
                tagsNone = listOf("paid"),
                fieldRanges = live.fieldRanges + (FieldKeys.DOC_DATE to FieldRange(from = "2024-01-01")),
            )

        draft.rebased(live, live.copy(tagsAll = emptyList())) shouldBe
            draft.copy(tagsAll = listOf("2024"))
        draft.rebased(live, live.withFieldBounds("amount", from = null)) shouldBe
            draft.copy(fieldRanges = mapOf(FieldKeys.DOC_DATE to FieldRange(from = "2024-01-01")))
    }

    @Test
    fun theLiveChangeWinsWhereBothChangedAPart() {
        val live = LibraryFilter()
        val draft = LibraryFilter(tagsAll = listOf("2024"), reviewed = true)
        val now = LibraryFilter(tagsNone = listOf("2024"), reviewed = false, untaggedOnly = true)

        draft.rebased(live, now) shouldBe now
        draft.rebased(live, live) shouldBe draft
    }
}
