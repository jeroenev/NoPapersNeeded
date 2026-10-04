package dev.npn.core.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.Properties
import kotlin.uuid.Uuid

/**
 * Desktop [SettingsRepository] persisted to a `settings.properties` file under
 * the platform config dir (`no-papers-needed/` in XDG_CONFIG_HOME on Unix,
 * %APPDATA% on Windows). Values are held in a [MutableStateFlow] loaded at init; a write
 * persists synchronously on [Dispatchers.IO], then updates the flow.
 *
 * A plain properties file sidesteps okio/path
 * fiddling on desktop and is enough for a handful of app-global settings.
 *
 * All key mapping, defaults and clamping live in the shared [SettingsCodec]; this class only owns
 * the storage primitive (the properties file, its owner-only permissions, and the persist mutex).
 */
class SettingsRepositoryImpl(
    private val file: File = defaultFile(),
) : SettingsRepository {
    private val props = Properties()
    private val isWindows =
        System.getProperty("os.name")?.startsWith("Windows", ignoreCase = true) ?: false
    private val persistMutex = Mutex()

    // Adapters that let the shared codec read from / write to the Properties file. Empty strings
    // read back as null so the codec's default fills in, matching the pre-refactor behaviour.
    // Declared before `state` because `state`'s initializer calls load(), which uses `reader`.
    private val reader =
        object : SettingsCodec.Reader {
            override fun string(key: String): String? = props.getProperty(key)?.ifEmpty { null }

            override fun int(key: String): Int? = props.getProperty(key)?.toIntOrNull()

            override fun boolean(key: String): Boolean? = props.getProperty(key)?.toBooleanStrictOrNull()
        }

    private val writer =
        object : SettingsCodec.Writer {
            override fun putString(
                key: String,
                value: String,
            ) {
                props.setProperty(key, value)
            }

            override fun putInt(
                key: String,
                value: Int,
            ) {
                props.setProperty(key, value.toString())
            }

            override fun putBoolean(
                key: String,
                value: Boolean,
            ) {
                props.setProperty(key, value.toString())
            }

            override fun remove(key: String) {
                props.remove(key)
            }
        }

    private val state = MutableStateFlow(load())

    private fun load(): SettingsSnapshot {
        if (file.exists()) {
            restrictFilePermissions()
            runCatching { file.inputStream().use { props.load(it) } }
        }
        val installationId =
            props.getProperty(SettingsKeys.INSTALLATION_ID)
                ?: Uuid.random().toString().also { fresh ->
                    props.setProperty(SettingsKeys.INSTALLATION_ID, fresh)
                    runCatching { storeProperties() }
                }
        return SettingsCodec.load(reader, installationId)
    }

    private suspend fun persist(snapshot: SettingsSnapshot) =
        withContext(Dispatchers.IO) {
            SettingsCodec.store(writer, snapshot)
            storeProperties()
        }

    private fun storeProperties() {
        file.parentFile?.mkdirs()
        if (!isWindows) {
            try {
                Files.createFile(file.toPath(), PosixFilePermissions.asFileAttribute(OWNER_ONLY_PERMISSIONS))
            } catch (_: FileAlreadyExistsException) {
                // Existing files are tightened below before they are opened for writing.
            }
            restrictFilePermissions()
        }
        file.outputStream().use { props.store(it, "no-papers-needed settings") }
    }

    private fun restrictFilePermissions() {
        if (!isWindows) {
            Files.setPosixFilePermissions(file.toPath(), OWNER_ONLY_PERMISSIONS)
        }
    }

    override val settings: Flow<SettingsSnapshot> = state

    override suspend fun edit(transform: (SettingsSnapshot) -> SettingsSnapshot) =
        persistMutex.withLock {
            val next = SettingsCodec.normalise(transform(state.value))
            // Persist first: a write that fails leaves the flow showing what is on disk.
            persist(next)
            state.value = next
        }

    override suspend fun legacyLibrarySettings(): LegacyLibrarySettings = SettingsCodec.legacyLibrarySettings(reader)

    companion object {
        private val OWNER_ONLY_PERMISSIONS = PosixFilePermissions.fromString("rw-------")

        fun defaultFile(): File {
            val configHome = PlatformDirs.configHome()
            return File("$configHome/no-papers-needed/settings.properties")
        }
    }
}
