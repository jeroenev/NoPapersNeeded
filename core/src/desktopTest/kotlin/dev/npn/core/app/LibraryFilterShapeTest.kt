package dev.npn.core.app

import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import java.lang.reflect.Modifier
import kotlin.test.Test

/**
 * [rebased] names every part of a [LibraryFilter] by hand, so a part it does not know would keep the
 * draft's value whatever the live filter did. This fails when the filter gains or loses one: give
 * the new part its line in [rebased] (and a chip in the library's active filters, if it is one the
 * user sets), then add it here.
 */
class LibraryFilterShapeTest {
    @Test
    fun rebasedKnowsEveryPartOfTheFilter() {
        val parts =
            LibraryFilter::class.java.declaredFields
                .filterNot { Modifier.isStatic(it.modifiers) }
                .map { it.name }
        parts shouldContainExactlyInAnyOrder
            listOf(
                "docIds",
                "query",
                "tagsAll",
                "tagsAny",
                "tagsNone",
                "reviewed",
                "fieldEquals",
                "fieldRanges",
                "addedFrom",
                "addedTo",
                "folder",
                "untaggedOnly",
                "unreviewedOrUntagged",
                "missingFields",
            )
    }
}
