package dev.npn.core.db

import androidx.room3.RoomRawQuery
import dev.npn.core.app.DocumentRepositoryImpl
import dev.npn.core.index.FtsRows
import dev.npn.core.index.requireByDocId
import dev.npn.core.model.FieldKeys
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

/** Document rows: upserts, the live/trashed/missing views, and one row per path. */
class DocumentDaoTest {
    private val temp = TempIndexDb("npn-db-test")
    private val db = temp.db

    @AfterTest
    fun teardown() = temp.close()

    @Test
    fun documentUpsertAndLookup() =
        runTest {
            val dao = db.documentDao()
            dao.upsert(documentEntity("a".repeat(64)))
            dao.requireByDocId("a".repeat(64)).fileName shouldBe "${"a".repeat(64)}.pdf"
            dao.allPathsAndMeta().single { it.relPath == "${"a".repeat(64)}.pdf" }.docId shouldBe "a".repeat(64)

            // upsert updates in place
            dao.upsert(documentEntity("a".repeat(64), reviewed = true))
            dao.requireByDocId("a".repeat(64)).reviewed shouldBe true
        }

    @Test
    fun observeAllExcludesTrashedDeletedMissing() =
        runTest {
            val dao = db.documentDao()
            dao.upsertAll(
                listOf(
                    documentEntity("a".repeat(64)),
                    documentEntity("b".repeat(64), relPath = "b.pdf", trashed = true),
                    documentEntity("c".repeat(64), relPath = "c.pdf", deleted = true),
                    documentEntity("d".repeat(64), relPath = "d.pdf", missing = true),
                ),
            )
            dao.getAllLive().map { it.docId } shouldContainExactly listOf("a".repeat(64))
            dao.observeTrashed().first().map { it.docId } shouldContainExactly listOf("b".repeat(64))
            dao.observeMissing().first().map { it.docId } shouldContainExactly listOf("d".repeat(64))
        }

    @Test
    fun reviewedFilterAndCounts() =
        runTest {
            val dao = db.documentDao()
            dao.upsertAll(
                listOf(
                    documentEntity("a".repeat(64), reviewed = false),
                    documentEntity("b".repeat(64), relPath = "b.pdf", reviewed = false),
                    documentEntity("c".repeat(64), relPath = "c.pdf", reviewed = true),
                ),
            )
            // All three are untagged, so the untagged-only review count is 3.
            dao.countUntagged().first() shouldBe 3
            // Unreviewed-or-untagged also 3 (all untagged).
            dao.countUnreviewedOrUntagged().first() shouldBe 3
        }

    /** relPath is unique: a hidden row gives its path up to a live one rather than swallow it. */
    @Test
    fun aLiveRowTakesThePathOfAHiddenOne() =
        runTest {
            val dao = db.documentDao()
            val trashed = documentEntity("a".repeat(64), relPath = "t.pdf", trashed = true)
            val deleted = documentEntity("b".repeat(64), relPath = "d.pdf", deleted = true)
            val missing = documentEntity("c".repeat(64), relPath = "m.pdf", missing = true)
            dao.upsertAll(listOf(trashed, deleted, missing))

            dao.upsertAll(
                listOf(
                    documentEntity("d".repeat(64), relPath = "t.pdf"),
                    documentEntity("e".repeat(64), relPath = "d.pdf"),
                ),
            )
            dao.upsert(documentEntity("f".repeat(64), relPath = "m.pdf"))

            dao.getAllLive().map { it.relPath } shouldContainExactlyInAnyOrder listOf("t.pdf", "d.pdf", "m.pdf")
            for (hidden in listOf(trashed, deleted, missing)) {
                val row = dao.getByDocId(hidden.docId)
                row shouldBe hidden.copy(relPath = missingSentinel(hidden.docId))
            }
        }

    /** Two live rows on one path is a bug, and the write says so instead of dropping one. */
    @Test
    fun aRowOnTheWayOfALiveOneFailsTheWrite() =
        runTest {
            val dao = db.documentDao()
            dao.upsert(documentEntity("a".repeat(64), relPath = "a.pdf"))

            assertFailsWith<IllegalStateException> {
                dao.upsertAll(listOf(documentEntity("b".repeat(64), relPath = "a.pdf")))
            }

            dao.allPathsAndMeta().map { it.docId } shouldContainExactly listOf("a".repeat(64))
        }

    @Test
    fun allPathsAndMetaProjection() =
        runTest {
            val dao = db.documentDao()
            dao.upsertAll(
                listOf(
                    documentEntity("a".repeat(64), relPath = "docs/a.pdf"),
                    documentEntity("b".repeat(64), relPath = "docs/b.pdf", deleted = true),
                ),
            )
            val metas = dao.allPathsAndMeta()
            metas.map { it.relPath } shouldContainExactly listOf("docs/a.pdf")
            metas.first().size shouldBe 100L
        }
}
