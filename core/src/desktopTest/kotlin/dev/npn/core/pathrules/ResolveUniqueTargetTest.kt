package dev.npn.core.pathrules

import dev.npn.core.db.documentEntity
import dev.npn.core.index.indexTest
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/** Where a storage-path rule may file a document: a path a file or a live document holds is taken. */
class ResolveUniqueTargetTest {
    @Test
    fun aHiddenRowGivesItsPathUpButAFileOrALiveRowKeepsIt() =
        indexTest { rig ->
            val dao = rig.db.documentDao()
            dao.upsert(documentEntity("trashed", relPath = "a/trashed.pdf", trashed = true))
            dao.upsert(documentEntity("deleted", relPath = "a/deleted.pdf", deleted = true))
            dao.upsert(documentEntity("missing", relPath = "a/missing.pdf", missing = true))
            // A live row whose file went outside the app holds its path until a rescan finds it missing.
            dao.upsert(documentEntity("live", relPath = "a/live.pdf"))
            rig.writePdf("a/file.pdf", "a file no row names yet")

            suspend fun target(path: String) = resolveUniqueTarget(rig.fs, dao, currentDocId = "doc", target = path)

            target("a/trashed") shouldBe "a/trashed.pdf"
            target("a/deleted") shouldBe "a/deleted.pdf"
            target("a/missing") shouldBe "a/missing.pdf"
            target("a/live") shouldBe "a/live-1.pdf"
            target("a/file") shouldBe "a/file-1.pdf"
            resolveUniqueTarget(rig.fs, dao, currentDocId = "live", target = "a/live") shouldBe "a/live.pdf"
        }
}
