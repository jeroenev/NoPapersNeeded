package dev.npn.core.app

import dev.npn.core.ai.AiConfig
import dev.npn.core.db.DeviceDatabase
import dev.npn.core.db.NpnDatabase
import dev.npn.core.fs.FileMeta
import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.fs.NpnPaths
import dev.npn.core.index.DeviceIdentity
import dev.npn.core.index.JournalCoordinator
import dev.npn.core.index.MutableClock
import dev.npn.core.journal.JournalOp
import dev.npn.core.journal.JournalPath
import dev.npn.core.journal.JournalPaths
import dev.npn.core.ocr.OcrEngine
import dev.npn.core.pathrules.StoragePathRule
import dev.npn.core.pdf.PdfEngine
import dev.npn.core.pdf.ThumbnailStore
import dev.npn.core.settings.AppDirs
import dev.npn.core.settings.LegacyLibrarySettings
import dev.npn.core.settings.SessionSettings
import dev.npn.core.util.JvmLog
import dev.npn.core.util.NpnLog
import kotlinx.coroutines.CoroutineScope
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/** No-op OCR engine (unavailable) so the pipeline uses embedded text only. */
class NoopOcrEngine : OcrEngine {
    override suspend fun available(): Boolean = false

    override val name: String = "noop"

    override suspend fun recognizePng(
        png: ByteArray,
        lang: String,
    ): String = ""
}

class FakeThumbStore(
    private val dir: File,
) : ThumbnailStore {
    override suspend fun write(
        docId: String,
        png: ByteArray,
    ): String {
        dir.mkdirs()
        val f = File(dir, "$docId.png")
        f.writeBytes(png)
        return f.absolutePath
    }

    override suspend fun delete(docId: String) {
        File(dir, "$docId.png").delete()
    }
}

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
class AppSessionRig private constructor(
    val libRoot: Path,
    val dbDir: File,
    val fs: LibraryFileSystem,
    val db: NpnDatabase,
    /** This device's own state: queues, edit runs, device id, chats. */
    val deviceDb: DeviceDatabase,
    val clock: MutableClock,
    val scope: CoroutineScope,
    val session: LibrarySessionImpl,
) {
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

    /** Published journal objects, for asserting that a write committed exactly once. */
    suspend fun journalObjectCount(): Int =
        fs
            .listAllUnder(NpnPaths.JOURNAL_DIR)
            .count { JournalPaths.classify(it.relPath) is JournalPath.ObjectV2 }

    /** Device databases of the [secondSession]s, closed with this rig. */
    private val otherDeviceDbs = ArrayList<DeviceDatabase>()

    suspend fun close() {
        session.close()
        db.close()
        deviceDb.close()
        otherDeviceDbs.forEach { it.close() }
        libRoot.toFile().deleteRecursively()
        dbDir.deleteRecursively()
    }

    companion object {
        suspend fun create(
            scope: CoroutineScope,
            ocr: OcrEngine = NoopOcrEngine(),
            /** The library's storage-path rule, written to its journal once the session is open. */
            storagePath: StoragePathRule? = null,
            log: NpnLog = JvmLog(),
            /** How long this device kept trashed documents, if it ever stored a retention. */
            deviceTrashRetentionDays: Int? = null,
            legacyLibrarySettings: suspend () -> LegacyLibrarySettings = { LegacyLibrarySettings() },
            aiConfig: suspend () -> AiConfig = { AiConfig.DISABLED },
            /** Whether the session records a deleted document's return ([JournalCoordinator]'s gate). */
            writeUndelete: Boolean = JournalOp.DocUndelete.WRITTEN,
            /** The PDF engine the session uses, over the real one. */
            pdfEngine: (PdfEngine) -> PdfEngine = { it },
            /** The library filesystem the session sees, over the real one the rig's [fs] reads directly. */
            files: (LibraryFileSystem) -> LibraryFileSystem = { it },
        ): AppSessionRig {
            val libRoot = Files.createTempDirectory("npn-app-lib")
            val dbDir = Files.createTempDirectory("npn-app-db").toFile()
            val clock = MutableClock(autoTick = true)
            val base =
                jvmSessionDeps(
                    libraryRoot = libRoot,
                    dirs = appDirs(dbDir, "first"),
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
                    installationId = "test-installation",
                    ocrEngine = ocr,
                    thumbnailStore = FakeThumbStore(File(dbDir, "thumbs")),
                    log = log,
                )
            val session =
                LibrarySessionImpl.openSession(base.over(files(base.fs), pdfEngine(base.pdfEngine)), writeUndelete)
            storagePath?.let {
                session.storagePath.setStoragePathTemplate(it.template)
                session.storagePath.setStoragePathEnabled(it.enabled)
            }
            return AppSessionRig(libRoot, dbDir, base.fs, base.db, base.deviceDb, clock, scope, session)
        }

        /** One device's app-local dirs; each session over the folder gets its own. */
        private fun appDirs(
            dbDir: File,
            device: String,
        ): AppDirs {
            val base = File(dbDir, device).path
            return AppDirs(dbDir = base, thumbsDir = "$base/thumbs")
        }
    }

    /**
     * Build a SECOND session over the SAME library folder but a fresh, separate DB cache and a
     * distinct deviceId (simulating another device syncing the same folder). Returns the session
     * plus its index db so the caller can close it; its device db closes with this rig.
     */
    suspend fun secondSession(
        scope: CoroutineScope,
        ocr: OcrEngine = NoopOcrEngine(),
        clock: MutableClock = MutableClock(50_000L, autoTick = true),
        aiConfig: suspend () -> AiConfig = { AiConfig.DISABLED },
        legacyLibrarySettings: suspend () -> LegacyLibrarySettings = { LegacyLibrarySettings() },
        /** How long this device kept trashed documents, if it ever stored a retention. */
        deviceTrashRetentionDays: Int? = null,
        /** Whether the session records a deleted document's return ([JournalCoordinator]'s gate). */
        writeUndelete: Boolean = JournalOp.DocUndelete.WRITTEN,
    ): Pair<LibrarySessionImpl, NpnDatabase> {
        val deps =
            jvmSessionDeps(
                libraryRoot = libRoot,
                dirs = appDirs(dbDir, "second"),
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
                ocrEngine = ocr,
                thumbnailStore = FakeThumbStore(File(dbDir, "thumbs2")),
            )
        // Force a distinct deviceId by seeding the kv before openSession.
        deps.deviceDb.keyValueDao().put(DeviceIdentity.KEY_DEVICE_ID, "device-two")
        otherDeviceDbs += deps.deviceDb
        return LibrarySessionImpl.openSession(deps, writeUndelete) to deps.db
    }
}

/**
 * A session over [library] whose app-local state lives in [data], as one device opens it, and
 * again after the last one closed: the index and device databases stay in [data] between opens.
 */
suspend fun openSession(
    scope: CoroutineScope,
    library: Path,
    data: File,
    installationId: String = "test-installation",
    aiConfig: suspend () -> AiConfig = { AiConfig.DISABLED },
    /** The library filesystem the session sees, over the real one. */
    files: (LibraryFileSystem) -> LibraryFileSystem = { it },
): OpenSession {
    val base =
        jvmSessionDeps(
            libraryRoot = library,
            dirs = AppDirs(dbDir = data.path, thumbsDir = "$data/thumbs"),
            scope = scope,
            settings = SessionSettings(ocrLanguage = { "eng" }, aiConfig = aiConfig),
            // Far behind the files' real mtimes, so a just-written PDF counts as settled.
            clock = MutableClock(autoTick = true),
            installationId = installationId,
            ocrEngine = NoopOcrEngine(),
            thumbnailStore = FakeThumbStore(File(data, "thumbs")),
        )
    val deps = base.over(files(base.fs), base.pdfEngine)
    return OpenSession(LibrarySessionImpl.openSession(deps), deps)
}

/** These deps with the library filesystem [fs] and the PDF engine [pdf] in place of their own. */
private fun SessionDeps.over(
    fs: LibraryFileSystem,
    pdf: PdfEngine,
) = SessionDeps(
    fs = fs,
    db = db,
    deviceDb = deviceDb,
    pdfEngine = pdf,
    ocrEngine = ocrEngine,
    xmpIO = xmpIO,
    thumbnailStore = thumbnailStore,
    renderParallelism = renderParallelism,
    scope = scope,
    settings = settings,
    log = log,
    clock = clock,
    installationId = installationId,
)

/** An [openSession] result: [use] it, and it closes the session and its databases afterwards. */
class OpenSession(
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
