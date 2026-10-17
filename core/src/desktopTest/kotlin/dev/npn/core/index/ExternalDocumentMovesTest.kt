package dev.npn.core.index

import dev.npn.core.journal.PendingOp
import dev.npn.core.model.SystemProperty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class ExternalDocumentMovesTest {
    @Test
    fun manualMovePublishesIntentAndKeepsIdentityAcrossCoordinatorRestart() =
        indexTest { rig ->
            rig.writePdf("original.pdf", "document")
            val hash = rig.fs.sha256("original.pdf")
            rig.coordinator(rig.writer("local")).rescan()
            rig.moveFile("original.pdf", "archive/manual.pdf")
            val restarted = rig.coordinator(rig.writer("local"))
            restarted.rescan()
            val intended = rig.accumulator.loadState().effectiveProp(hash, SystemProperty.STORAGE_PATH)
            intended shouldBe "archive/manual.pdf"
            rig.db
                .documentDao()
                .requireByDocId(hash)
                .relPath shouldBe "archive/manual.pdf"
            rig.exists("original.pdf") shouldBe false
            val version = rig.accumulator.loadState().propVersion(hash, SystemProperty.STORAGE_PATH)
            restarted.rescan()
            rig.accumulator.loadState().propVersion(hash, SystemProperty.STORAGE_PATH) shouldBe version
        }

    /**
     * Baselines are compared as the strings stored, so a build must encode one exactly as earlier
     * builds did: one that no longer compares equal hides the next move outside the app.
     */
    @Test
    fun aBaselineStoredByAnEarlierBuildStillComparesEqual() =
        indexTest { rig ->
            rig.writePdf("original.pdf", "document")
            val hash = rig.fs.sha256("original.pdf")
            val coordinator = rig.coordinator(rig.writer("local"))
            coordinator.rescan()
            val kv = rig.db.keyValueDao()
            val key = "observed-document-path:$hash"
            kv.get(key) shouldBe """{"path":"original.pdf","version":null}"""

            kv.put(key, """{"path":"original.pdf","version":null}""")
            rig.moveFile("original.pdf", "archive/manual.pdf")
            coordinator.rescan()

            val state = rig.accumulator.loadState()
            state.effectiveProp(hash, SystemProperty.STORAGE_PATH) shouldBe "archive/manual.pdf"
            val version = checkNotNull(state.propVersion(hash, SystemProperty.STORAGE_PATH))
            kv.get(key) shouldBe
                """{"path":"archive/manual.pdf","version":""" +
                """{"t":${version.physicalMillis},"c":${version.counter},"d":"${version.deviceId}"}}"""
        }

    @Test
    fun manualMoveOverridesAppliedIntentAndReplicates() =
        runTest {
            val a = IndexRig.create()
            val b = IndexRig.create()
            try {
                a.writePdf("original.pdf", "document")
                val hash = a.fs.sha256("original.pdf")
                val coordinator = a.coordinator(a.writer("local"))
                coordinator.rescan()
                coordinator.commitAndApply(listOf(PendingOp.propSet(hash, SystemProperty.STORAGE_PATH, "app.pdf")))
                a.moveFile("app.pdf", "manual.pdf")
                coordinator.commitAndApply(listOf(PendingOp.propSet(hash, SystemProperty.REVIEWED, "true")))
                coordinator.rescan()
                a.db
                    .documentDao()
                    .requireByDocId(hash)
                    .relPath shouldBe "manual.pdf"
                a.accumulator.loadState().effectiveProp(hash, SystemProperty.STORAGE_PATH) shouldBe "manual.pdf"
                a.exists("app.pdf") shouldBe false
                for (entry in a.fs.listAll()) {
                    if (!entry.isDirectory && entry.relPath.startsWith(".npn/journal/")) {
                        b.fs.writeBytesOnce(entry.relPath, a.fs.readBytes(entry.relPath))
                    }
                }
                b.writePdf("original.pdf", "document")
                b.coordinator(b.writer("replica")).rescan()
                b.db
                    .documentDao()
                    .requireByDocId(hash)
                    .relPath shouldBe "manual.pdf"
                b.db
                    .documentDao()
                    .requireByDocId(hash)
                    .reviewed shouldBe true
            } finally {
                a.close()
                b.close()
            }
        }

    @Test
    fun unappliedRemoteRenameWinsOverObservedDisplacement() =
        indexTest { rig ->
            rig.writePdf("old.pdf", "document")
            val hash = rig.fs.sha256("old.pdf")
            val coordinator = rig.coordinator(rig.writer("local"))
            coordinator.rescan()
            coordinator.commitAndApply(listOf(PendingOp.propSet(hash, SystemProperty.STORAGE_PATH, "applied.pdf")))
            rig.moveFile("applied.pdf", "displaced.pdf")
            rig.clock.advance(100)
            rig.writer("remote").commit(listOf(PendingOp.propSet(hash, SystemProperty.STORAGE_PATH, "remote.pdf")))
            coordinator.reconcileMetadata()
            val remoteVersion = rig.accumulator.loadState().propVersion(hash, SystemProperty.STORAGE_PATH)
            coordinator.rescan()
            rig.db
                .documentDao()
                .requireByDocId(hash)
                .relPath shouldBe "remote.pdf"
            rig.accumulator.loadState().propVersion(hash, SystemProperty.STORAGE_PATH) shouldBe remoteVersion
        }

    @Test
    fun unappliedPredecessorRenameWinsOverObservedDisplacement() =
        indexTest { rig ->
            rig.writePdf("original.pdf", "original document")
            rig.writePdf("replacement.pdf", "replacement document")
            val original = rig.fs.sha256("original.pdf")
            val replacement = rig.fs.sha256("replacement.pdf")
            val coordinator = rig.coordinator(rig.writer("local"))
            coordinator.rescan()
            coordinator.commitAndRescan(listOf(PendingOp.relink(original, replacement)))
            coordinator.commitAndApply(
                listOf(PendingOp.propSet(original, SystemProperty.STORAGE_PATH, "applied.pdf")),
            )
            rig.moveFile("applied.pdf", "displaced.pdf")
            rig.clock.advance(100)
            rig
                .writer(
                    "remote",
                ).commit(listOf(PendingOp.propSet(original, SystemProperty.STORAGE_PATH, "remote.pdf")))
            coordinator.reconcileMetadata()
            val remoteVersion = rig.accumulator.loadState().propVersion(original, SystemProperty.STORAGE_PATH)

            coordinator.rescan()

            rig.db
                .documentDao()
                .getByDocId(replacement)
                .shouldNotBeNull()
                .relPath shouldBe "remote.pdf"
            val state = rig.accumulator.loadState()
            state.inheritedProp(replacement, SystemProperty.STORAGE_PATH) shouldBe "remote.pdf"
            state.propVersion(original, SystemProperty.STORAGE_PATH) shouldBe remoteVersion
            state.propVersion(replacement, SystemProperty.STORAGE_PATH) shouldBe null
        }

    @Test
    fun newerSamePathIntentDoesNotBecomeAnExternalRename() =
        indexTest { rig ->
            rig.writePdf("old.pdf", "document")
            val hash = rig.fs.sha256("old.pdf")
            val coordinator = rig.coordinator(rig.writer("local"))
            coordinator.rescan()
            coordinator.commitAndApply(listOf(PendingOp.propSet(hash, SystemProperty.STORAGE_PATH, "applied.pdf")))
            rig.moveFile("applied.pdf", "displaced.pdf")
            rig.clock.advance(100)
            rig.writer("remote").commit(listOf(PendingOp.propSet(hash, SystemProperty.STORAGE_PATH, "applied.pdf")))
            coordinator.reconcileMetadata()
            val version = rig.accumulator.loadState().propVersion(hash, SystemProperty.STORAGE_PATH)
            coordinator.rescan()
            rig.db
                .documentDao()
                .requireByDocId(hash)
                .relPath shouldBe "applied.pdf"
            rig.accumulator.loadState().propVersion(hash, SystemProperty.STORAGE_PATH) shouldBe version
        }

    @Test
    fun ambiguousCopiesAndIncompleteListingDoNotPublishMoves() =
        indexTest { rig ->
            rig.writePdf("old.pdf", "document")
            val hash = rig.fs.sha256("old.pdf")
            val coordinator = rig.coordinator(rig.writer("local"))
            coordinator.rescan()
            rig.copyFile("old.pdf", "first.pdf")
            val state = rig.accumulator.loadState()
            val partial = rig.fs.listAllIndexable().filter { it.relPath == "first.pdf" }
            ExternalDocumentMoves(rig.fs, rig.db.keyValueDao()).detect(
                state,
                rig.db.documentDao().allPathsAndMeta(),
                partial,
                mapOf("first.pdf" to hash),
            ) shouldBe emptyList()
            rig.copyFile("old.pdf", "second.pdf")
            rig.deleteFile("old.pdf")
            coordinator.rescan()
            rig.accumulator.loadState().effectiveProp(hash, SystemProperty.STORAGE_PATH) shouldBe null
        }
}
