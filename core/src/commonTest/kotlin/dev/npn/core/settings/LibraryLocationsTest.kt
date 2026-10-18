package dev.npn.core.settings

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class LibraryLocationsTest {
    /**
     * The key names where a library's `device.db` lives, which holds what nothing could rebuild:
     * a key that changed for the same location would orphan that data. Pinned to literal values.
     */
    @Test
    fun keysAreStableForPathsAndContentUris() {
        LibraryLocations.key("/home/user/Documents/Library") shouldBe "d37921734d5d6690"
        LibraryLocations.key(
            "content://com.android.externalstorage.documents/tree/primary%3ADocuments%2FLibrary",
        ) shouldBe "aabb11bdfff9e2d9"
        // Every byte of a non-ASCII name counts, and a short hash keeps its leading zero.
        LibraryLocations.key("é") shouldBe "0ac21707b7181e01"
    }

    @Test
    fun onlyAContentSchemeIsAContentUri() {
        LibraryLocations.isContentUri("content://com.android.externalstorage.documents/tree/x").shouldBeTrue()
        LibraryLocations.isContentUri("/storage/emulated/0/Documents/Library").shouldBeFalse()
        LibraryLocations.isContentUri("/home/user/content://x").shouldBeFalse()
        LibraryLocations.isContentUri("file:///home/user/Library").shouldBeFalse()
    }
}
