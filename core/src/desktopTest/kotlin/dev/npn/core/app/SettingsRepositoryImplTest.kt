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
    fun settingsFileIsOwnerOnly() = runTest {
        val directory = Files.createTempDirectory("npn-settings-test")
        try {
            val file = directory.resolve("settings.properties").toFile()
            SettingsRepositoryImpl(file).setAiApiKey("secret")

            Files.getPosixFilePermissions(file.toPath()) shouldBe setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
            )
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun concurrentUpdatesPersistOneCombinedSnapshot() = runTest {
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
            reloaded.aiBaseUrl.first() shouldBe "https://example.test"
            reloaded.aiApiKey.first() shouldBe "secret"
            reloaded.aiModel.first() shouldBe "model"
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
