package dev.npn.core.fs

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class ExternalStorageTreePathsTest {
    private val primary = "/storage/emulated/0"

    private fun resolve(
        authority: String,
        docId: String,
    ) = ExternalStorageTreePaths.resolve(authority, docId, primary)

    @Test
    fun primaryVolumeMapsUnderSharedStorageRoot() {
        resolve(ExternalStorageTreePaths.EXTERNAL_STORAGE_AUTHORITY, "primary:Documents/NoPapersNeeded") shouldBe
            "/storage/emulated/0/Documents/NoPapersNeeded"
    }

    @Test
    fun primaryVolumeRootAndTrailingSlashesAreNormalised() {
        resolve(ExternalStorageTreePaths.EXTERNAL_STORAGE_AUTHORITY, "primary:") shouldBe primary
        resolve(ExternalStorageTreePaths.EXTERNAL_STORAGE_AUTHORITY, "primary:Documents/") shouldBe
            "/storage/emulated/0/Documents"
    }

    @Test
    fun homeVolumeIsTheDocumentsFolder() {
        resolve(ExternalStorageTreePaths.EXTERNAL_STORAGE_AUTHORITY, "home:NPN") shouldBe
            "/storage/emulated/0/Documents/NPN"
    }

    @Test
    fun removableVolumeMapsByUuid() {
        resolve(ExternalStorageTreePaths.EXTERNAL_STORAGE_AUTHORITY, "1234-ABCD:Scans") shouldBe
            "/storage/1234-ABCD/Scans"
        ExternalStorageTreePaths.resolve(
            ExternalStorageTreePaths.EXTERNAL_STORAGE_AUTHORITY,
            "1234-ABCD:Scans",
            primary,
            volumeRoot = { "/mnt/media_rw/$it" },
        ) shouldBe "/mnt/media_rw/1234-ABCD/Scans"
    }

    @Test
    fun downloadsProviderRootAndRawIdsResolve() {
        resolve(ExternalStorageTreePaths.DOWNLOADS_AUTHORITY, "downloads") shouldBe "/storage/emulated/0/Download"
        resolve(ExternalStorageTreePaths.DOWNLOADS_AUTHORITY, "raw:/storage/emulated/0/Download/NPN") shouldBe
            "/storage/emulated/0/Download/NPN"
        resolve(ExternalStorageTreePaths.DOWNLOADS_AUTHORITY, "msf:42").shouldBeNull()
    }

    @Test
    fun cloudProvidersHaveNoPath() {
        resolve("com.google.android.apps.docs.storage", "folder:abc").shouldBeNull()
        resolve("com.dropbox.product.android.dbapp.document_provider.documents", "x").shouldBeNull()
    }

    @Test
    fun traversalAndMalformedIdsAreRejected() {
        resolve(ExternalStorageTreePaths.EXTERNAL_STORAGE_AUTHORITY, "primary:../data").shouldBeNull()
        resolve(ExternalStorageTreePaths.EXTERNAL_STORAGE_AUTHORITY, "primary:Documents/../../x").shouldBeNull()
        resolve(ExternalStorageTreePaths.EXTERNAL_STORAGE_AUTHORITY, "noColon").shouldBeNull()
        resolve(ExternalStorageTreePaths.EXTERNAL_STORAGE_AUTHORITY, "we/ird:x").shouldBeNull()
        resolve(ExternalStorageTreePaths.DOWNLOADS_AUTHORITY, "raw:relative/path").shouldBeNull()
    }
}
