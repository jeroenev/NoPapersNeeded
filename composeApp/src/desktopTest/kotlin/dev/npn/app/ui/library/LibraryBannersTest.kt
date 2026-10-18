@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.npn.app.ui.library

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import dev.npn.app.EnglishLocaleTest
import dev.npn.app.ui.click
import dev.npn.app.ui.closing
import dev.npn.app.ui.has
import dev.npn.app.ui.onUiThread
import dev.npn.app.ui.text
import dev.npn.app.ui.themedScene
import dev.npn.core.index.PathConflict
import dev.npn.core.index.ScanReport
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/** The last scan's issues above the results: a few sentences, however many documents a scan reports. */
class LibraryBannersTest : EnglishLocaleTest() {
    /**
     * Storage that cannot move documents leaves every document a storage-path rule names where it
     * is: one counted sentence says so, shows those documents, and goes with the scan's other issues.
     */
    @Test
    fun documentsThatCouldNotMoveAreCountedShownAndDismissed() =
        onUiThread {
            val report =
                scan(pathConflicts = List(UNMOVED) { PathConflict("doc-$it", "inbox/$it.pdf", "2024/$it.pdf") })
            var dismissed by mutableStateOf(false)
            var shown: List<String>? = null
            themedScene(WIDTH, HEIGHT) {
                Banners(
                    banners = LibraryBanners(lastScan = report),
                    dismissed = dismissed,
                    onDismiss = { dismissed = true },
                    onGoToTrash = {},
                    onShowDocuments = { shown = it },
                    onOpenQueue = {},
                )
            }.closing {
                render()
                has { it.text() == "Review" } shouldBe false

                click { it.text() == "$UNMOVED documents could not be moved to their new location." }
                shown.orEmpty() shouldContainExactly List(UNMOVED) { "doc-$it" }

                click { it.text() == "Dismiss" }
                Snapshot.sendApplyNotifications()
                render()
                has { it.text()?.contains("could not be moved") == true } shouldBe false
            }
        }

    private fun scan(pathConflicts: List<PathConflict>) =
        ScanReport(
            added = 0,
            moved = 0,
            changed = 0,
            missingCount = 0,
            duplicates = emptyList(),
            conflictFiles = emptyList(),
            relinkCandidates = emptyList(),
            hashedCount = 0,
            durationMillis = 0L,
            pathConflicts = pathConflicts,
        )

    private companion object {
        const val UNMOVED = 500
        const val WIDTH = 600
        const val HEIGHT = 400
    }
}
