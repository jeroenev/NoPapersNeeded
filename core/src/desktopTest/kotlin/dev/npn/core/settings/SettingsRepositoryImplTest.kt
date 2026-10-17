package dev.npn.core.settings

import dev.npn.core.ai.AiProvider
import dev.npn.core.ai.AiReasoningLevel
import dev.npn.core.ai.AiTagging
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
            SettingsRepositoryImpl(file).edit { it.copy(aiApiKey = "secret") }

            if (supportsPosixView(file)) {
                Files.getPosixFilePermissions(file.toPath()) shouldBe
                    setOf(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                    )
            }
        }

    private fun supportsPosixView(file: java.io.File): Boolean =
        runCatching { Files.getFileStore(file.toPath()).supportsFileAttributeView("posix") }
            .getOrDefault(false)

    @Test
    fun concurrentUpdatesPersistOneCombinedSnapshot() =
        runTest {
            val file = directory.resolve("settings.properties").toFile()
            val repository = SettingsRepositoryImpl(file)

            listOf(
                async { repository.edit { it.copy(aiBaseUrl = "https://example.test") } },
                async { repository.edit { it.copy(aiApiKey = "secret") } },
                async { repository.edit { it.copy(aiModel = "model") } },
            ).awaitAll()

            val reloaded = SettingsRepositoryImpl(file)
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
            val repository = SettingsRepositoryImpl(file)
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
            SettingsRepositoryImpl(file).edit { it.copy(aiModel = "model", aiApiKey = "secret") }
            val saved = SettingsRepositoryImpl(file).settings.first()
            val diskFull =
                SettingsRepositoryImpl(file) { target -> FailingAfter(FileOutputStream(target), bytes = 16) }

            assertFailsWith<IOException> { diskFull.edit { it.copy(aiModel = "other") } }

            SettingsRepositoryImpl(file).settings.first() shouldBe saved
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
    fun aiProviderAndReasoningPersist() =
        runTest {
            val file = directory.resolve("settings.properties").toFile()
            val repository = SettingsRepositoryImpl(file)
            repository.edit { it.copy(aiProvider = AiProvider.ANTHROPIC) }
            repository.edit { it.copy(aiReasoningAssistant = AiReasoningLevel.MEDIUM) }
            repository.edit { it.copy(aiReasoningEdit = AiReasoningLevel.NONE) }

            val snapshot = SettingsRepositoryImpl(file).settings.first()
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
            SettingsRepositoryImpl(file).settings.first().aiOcrAuto shouldBe false

            SettingsRepositoryImpl(file).edit { it.copy(aiOcrAuto = true) }
            val snapshot = SettingsRepositoryImpl(file).settings.first()
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

            val snapshot = SettingsRepositoryImpl(file).settings.first()
            snapshot.aiProvider shouldBe AiProvider.CUSTOM
            snapshot.aiReasoningAssistant shouldBe AiReasoningLevel.DEFAULT
            snapshot.aiReasoningEdit shouldBe AiReasoningLevel.LOW
        }

    @Test
    fun freshSettingsDefaultToFirstProvider() =
        runTest {
            val file = directory.resolve("settings.properties").toFile()
            SettingsRepositoryImpl(file).settings.first().aiProvider shouldBe AiProvider.OPENAI
        }

    @Test
    fun outOfRangeValuesAreClampedByTheRepository() =
        runTest {
            val file = directory.resolve("settings.properties").toFile()
            // Caller passes values outside the allowed ranges; the repository (via SettingsCodec)
            // coerces them, so neither a write nor a reload can hold an out-of-range value.
            SettingsRepositoryImpl(file).edit {
                it.copy(aiMaxTagsPerDoc = 999, aiMaxNewTagsPerDoc = -5, gridColumnWidth = 10)
            }

            val snapshot = SettingsRepositoryImpl(file).settings.first()
            snapshot.aiMaxTagsPerDoc shouldBe AiTagging.MAX_TAGS_PER_DOC
            snapshot.aiMaxNewTagsPerDoc shouldBe AiTagging.MIN_NEW_TAGS_PER_DOC
            snapshot.gridColumnWidth shouldBe GridSizing.MIN_COLUMN_WIDTH
        }

    @Test
    fun appLanguagePersistsAndClears() =
        runTest {
            val file = directory.resolve("settings.properties").toFile()
            SettingsRepositoryImpl(file).edit { it.copy(appLanguage = "nl") }

            var reloaded = SettingsRepositoryImpl(file)
            reloaded.settings.first().appLanguage shouldBe "nl"

            SettingsRepositoryImpl(file).edit { it.copy(appLanguage = null) }
            reloaded = SettingsRepositoryImpl(file)
            reloaded.settings.first().appLanguage shouldBe null
        }

    @Test
    fun ocrLanguagesDefaultToAutomaticAndPersistAChoice() =
        runTest {
            val file = directory.resolve("settings.properties").toFile()
            SettingsRepositoryImpl(file).settings.first().ocrLanguage shouldBe null

            // A choice of English only is still a choice, not the automatic default.
            SettingsRepositoryImpl(file).edit { it.copy(ocrLanguage = "eng") }
            SettingsRepositoryImpl(file).settings.first().ocrLanguage shouldBe "eng"

            SettingsRepositoryImpl(file).edit { it.copy(ocrLanguage = "nld + eng") }
            SettingsRepositoryImpl(file).settings.first().ocrLanguage shouldBe "nld+eng"

            SettingsRepositoryImpl(file).edit { it.copy(ocrLanguage = null) }
            SettingsRepositoryImpl(file).settings.first().ocrLanguage shouldBe null
        }

    @Test
    fun legacyOcrLanguageMigrates() =
        runTest {
            // Every install before the language picker stored a single code, "eng" by default.
            val file = directory.resolve("settings.properties").toFile()
            file.writeText("ocrLanguage=eng\n")
            SettingsRepositoryImpl(file).settings.first().ocrLanguage shouldBe null

            file.writeText("ocrLanguage=fra\n")
            SettingsRepositoryImpl(file).settings.first().ocrLanguage shouldBe "fra"

            // The next write moves it to the new key and drops the old one.
            SettingsRepositoryImpl(file).edit { it.copy(appLanguage = "fr") }
            file.readText().lines().none { it.startsWith("ocrLanguage=") } shouldBe true
            SettingsRepositoryImpl(file).settings.first().ocrLanguage shouldBe "fra"
        }

    /**
     * The trash retention moved into each library's journal: the device's stored value is read only
     * for a library to adopt, and stays stored through later writes for every library opened after.
     */
    @Test
    fun aStoredTrashRetentionIsKeptForLibrariesToAdopt() =
        runTest {
            val file = directory.resolve("settings.properties").toFile()
            SettingsRepositoryImpl(file).legacyLibrarySettings().trashRetentionDays shouldBe null

            file.writeText("trashRetentionDays=7\n")
            SettingsRepositoryImpl(file).edit { it.copy(appLanguage = "fr") }

            SettingsRepositoryImpl(file).legacyLibrarySettings().trashRetentionDays shouldBe 7
        }
}
