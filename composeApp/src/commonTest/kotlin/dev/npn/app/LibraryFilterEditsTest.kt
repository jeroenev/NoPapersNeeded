package dev.npn.app

import dev.npn.app.ui.library.FilterChip
import dev.npn.app.ui.library.TagBucket
import dev.npn.app.ui.library.activeChips
import dev.npn.app.ui.library.cycleTag
import dev.npn.app.ui.library.removeChip
import dev.npn.app.ui.library.tagBucket
import dev.npn.core.app.LibraryFilter
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * Pure tests for the tag-bucket cycle and the active-chip composition — value in, value out, no
 * Compose. These own the invariants that used to be untested private helpers in FilterSheet and
 * ActiveFilters: the OFF->ALL->ANY->NONE->OFF cycle, the strip-from-all-buckets-first rule, chip
 * ordering, and per-chip removal clearing exactly the right facet.
 */
class LibraryFilterEditsTest {
    // ---- tagBucket ----

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

    // ---- cycleTag: the full OFF -> ALL -> ANY -> NONE -> OFF loop ----

    @Test
    fun cyclingATagWalksAllFourBucketStatesAndReturnsToOff() {
        val tag = "finance"
        val start = LibraryFilter()
        tagBucket(start, tag) shouldBe TagBucket.OFF

        val all = cycleTag(start, tag)
        tagBucket(all, tag) shouldBe TagBucket.ALL
        all.tagsAll shouldContainExactly listOf(tag)

        val any = cycleTag(all, tag)
        tagBucket(any, tag) shouldBe TagBucket.ANY
        any.tagsAny shouldContainExactly listOf(tag)

        val none = cycleTag(any, tag)
        tagBucket(none, tag) shouldBe TagBucket.NONE
        none.tagsNone shouldContainExactly listOf(tag)

        val off = cycleTag(none, tag)
        tagBucket(off, tag) shouldBe TagBucket.OFF
        off shouldBe LibraryFilter()
    }

    @Test
    fun eachCycleStepLeavesTheTagInExactlyOneBucket() {
        var filter = LibraryFilter()
        val tag = "x"
        // Walk the whole loop and assert the strip-first invariant at every step: the tag is never
        // present in more than one of the three bucket lists at once.
        repeat(4) {
            filter = cycleTag(filter, tag)
            val presentIn =
                listOf(filter.tagsAll, filter.tagsAny, filter.tagsNone).count { tag in it }
            presentIn shouldBe if (tagBucket(filter, tag) == TagBucket.OFF) 0 else 1
        }
    }

    @Test
    fun cyclingStripsTheTagFromAllBucketsBeforeReplacingIt() {
        // A hand-corrupted filter with the SAME tag in two buckets at once. Cycling must not leave a
        // stale copy behind: after one cycle the tag lives in exactly one bucket.
        val corrupt = LibraryFilter(tagsAll = listOf("dup"), tagsNone = listOf("dup"))
        // tagBucket sees ALL first, so the next bucket is ANY; both the ALL and the stale NONE entry
        // must be gone.
        val cycled = cycleTag(corrupt, "dup")
        cycled.tagsAll shouldContainExactly emptyList()
        cycled.tagsNone shouldContainExactly emptyList()
        cycled.tagsAny shouldContainExactly listOf("dup")
    }

    @Test
    fun cyclingOneTagLeavesOtherTagsInTheirBucketsUntouched() {
        val filter =
            LibraryFilter(
                tagsAll = listOf("keep-all"),
                tagsAny = listOf("keep-any"),
                tagsNone = listOf("keep-none"),
            )
        val cycled = cycleTag(filter, "new")
        cycled.tagsAll shouldContainExactly listOf("keep-all", "new")
        cycled.tagsAny shouldContainExactly listOf("keep-any")
        cycled.tagsNone shouldContainExactly listOf("keep-none")
    }

    // ---- activeChips: which facets show, in what order ----

    @Test
    fun emptyFilterProducesNoChips() {
        LibraryFilter().activeChips() shouldBe emptyList()
    }

    @Test
    fun chipsAreOrderedTagsFirstThenSingleValueFacetsThenToggles() {
        val filter =
            LibraryFilter(
                tagsAll = listOf("a1", "a2"),
                tagsAny = listOf("o1"),
                tagsNone = listOf("n1"),
                reviewed = true,
                correspondent = "ACME",
                docType = "invoice",
                docDateFrom = "2024-01-01",
                docDateTo = "2024-12-31",
                untaggedOnly = true,
                unreviewedOrUntagged = true,
            )
        filter.activeChips() shouldContainExactly
            listOf(
                FilterChip.TagAll("a1"),
                FilterChip.TagAll("a2"),
                FilterChip.TagAny("o1"),
                FilterChip.TagNone("n1"),
                FilterChip.Reviewed(true),
                FilterChip.Correspondent("ACME"),
                FilterChip.DocType("invoice"),
                FilterChip.DocDateFrom("2024-01-01"),
                FilterChip.DocDateTo("2024-12-31"),
                FilterChip.UntaggedOnly,
                FilterChip.ReviewQueue,
            )
    }

    @Test
    fun reviewedChipCarriesTheFilteredValue() {
        LibraryFilter(reviewed = false).activeChips() shouldContainExactly
            listOf(FilterChip.Reviewed(false))
    }

    @Test
    fun onlyActiveFacetsProduceChips() {
        LibraryFilter(correspondent = "ACME", untaggedOnly = true).activeChips() shouldContainExactly
            listOf(FilterChip.Correspondent("ACME"), FilterChip.UntaggedOnly)
    }

    // ---- removeChip: each chip clears exactly the right facet ----

    @Test
    fun removingATagChipClearsOnlyThatTagFromOnlyItsBucket() {
        // Same tag string in ALL and NONE; removing the ALL chip must not touch the NONE entry.
        val filter = LibraryFilter(tagsAll = listOf("x", "y"), tagsNone = listOf("x"))
        val removed = filter.removeChip(FilterChip.TagAll("x"))
        removed.tagsAll shouldContainExactly listOf("y")
        removed.tagsNone shouldContainExactly listOf("x")
    }

    @Test
    fun removingEachSingleValueChipNullsExactlyThatField() {
        val full =
            LibraryFilter(
                tagsAny = listOf("t"),
                reviewed = true,
                correspondent = "ACME",
                docType = "invoice",
                docDateFrom = "2024-01-01",
                docDateTo = "2024-12-31",
                untaggedOnly = true,
                unreviewedOrUntagged = true,
            )
        full.removeChip(FilterChip.TagAny("t")).tagsAny shouldBe emptyList()
        full.removeChip(FilterChip.Reviewed(true)).reviewed shouldBe null
        full.removeChip(FilterChip.Correspondent("ACME")).correspondent shouldBe null
        full.removeChip(FilterChip.DocType("invoice")).docType shouldBe null
        full.removeChip(FilterChip.DocDateFrom("2024-01-01")).docDateFrom shouldBe null
        full.removeChip(FilterChip.DocDateTo("2024-12-31")).docDateTo shouldBe null
        full.removeChip(FilterChip.UntaggedOnly).untaggedOnly shouldBe false
        full.removeChip(FilterChip.ReviewQueue).unreviewedOrUntagged shouldBe false
    }

    @Test
    fun removingOneChipLeavesEveryOtherFacetUntouched() {
        val full =
            LibraryFilter(
                tagsAll = listOf("a"),
                reviewed = true,
                correspondent = "ACME",
                untaggedOnly = true,
            )
        val afterReviewed = full.removeChip(FilterChip.Reviewed(true))
        afterReviewed shouldBe full.copy(reviewed = null)
    }

    @Test
    fun removeChipIsTheInverseOfActiveChipsForEachFacet() {
        // Removing every chip in turn from a fully-populated filter empties it completely.
        val full =
            LibraryFilter(
                tagsAll = listOf("a"),
                tagsAny = listOf("o"),
                tagsNone = listOf("n"),
                reviewed = true,
                correspondent = "ACME",
                docType = "invoice",
                docDateFrom = "2024-01-01",
                docDateTo = "2024-12-31",
                untaggedOnly = true,
                unreviewedOrUntagged = true,
            )
        val emptied = full.activeChips().fold(full) { acc, chip -> acc.removeChip(chip) }
        emptied shouldBe LibraryFilter()
    }
}
