package dev.npn.core.db

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test

class DeviceDatabaseTest {
    private val dir: File = Files.createTempDirectory("npn-device-db").toFile()
    private val db: DeviceDatabase = deviceDatabase(File(dir, "device.db"))

    @AfterTest
    fun teardown() {
        db.close()
        dir.deleteRecursively()
    }

    @Test
    fun ocrQueueNextPending() =
        runTest {
            val dao = db.ocrQueueDao()
            dao.upsert(OcrQueueEntity("a".repeat(64), "done", updatedAt = 1))
            dao.upsert(OcrQueueEntity("b".repeat(64), "pending", updatedAt = 5))
            dao.upsert(OcrQueueEntity("c".repeat(64), "pending", updatedAt = 2))
            dao.upsert(OcrQueueEntity("d".repeat(64), "pending", updatedAt = 1, nextAttemptAt = 100))
            // Oldest due pending first; a row waiting out its backoff is skipped until it is due.
            dao.nextPending(now = 99)?.docId shouldBe "c".repeat(64)
            dao.nextPending(now = 100)?.docId shouldBe "d".repeat(64)
        }

    @Test
    fun anEnrichedDocumentIsRememberedByHash() =
        runTest {
            val dao = db.aiEnrichQueueDao()
            dao.enrichedAt("a".repeat(64)) shouldBe null
            dao.markEnriched(AiEnrichedEntity("a".repeat(64), enrichedAt = 7))
            dao.enrichedAt("a".repeat(64)) shouldBe 7
        }

    @Test
    fun mailCursorsRoundTripAndDecodeAStoredReason() =
        runTest {
            val dao = db.mailCursorDao()
            dao.get("someone@example.com:993", "INBOX") shouldBe null
            dao.upsert(
                MailCursorEntity(
                    accountKey = "someone@example.com:993",
                    folder = "INBOX",
                    uidValidity = 9,
                    lastUid = 42,
                    lastCheckedAt = 100,
                    lastError = "AUTHENTICATION_FAILED",
                ),
            )
            val row = dao.get("someone@example.com:993", "INBOX")
            row?.uidValidity shouldBe 9
            row?.lastUid shouldBe 42
            row?.lastError shouldBe "AUTHENTICATION_FAILED"
        }
}
