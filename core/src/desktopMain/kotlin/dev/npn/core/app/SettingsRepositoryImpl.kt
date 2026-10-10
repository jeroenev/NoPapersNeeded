package dev.npn.core.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.FileAttribute
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
class SettingsRepositoryImpl internal constructor(
    private val file: File,
    /** Opens the stream a write goes to: a file's own stream, but a test hands one that fails partway. */
    private val openForWrite: (File) -> OutputStream,
) : SettingsRepository {
    constructor(file: File = defaultFile()) : this(file, { FileOutputStream(it) })

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

    /**
     * Writes the properties to a new owner-only file beside [file], then moves it over [file] in one
     * step. A write cut short (a full disk, a crash) leaves the saved settings whole; writing [file]
     * in place would have truncated them first.
     */
    private fun storeProperties() {
        val dir = file.absoluteFile.parentFile.toPath()
        Files.createDirectories(dir)
        // Owner-only from the start: the file holds the AI endpoint's key.
        val attributes: Array<FileAttribute<*>> =
            if (isWindows) emptyArray() else arrayOf(PosixFilePermissions.asFileAttribute(OWNER_ONLY_PERMISSIONS))
        val tmp = Files.createTempFile(dir, file.name, TEMP_SUFFIX, *attributes)
        try {
            openForWrite(tmp.toFile()).use { props.store(it, "no-papers-needed settings") }
            // On disk before it replaces the old file, so a crash just after the move finds it whole.
            FileChannel.open(tmp, StandardOpenOption.WRITE).use { it.force(true) }
            Files.move(tmp, file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(tmp)
        }
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
        private const val TEMP_SUFFIX = ".tmp"

        fun defaultFile(): File {
            val configHome = PlatformDirs.configHome()
            return File("$configHome/no-papers-needed/settings.properties")
        }
    }
}
