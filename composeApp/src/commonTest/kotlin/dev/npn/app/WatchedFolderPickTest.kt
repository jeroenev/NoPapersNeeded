package dev.npn.app

import dev.npn.app.platform.FolderAccess
import dev.npn.app.platform.FolderPick
import dev.npn.app.ui.settings.watchedFolderPick
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * A folder picked as the watched folder is watched only through an access the app keeps; a grant
 * the system would not keep is refused as such, so the folder watched before stays.
 */
class WatchedFolderPickTest {
    private val asked = mutableListOf<FolderAccess>()

    private fun pick(
        directUsable: Boolean,
        saf: FolderPick,
        direct: FolderPick,
    ): FolderPick =
        watchedFolderPick(directUsable) { access ->
            asked += access
            if (access == FolderAccess.SAF) saf else direct
        }

    @Test
    fun aGrantNotKeptIsSaidWhenNoOtherAccessCanWatchTheFolder() {
        pick(directUsable = false, saf = FolderPick.AccessNotKept, direct = PATH) shouldBe
            FolderPick.AccessNotKept
        // Without the storage grant a direct path cannot be read, so it is not even asked for.
        asked shouldContainExactly listOf(FolderAccess.SAF)
    }

    @Test
    fun aCloudFolderWhoseGrantIsNotKeptIsNotWatched() {
        val cloud =
            pick(directUsable = true, saf = FolderPick.AccessNotKept, direct = FolderPick.Unusable)
        cloud shouldBe FolderPick.AccessNotKept
        asked shouldContainExactly listOf(FolderAccess.DIRECT, FolderAccess.SAF)
    }

    @Test
    fun aFolderOnTheDeviceIsWatchedByItsPathWhenItsGrantIsNotKept() {
        pick(directUsable = true, saf = FolderPick.AccessNotKept, direct = PATH) shouldBe PATH
        asked shouldContainExactly listOf(FolderAccess.DIRECT)
    }

    @Test
    fun aPathIsPreferredAndATreeIsTheFallback() {
        pick(directUsable = true, saf = TREE, direct = PATH) shouldBe PATH
        pick(directUsable = true, saf = TREE, direct = FolderPick.Unusable) shouldBe TREE
        pick(directUsable = false, saf = TREE, direct = PATH) shouldBe TREE
    }

    @Test
    fun aFolderNoAccessCanUseIsUnusable() {
        pick(directUsable = true, saf = FolderPick.Unusable, direct = FolderPick.Unusable) shouldBe
            FolderPick.Unusable
    }

    private companion object {
        val PATH = FolderPick.Location("/storage/emulated/0/Download/Inbox")
        val TREE = FolderPick.Location("content://com.example.documents/tree/primary%3AInbox")
    }
}
