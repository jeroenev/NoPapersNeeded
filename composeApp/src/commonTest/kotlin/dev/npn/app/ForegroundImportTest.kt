package dev.npn.app

import dev.npn.app.di.AppEnvironment
import dev.npn.app.platform.withLibraryForForegroundImport
import dev.npn.core.app.LibrarySession
import dev.npn.core.app.SettingsRepository
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class ForegroundImportTest {
    @Test
    fun coldShareRestoresLibraryAndFinishesItsFollowUpWork() =
        runTest {
            val library = FakeLibrarySession()
            val environment = FakeImportEnvironment(this, library)

            val result = environment.withLibraryForForegroundImport { "imported" }

            result shouldBe "imported"
            environment.restoreCalls shouldBe 1
            library.work.ocrDrains shouldBe 1
            library.work.enrichmentDrains shouldBe 1
        }

    private class FakeImportEnvironment(
        override val appScope: CoroutineScope,
        private val restoredLibrary: LibrarySession,
    ) : AppEnvironment {
        override val settings: SettingsRepository = FakeSettingsRepository()
        private val mutableSession = MutableStateFlow<LibrarySession?>(null)
        override val session: StateFlow<LibrarySession?> = mutableSession
        override val opening: StateFlow<Boolean> = MutableStateFlow(false)
        var restoreCalls = 0

        override suspend fun openLibrary(location: String) = Unit

        override suspend fun closeLibrary() = Unit

        override suspend fun openPersistedLibrary() {
            restoreCalls++
            mutableSession.value = restoredLibrary
        }

        override suspend fun scanWatchedFolder(): Int = 0
    }
}
