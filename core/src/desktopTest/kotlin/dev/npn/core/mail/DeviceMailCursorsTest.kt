package dev.npn.core.mail

import dev.npn.core.db.DeviceDatabase
import dev.npn.core.db.deviceDatabase
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test

class DeviceMailCursorsTest {
    private val dir: File = Files.createTempDirectory("npn-mail-cursors").toFile()
    private val db: DeviceDatabase = deviceDatabase(File(dir, "device.db"))
    private val cursors = DeviceMailCursors(db.mailCursorDao(), db.keyValueDao())

    @AfterTest
    fun teardown() {
        db.close()
        dir.deleteRecursively()
    }

    @Test
    fun theCatchUpStateRoundTripsBesideTheCursorRow() =
        runTest {
            val cursor =
                MailCursor(
                    accountKey = "someone@example.com:993",
                    folder = "INBOX",
                    uidValidity = 9,
                    lastUid = 42,
                    lastCheckedAtMillis = 100,
                    lastError = MailFailureReason.FETCH_FAILED,
                    startFrom = MailStartPoint.LAST_YEAR,
                    catchingUp = true,
                    throughMillis = 12_345,
                    remaining = 7,
                )
            cursors.save(cursor)
            cursors.get(cursor.accountKey, cursor.folder) shouldBe cursor
            // Another folder of the same account has its own state.
            cursors.get(cursor.accountKey, "Archive") shouldBe null
        }

    @Test
    fun aCursorRowWithoutStoredProgressReadsAsPlacedBeforeStartPoints() =
        runTest {
            db.mailCursorDao().upsert(
                dev.npn.core.db.MailCursorEntity(
                    accountKey = "a@b:993",
                    folder = "INBOX",
                    uidValidity = 3,
                    lastUid = 150,
                ),
            )
            val cursor = cursors.get("a@b:993", "INBOX")
            cursor?.lastUid shouldBe 150
            cursor?.startFrom shouldBe null
            cursor?.catchingUp shouldBe false
        }

    @Test
    fun aStartPointFromALaterBuildReadsAsNone() =
        runTest {
            db.mailCursorDao().upsert(
                dev.npn.core.db.MailCursorEntity(
                    accountKey = "a@b:993",
                    folder = "INBOX",
                    uidValidity = 3,
                    lastUid = 5,
                ),
            )
            db.keyValueDao().put(
                "mail.cursor.progress:a@b:993\u0000INBOX",
                """{"startFrom":"LAST_DECADE","catchingUp":true}""",
            )
            val cursor = cursors.get("a@b:993", "INBOX")
            cursor?.startFrom shouldBe null
            cursor?.catchingUp shouldBe true
        }
}
