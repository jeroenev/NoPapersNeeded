package dev.npn.app

import dev.npn.app.di.AppEnvironment
import dev.npn.app.di.CloudSetupEngine
import dev.npn.core.app.LibrarySession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/**
 * [AppEnvironment] that behaves the way [dev.npn.app.di.DefaultAppEnvironment] does at its seam —
 * a failed switch leaves the previous library open and throws, the saved location only changes on
 * success — without building a library. Every location opens as its own [FakeLibrarySession]
 * unless [failures] says otherwise.
 */
class FakeAppEnvironment(
    override val appScope: CoroutineScope,
    savedLocation: String? = null,
    /** What [newCloudSetup] hands out; null is a platform without one. */
    private val cloudSetup: FakeCloudSetup? = null,
) : AppEnvironment {
    override val settings = FakeSettingsRepository()
    override val session = MutableStateFlow<LibrarySession?>(null)
    override val opening = MutableStateFlow(false)

    /** Locations that throw when opened, and what they throw. */
    val failures = mutableMapOf<String, Throwable>()

    /** Every location an open was attempted for, in order. */
    val opened = mutableListOf<String>()

    /** The library open at each location, once it has opened. */
    val sessions = mutableMapOf<String, FakeLibrarySession>()

    /** When set, an open waits here with [opening] raised, so a test can see it in flight. */
    var gate: CompletableDeferred<Unit>? = null

    init {
        if (savedLocation != null) settings.updateSettings { it.copy(libraryLocation = savedLocation) }
    }

    override suspend fun openLibrary(location: String) {
        open(location)
        settings.edit { it.copy(libraryLocation = location) }
    }

    override suspend fun openPersistedLibrary() {
        if (session.value != null) return
        val location = settings.settings.first().libraryLocation ?: return
        open(location)
    }

    private suspend fun open(location: String) {
        opened += location
        opening.value = true
        try {
            gate?.await()
            failures[location]?.let { throw it }
            session.value = sessions.getOrPut(location) { FakeLibrarySession() }
        } finally {
            opening.value = false
        }
    }

    override suspend fun closeLibrary() {
        session.value = null
    }

    override suspend fun scanWatchedFolder(): Int = 0

    /** How many setup engines were handed out, and how many of them were closed. */
    var cloudEnginesStarted = 0
    var cloudEnginesClosed = 0

    override fun newCloudSetup(): CloudSetupEngine? =
        cloudSetup?.let { setup ->
            cloudEnginesStarted++
            CloudSetupEngine(setup) { cloudEnginesClosed++ }
        }
}
