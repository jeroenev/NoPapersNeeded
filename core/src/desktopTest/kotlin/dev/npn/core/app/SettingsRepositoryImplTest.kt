package dev.npn.core.app

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
                SettingsRepositoryImpl(file).setAiApiKey("secret")

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
                    async { repository.setAiBaseUrl("https://example.test") },
                    async { repository.setAiApiKey("secret") },
                    async { repository.setAiModel("model") },
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
    fun appLanguagePersistsAndClears() =
        runTest {
            val directory = Files.createTempDirectory("npn-settings-test")
            try {
                val file = directory.resolve("settings.properties").toFile()
                SettingsRepositoryImpl(file).setAppLanguage("nl")

                var reloaded = SettingsRepositoryImpl(file)
                reloaded.settings.first().appLanguage shouldBe "nl"

                SettingsRepositoryImpl(file).setAppLanguage(null)
                reloaded = SettingsRepositoryImpl(file)
                reloaded.settings.first().appLanguage shouldBe null
            } finally {
                directory.toFile().deleteRecursively()
            }
        }
}
