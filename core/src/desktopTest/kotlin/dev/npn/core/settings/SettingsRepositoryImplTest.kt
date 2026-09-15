package dev.npn.core.settings

import dev.npn.core.ai.AiProvider
import dev.npn.core.ai.AiReasoningLevel
import dev.npn.core.ai.AiTagging
import dev.npn.core.sync.CloudSyncConfig
import dev.npn.core.util.NpnLog
import dev.npn.core.util.RecordingLog
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

class SettingsRepositoryImplTest {
    private val directory = Files.createTempDirectory("npn-settings-test")

    @AfterTest
    fun cleanup() {
        directory.toFile().deleteRecursively()
    }

    @Test
    fun settingsFileIsOwnerOnly() =
        runTest {
            val file = directory.resolve("settings.properties").toFile()
            SettingsRepositoryImpl(NpnLog.None, file).edit { it.copy(aiApiKey = "secret") }

            if (supportsPosixView(file)) {
                Files.getPosixFilePermissions(file.toPath()) shouldBe
                    setOf(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                    )
            }
        }

    @Test
    fun aSettingsFileThatDoesNotReadIsReportedAndTheDefaultsApply() =
        runTest {
            val file = directory.resolve("settings.properties").toFile()
            // A malformed \u escape is the one thing Properties.load refuses.
            file.writeText("appLanguage=\\uZZZZ\n")
            val log = RecordingLog()

            SettingsRepositoryImpl(log, file).settings.first().appLanguage shouldBe null

            log.entries.map { it.message } shouldBe
                listOf("Reading the settings in $file failed; starting from the defaults")
        }

    private fun supportsPosixView(file: java.io.File): Boolean =
        runCatching { Files.getFileStore(file.toPath()).supportsFileAttributeView("posix") }
            .getOrDefault(false)

    @Test
    fun concurrentUpdatesPersistOneCombinedSnapshot() =
        runTest {
            val file = directory.resolve("settings.properties").toFile()
            val repository = SettingsRepositoryImpl(NpnLog.None, file)

            listOf(
                async { repository.edit { it.copy(aiBaseUrl = "https://example.test") } },
                async { repository.edit { it.copy(aiApiKey = "secret") } },
                async { repository.edit { it.copy(aiModel = "model") } },
            ).awaitAll()

            val reloaded = SettingsRepositoryImpl(NpnLog.None, file)
            val snapshot = reloaded.settings.first()
            snapshot.aiBaseUrl shouldBe "https://example.test"
            snapshot.aiApiKey shouldBe "secret"
            snapshot.aiModel shouldBe "model"
        }

    @Test
    fun aFailedWriteLeavesTheSettingsAsTheyAreOnDisk() =
        runTest {
            // A directory where the file belongs: every write fails.
            val file = Files.createDirectory(directory.resolve("settings.properties")).toFile()
            val repository = SettingsRepositoryImpl(NpnLog.None, file)
            val before = repository.settings.first()

            assertFailsWith<IOException> { repository.edit { it.copy(aiModel = "model") } }

            repository.settings.first() shouldBe before
        }

    /**
     * A write cut short (a full disk) must not cost the settings already saved: the file keeps them
     * whole until a complete new one takes its place.
     */
    @Test
    fun aWriteCutShortKeepsTheSavedSettings() =
        runTest {
            val file = directory.resolve("settings.properties").toFile()
            SettingsRepositoryImpl(NpnLog.None, file).edit { it.copy(aiModel = "model", aiApiKey = "secret") }
            val saved = SettingsRepositoryImpl(NpnLog.None, file).settings.first()
            val diskFull =
                SettingsRepositoryImpl(
                    file,
                    NpnLog.None,
                ) { target -> FailingAfter(FileOutputStream(target), bytes = 16) }

            assertFailsWith<IOException> { diskFull.edit { it.copy(aiModel = "other") } }

            SettingsRepositoryImpl(NpnLog.None, file).settings.first() shouldBe saved
        }

    /** Writes [bytes] bytes through to [out], then fails as a full disk does. */
    private class FailingAfter(
        private val out: OutputStream,
        private var bytes: Int,
    ) : OutputStream() {
        override fun write(b: Int) {
            if (bytes-- <= 0) throw IOException("No space left on device")
            out.write(b)
        }

        override fun close() = out.close()
    }

    @Test
    fun theCloudRemoteRoundTripsUntrimmed() =
        runTest {
            val file = directory.resolve("settings.properties").toFile()
            // The field is edited one keystroke at a time, so the repository must not trim: a
            // leading or trailing space is a half-typed path, and the space inside the path is
            // part of it. Consumers trim at the point of use. Both the write and the reload
            // (Properties escaping included) have to leave every space where it was.
            SettingsRepositoryImpl(NpnLog.None, file).edit { it.copy(cloudRemote = " drive:My Docs ") }

            SettingsRepositoryImpl(NpnLog.None, file).settings.first().cloudRemote shouldBe " drive:My Docs "
        }

    @Test
    fun cloudSyncSettingsAreNormalisedOnWrite() =
        runTest {
            val file = directory.resolve("settings.properties").toFile()
            SettingsRepositoryImpl(NpnLog.None, file).edit {
                it.copy(
                    cloudSyncEnabled = true,
                    cloudRemote = "drive:My Docs",
                    // Below the allowed range: clamped up to the minimum.
                    cloudSyncIntervalMinutes = 1,
                    rclonePath = "/opt/rclone/rclone",
                    // Whitespace-only reads as "not set", never as a path.
                    rcloneConfigPath = "   ",
                )
            }

            val snapshot = SettingsRepositoryImpl(NpnLog.None, file).settings.first()
            snapshot.cloudSyncEnabled shouldBe true
            snapshot.cloudRemote shouldBe "drive:My Docs"
            snapshot.cloudSyncIntervalMinutes shouldBe CloudSyncConfig.MIN_INTERVAL_MINUTES
            snapshot.rclonePath shouldBe "/opt/rclone/rclone"
            snapshot.rcloneConfigPath shouldBe null
            snapshot.toCloudSyncConfig().configured shouldBe true
        }

    @Test
    fun aiProviderAndReasoningPersist() =
        runTest {
            val file = directory.resolve("settings.properties").toFile()
            val repository = SettingsRepositoryImpl(NpnLog.None, file)
            repository.edit { it.copy(aiProvider = AiProvider.ANTHROPIC) }
            repository.edit { it.copy(aiReasoningAssistant = AiReasoningLevel.MEDIUM) }
            repository.edit { it.copy(aiReasoningEdit = AiReasoningLevel.NONE) }

            val snapshot = SettingsRepositoryImpl(NpnLog.None, file).settings.first()
            snapshot.aiProvider shouldBe AiProvider.ANTHROPIC
            snapshot.aiReasoningAssistant shouldBe AiReasoningLevel.MEDIUM
            snapshot.aiReasoningEdit shouldBe AiReasoningLevel.NONE
        }

    @Test
    fun aiOcrEngineToggleDefaultsOffAndPersists() =
        runTest {
            // Default off matters more than persistence here: this switch bills per page, so a
            // settings file that predates it must never load as "on".
            val file = directory.resolve("settings.properties").toFile()
            SettingsRepositoryImpl(NpnLog.None, file).settings.first().aiOcrAuto shouldBe false

            SettingsRepositoryImpl(NpnLog.None, file).edit { it.copy(aiOcrAuto = true) }
            val snapshot = SettingsRepositoryImpl(NpnLog.None, file).settings.first()
            snapshot.aiOcrAuto shouldBe true
            snapshot.toAiConfig().ocrAuto shouldBe true
        }

    @Test
    fun preProviderSettingsWithBaseUrlMigrateToCustom() =
        runTest {
            // A settings file written before provider selection existed: base URL set, no
            // provider key. That was the free-form OpenAI-compatible endpoint = CUSTOM.
            val file = directory.resolve("settings.properties").toFile()
            file.writeText("aiEnabled=true\naiBaseUrl=http\\://localhost\\:11434\naiModel=qwen3\\:8b\n")

            val snapshot = SettingsRepositoryImpl(NpnLog.None, file).settings.first()
            snapshot.aiProvider shouldBe AiProvider.CUSTOM
            snapshot.aiReasoningAssistant shouldBe AiReasoningLevel.DEFAULT
            snapshot.aiReasoningEdit shouldBe AiReasoningLevel.LOW
        }

    @Test
    fun freshSettingsDefaultToFirstProvider() =
        runTest {
            val file = directory.resolve("settings.properties").toFile()
            SettingsRepositoryImpl(NpnLog.None, file).settings.first().aiProvider shouldBe AiProvider.OPENAI
        }

    @Test
    fun outOfRangeValuesAreClampedByTheRepository() =
        runTest {
            val file = directory.resolve("settings.properties").toFile()
            // Caller passes values outside the allowed ranges; the repository (via SettingsCodec)
            // coerces them, so neither a write nor a reload can hold an out-of-range value.
            SettingsRepositoryImpl(NpnLog.None, file).edit {
                it.copy(aiMaxTagsPerDoc = 999, aiMaxNewTagsPerDoc = -5, gridColumnWidth = 10)
            }

            val snapshot = SettingsRepositoryImpl(NpnLog.None, file).settings.first()
            snapshot.aiMaxTagsPerDoc shouldBe AiTagging.MAX_TAGS_PER_DOC
            snapshot.aiMaxNewTagsPerDoc shouldBe AiTagging.MIN_NEW_TAGS_PER_DOC
            snapshot.gridColumnWidth shouldBe GridSizing.MIN_COLUMN_WIDTH
        }

    @Test
    fun appLanguagePersistsAndClears() =
        runTest {
            val file = directory.resolve("settings.properties").toFile()
            SettingsRepositoryImpl(NpnLog.None, file).edit { it.copy(appLanguage = "nl") }

            var reloaded = SettingsRepositoryImpl(NpnLog.None, file)
            reloaded.settings.first().appLanguage shouldBe "nl"

            SettingsRepositoryImpl(NpnLog.None, file).edit { it.copy(appLanguage = null) }
            reloaded = SettingsRepositoryImpl(NpnLog.None, file)
            reloaded.settings.first().appLanguage shouldBe null
        }

    @Test
    fun ocrLanguagesDefaultToAutomaticAndPersistAChoice() =
        runTest {
            val file = directory.resolve("settings.properties").toFile()
            SettingsRepositoryImpl(NpnLog.None, file).settings.first().ocrLanguage shouldBe null

            // A choice of English only is still a choice, not the automatic default.
            SettingsRepositoryImpl(NpnLog.None, file).edit { it.copy(ocrLanguage = "eng") }
            SettingsRepositoryImpl(NpnLog.None, file).settings.first().ocrLanguage shouldBe "eng"

            SettingsRepositoryImpl(NpnLog.None, file).edit { it.copy(ocrLanguage = "nld + eng") }
            SettingsRepositoryImpl(NpnLog.None, file).settings.first().ocrLanguage shouldBe "nld+eng"

            SettingsRepositoryImpl(NpnLog.None, file).edit { it.copy(ocrLanguage = null) }
            SettingsRepositoryImpl(NpnLog.None, file).settings.first().ocrLanguage shouldBe null
        }

    @Test
    fun legacyOcrLanguageMigrates() =
        runTest {
            // Every install before the language picker stored a single code, "eng" by default.
            val file = directory.resolve("settings.properties").toFile()
            file.writeText("ocrLanguage=eng\n")
            SettingsRepositoryImpl(NpnLog.None, file).settings.first().ocrLanguage shouldBe null

            file.writeText("ocrLanguage=fra\n")
            SettingsRepositoryImpl(NpnLog.None, file).settings.first().ocrLanguage shouldBe "fra"

            // The next write moves it to the new key and drops the old one.
            SettingsRepositoryImpl(NpnLog.None, file).edit { it.copy(appLanguage = "fr") }
            file.readText().lines().none { it.startsWith("ocrLanguage=") } shouldBe true
            SettingsRepositoryImpl(NpnLog.None, file).settings.first().ocrLanguage shouldBe "fra"
        }

    /**
     * The trash retention moved into each library's journal: the device's stored value is read only
     * for a library to adopt, and stays stored through later writes for every library opened after.
     */
    @Test
    fun aStoredTrashRetentionIsKeptForLibrariesToAdopt() =
        runTest {
            val file = directory.resolve("settings.properties").toFile()
            SettingsRepositoryImpl(NpnLog.None, file).legacyLibrarySettings().trashRetentionDays shouldBe null

            file.writeText("trashRetentionDays=7\n")
            SettingsRepositoryImpl(NpnLog.None, file).edit { it.copy(appLanguage = "fr") }

            SettingsRepositoryImpl(NpnLog.None, file).legacyLibrarySettings().trashRetentionDays shouldBe 7
        }
}
