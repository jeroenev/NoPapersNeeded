package dev.npn.core.app

import dev.npn.core.db.AutoMatchRuleEntity
import dev.npn.core.index.MutableClock
import dev.npn.core.journal.ContentAddressedJournalWriter
import dev.npn.core.journal.HlcWatermarkAllocator
import dev.npn.core.journal.PendingOp
import dev.npn.core.pdf.PdfFixtures
import dev.npn.core.rules.AutoMatchRule
import dev.npn.core.rules.AutoMatchRuleJson
import dev.npn.core.rules.MatchAlgorithm
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * The storage-path rule and the auto-match rules are library data in the journal: every device
 * reads the same, a device's old settings are adopted once, and the rules table is only a cache.
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

                val first = rig.session.storagePath.storagePath.first()
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
                rig.session.storagePath.storagePath.first() shouldBe StoragePathSetting("{title}.pdf", enabled = true)
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

    @Test
    fun rulesSurviveACorruptCache() =
        runTest {
            val library = Files.createTempDirectory("npn-rules-lib")
            val data = Files.createTempDirectory("npn-rules-data").toFile()
            try {
                open(backgroundScope, library, data).use { session, deps ->
                    session.rules.save(rule("r1", "Invoice rule"))
                    // Rows the journal never held, in place of the ones it does.
                    deps.db.autoMatchRuleDao().replaceAll(listOf(junkRow()))

                    // Matching reads the journal, not the cache.
                    deps.fs.writeBytesOnce("inv.pdf", PdfFixtures.embeddedTextPdf("invoice"))
                    session.rescan()
                    session.drainOcr()
                    val id = deps.fs.sha256("inv.pdf")
                    session.documents
                        .observeDoc(id)
                        .first()
                        ?.tags
                        .orEmpty() shouldContain "t"
                }

                // The next open puts the journal's list back.
                open(backgroundScope, library, data).use { session, _ ->
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

    /** A session over [library] whose app-local state lives in [data], as one device reopens it. */
    private suspend fun open(
        scope: CoroutineScope,
        library: Path,
        data: File,
    ): OpenSession {
        val deps =
            jvmSessionDeps(
                libraryRoot = library,
                dirs = AppDirs(dbDir = data.path, thumbsDir = "$data/thumbs", tmpDir = "$data/tmp"),
                scope = scope,
                ocrLanguage = { "eng" },
                trashRetentionDays = { 30 },
                // Far behind the files' real mtimes, so a just-written PDF counts as settled.
                clock = MutableClock(autoTick = true),
                installationId = "test-installation",
                ocrEngine = NoopOcrEngine(),
                thumbnailStore = FakeThumbStore(File(data, "thumbs")),
            )
        return OpenSession(LibrarySessionImpl.openSession(deps), deps)
    }

    private class OpenSession(
        val session: LibrarySessionImpl,
        private val deps: SessionDeps,
    ) {
        suspend fun use(block: suspend (LibrarySessionImpl, SessionDeps) -> Unit) {
            try {
                block(session, deps)
            } finally {
                session.close()
                deps.closeDb()
            }
        }
    }
}
