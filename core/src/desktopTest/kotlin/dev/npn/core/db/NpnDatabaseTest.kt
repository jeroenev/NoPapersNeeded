package dev.npn.core.db

import androidx.room3.RoomRawQuery
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Integration tests for the local index database, run against the real bundled SQLite
 * driver on the JVM (desktop) target. Verifies Room 3 wiring and, critically, that the
 * @Fts5 entity works with BundledSQLiteDriver on JVM.
 */
class NpnDatabaseTest {
    private val temp = TempIndexDb("npn-db-test")

    private val db = temp.db

    @AfterTest
    fun teardown() = temp.close()

    @Test
    fun rawQueryFilterByReviewedAndTagJoin() =
        runTest {
            val docDao = db.documentDao()
            val tagDao = db.tagDao()
            val a = "a".repeat(64)
            val b = "b".repeat(64)
            val c = "c".repeat(64)
            docDao.upsertAll(
                listOf(
                    documentEntity(a, reviewed = false),
                    documentEntity(b, relPath = "b.pdf", reviewed = false),
                    documentEntity(c, relPath = "c.pdf", reviewed = true),
                ),
            )
            tagDao.setForDocuments(mapOf(a to listOf("invoice"), c to listOf("invoice")))

            // Upper-layer-built SQL: unreviewed docs that carry the 'invoice' tag -> only `a`.
            val query =
                RoomRawQuery(
                    sql =
                        "SELECT d.* FROM documents d " +
                            "JOIN document_tags t ON d.docId = t.docId " +
                            "WHERE d.reviewed = ? AND t.tag = ? ORDER BY d.createdAt DESC",
                    onBindStatement = {
                        it.bindLong(1, 0L)
                        it.bindText(2, "invoice")
                    },
                )
            db
                .documentDao()
                .observeLibraryRaw(query)
                .first()
                .map { it.docId } shouldContainExactly listOf(a)
        }

    @Test
    fun keyValueRoundtrip() =
        runTest {
            val kv = db.keyValueDao()
            kv.get("deviceId") shouldBe null
            kv.put("deviceId", "uuid-123")
            kv.get("deviceId") shouldBe "uuid-123"
            kv.put("deviceId", "uuid-456")
            kv.get("deviceId") shouldBe "uuid-456"
            kv.delete("deviceId")
            kv.get("deviceId") shouldBe null
        }

    @Test
    fun duplicatesReplaceAll() =
        runTest {
            val dao = db.duplicateDao()
            val id = "a".repeat(64)
            val other = "b".repeat(64)
            dao.replaceAll(listOf(DuplicateEntity(id, "copy1.pdf"), DuplicateEntity(other, "copy2.pdf")))
            dao
                .observeAll()
                .first()
                .map { it.relPath }
                .shouldContainExactlyInAnyOrder("copy1.pdf", "copy2.pdf")
            // A rescan's duplicates replace the whole table, a document with none left included.
            dao.replaceAll(listOf(DuplicateEntity(id, "copy1.pdf")))
            dao.observeAll().first().map { it.relPath } shouldContainExactly listOf("copy1.pdf")
            dao.delete(id, "copy1.pdf")
            dao.observeAll().first().shouldBeEmpty()
        }

    @Test
    fun metadataAccumulatorRejectsIdentityAndHlcCollisions() =
        runTest {
            val dao = db.metadataProjectionDao()

            fun source(path: String) =
                MetadataSourceEntity(
                    path,
                    SourceKind.SNAPSHOT,
                    SourceFingerprint(path),
                    null,
                    SourceStatus.APPLIED,
                )

            fun event(
                dev: String,
                seq: Long,
                canonical: String,
                hlcDevice: String = dev,
            ) = MetadataEventEntity(
                deviceId = dev,
                sequence = seq,
                canonicalJson = canonical,
                hlcPhysical = 10,
                hlcCounter = 0,
                hlcDevice = hlcDevice,
                documentId = null,
                operation = "future",
            )

            dao.ingest(source("one"), listOf(event("dev-a", 1, "a")))
            assertFailsWith<MetadataIdentityCollision> {
                dao.ingest(source("two"), listOf(event("dev-a", 1, "different")))
            }
            assertFailsWith<MetadataIdentityCollision> {
                dao.ingest(source("three"), listOf(event("dev-b", 1, "b", hlcDevice = "dev-a")))
            }
        }

    /**
     * A copy of an event that lacks only keys of the other (a build that does not know them encoded
     * it again) is the same event, and the richer copy is the one kept, whichever came first. Any
     * other difference is still a collision.
     */
    @Test
    fun metadataAccumulatorKeepsTheRicherCopyOfOneEvent() =
        runTest {
            val dao = db.metadataProjectionDao()

            fun source(path: String) =
                MetadataSourceEntity(path, SourceKind.SNAPSHOT, SourceFingerprint(path), null, SourceStatus.APPLIED)

            fun event(
                dev: String,
                canonical: String,
            ) = MetadataEventEntity(
                deviceId = dev,
                sequence = 1,
                canonicalJson = canonical,
                hlcPhysical = 10,
                hlcCounter = 0,
                hlcDevice = dev,
                documentId = null,
                operation = "field-def",
            )
            val poorer = """{"op":"field-def","p":{"def":{"label":"Title","type":"text"},"key":"title"}}"""
            val richer =
                """{"op":"field-def","p":{"def":{"label":"Title","perDocument":true,"type":"text"},"key":"title"}}"""

            dao.ingest(source("poorer"), listOf(event("dev-a", poorer)))
            dao.ingest(source("richer"), listOf(event("dev-a", richer)))
            dao.event("dev-a", 1)?.canonicalJson shouldBe richer
            dao.ingest(source("poorer-again"), listOf(event("dev-a", poorer)))
            dao.event("dev-a", 1)?.canonicalJson shouldBe richer

            val otherValue =
                """{"op":"field-def","p":{"def":{"label":"Name","perDocument":true,"type":"text"},"key":"title"}}"""
            val otherKeys =
                """{"op":"field-def","p":{"def":{"hint":"x","label":"Title","type":"text"},"key":"title"}}"""
            for ((name, other) in listOf("other-value" to otherValue, "other-keys" to otherKeys)) {
                assertFailsWith<MetadataIdentityCollision> { dao.ingest(source(name), listOf(event("dev-a", other))) }
            }
            dao.event("dev-a", 1)?.canonicalJson shouldBe richer

            // Arrays hold nothing of each other: they are equal or they differ.
            dao.ingest(source("dots"), listOf(event("dev-b", """{"p":{"dots":[["dev-a",1]]}}""")))
            assertFailsWith<MetadataIdentityCollision> {
                dao.ingest(source("no-dots"), listOf(event("dev-b", """{"p":{"dots":[]}}""")))
            }
        }
}
