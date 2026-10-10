package dev.npn.app

import dev.npn.app.ui.library.ActiveFilterChip
import dev.npn.app.ui.library.TagBucket
import dev.npn.app.ui.library.activeChips
import dev.npn.app.ui.library.matchIncludedTags
import dev.npn.app.ui.library.rebased
import dev.npn.app.ui.library.removeChip
import dev.npn.app.ui.library.tagBucket
import dev.npn.app.ui.library.withTag
import dev.npn.core.app.FieldRange
import dev.npn.core.app.LibraryFilter
import dev.npn.core.model.FieldKeys
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * Pure tests for tag-bucket placement and the active-chip composition — value in, value out, no
 * Compose. These own the invariants that would otherwise live in untested private helpers in
 * FilterSheet and ActiveFilters: the strip-from-all-buckets-first rule, the whole-group match
 * mode, chip ordering, and per-chip removal clearing exactly the right facet.
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
    fun emptyFilterProducesNoChips() {
        LibraryFilter().activeChips() shouldBe emptyList()
    }

    @Test
    fun chipsAreOrderedDocIdsFirstThenTagsThenSingleValueFacetsThenToggles() {
        val filter =
            LibraryFilter(
                docIds = listOf("d1", "d2", "d3"),
                tagsAll = listOf("a1", "a2"),
                tagsAny = listOf("o1"),
                tagsNone = listOf("n1"),
                reviewed = true,
                fieldEquals =
                    mapOf(
                        FieldKeys.CORRESPONDENT to "ACME",
                        FieldKeys.DOC_TYPE to "invoice",
                    ),
                fieldRanges = mapOf(FieldKeys.DOC_DATE to FieldRange("2024-01-01", "2024-12-31")),
                untaggedOnly = true,
                unreviewedOrUntagged = true,
            )
        filter.activeChips() shouldContainExactly
            listOf(
                ActiveFilterChip.DocIds(3),
                ActiveFilterChip.TagAll("a1"),
                ActiveFilterChip.TagAll("a2"),
                ActiveFilterChip.TagAny("o1"),
                ActiveFilterChip.TagNone("n1"),
                ActiveFilterChip.Reviewed(true),
                ActiveFilterChip.FieldEquals(FieldKeys.CORRESPONDENT, "ACME"),
                ActiveFilterChip.FieldEquals(FieldKeys.DOC_TYPE, "invoice"),
                ActiveFilterChip.FieldFrom(FieldKeys.DOC_DATE, "2024-01-01"),
                ActiveFilterChip.FieldTo(FieldKeys.DOC_DATE, "2024-12-31"),
                ActiveFilterChip.UntaggedOnly,
                ActiveFilterChip.ReviewQueue,
            )
    }

    @Test
    fun reviewedChipCarriesTheFilteredValue() {
        LibraryFilter(reviewed = false).activeChips() shouldContainExactly
            listOf(ActiveFilterChip.Reviewed(false))
    }

    @Test
    fun aDocumentSetRestrictionIsVisibleAsOneRemovableChip() {
        // The AI assistant's "open all documents" is the only way to get here, and it is the only
        // facet the filter sheet cannot re-create -- so without this chip the restriction is
        // invisible and the user cannot get back to the full library.
        val filter = LibraryFilter(docIds = listOf("d1", "d2"))
        filter.activeChips() shouldContainExactly listOf(ActiveFilterChip.DocIds(2))
        filter.removeChip(ActiveFilterChip.DocIds(2)) shouldBe LibraryFilter()
    }

    @Test
    fun onlyActiveFacetsProduceChips() {
        LibraryFilter(
            fieldEquals = mapOf(FieldKeys.CORRESPONDENT to "ACME"),
            untaggedOnly = true,
        ).activeChips() shouldContainExactly
            listOf(ActiveFilterChip.FieldEquals(FieldKeys.CORRESPONDENT, "ACME"), ActiveFilterChip.UntaggedOnly)
    }

    @Test
    fun removingATagChipClearsOnlyThatTagFromOnlyItsBucket() {
        // Same tag string in ALL and NONE; removing the ALL chip must not touch the NONE entry.
        val filter = LibraryFilter(tagsAll = listOf("x", "y"), tagsNone = listOf("x"))
        val removed = filter.removeChip(ActiveFilterChip.TagAll("x"))
        removed.tagsAll shouldContainExactly listOf("y")
        removed.tagsNone shouldContainExactly listOf("x")
    }

    @Test
    fun removingEachSingleValueChipNullsExactlyThatField() {
        val full =
            LibraryFilter(
                tagsAny = listOf("t"),
                reviewed = true,
                fieldEquals =
                    mapOf(
                        FieldKeys.CORRESPONDENT to "ACME",
                        FieldKeys.DOC_TYPE to "invoice",
                    ),
                fieldRanges = mapOf(FieldKeys.DOC_DATE to FieldRange("2024-01-01", "2024-12-31")),
                untaggedOnly = true,
                unreviewedOrUntagged = true,
            )
        full.removeChip(ActiveFilterChip.TagAny("t")).tagsAny shouldBe emptyList()
        full.removeChip(ActiveFilterChip.Reviewed(true)).reviewed shouldBe null
        full
            .removeChip(
                ActiveFilterChip.FieldEquals(FieldKeys.CORRESPONDENT, "ACME"),
            ).fieldEquals[FieldKeys.CORRESPONDENT]
            .shouldBeNull()
        full
            .removeChip(ActiveFilterChip.FieldEquals(FieldKeys.DOC_TYPE, "invoice"))
            .fieldEquals[FieldKeys.DOC_TYPE]
            .shouldBeNull()
        // Clearing the lower bound keeps the upper one on the same range.
        full
            .removeChip(ActiveFilterChip.FieldFrom(FieldKeys.DOC_DATE, "2024-01-01"))
            .fieldRanges[FieldKeys.DOC_DATE] shouldBe FieldRange(from = null, to = "2024-12-31")
        full
            .removeChip(ActiveFilterChip.FieldTo(FieldKeys.DOC_DATE, "2024-12-31"))
            .fieldRanges[FieldKeys.DOC_DATE] shouldBe FieldRange(from = "2024-01-01", to = null)
        full.removeChip(ActiveFilterChip.UntaggedOnly).untaggedOnly shouldBe false
        full.removeChip(ActiveFilterChip.ReviewQueue).unreviewedOrUntagged shouldBe false
    }

    @Test
    fun removingOneChipLeavesEveryOtherFacetUntouched() {
        val full =
            LibraryFilter(
                tagsAll = listOf("a"),
                reviewed = true,
                fieldEquals = mapOf(FieldKeys.CORRESPONDENT to "ACME"),
                untaggedOnly = true,
            )
        val afterReviewed = full.removeChip(ActiveFilterChip.Reviewed(true))
        afterReviewed shouldBe full.copy(reviewed = null)
    }

    @Test
    fun removeChipIsTheInverseOfActiveChipsForEachFacet() {
        // Removing every chip in turn from a fully-populated filter empties it completely.
        val full =
            LibraryFilter(
                docIds = listOf("d"),
                tagsAll = listOf("a"),
                tagsAny = listOf("o"),
                tagsNone = listOf("n"),
                reviewed = true,
                fieldEquals =
                    mapOf(
                        FieldKeys.CORRESPONDENT to "ACME",
                        FieldKeys.DOC_TYPE to "invoice",
                    ),
                fieldRanges = mapOf(FieldKeys.DOC_DATE to FieldRange("2024-01-01", "2024-12-31")),
                untaggedOnly = true,
                unreviewedOrUntagged = true,
            )
        val emptied = full.activeChips().fold(full) { acc, chip -> acc.removeChip(chip) }
        emptied shouldBe LibraryFilter()
    }

    /** A search typed beside the panel replaces the draft's search and nothing it drafted. */
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

    /** A chip removed beside the panel takes only its own tag or bound out of the draft. */
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

        draft.rebased(live, live.removeChip(ActiveFilterChip.TagAll("bills"))) shouldBe
            draft.copy(tagsAll = listOf("2024"))
        draft.rebased(live, live.removeChip(ActiveFilterChip.FieldFrom("amount", "10"))) shouldBe
            draft.copy(fieldRanges = mapOf(FieldKeys.DOC_DATE to FieldRange(from = "2024-01-01")))
    }

    /** A part both changed takes the live value; an unchanged live filter leaves the draft as it is. */
    @Test
    fun theLiveChangeWinsWhereBothChangedAPart() {
        val live = LibraryFilter()
        val draft = LibraryFilter(tagsAll = listOf("2024"), reviewed = true)
        val now = LibraryFilter(tagsNone = listOf("2024"), reviewed = false, untaggedOnly = true)

        draft.rebased(live, now) shouldBe now
        draft.rebased(live, live) shouldBe draft
    }
}
