package dev.npn.app.ui.library

import dev.npn.core.app.FieldRange
import dev.npn.core.app.LibraryFilter
import dev.npn.core.model.FieldKeys
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * Pure tests for the active-chip composition: chip ordering, and per-chip removal clearing exactly
 * the right facet.
 */
class ActiveFilterChipTest {
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
}
