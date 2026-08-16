package dev.npn.core.app

import dev.npn.core.ai.AiProvider
import dev.npn.core.ai.AiReasoningLevel
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.Test

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
}
