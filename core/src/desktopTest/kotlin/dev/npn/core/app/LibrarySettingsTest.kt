package dev.npn.core.app

import dev.npn.core.db.AutoMatchRuleEntity
import dev.npn.core.fs.NpnPaths
import dev.npn.core.index.MutableClock
import dev.npn.core.journal.ContentAddressedJournalWriter
import dev.npn.core.journal.HlcWatermarkAllocator
import dev.npn.core.journal.PendingOp
import dev.npn.core.pdf.PdfFixtures
import dev.npn.core.rules.AutoMatchRule
import dev.npn.core.rules.AutoMatchRuleJson
import dev.npn.core.rules.MatchAlgorithm
import dev.npn.core.util.RecordingLog
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * The storage-path rule, the auto-match rules and the trash retention are library data in the
 * journal: every device reads the same, a device's old settings are adopted once, and the rules
 * table is only a cache.
 */
class LibrarySettingsTest {
    @Test
    fun twoDevicesWithDifferentTemplatesConvergeOnOneValue() =
        runTest {
            val rig = AppSessionRig.create(backgroundScope)
            val (second, secondDb) = rig.secondSession(backgroundScope)
            try {
                rig.session.storagePath.setStoragePathTemplate("{title}.pdf")
                second.storagePath.setStoragePathTemplate("archive/{title}.pdf")

                rig.session.rescan()
                second.rescan()

                val first =
                    rig.session.storagePath.storagePath
                        .first()
                first shouldBe second.storagePath.storagePath.first()
                // The later write wins everywhere, whichever device made it.
                first.template shouldBe "archive/{title}.pdf"
            } finally {
                second.close()
                secondDb.close()
                rig.close()
            }
        }

    @Test
    fun deviceSettingsMoveIntoTheLibraryOnItsFirstOpen() =
        runTest {
            val legacy =
                LegacyLibrarySettings(
                    storagePathTemplate = "{title}.pdf",
                    storagePathEnabled = true,
                    autoMatchRulesJson = AutoMatchRuleJson.encodeList(listOf(rule("r1", "Invoice rule"))),
                )
            val rig = AppSessionRig.create(backgroundScope, legacyLibrarySettings = { legacy })
            try {
                rig.session.storagePath.storagePath
                    .first() shouldBe StoragePathSetting("{title}.pdf", enabled = true)
                rig.session.rules
                    .observeRules()
                    .first()
                    .map { it.name } shouldBe listOf("Invoice rule")

                // A value the library already holds is never replaced, a cleared one included.
                rig.session.storagePath.setStoragePathTemplate(null)
                val other = LegacyLibrarySettings("other/{title}.pdf", autoMatchRulesJson = "[]")
                val (second, secondDb) = rig.secondSession(backgroundScope, legacyLibrarySettings = { other })
                try {
                    second.storagePath.storagePath.first() shouldBe StoragePathSetting(null, enabled = true)
                    second.rules
                        .observeRules()
                        .first()
                        .map { it.name } shouldBe listOf("Invoice rule")
                } finally {
                    second.close()
                    secondDb.close()
                }
            } finally {
                rig.close()
            }
        }

    /**
     * The template and its switch are one setting: a device's pair is adopted whole, its switch
     * included when off, and not at all once the library holds either half.
     */
    @Test
    fun aDevicesStoragePathRuleIsAdoptedWholeOrNotAtAll() =
        runTest {
            val first = LegacyLibrarySettings("{title}.pdf", storagePathEnabled = false)
            val rig = AppSessionRig.create(backgroundScope, legacyLibrarySettings = { first })
            try {
                val other = LegacyLibrarySettings("archive/{title}.pdf", storagePathEnabled = true)
                val (second, secondDb) = rig.secondSession(backgroundScope, legacyLibrarySettings = { other })
                try {
                    second.storagePath.storagePath.first() shouldBe StoragePathSetting("{title}.pdf", enabled = false)
                    rig.session.rescan()
                    rig.session.storagePath.storagePath
                        .first() shouldBe StoragePathSetting("{title}.pdf", enabled = false)
                } finally {
                    second.close()
                    secondDb.close()
                }
            } finally {
                rig.close()
            }
        }

    /** A switch without a template is no rule: nothing is adopted, and a later device's whole pair is. */
    @Test
    fun aSwitchWithoutATemplateIsNotAdopted() =
        runTest {
            val first = LegacyLibrarySettings(" ", storagePathEnabled = true)
            val rig = AppSessionRig.create(backgroundScope, legacyLibrarySettings = { first })
            try {
                rig.session.storagePath.storagePath
                    .first() shouldBe StoragePathSetting()
                val other = LegacyLibrarySettings("archive/{title}.pdf", storagePathEnabled = false)
                val (second, secondDb) = rig.secondSession(backgroundScope, legacyLibrarySettings = { other })
                try {
                    second.storagePath.storagePath.first() shouldBe
                        StoragePathSetting("archive/{title}.pdf", enabled = false)
                } finally {
                    second.close()
                    secondDb.close()
                }
            } finally {
                rig.close()
            }
        }

    /**
     * The purge deletes entries in the library's own trash, which sync carries to every device, so a
     * device that kept its trash for no time at all must not purge what the library still keeps.
     */
    @Test
    fun aDeviceWithAShorterRetentionKeepsTheLibrarysTrash() =
        runTest {
            val rig = AppSessionRig.create(backgroundScope, deviceTrashRetentionDays = 30)
            try {
                rig.clock.now = 400 * DAY
                rig.writePdf("bill.pdf", PdfFixtures.embeddedTextPdf("electricity bill"))
                Files.setLastModifiedTime(rig.libRoot.resolve("bill.pdf"), FileTime.fromMillis(300 * DAY))
                rig.session.rescan()
                val id = rig.docId("bill.pdf")
                rig.session.docLifecycle.trashDoc(id)

                val (second, secondDb) =
                    rig.secondSession(
                        backgroundScope,
                        clock = MutableClock(400 * DAY + HOUR),
                        deviceTrashRetentionDays = 0,
                    )
                try {
                    second.rescan()
                    second.purgeExpiredTrash()
                } finally {
                    second.close()
                    secondDb.close()
                }

                rig.fs.sha256(NpnPaths.trashPath("bill.pdf", id)) shouldBe id
            } finally {
                rig.close()
            }
        }

    @Test
    fun twoDevicesWithDifferentRetentionsConvergeOnOneValue() =
        runTest {
            val rig = AppSessionRig.create(backgroundScope)
            val (second, secondDb) = rig.secondSession(backgroundScope)
            try {
                rig.session.trashRetention.setTrashRetentionDays(7)
                second.trashRetention.setTrashRetentionDays(14)

                rig.session.rescan()
                second.rescan()

                rig.session.trashRetention.trashRetentionDays
                    .first() shouldBe 14
                second.trashRetention.trashRetentionDays
                    .first() shouldBe 14
            } finally {
                second.close()
                secondDb.close()
                rig.close()
            }
        }

    /** The first device to open the library brings its retention; no device's replaces it later. */
    @Test
    fun aDevicesRetentionIsAdoptedOnce() =
        runTest {
            val rig = AppSessionRig.create(backgroundScope, deviceTrashRetentionDays = 7)
            try {
                rig.session.trashRetention.trashRetentionDays
                    .first() shouldBe 7
                rig.session.trashRetention.setTrashRetentionDays(20)
                val objects = rig.journalObjectCount()

                val (second, secondDb) = rig.secondSession(backgroundScope, deviceTrashRetentionDays = 3)
                try {
                    second.trashRetention.trashRetentionDays.first() shouldBe 20
                } finally {
                    second.close()
                    secondDb.close()
                }
                rig.journalObjectCount() shouldBe objects
            } finally {
                rig.close()
            }
        }

    /** A retention a newer build wrote, which this one cannot read, purges nothing. */
    @Test
    fun aRetentionThisBuildCannotReadKeepsTheTrash() =
        runTest {
            val rig = AppSessionRig.create(backgroundScope)
            try {
                rig.clock.now = 400 * DAY
                rig.writePdf("bill.pdf", PdfFixtures.embeddedTextPdf("electricity bill"))
                Files.setLastModifiedTime(rig.libRoot.resolve("bill.pdf"), FileTime.fromMillis(300 * DAY))
                rig.session.rescan()
                val id = rig.docId("bill.pdf")
                rig.session.docLifecycle.trashDoc(id)
                ContentAddressedJournalWriter(rig.fs, "newer-installation", HlcWatermarkAllocator(rig.clock))
                    .commit(listOf(PendingOp.settingSet(LibrarySettingKeys.TRASH_RETENTION_DAYS, "forever")))
                rig.session.rescan()

                rig.clock.now = 800 * DAY
                rig.session.purgeExpiredTrash()

                rig.fs.sha256(NpnPaths.trashPath("bill.pdf", id)) shouldBe id
            } finally {
                rig.close()
            }
        }

    @Test
    fun rulesSurviveACorruptCache() =
        runTest {
            val library = Files.createTempDirectory("npn-rules-lib")
            val data = Files.createTempDirectory("npn-rules-data").toFile()
            try {
                openSession(backgroundScope, library, data).use { session, deps ->
                    session.rules.save(rule("r1", "Invoice rule"))
                    // Rows the journal never held, in place of the ones it does.
                    deps.db.autoMatchRuleDao().replaceAll(listOf(junkRow()))

                    // Matching reads the journal, not the cache.
                    deps.fs.writeBytesOnce("inv.pdf", PdfFixtures.embeddedTextPdf("invoice"))
                    session.rescan()
                    session.work.drainOcr()
                    val id = deps.fs.sha256("inv.pdf")
                    session.documents
                        .observeDoc(id)
                        .first()
                        ?.tags
                        .orEmpty() shouldContain "t"
                }

                // The next open puts the journal's list back.
                openSession(backgroundScope, library, data).use { session, _ ->
                    session.rules
                        .observeRules()
                        .first()
                        .map { it.name } shouldBe listOf("Invoice rule")
                }
            } finally {
                library.toFile().deleteRecursively()
                data.deleteRecursively()
            }
        }

    /**
     * A list this build cannot read (from a newer version, say) is not "no rules": the cache keeps
     * the last list that decoded, and an edit is refused rather than written over it.
     */
    @Test
    fun rulesTheJournalCannotDecodeKeepTheCacheAndAreNeverWrittenOver() =
        runTest {
            val rig = AppSessionRig.create(backgroundScope)
            try {
                rig.session.rules.save(rule("r1", "Invoice rule"))
                ContentAddressedJournalWriter(rig.fs, "newer-installation", HlcWatermarkAllocator(rig.clock))
                    .commit(listOf(PendingOp.settingSet(LibrarySettingKeys.AUTO_MATCH_RULES, "{not json")))
                rig.session.rescan()
                rig.session.rules.mirror()

                rig.session.rules
                    .observeRules()
                    .first()
                    .map { it.name } shouldBe listOf("Invoice rule")
                val objects = rig.journalObjectCount()
                assertFailsWith<IllegalStateException> { rig.session.rules.save(rule("", "Added later")) }
                rig.journalObjectCount() shouldBe objects
                rig.session.rules
                    .observeRules()
                    .first()
                    .map { it.name } shouldContain "Invoice rule"
            } finally {
                rig.close()
            }
        }

    /**
     * Rules are read for every indexed document and every rule pass, so a list that does not decode
     * is reported once per stored value, not on every read: a flood would push everything else out
     * of the size-capped log.
     */
    @Test
    fun rulesTheJournalCannotDecodeAreReportedOncePerStoredValue() =
        runTest {
            val log = RecordingLog()
            val rig = AppSessionRig.create(backgroundScope, log = log)
            try {
                rig.session.rules.save(rule("r1", "Invoice rule"))

                fun warnings() = log.entries.count { it.message.startsWith("Auto-match rules did not decode") }

                rig.storeRulesFromANewerBuild("{not json")
                repeat(50) {
                    rig.session.rules
                        .current()
                        .map(AutoMatchRule::name) shouldBe listOf("Invoice rule")
                }
                warnings() shouldBe 1

                rig.storeRulesFromANewerBuild("[{still not json")
                repeat(50) {
                    rig.session.rules
                        .current()
                        .map(AutoMatchRule::name) shouldBe listOf("Invoice rule")
                }
                warnings() shouldBe 2
            } finally {
                rig.close()
            }
        }

    /** Store [rules] as the library's rule list, as a newer build on another device would. */
    private suspend fun AppSessionRig.storeRulesFromANewerBuild(rules: String) {
        ContentAddressedJournalWriter(fs, "newer-installation", HlcWatermarkAllocator(clock))
            .commit(listOf(PendingOp.settingSet(LibrarySettingKeys.AUTO_MATCH_RULES, rules)))
        session.rescan()
    }

    private fun rule(
        id: String,
        name: String,
    ) = AutoMatchRule(
        id = id,
        name = name,
        algorithm = MatchAlgorithm.ANY_WORD,
        pattern = "invoice",
        tags = listOf("t"),
    )

    private companion object {
        const val HOUR = 60L * 60L * 1000L
        const val DAY = 24 * HOUR
    }

    private fun junkRow() =
        AutoMatchRuleEntity(
            id = "junk",
            name = "Junk",
            enabled = true,
            field = "content",
            algorithm = "EXACT",
            pattern = "never matches anything",
            tags = null,
            sets = "{}",
            position = 0,
        )
}
