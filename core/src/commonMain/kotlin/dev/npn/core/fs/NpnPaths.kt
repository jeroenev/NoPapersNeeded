package dev.npn.core.fs

import kotlin.uuid.Uuid

/**
 * Layout constants and pure path helpers for the library folder. No I/O here; everything is
 * string manipulation on library-relative, '/'-separated paths.
 */
object NpnPaths {
    const val NPN_DIR = ".npn"
    const val JOURNAL_DIR = ".npn/journal"
    const val OCR_DIR = ".npn/ocr"
    const val TRASH_DIR = ".npn/trash"

    /**
     * `<deviceId>/<installationId>.json`: one write-once marker per installation that has used a
     * device id in this library, which is how a cloned installation is found out
     * (see `DeviceIdentity`).
     */
    const val DEVICES_DIR = ".npn/devices"

    /**
     * `<deviceId>/<n>.json`: each device's write-once acknowledgment records, a chain numbered from
     * 1 (`dev.npn.core.index.AckRecords`). Not under [DEVICES_DIR], whose `*.json` files are
     * installation markers.
     */
    const val ACKS_DIR = ".npn/acks"

    /**
     * Import staging: a file is copied here, hashed, dedup-checked, then moved into place or
     * deleted. Under `.npn/` so it is never indexed; `.part` so an interrupted import is
     * recognizable as incomplete rather than mistaken for a document.
     */
    const val TMP_DIR = ".npn/tmp"
    const val DEFAULT_INBOX = "inbox"

    /** Length of a SHA-256 digest rendered as lowercase hex (document/content identity). */
    const val SHA256_HEX_LENGTH = 64

    /** Number of leading hash characters used to disambiguate write-once trash filenames. */
    const val TRASH_HASH_PREFIX_LENGTH = 8

    private val trashNamePattern = Regex("__([0-9a-f]{$TRASH_HASH_PREFIX_LENGTH})(-[0-9]+)?(\\.[^.]*)?$")

    /** Sync-conflict filename markers that [isSyncConflict] detects. */
    private const val MARKER_SYNCTHING = ".sync-conflict-"
    private const val MARKER_CONFLICTED_COPY = " (conflicted copy"
    private const val MARKER_CASE_CONFLICT = " (Case Conflict"

    /** rclone bisync's conflict copy: its suffix and a number after the whole name, `doc.pdf.conflict1`. */
    private val bisyncConflictPattern = Regex("^.+\\.conflict[0-9]+$")

    /**
     * `.npn/trash/<baseName>__<first 8 hash chars>-<episode>.<ext>`
     *
     * The hash suffix keeps trash filenames collision-resistant across devices while staying
     * write-once, and [episodeMillis] — when this trash happened — keeps them episode-unique: a
     * restore and a trash again give the document a new name, so the purge that reads an old trash
     * time from a lagging journal ([dev.npn.core.fs.TrashOps.purgeOlderThan]) can see that the entry
     * before it is not the old episode's. Two entries in the same millisecond take the next free
     * episode ([dev.npn.core.fs.TrashOps.trashTarget]). Files without an extension are handled (no
     * trailing dot).
     */
    fun trashPath(
        originalName: String,
        sha256: String,
        episodeMillis: Long,
    ): String {
        val hashPrefix = sha256.take(TRASH_HASH_PREFIX_LENGTH)
        val dot = originalName.lastIndexOf('.')
        return if (dot > 0) {
            val base = originalName.substring(0, dot)
            val ext = originalName.substring(dot + 1)
            "$TRASH_DIR/${base}__$hashPrefix-$episodeMillis.$ext"
        } else {
            "$TRASH_DIR/${originalName}__$hashPrefix-$episodeMillis"
        }
    }

    /**
     * The document hash prefix in a [trashPath] file name, past any episode suffix, or null when
     * [fileName] is not one of those names.
     */
    fun trashHashPrefix(fileName: String): String? = trashNamePattern.find(fileName)?.groupValues?.get(1)

    /**
     * When the trash a [trashPath] file name stands for happened, or null for a name without one: an
     * older build's `-N` collision suffix reads here as an episode of 1, 2, ... — as old as any
     * recorded trash time — and a name without a suffix is that older build's plain one.
     */
    fun trashEpisodeMillis(fileName: String): Long? =
        trashNamePattern
            .find(fileName)
            ?.groupValues
            ?.get(2)
            ?.drop(1)
            ?.toLongOrNull()

    /**
     * The app's own temporary files in the library. Each kind is named here alone, so a rescan can
     * tell one a crash left behind from a user's file ([kindOf]) and sweep it once it is old, whoever
     * wrote it: a sync tool carries one to every device, where only another device's sweep may
     * ever see it again.
     */
    object Temp {
        /**
         * Prefix and suffix of a direct-filesystem write's staging file, `.npn-tmp-<random>.part`,
         * created beside the file it becomes.
         */
        const val WRITE_PREFIX = ".npn-tmp-"
        const val PART = ".part"
        private const val RELOCATION_INFIX = ".npn-"
        private const val RELOCATION_SUFFIX = ".tmp"
        private const val BAKE_SUFFIX = ".npnbake.tmp"
        private val journalStagingPattern = Regex("^\\.[0-9a-f]{64}\\.[^.]+\\.[^.]+\\.part$")
        private val relocationPattern = Regex("\\.npn-[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\\.tmp$")

        /** Names a document file claimed mid-move ([recoveryName]); never mistaken for a move the user made. */
        const val RECOVERY_PREFIX = "npn-recovery-"

        /** The kinds [kindOf] tells apart. */
        enum class Kind { WRITE, RELOCATION, BAKE, IMPORT, MARKER_STAGING, JOURNAL_STAGING }

        /** A relocation's verified copy across volumes, beside its [target]: `<target>.npn-<uuid>.tmp`. */
        fun relocationPath(
            target: String,
            nonce: String,
        ): String = "$target$RELOCATION_INFIX$nonce$RELOCATION_SUFFIX"

        /** Where a bake leaves the baked bytes of [document] before they replace it: `<document>.npnbake.tmp`. */
        fun bakePath(document: String): String = "$document$BAKE_SUFFIX"

        /** The document a [bakePath] belongs to, or null for any other path. */
        fun bakedDocument(path: String): String? = path.removeSuffix(BAKE_SUFFIX).takeIf { it != path }

        /** One import attempt's staging file, `.npn/tmp/<nonce>.part`; [nonce] keeps concurrent imports apart. */
        fun importPath(nonce: String): String = "$TMP_DIR/$nonce$PART"

        /** A device marker's or acknowledgment record's staging file beside it: `.<installationId>.<nonce>.part`. */
        fun markerStagingName(
            installationId: String,
            nonce: String,
        ): String = ".$installationId.$nonce$PART"

        /**
         * A journal object's or snapshot's staging file in its bucket,
         * `.<hash>.<installationId>.<nonce>.part` ([dev.npn.core.journal.JournalPaths.stagingPath]).
         */
        fun journalStagingName(
            hash: String,
            installationId: String,
            nonce: String,
        ): String = ".$hash.$installationId.$nonce$PART"

        /** Whether [name] is a [journalStagingName]. */
        fun isJournalStagingName(name: String): Boolean = journalStagingPattern.matches(name)

        /**
         * The name a document file is claimed under while it moves, `npn-recovery-<nonce>.pdf`. Not
         * a temporary file ([kindOf] is null): it is an ordinary indexable PDF, because a move
         * interrupted mid-way leaves the only copy of the document there.
         */
        fun recoveryName(nonce: String): String = "$RECOVERY_PREFIX$nonce.pdf"

        /** Whether [relPath] is named as a [recoveryName] is. */
        fun isRecovery(relPath: String): Boolean = fileName(relPath).startsWith(RECOVERY_PREFIX)

        /** A fresh [recoveryName] in the folder of [beside]. */
        fun recoveryPath(
            beside: String,
            nonce: String = Uuid.random().toString(),
        ): String {
            val directory = parentDir(beside)
            val name = recoveryName(nonce)
            return if (directory.isEmpty()) name else "$directory/$name"
        }

        /** Which kind of temporary file [relPath] names, or null for a file that is none of them. */
        fun kindOf(relPath: String): Kind? {
            val name = fileName(relPath)
            return when {
                name.startsWith(WRITE_PREFIX) && name.endsWith(PART) -> {
                    Kind.WRITE
                }

                name.endsWith(BAKE_SUFFIX) -> {
                    Kind.BAKE
                }

                relocationPattern.containsMatchIn(name) -> {
                    Kind.RELOCATION
                }

                relPath.startsWith("$TMP_DIR/") && name.endsWith(PART) -> {
                    Kind.IMPORT
                }

                (relPath.startsWith("$DEVICES_DIR/") || relPath.startsWith("$ACKS_DIR/")) &&
                    name.startsWith('.') &&
                    name.endsWith(PART) -> {
                    Kind.MARKER_STAGING
                }

                relPath.startsWith("$JOURNAL_DIR/") && isJournalStagingName(name) -> {
                    Kind.JOURNAL_STAGING
                }

                else -> {
                    null
                }
            }
        }
    }

    /** True when [relPath] is inside the `.npn/` metadata directory (app-internal). */
    fun isInternal(relPath: String): Boolean {
        val norm = joinSegments(relPath)
        return norm == NPN_DIR || norm.startsWith("$NPN_DIR/")
    }

    /** True when any path segment begins with a dot (hidden file/dir), e.g. `.npn`, `a/.b/c`. */
    fun isHidden(relPath: String): Boolean = segments(relPath).any { it.startsWith('.') }

    /** True when [name] has a `.pdf` extension (case-insensitive). */
    fun isPdf(name: String): Boolean = name.substringAfterLast('.', "").equals("pdf", ignoreCase = true)

    /** The folder [relPath] is in, `""` at the library root. */
    fun parentDir(relPath: String): String = relPath.substringBeforeLast('/', "")

    /** The last segment of [relPath]: a file's own name. */
    fun fileName(relPath: String): String = relPath.substringAfterLast('/')

    /** [name] without its `.pdf` extension, in any case; a name that is nothing but the extension stays. */
    fun stripPdfExtension(name: String): String =
        if (name.length > PDF_EXTENSION.length && name.endsWith(PDF_EXTENSION, ignoreCase = true)) {
            name.dropLast(PDF_EXTENSION.length)
        } else {
            name
        }

    private const val PDF_EXTENSION = ".pdf"

    /** Reserved filesystem characters within a path segment: Windows-invalid plus '/' and '\\'. */
    private val invalidSegmentChars = setOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')

    /** Windows-reserved device names; any exact-case base matching one is prefixed with '_'. */
    private val reservedWindowsDeviceNames =
        setOf("CON", "PRN", "AUX", "NUL") +
            (1..9).map { "COM$it" } +
            (1..9).map { "LPT$it" }

    /**
     * Sanitize a single path SEGMENT. Directory separators and Windows-reserved characters
     * (`<>:"/\|?*`) are replaced with '_'; control characters too. Leading/trailing whitespace
     * and dots are trimmed to avoid hidden files and Windows-reserved names. Windows-reserved
     * device names (CON, PRN, AUX, NUL, COM1-9, LPT1-9) get a '_' prefix, so every name the app
     * builds — imports, renames, trash entries, storage paths — spells them the way Windows can
     * create them.
     */
    fun sanitizeSegment(input: String): String {
        val cleaned = StringBuilder(input.length)
        for (char in input) {
            when {
                char.code < 0x20 || char in invalidSegmentChars -> cleaned.append('_')
                else -> cleaned.append(char)
            }
        }
        val trimmed = cleaned.toString().trim { it.isWhitespace() || it == '.' }
        return if (trimmed.uppercase() in reservedWindowsDeviceNames) "_$trimmed" else trimmed
    }

    /**
     * Sanitize a full file name, preserving its extension. The part after the LAST dot is treated
     * as an extension only when a non-empty base precedes it and a non-empty extension follows it;
     * otherwise the whole string is the base (so trailing-dot names like `x.` cannot come back).
     * The base is sanitized via [sanitizeSegment]. Identity for already-valid names.
     */
    fun sanitizeFileName(name: String): String {
        val dot = name.lastIndexOf('.')
        val hasExtension = dot > 0 && dot < name.lastIndex
        val base = if (hasExtension) name.substring(0, dot) else name
        val ext = if (hasExtension) name.substring(dot) else ""
        val safeBase = sanitizeSegment(base)
        return if (ext.isEmpty()) safeBase else "$safeBase$ext"
    }

    /**
     * Detects sync-conflict files created by external sync tools. Families covered:
     * - Syncthing: `*.sync-conflict-YYYYMMDD-HHMMSS-XXXXXXX.*` (matched by the `.sync-conflict-` marker)
     * - Dropbox: `* (conflicted copy*` and `* (Case Conflict*`
     * - Nextcloud / ownCloud: `* (conflicted copy *)`
     *
     * These files are excluded from the index; a rescan reports them (`ScanReport.conflictFiles`),
     * and the library shows how many there are.
     */
    fun isSyncConflict(name: String): Boolean {
        if (name.contains(MARKER_SYNCTHING)) return true
        // Match leniently (without the leading space) so variants are still detected.
        if (name.contains(MARKER_CONFLICTED_COPY.trimStart())) return true
        if (name.contains(MARKER_CASE_CONFLICT.trimStart())) return true
        return bisyncConflictPattern.matches(name)
    }

    /**
     * Detects sync-tool *temporary* files that should be ignored entirely (never indexed, never
     * surfaced, never deleted: the tool renames one into place, or drops it, itself). Each is written
     * beside the file it transfers:
     * - Syncthing: `~syncthing~<name>.tmp` and `.syncthing.<name>.tmp`
     * - Nextcloud / ownCloud desktop clients: `.<name>.~<hex>`
     * - Resilio Sync: `<name>.!sync`
     * - rclone (cloud sync, without `--inplace`): `<name>.<8 hex digits>.partial`
     */
    fun isSyncTemp(name: String): Boolean {
        if (name.startsWith("~syncthing~")) return true
        if (name.startsWith(".syncthing.")) return true
        if (nextcloudTempPattern.matches(name)) return true
        if (rclonePartialPattern.matches(name)) return true
        return name.endsWith(RESILIO_TEMP_SUFFIX)
    }

    private val rclonePartialPattern = Regex("^.+\\.[0-9a-f]{8}\\.partial$")

    private val nextcloudTempPattern = Regex("^\\..+\\.~[0-9a-fA-F]+$")
    private const val RESILIO_TEMP_SUFFIX = ".!sync"

    /**
     * True when a file should be indexed as a document: a PDF that is not app-internal, not
     * hidden, not a sync-conflict file, not a sync temp file, and at a path this app can address
     * ([isSafe]) — a name no replica could hold is left alone rather than half-managed.
     */
    fun indexable(meta: FileMeta): Boolean =
        !meta.isDirectory &&
            isPdf(meta.name) &&
            isSafe(meta.relPath) &&
            !isInternal(meta.relPath) &&
            !isHidden(meta.relPath) &&
            !isSyncConflict(meta.name) &&
            !isSyncTemp(meta.name)

    /** True when [path], already in its [normalize]d form, is one a document file may have ([indexable]). */
    fun isDocumentPath(path: String): Boolean =
        isSafe(path) && normalize(path) == path && indexable(FileMeta(path, fileName(path), 0, 0, false))

    /**
     * Normalize a library-relative path: strip leading/trailing slashes, collapse repeats.
     *
     * This is also the one gate every path passes before it can reach disk — both filesystem
     * implementations resolve through it — so it refuses anything that could address a location
     * outside the library or mean something different on another replica's platform
     * (see [isSafe]). Paths the app generates itself never trip it; the check exists for paths
     * that arrive from outside, which today is user input and tomorrow is a sync peer.
     *
     * @throws IllegalArgumentException when [relPath] is not [isSafe].
     */
    fun normalize(relPath: String): String {
        val reason = unsafeReason(relPath)
        require(reason == null) { "${reason?.message}: '$relPath'" }
        return joinSegments(relPath)
    }

    /**
     * Whether [relPath] can be handled as a library-relative path at all: no `.`/`..` segments,
     * no NUL, no backslash (a separator on Windows, so `a\..\b` would traverse there), and no
     * drive prefix on the first segment (`C:` makes java.nio treat the rest as absolute on
     * Windows). Leading, trailing and repeated `/` are tolerated — they cannot escape.
     *
     * Whatever reads a path seen on disk (a listing, a watcher event) uses this to *ignore* such a
     * path ([indexable], `JournalPaths.classify`, the session's `watchAction`) or reads it without
     * the gate ([isInternal], [isHidden], [dev.npn.core.ocr.OcrSidecarName.isOwnWrite]); only the
     * I/O side ([normalize]) refuses it. The split keeps a hostile or merely exotic name on disk
     * from failing a rescan, a journal reconcile or the watch.
     */
    fun isSafe(relPath: String): Boolean = unsafeReason(relPath) == null

    /** Why [relPath] is not [isSafe], or null when it is. */
    fun unsafeReason(relPath: String): UnsafePath? {
        val segments = segments(relPath)
        return when {
            segments.firstOrNull()?.let(drivePrefix::matches) == true -> UnsafePath.DRIVE_PREFIX
            segments.any { '\\' in it } -> UnsafePath.BACKSLASH
            segments.any { it == "." || it == ".." } -> UnsafePath.TRAVERSAL
            segments.any { '\u0000' in it } -> UnsafePath.NUL
            else -> null
        }
    }

    /** What makes a path [unsafeReason] refuses unusable as a library-relative path. */
    enum class UnsafePath(
        val message: String,
    ) {
        DRIVE_PREFIX("Drive prefixes are not library-relative"),
        BACKSLASH("Backslash is not a path separator here"),
        TRAVERSAL("Path traversal is not allowed"),
        NUL("NUL is not allowed in a path"),
    }

    private fun segments(relPath: String): List<String> = relPath.split('/').filter { it.isNotEmpty() }

    /** [normalize] without the safety gate, for classifying paths observed on disk. */
    internal fun joinSegments(relPath: String): String = segments(relPath).joinToString("/")

    /** A drive letter and colon (`C:`, `c:relative.pdf`), absolute or drive-relative on Windows. */
    private val drivePrefix = Regex("^[A-Za-z]:.*")
}

/** How many hex digits of a file's SHA-256 a name the app invents for it carries ([withNameToken]). */
const val NAME_TOKEN_LENGTH = 4

/**
 * [path] with ` (label, 7f3a)` (or ` (7f3a)` without a [label]) before its extension: the first
 * [NAME_TOKEN_LENGTH] hex digits of [hash], the SHA-256 of the bytes it will hold. A name the app
 * invents in a shared folder carries it, so two devices that place different bytes before they sync
 * never pick one name (which a sync tool would resolve into a conflict copy no rescan indexes), and
 * two that place the same bytes agree on it.
 */
fun withNameToken(
    path: String,
    hash: String,
    label: String? = null,
): String {
    val (base, ext) = splitExtension(path)
    val token = hash.take(NAME_TOKEN_LENGTH)
    return "$base (${label?.let { "$it, " }.orEmpty()}$token)$ext"
}

/**
 * [path] when free; else beside it, named for the bytes it will hold ([withNameToken]); else, as two
 * documents' tokens can coincide, that name with " (2)", " (3)", ...: the first not taken by [exists].
 */
suspend fun uniquifyByHash(
    path: String,
    hash: String,
    label: String? = null,
    exists: suspend (String) -> Boolean,
): String =
    if (!exists(path)) {
        path
    } else {
        uniquifyPath(withNameToken(path, hash, label), startIndex = 2, suffix = { " ($it)" }, exists = exists)
    }

/** [path] as its base and its extension (with the dot; empty for none), keeping dotfiles and directory dots. */
private fun splitExtension(path: String): Pair<String, String> {
    val slash = path.lastIndexOf('/')
    val dot = path.lastIndexOf('.')
    return if (dot > slash + 1) path.substring(0, dot) to path.substring(dot) else path to ""
}

/** Return [path] or a suffixed free candidate, preserving dotfiles and directory-name dots. */
suspend fun uniquifyPath(
    path: String,
    startIndex: Int,
    firstSuffix: String? = null,
    suffix: (Int) -> String,
    exists: suspend (String) -> Boolean,
): String {
    if (!exists(path)) return path
    val (base, ext) = splitExtension(path)
    if (firstSuffix != null) {
        val first = "$base$firstSuffix$ext"
        if (!exists(first)) return first
    }
    var index = startIndex
    while (true) {
        val candidate = "$base${suffix(index)}$ext"
        if (!exists(candidate)) return candidate
        index++
    }
}
