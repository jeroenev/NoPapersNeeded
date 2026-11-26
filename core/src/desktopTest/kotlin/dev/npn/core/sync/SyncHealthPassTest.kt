package dev.npn.core.sync

import dev.npn.core.fs.NpnPaths
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlin.test.Test

/** What real passes leave the settings screen to say: what is still owed, and when a pass last synced. */
internal class SyncHealthPassTest : SyncEngineTestBase() {
    @Test
    fun anUploadFoundDoneAlreadyIsNotLeftPending() =
        withPair { pair ->
            // Trashed before it ever synced: the pass puts the trash entry up.
            val docId = pair.importOnA("old.pdf", "three")
            pair.a.session.docLifecycle
                .trashDoc(docId)
            val entry =
                pair.a.fs
                    .listAllUnder(NpnPaths.TRASH_DIR)
                    .single { !it.isDirectory }
                    .relPath
            // Another device puts the same entry up between this pass's listing and its upload.
            pair.remote.onceBeforeStat(entry) { pair.remote.put(entry, pdf("three")) }

            val report = pair.a.session.syncCompleted()

            report.errors shouldBe emptyList()
            report.pendingUploads shouldBe 0
        }

    @Test
    fun aPassThatCannotPullAnotherDevicesChangesIsNoSuccess() =
        withPair { pair ->
            pair.importOnA("invoice.pdf", "one")
            pair.a.session.syncCompleted()
            val objects =
                pair.remote
                    .list()
                    .map { it.path }
                    .filter { it.startsWith("${NpnPaths.JOURNAL_DIR}/") }
            objects.shouldNotBeEmpty()
            pair.remote.failDownloads += objects

            repeat(2) {
                val stuck = pair.b.session.syncCompleted()
                stuck.documentsSynced shouldBe false
            }
            val stuckHealth =
                pair.b.session.sync.health
                    .first()
            stuckHealth.lastSuccessAt.shouldBeNull()
            stuckHealth.consecutiveFailures shouldBe 2
            stuckHealth.healthy shouldBe false

            pair.remote.failDownloads.clear()
            val synced = pair.b.session.syncCompleted()
            synced.documentsSynced shouldBe true
            val health =
                pair.b.session.sync.health
                    .first()
            health.lastSuccessAt shouldBe health.lastPass?.finishedAt
            health.consecutiveFailures shouldBe 0
        }
}
