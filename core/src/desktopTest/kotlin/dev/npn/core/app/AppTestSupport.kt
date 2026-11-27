package dev.npn.core.app

import dev.npn.core.ai.AiConfig
import dev.npn.core.db.DeviceDatabase
import dev.npn.core.db.NpnDatabase
import dev.npn.core.fs.FileMeta
import dev.npn.core.fs.KeepStore
import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.fs.NpnPaths
import dev.npn.core.fs.TrashOps
import dev.npn.core.fs.sha256Bytes
import dev.npn.core.index.DeviceIdentity
import dev.npn.core.index.IndexRig
import dev.npn.core.index.JournalCoordinator
import dev.npn.core.index.MutableClock
import dev.npn.core.journal.ContentAddressedJournalWriter
import dev.npn.core.journal.HlcWatermarkAllocator
import dev.npn.core.journal.JournalOp
import dev.npn.core.journal.JournalPaths
import dev.npn.core.journal.journalObjects
import dev.npn.core.ocr.FakeOcrEngine
import dev.npn.core.ocr.OcrEngine
import dev.npn.core.pathrules.StoragePathRule
import dev.npn.core.pdf.PdfEngine
import dev.npn.core.settings.AppDirs
import dev.npn.core.settings.LegacyLibrarySettings
import dev.npn.core.settings.SessionSettings
import dev.npn.core.sync.CloudSyncConfig
import dev.npn.core.sync.SyncTransport
import dev.npn.core.testing.copy
import dev.npn.core.util.EpochClock
import dev.npn.core.util.JvmLog
import dev.npn.core.util.NpnLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertFailsWith

/**
 * A library filesystem, for [AppSessionRig.create]'s `files`, that runs [next] once, right before the
 * trash is next listed: another operation landing in the window of one that reads the trash.
 */
class BeforeTrashListing {
    @Volatile var next: (suspend () -> Unit)? = null

    fun over(fs: LibraryFileSystem): LibraryFileSystem =
        object : LibraryFileSystem by fs {
            override suspend fun listAllUnder(relPath: String): List<FileMeta> {
                if (relPath == NpnPaths.TRASH_DIR) next?.also { next = null }?.invoke()
                return fs.listAllUnder(relPath)
            }
        }
}

/**
 * Temp-dir rig that builds a real [LibrarySessionImpl] over a JVM filesystem + real Room DB +
 * real journal/index/ocr pieces, wired through the same [jvmSessionDeps] the desktop app uses.
 * A second session on the same library (different deviceId) is built via [secondSession] for
 * convergence tests.
 */
internal class AppSessionRig private constructor(
    val libRoot: Path,
    val dbDir: File,
    /** What [session] was built from, before `create`'s `files` and `pdfEngine` wrapped its pieces. */
    val deps: SessionDeps,
    val clock: MutableClock,
    val scope: CoroutineScope,
    val session: LibrarySessionImpl,
) {
    val fs: LibraryFileSystem = deps.fs
    val db: NpnDatabase = deps.db

    /** This device's own state: queues, edit runs, device id, chats. */
    val deviceDb: DeviceDatabase = deps.deviceDb

    fun writePdf(
        relPath: String,
        bytes: ByteArray,
    ) {
        val p = libRoot.resolve(relPath)
        Files.createDirectories(p.parent ?: libRoot)
        Files.write(p, bytes)
    }

    fun exists(relPath: String): Boolean = Files.exists(libRoot.resolve(relPath))

    /** A document's id is the hash of its bytes, so the library path is enough to name it. */
    suspend fun docId(relPath: String): String = fs.sha256(relPath)

    /**
     * A journal writer over this library for [installation], publishing as another device does:
     * its writes bypass this session, which sees them at its next rescan.
     */
    internal fun writer(installation: String): ContentAddressedJournalWriter =
        ContentAddressedJournalWriter(fs, installation, HlcWatermarkAllocator(clock))

    /** Published journal objects, for asserting that a write committed exactly once. */
    suspend fun journalObjectCount(): Int = fs.journalObjects().size

    /** The [secondSession]s and their databases, closed with this rig. */
    private val otherSessions = ArrayList<LibrarySessionImpl>()
    private val otherDeps = ArrayList<SessionDeps>()

    /** Run [block] over this rig, then close it, whether or not [block] threw. */
    suspend fun use(block: suspend (AppSessionRig) -> Unit) {
        try {
            block(this)
        } finally {
            close()
        }
    }

    private suspend fun closeSessions() {
        otherSessions.forEach { it.close() }
        otherDeps.forEach { it.closeDb() }
        session.close()
        db.close()
        deviceDb.close()
    }

    suspend fun close() {
        closeSessions()
        libRoot.toFile().deleteRecursively()
        dbDir.deleteRecursively()
    }

    /** Reopen the same library and device database after an interrupted session. */
    suspend fun restart(
        syncTransport: SyncTransport,
        installationId: String,
    ): AppSessionRig {
        closeSessions()
        val base =
            testSessionDeps(scope, libRoot, File(dbDir, "first"), installationId, clock, syncTransport = syncTransport)
        return AppSessionRig(libRoot, dbDir, base, clock, scope, openSession(base, JournalOp.DocUndelete.WRITTEN))
    }

    companion object {
        suspend fun create(
            scope: CoroutineScope,
            ocr: OcrEngine = FakeOcrEngine.unavailable(),
            /** The library's storage-path rule, written to its journal once the session is open. */
            storagePath: StoragePathRule? = null,
            log: NpnLog = JvmLog(),
            /** How long this device kept trashed documents, if it ever stored a retention. */
            deviceTrashRetentionDays: Int? = null,
            legacyLibrarySettings: suspend () -> LegacyLibrarySettings = { LegacyLibrarySettings() },
            aiConfig: suspend () -> AiConfig = { AiConfig.DISABLED },
            /** Whether the session records a deleted document's return ([JournalCoordinator]'s gate). */
            writeUndelete: Boolean = JournalOp.DocUndelete.WRITTEN,
            /**
             * Null (the default) wires no cloud sync, so ordinary tests never spawn rclone; a sync
             * test passes its directory-backed transport and a [cloudSync] naming the remote, which
             * the library then keeps on this device as a user's choice would be.
             */
            syncTransport: SyncTransport? = null,
            cloudSync: CloudSyncConfig? = null,
            installationId: String = "test-installation",
            /** The PDF engine the session uses, over the real one. */
            pdfEngine: (PdfEngine) -> PdfEngine = { it },
            /** The library filesystem the session sees, over the real one the rig's [fs] reads directly. */
            files: (LibraryFileSystem) -> LibraryFileSystem = { it },
        ): AppSessionRig {
            val libRoot = Files.createTempDirectory("npn-app-lib")
            val dbDir = Files.createTempDirectory("npn-app-db").toFile()
            val clock = MutableClock(autoTick = true)
            val base =
                testSessionDeps(
                    scope = scope,
                    library = libRoot,
                    data = File(dbDir, "first"),
                    installationId = installationId,
                    clock = clock,
                    ocr = ocr,
                    log = log,
                    aiConfig = aiConfig,
                    legacyLibrarySettings = legacyLibrarySettings,
                    deviceTrashRetentionDays = deviceTrashRetentionDays,
                    syncTransport = syncTransport,
                )
            val session =
                openSession(base.copy(fs = files(base.fs), pdfEngine = pdfEngine(base.pdfEngine)), writeUndelete)
            cloudSync?.let { config -> session.sync.configure { config } }
            storagePath?.let {
                session.storagePath.setStoragePathTemplate(it.template)
                session.storagePath.setStoragePathEnabled(it.enabled)
            }
            return AppSessionRig(libRoot, dbDir, base, clock, scope, session)
        }
    }

    /**
     * Build a SECOND session over the SAME library folder but a fresh, separate DB cache and a
     * distinct deviceId (simulating another device syncing the same folder). Returns the session
     * plus its index db; both close with this rig.
     */
    suspend fun secondSession(
        scope: CoroutineScope,
        ocr: OcrEngine = FakeOcrEngine.unavailable(),
        clock: MutableClock = MutableClock(50_000L, autoTick = true),
        aiConfig: suspend () -> AiConfig = { AiConfig.DISABLED },
        legacyLibrarySettings: suspend () -> LegacyLibrarySettings = { LegacyLibrarySettings() },
        /** How long this device kept trashed documents, if it ever stored a retention. */
        deviceTrashRetentionDays: Int? = null,
        /** Whether the session records a deleted document's return ([JournalCoordinator]'s gate). */
        writeUndelete: Boolean = JournalOp.DocUndelete.WRITTEN,
    ): Pair<LibrarySessionImpl, NpnDatabase> {
        val deps =
            testSessionDeps(
                scope = scope,
                library = libRoot,
                data = File(dbDir, "second"),
                installationId = "second-installation",
                clock = clock,
                ocr = ocr,
                aiConfig = aiConfig,
                legacyLibrarySettings = legacyLibrarySettings,
                deviceTrashRetentionDays = deviceTrashRetentionDays,
            )
        // Force a distinct deviceId by seeding the kv before openSession.
        deps.deviceDb.keyValueDao().put(DeviceIdentity.KEY_DEVICE_ID, "device-two")
        otherDeps += deps
        return openSession(deps, writeUndelete).also { otherSessions += it } to deps.db
    }
}

/**
 * A [DocLifecycleImpl] over [coordinator] on this rig's index, which sees the library through
 * [files] and owns its calls on [lifetime]; a rename or move sets nothing off.
 */
internal fun IndexRig.docLifecycle(
    coordinator: JournalCoordinator,
    files: LibraryFileSystem = fs,
    lifetime: SessionLifetime = SessionLifetime(CoroutineScope(Dispatchers.Unconfined)),
    keepOn: Boolean = false,
): DocLifecycleImpl =
    DocLifecycleImpl(
        lifetime,
        coordinator,
        db,
        files,
        TrashOps(files),
        NpnLog.None,
        BakeLock(),
        clock,
        keep = KeepStore(files, "test-installation"),
        keepOn = { keepOn },
    )

/** The tags of [docId] among [items]. */
fun tagsOf(
    items: List<DocListItem>,
    docId: String,
): List<String> = items.first { it.doc.docId == docId }.tags

/** A journal store from a newer build makes the rescan refuse, which blocks metadata writes. */
internal suspend fun AppSessionRig.blockMetadataWrites() {
    Files.createDirectories(libRoot.resolve(NEWER_STORE))
    assertFailsWith<IllegalStateException> { session.rescan() }
}

/** Take away what [blockMetadataWrites] put there; the next rescan lets writes through again. */
internal fun AppSessionRig.unblockMetadataWrites() {
    Files.delete(libRoot.resolve(NEWER_STORE))
}

/** An object store from a newer build. */
private const val NEWER_STORE = "${NpnPaths.JOURNAL_DIR}/objects-v9-00"

/** Publish a storage-v2 object, as a newer build would, holding an op this build does not know (`future`). */
internal suspend fun LibraryFileSystem.writeNewerBuildsObject() {
    val actor = "00000000-0000-4000-8000-000000000001"
    val line = """{"v":1,"seq":1,"hlc":{"t":1,"c":0,"d":"$actor"},"dev":"$actor","doc":null,"op":"future","p":{}}"""
    val bytes = "$line\n".encodeToByteArray()
    writeBytesOnce(JournalPaths.objectPath(sha256Bytes(bytes)), bytes)
}

/**
 * A test over a fresh [AppSessionRig], built in the test's background scope with these
 * [AppSessionRig.create] options and closed before that scope ends.
 */
internal fun appSessionTest(
    ocr: OcrEngine = FakeOcrEngine.unavailable(),
    storagePath: StoragePathRule? = null,
    log: NpnLog = JvmLog(),
    deviceTrashRetentionDays: Int? = null,
    legacyLibrarySettings: suspend () -> LegacyLibrarySettings = { LegacyLibrarySettings() },
    aiConfig: suspend () -> AiConfig = { AiConfig.DISABLED },
    writeUndelete: Boolean = JournalOp.DocUndelete.WRITTEN,
    pdfEngine: (PdfEngine) -> PdfEngine = { it },
    files: (LibraryFileSystem) -> LibraryFileSystem = { it },
    block: suspend TestScope.(AppSessionRig) -> Unit,
): TestResult =
    runTest {
        AppSessionRig
            .create(
                backgroundScope,
                ocr,
                storagePath,
                log,
                deviceTrashRetentionDays,
                legacyLibrarySettings,
                aiConfig,
                writeUndelete,
                pdfEngine = pdfEngine,
                files = files,
            ).use { block(it) }
    }

/**
 * A library folder and the app-local [data] directory one device keeps for it, as [openSession]
 * takes them; [close] deletes both.
 */
class LibraryDirs : AutoCloseable {
    val library: Path = Files.createTempDirectory("npn-lib")
    val data: File = Files.createTempDirectory("npn-data").toFile()

    operator fun component1(): Path = library

    operator fun component2(): File = data

    override fun close() {
        library.toFile().deleteRecursively()
        data.deleteRecursively()
    }
}

/**
 * A session over [library] whose app-local state lives in [data], as one device opens it, and
 * again after the last one closed: the index and device databases stay in [data] between opens.
 */
internal suspend fun openSession(
    scope: CoroutineScope,
    library: Path,
    data: File,
    installationId: String = "test-installation",
    aiConfig: suspend () -> AiConfig = { AiConfig.DISABLED },
    /** The library filesystem the session sees, over the real one. */
    files: (LibraryFileSystem) -> LibraryFileSystem = { it },
): OpenSession {
    val base = testSessionDeps(scope, library, data, installationId, aiConfig = aiConfig)
    val deps = base.copy(fs = files(base.fs))
    return OpenSession(openSession(deps, JournalOp.DocUndelete.WRITTEN), deps)
}

/**
 * One device's [jvmSessionDeps] over [library], its app-local state in [data] (each session over the
 * folder gets its own), reading OCR in English, with [deviceTrashRetentionDays] as the trash
 * retention this device stored, if any.
 */
internal fun testSessionDeps(
    scope: CoroutineScope,
    library: Path,
    data: File,
    installationId: String,
    /** By default far behind the files' real mtimes, so a just-written PDF counts as settled. */
    clock: EpochClock = MutableClock(autoTick = true),
    ocr: OcrEngine = FakeOcrEngine.unavailable(),
    log: NpnLog = JvmLog(),
    aiConfig: suspend () -> AiConfig = { AiConfig.DISABLED },
    legacyLibrarySettings: suspend () -> LegacyLibrarySettings = { LegacyLibrarySettings() },
    deviceTrashRetentionDays: Int? = null,
    syncTransport: SyncTransport? = null,
): SessionDeps =
    jvmSessionDeps(
        libraryRoot = library,
        dirs = AppDirs(dbDir = data.path, thumbsDir = "$data/thumbs"),
        scope = scope,
        settings =
            SessionSettings(
                ocrLanguage = { "eng" },
                legacyLibrarySettings = {
                    legacyLibrarySettings().copy(trashRetentionDays = deviceTrashRetentionDays)
                },
                aiConfig = aiConfig,
            ),
        clock = clock,
        installationId = installationId,
        ocrEngine = ocr,
        log = log,
        syncTransport = syncTransport,
    )

/** An [openSession] result: [use] it, and it closes the session and its databases afterwards. */
internal class OpenSession(
    val session: LibrarySessionImpl,
    private val deps: SessionDeps,
) {
    suspend fun use(block: suspend (LibrarySessionImpl, SessionDeps) -> Unit) {
        try {
            block(session, deps)
        } finally {
            session.close()
            deps.closeDb()
        }
    }
}
