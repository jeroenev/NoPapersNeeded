package dev.npn.core.db

import dev.npn.core.index.requireByDocId
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFails
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

    /** A hidden row moved onto another hidden row's path takes it, as a live one would. */
    @Test
    fun aHiddenRowTakesAHiddenRowsPath() =
        runTest {
            val dao = db.documentDao()
            val first = documentEntity("a".repeat(64), relPath = "a.pdf", deleted = true)
            val second = documentEntity("b".repeat(64), relPath = "b.pdf", deleted = true)
            dao.upsertAll(listOf(first, second))

            dao.upsertAll(listOf(second.copy(relPath = "a.pdf")))

            dao.getByRelPath("a.pdf")?.docId shouldBe second.docId
            dao.getByDocId(first.docId)?.relPath shouldBe missingSentinel(first.docId)
        }

    /** Within one write, a hidden row lands on a path a live row leaves earlier in it. */
    @Test
    fun aHiddenRowFollowsTheLiveRowsWrittenBeforeIt() =
        runTest {
            val dao = db.documentDao()
            val moving = documentEntity("a".repeat(64), relPath = "a.pdf")
            val hidden = documentEntity("b".repeat(64), relPath = "b.pdf", deleted = true)
            dao.upsertAll(listOf(moving, hidden))

            dao.upsertAll(listOf(moving.copy(relPath = "elsewhere.pdf"), hidden.copy(relPath = "a.pdf")))

            dao.getByRelPath("a.pdf")?.docId shouldBe hidden.docId
            dao.getByRelPath("elsewhere.pdf")?.docId shouldBe moving.docId
        }

    /**
     * A row on a live row's path is a bug, hidden or not, and the write says so instead of dropping
     * one: no writer puts a row there.
     */
    @Test
    fun aRowOnTheWayOfALiveOneFailsTheWrite() =
        runTest {
            val dao = db.documentDao()
            val live = documentEntity("a".repeat(64), relPath = "a.pdf")
            val hidden = documentEntity("c".repeat(64), relPath = "c.pdf", deleted = true)
            dao.upsertAll(listOf(live, hidden))

            assertFailsWith<IllegalStateException> {
                dao.upsertAll(listOf(documentEntity("b".repeat(64), relPath = "a.pdf")))
            }
            assertFailsWith<IllegalStateException> {
                dao.upsertAll(listOf(documentEntity("d".repeat(64), relPath = "a.pdf", trashed = true)))
            }
            // An existing row's move there fails in the UPDATE Room's upsert falls back to.
            assertFails { dao.upsertAll(listOf(hidden.copy(relPath = "a.pdf"))) }

            dao.getByRelPath("a.pdf")?.docId shouldBe live.docId
            dao.getByDocId(hidden.docId)?.relPath shouldBe "c.pdf"
            dao.getByDocId("b".repeat(64)) shouldBe null
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
