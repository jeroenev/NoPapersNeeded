package dev.npn.core.app

import dev.npn.core.ai.AiProvider
import dev.npn.core.ai.AiReasoningLevel
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.Test
import kotlin.test.assertFailsWith

class SettingsRepositoryImplTest {
    @Test
    fun settingsFileIsOwnerOnly() =
        runTest {
            val directory = Files.createTempDirectory("npn-settings-test")
            try {
                val file = directory.resolve("settings.properties").toFile()
                SettingsRepositoryImpl(file).edit { it.copy(aiApiKey = "secret") }

                if (supportsPosixView(file)) {
                    Files.getPosixFilePermissions(file.toPath()) shouldBe
                        setOf(
                            PosixFilePermission.OWNER_READ,
                            PosixFilePermission.OWNER_WRITE,
                        )
                }
            } finally {
                directory.toFile().deleteRecursively()
            }
        }

    private fun supportsPosixView(file: java.io.File): Boolean =
        runCatching { Files.getFileStore(file.toPath()).supportsFileAttributeView("posix") }
            .getOrDefault(false)

    @Test
    fun concurrentUpdatesPersistOneCombinedSnapshot() =
        runTest {
            val directory = Files.createTempDirectory("npn-settings-test")
            try {
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
            } finally {
                directory.toFile().deleteRecursively()
            }
        }

    @Test
    fun aFailedWriteLeavesTheSettingsAsTheyAreOnDisk() =
        runTest {
            val directory = Files.createTempDirectory("npn-settings-test")
            try {
                // A directory where the file belongs: every write fails.
                val file = Files.createDirectory(directory.resolve("settings.properties")).toFile()
                val repository = SettingsRepositoryImpl(file)
                val before = repository.settings.first()

                assertFailsWith<IOException> { repository.edit { it.copy(aiModel = "model") } }

                repository.settings.first() shouldBe before
            } finally {
                directory.toFile().deleteRecursively()
            }
        }

    @Test
    fun aiProviderAndReasoningPersist() =
        runTest {
            val directory = Files.createTempDirectory("npn-settings-test")
            try {
                val file = directory.resolve("settings.properties").toFile()
                val repository = SettingsRepositoryImpl(file)
                repository.edit { it.copy(aiProvider = AiProvider.ANTHROPIC) }
                repository.edit { it.copy(aiReasoningAssistant = AiReasoningLevel.MEDIUM) }
                repository.edit { it.copy(aiReasoningEdit = AiReasoningLevel.NONE) }

                val snapshot = SettingsRepositoryImpl(file).settings.first()
                snapshot.aiProvider shouldBe AiProvider.ANTHROPIC
                snapshot.aiReasoningAssistant shouldBe AiReasoningLevel.MEDIUM
                snapshot.aiReasoningEdit shouldBe AiReasoningLevel.NONE
            } finally {
                directory.toFile().deleteRecursively()
            }
        }

    @Test
    fun aiOcrEngineToggleDefaultsOffAndPersists() =
        runTest {
            val directory = Files.createTempDirectory("npn-settings-test")
            try {
                // Default off matters more than persistence here: this switch bills per page, so a
                // settings file that predates it must never load as "on".
                val file = directory.resolve("settings.properties").toFile()
                SettingsRepositoryImpl(file).settings.first().aiOcrAuto shouldBe false

                SettingsRepositoryImpl(file).edit { it.copy(aiOcrAuto = true) }
                val snapshot = SettingsRepositoryImpl(file).settings.first()
                snapshot.aiOcrAuto shouldBe true
                snapshot.toAiConfig().ocrAuto shouldBe true
            } finally {
                directory.toFile().deleteRecursively()
            }
        }

    @Test
    fun preProviderSettingsWithBaseUrlMigrateToCustom() =
        runTest {
            val directory = Files.createTempDirectory("npn-settings-test")
            try {
                // A settings file written before provider selection existed: base URL set, no
                // provider key. That was the free-form OpenAI-compatible endpoint = CUSTOM.
                val file = directory.resolve("settings.properties").toFile()
                file.writeText("aiEnabled=true\naiBaseUrl=http\\://localhost\\:11434\naiModel=qwen3\\:8b\n")

                val snapshot = SettingsRepositoryImpl(file).settings.first()
                snapshot.aiProvider shouldBe AiProvider.CUSTOM
                snapshot.aiReasoningAssistant shouldBe AiReasoningLevel.DEFAULT
                snapshot.aiReasoningEdit shouldBe AiReasoningLevel.LOW
            } finally {
                directory.toFile().deleteRecursively()
            }
        }

    @Test
    fun freshSettingsDefaultToFirstProvider() =
        runTest {
            val directory = Files.createTempDirectory("npn-settings-test")
            try {
                val file = directory.resolve("settings.properties").toFile()
                SettingsRepositoryImpl(file).settings.first().aiProvider shouldBe AiProvider.OPENAI
            } finally {
                directory.toFile().deleteRecursively()
            }
        }

    @Test
    fun outOfRangeValuesAreClampedByTheRepository() =
        runTest {
            val directory = Files.createTempDirectory("npn-settings-test")
            try {
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
            } finally {
                directory.toFile().deleteRecursively()
            }
        }

    @Test
    fun appLanguagePersistsAndClears() =
        runTest {
            val directory = Files.createTempDirectory("npn-settings-test")
            try {
                val file = directory.resolve("settings.properties").toFile()
                SettingsRepositoryImpl(file).edit { it.copy(appLanguage = "nl") }

                var reloaded = SettingsRepositoryImpl(file)
                reloaded.settings.first().appLanguage shouldBe "nl"

                SettingsRepositoryImpl(file).edit { it.copy(appLanguage = null) }
                reloaded = SettingsRepositoryImpl(file)
                reloaded.settings.first().appLanguage shouldBe null
            } finally {
                directory.toFile().deleteRecursively()
            }
        }

    @Test
    fun ocrLanguagesDefaultToAutomaticAndPersistAChoice() =
        runTest {
            val directory = Files.createTempDirectory("npn-settings-test")
            try {
                val file = directory.resolve("settings.properties").toFile()
                SettingsRepositoryImpl(file).settings.first().ocrLanguage shouldBe null

                // A choice of English only is still a choice, not the automatic default.
                SettingsRepositoryImpl(file).edit { it.copy(ocrLanguage = "eng") }
                SettingsRepositoryImpl(file).settings.first().ocrLanguage shouldBe "eng"

                SettingsRepositoryImpl(file).edit { it.copy(ocrLanguage = "nld + eng") }
                SettingsRepositoryImpl(file).settings.first().ocrLanguage shouldBe "nld+eng"

                SettingsRepositoryImpl(file).edit { it.copy(ocrLanguage = null) }
                SettingsRepositoryImpl(file).settings.first().ocrLanguage shouldBe null
            } finally {
                directory.toFile().deleteRecursively()
            }
        }

    @Test
    fun legacyOcrLanguageMigrates() =
        runTest {
            val directory = Files.createTempDirectory("npn-settings-test")
            try {
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
            } finally {
                directory.toFile().deleteRecursively()
            }
        }
}
