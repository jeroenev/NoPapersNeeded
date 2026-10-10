package dev.npn.core.watch

import dev.npn.core.fs.ExternalStorageTreePaths.DOWNLOADS_AUTHORITY
import dev.npn.core.fs.ExternalStorageTreePaths.EXTERNAL_STORAGE_AUTHORITY
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/** Where a SAF tree lies relative to a SAF library's tree, by their document ids. */
class TreeDocumentTest {
    private fun storage(id: String) = TreeDocument(EXTERNAL_STORAGE_AUTHORITY, id)

    private val library = storage("primary:Documents/NoPapersNeeded")

    @Test
    fun theLibrarysTreeAndEveryTreeBelowItAreInIt() {
        library.liesIn(library) shouldBe true
        storage("primary:Documents/NoPapersNeeded/scans").liesIn(library) shouldBe true
        storage("primary:Documents/NoPapersNeeded/scans/2026").liesIn(library) shouldBe true
        storage("primary:Documents").liesIn(storage("primary:")) shouldBe true
        TreeDocument(DOWNLOADS_AUTHORITY, "raw:/storage/emulated/0/Download/NPN/scans")
            .liesIn(TreeDocument(DOWNLOADS_AUTHORITY, "raw:/storage/emulated/0/Download/NPN")) shouldBe true
    }

    @Test
    fun aParentASiblingOrAnotherProviderIsNot() {
        storage("primary:Documents").liesIn(library) shouldBe false
        storage("primary:Documents/NoPapersNeeded2").liesIn(library) shouldBe false
        storage("1234-ABCD:Documents/NoPapersNeeded/scans").liesIn(library) shouldBe false
        TreeDocument("com.example.cloud", "primary:Documents/NoPapersNeeded/scans").liesIn(library) shouldBe false
    }
}
