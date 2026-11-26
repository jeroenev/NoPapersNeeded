package dev.npn.core.db

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test

/** Tags in the index: per-document sets, counts over live documents, and tag filters. */
class TagDaoTest {
    private val temp = TempIndexDb("npn-db-test")
    private val db = temp.db

    @AfterTest
    fun teardown() = temp.close()

    @Test
    fun untaggedQueryAndTagCounts() =
        runTest {
            val docDao = db.documentDao()
            val tagDao = db.tagDao()
            val tagged = "a".repeat(64)
            val untagged = "b".repeat(64)
            docDao.upsertAll(listOf(documentEntity(tagged), documentEntity(untagged, relPath = "b.pdf")))
            tagDao.upsertAll(listOf(TagEntity("invoice", color = "#ff0000"), TagEntity("empty")))
            tagDao.setForDocuments(mapOf(tagged to listOf("invoice")))

            docDao.countUntagged().first() shouldBe 1

            val counts = tagDao.observeAllWithCounts().first().associateBy { it.name }
            counts.getValue("invoice").count shouldBe 1
            counts.getValue("invoice").color shouldBe "#ff0000"
            counts.getValue("empty").count shouldBe 0
        }

    @Test
    fun tagCountsIncludeOnlyLiveDocuments() =
        runTest {
            val docDao = db.documentDao()
            val tagDao = db.tagDao()
            val live = "a".repeat(64)
            val trashed = "b".repeat(64)
            val missing = "c".repeat(64)
            docDao.upsertAll(
                listOf(
                    documentEntity(live),
                    documentEntity(trashed, relPath = "trashed.pdf", trashed = true),
                    documentEntity(missing, relPath = "missing.pdf", missing = true),
                ),
            )
            tagDao.upsertAll(listOf(TagEntity("invoice")))
            tagDao.setForDocuments(listOf(live, trashed, missing).associateWith { listOf("invoice") })

            tagDao
                .allWithLiveCounts()
                .single { it.name == "invoice" }
                .count shouldBe 1
            // The tags screen counts the same way.
            tagDao
                .observeAllWithCounts()
                .first()
                .single { it.name == "invoice" }
                .count shouldBe 1
        }

    /** Only an explicitly retained tag survives without a document, even if another tag is styled. */
    @Test
    fun pruneKeepsRetainedTagsAndDropsOtherUnreferencedOnes() =
        runTest {
            val docDao = db.documentDao()
            val tagDao = db.tagDao()
            val doc = "a".repeat(64)
            docDao.upsert(documentEntity(doc))
            tagDao.upsertAll(
                listOf(
                    TagEntity("created", color = "#ff0000"),
                    TagEntity("auto", color = "#123456"),
                    TagEntity("carried"),
                ),
            )
            tagDao.setForDocuments(mapOf(doc to listOf("carried")))

            tagDao.pruneUnreferenced(setOf("created"))

            // The retained (created) tag survives; the unreferenced one goes, styled or not; the carried one stays.
            tagDao.names() shouldContainExactly listOf("carried", "created")
        }

    @Test
    fun setForDocumentReplacesTags() =
        runTest {
            val docDao = db.documentDao()
            val tagDao = db.tagDao()
            val id = "a".repeat(64)
            docDao.upsert(documentEntity(id))
            tagDao.setForDocuments(mapOf(id to listOf("x", "y")))
            tagDao.tagsForDocument(id) shouldContainExactly listOf("x", "y")
            tagDao.setForDocuments(mapOf(id to listOf("y", "z")))
            tagDao.tagsForDocument(id) shouldContainExactly listOf("y", "z")
        }

    @Test
    fun getLiveWithTagFiltersAndLimitsInDatabase() =
        runTest {
            val docDao = db.documentDao()
            val tagDao = db.tagDao()
            val liveA = "a".repeat(64)
            val liveB = "b".repeat(64)
            val trashed = "c".repeat(64)
            docDao.upsertAll(
                listOf(
                    documentEntity(liveA),
                    documentEntity(liveB, relPath = "b.pdf"),
                    documentEntity(trashed, relPath = "c.pdf", trashed = true),
                ),
            )
            tagDao.setForDocuments(listOf(liveA, liveB, trashed).associateWith { listOf("invoice") })

            docDao.getLiveWithTag("invoice", limit = 1).map { it.docId }.size shouldBe 1
            docDao
                .getLiveWithTag("invoice", limit = 10)
                .map { it.docId }
                .shouldContainExactlyInAnyOrder(liveA, liveB)
        }

    @Test
    fun setForDocumentAddsAndClearsTags() =
        runTest {
            val docDao = db.documentDao()
            val tagDao = db.tagDao()
            val a = "a".repeat(64)
            val b = "b".repeat(64)
            docDao.upsertAll(listOf(documentEntity(a), documentEntity(b, relPath = "b.pdf")))
            tagDao.setForDocuments(mapOf(a to listOf("shared"), b to listOf("shared")))
            tagDao.tagsForDocument(a) shouldContainExactly listOf("shared")
            tagDao.tagsForDocument(b) shouldContainExactly listOf("shared")
            tagDao.setForDocuments(mapOf(a to emptyList()))
            tagDao.tagsForDocument(a) shouldBe emptyList()
            tagDao.tagsForDocument(b) shouldContainExactly listOf("shared")
        }
}
