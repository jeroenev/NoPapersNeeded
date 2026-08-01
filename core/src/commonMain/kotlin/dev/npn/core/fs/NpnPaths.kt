package dev.npn.core.fs

/**
 * Layout constants and pure path helpers for the library folder (PLAN section 2, "Library
 * folder layout"). No I/O here; everything is string manipulation on library-relative,
 * '/'-separated paths.
 */
@Suppress("TooManyFunctions")
object NpnPaths {
    const val NPN_DIR = ".npn"
    const val JOURNAL_DIR = ".npn/journal"
    const val OCR_DIR = ".npn/ocr"
    const val TRASH_DIR = ".npn/trash"
    const val DEFAULT_INBOX = "inbox"

    const val STORAGE_V1 = 1
    const val STORAGE_V2 = 2

    /** Length of a SHA-256 digest rendered as lowercase hex (document/content identity). */
    const val SHA256_HEX_LENGTH = 64

    /** Number of leading hash characters used to disambiguate write-once trash filenames. */
    const val TRASH_HASH_PREFIX_LENGTH = 8

    /** Device-local source classification for compaction snapshots (not an on-disk store marker). */
    const val STORAGE_SNAPSHOT = 3

    private val objectBucketPattern = Regex("^objects-v([1-9][0-9]*)-([0-9a-fA-F]{2})$")

    // Snapshots use a DISTINCT bucket namespace so pre-snapshot readers classify them as Unknown
    // (ignored) rather than as an object they must fold or an unsupported store that blocks writes.
    // See METADATA_STORAGE_PLAN.md "Compaction and Retention".
    private val snapshotBucketPattern = Regex("^objects-snap-v([1-9][0-9]*)-([0-9a-fA-F]{2})$")

    /** A canonical lowercase-hex SHA-256 digest (document/content identity), anchored. */
    private val canonicalHashPattern = Regex("^[0-9a-f]{$SHA256_HEX_LENGTH}$")
    private val objectNamePattern = Regex("^([0-9a-f]{64})\\.jsonl$")
    private val objectStagingPattern = Regex("^\\.[0-9a-f]{64}\\.[^.]+\\.[^.]+\\.part$")
    private val legacySegmentPattern = Regex("^[0-9]+(?:-[0-9]+\\.snap)?\\.jsonl$")

    /** Snapshot object-store version. Independent axis from [STORAGE_V2]; currently the same value. */
    const val SNAPSHOT_V2 = 2

    sealed interface JournalPath {
        data class Legacy(
            val deviceId: String,
            val fileName: String,
        ) : JournalPath

        data class ObjectV2(
            val expectedHash: String,
            val canonical: Boolean,
        ) : JournalPath

        /** A compaction snapshot object (multi-actor events + a supersession manifest). */
        data class SnapshotV2(
            val expectedHash: String,
            val canonical: Boolean,
        ) : JournalPath

        data class UnsupportedObjectStore(
            val version: Int,
        ) : JournalPath

        data object Staging : JournalPath

        data object Unknown : JournalPath
    }

    /**
     * Sync-conflict filename markers, shared between detection ([isSyncConflict]) and stripping
     * (MaintenanceService.cleanConflictName) so the two never drift.
     */
    const val MARKER_SYNCTHING = ".sync-conflict-"
    const val MARKER_CONFLICTED_COPY = " (conflicted copy"
    const val MARKER_CASE_CONFLICT = " (Case Conflict"

    /** `.npn/journal/<deviceId>` */
    fun journalDeviceDir(deviceId: String): String = "$JOURNAL_DIR/$deviceId"

    /** `.npn/journal/<deviceId>/<fileName>` */
    fun journalSegmentPath(
        deviceId: String,
        fileName: String,
    ): String = "${journalDeviceDir(deviceId)}/$fileName"

    fun journalObjectPath(
        hash: String,
        snapshot: Boolean = false,
    ): String {
        require(canonicalHashPattern.matches(hash)) { "Invalid SHA-256: $hash" }
        val infix = if (snapshot) "objects-snap-v$SNAPSHOT_V2" else "objects-v$STORAGE_V2"
        return "$JOURNAL_DIR/$infix-${hash.take(2)}/$hash.jsonl"
    }

    fun journalStagingPath(
        hash: String,
        installationId: String,
        nonce: String,
        snapshot: Boolean = false,
    ): String =
        journalObjectPath(hash, snapshot).substringBeforeLast('/') +
            "/.$hash.$installationId.$nonce.part"

    /** Classify direct journal source files without letting object buckets masquerade as devices. */
    fun journalPath(relPath: String): JournalPath {
        val norm = normalize(relPath)
        val prefix = "$JOURNAL_DIR/"
        if (!norm.startsWith(prefix)) return JournalPath.Unknown
        val parts = norm.removePrefix(prefix).split('/')
        return if (parts.size == 2) classifyJournalFile(parts[0], parts[1]) else JournalPath.Unknown
    }

    private fun classifyJournalFile(
        dir: String,
        name: String,
    ): JournalPath {
        val snapshotBucket = snapshotBucketPattern.matchEntire(dir)
        val bucket = objectBucketPattern.matchEntire(dir)
        return when {
            snapshotBucket != null -> classifyBucketFile(snapshotBucket, name, SNAPSHOT_V2, snapshot = true)
            bucket != null -> classifyBucketFile(bucket, name, STORAGE_V2, snapshot = false)
            dir.isNotEmpty() && legacySegmentPattern.matches(name) -> JournalPath.Legacy(dir, name)
            else -> JournalPath.Unknown
        }
    }

    /**
     * Classify a file inside a matched object/snapshot bucket. [supported] is the store version
     * this reader understands ([STORAGE_V2] or [SNAPSHOT_V2]); [snapshot] selects the resulting
     * canonical path type. Object and snapshot buckets share identical structure, so a single
     * helper keeps the two in lockstep.
     */
    private fun classifyBucketFile(
        bucket: MatchResult,
        name: String,
        supported: Int,
        snapshot: Boolean,
    ): JournalPath {
        val version = bucket.groupValues[1].toIntOrNull() ?: Int.MAX_VALUE
        val expected = recoverObjectHash(name)
        val prefixMatches = expected?.startsWith(bucket.groupValues[2].lowercase()) == true
        return when {
            version != supported -> JournalPath.UnsupportedObjectStore(version)
            objectStagingPattern.matches(name) -> JournalPath.Staging
            expected == null || !prefixMatches -> JournalPath.Unknown
            snapshot -> JournalPath.SnapshotV2(expected, objectNamePattern.matches(name))
            else -> JournalPath.ObjectV2(expected, objectNamePattern.matches(name))
        }
    }

    /** Recover a canonical digest from a canonical or common sync-conflict object filename. */
    fun recoverObjectHash(name: String): String? {
        objectNamePattern.matchEntire(name)?.let { return it.groupValues[1] }
        if (!isSyncConflict(name)) return null
        val matches =
            Regex("(?:^|[^0-9a-f])([0-9a-f]{64})(?=[^0-9a-f]|$)")
                .findAll(name.lowercase())
                .map { it.groupValues[1] }
                .distinct()
                .toList()
        return matches.singleOrNull()
    }

    fun objectStoreVersion(relPath: String): Int? {
        val norm = normalize(relPath)
        if (!norm.startsWith("$JOURNAL_DIR/")) return null
        val dir = norm.removePrefix("$JOURNAL_DIR/").substringBefore('/')
        val version =
            objectBucketPattern.matchEntire(dir)?.groupValues?.get(1)
                ?: snapshotBucketPattern.matchEntire(dir)?.groupValues?.get(1)
                ?: return null
        return version.toIntOrNull() ?: Int.MAX_VALUE
    }

    /**
     * `.npn/ocr/<sha256>.<writerKey>.json`. The writer key is `<deviceId>` for legacy sidecars
     * (one per device) and `<deviceId>.<engine>` for engine-qualified sidecars (one per
     * device+engine, letting multiple OCR versions co-exist). Readers treat the whole middle
     * as an opaque key; see [OcrSidecarFiles.listForHash].
     */
    fun ocrSidecarPath(
        sha256: String,
        writerKey: String,
    ): String = "$OCR_DIR/$sha256.$writerKey.json"

    /** Writer key for an engine-qualified sidecar: `<deviceId>.<engine>`. */
    fun ocrWriterKey(
        deviceId: String,
        engine: String,
    ): String = "$deviceId.$engine"

    /**
     * `.npn/trash/<baseName>__<first 8 hash chars>.<ext>`
     *
     * The hash suffix keeps trash filenames collision-resistant across devices while staying
     * write-once. Files without an extension are handled (no trailing dot).
     */
    fun trashPath(
        originalName: String,
        sha256: String,
    ): String {
        val hashPrefix = sha256.take(TRASH_HASH_PREFIX_LENGTH)
        val dot = originalName.lastIndexOf('.')
        return if (dot > 0) {
            val base = originalName.substring(0, dot)
            val ext = originalName.substring(dot + 1)
            "$TRASH_DIR/${base}__$hashPrefix.$ext"
        } else {
            "$TRASH_DIR/${originalName}__$hashPrefix"
        }
    }

    /** True when [relPath] is inside the `.npn/` metadata directory (app-internal). */
    fun isInternal(relPath: String): Boolean {
        val norm = normalize(relPath)
        return norm == NPN_DIR || norm.startsWith("$NPN_DIR/")
    }

    /**
     * True when [relPath] is an internal file that [deviceId] wrote itself: its own journal
     * directory/segments or its own OCR sidecars. The desktop watcher uses this to drop
     * self-echo events — the writing code paths already refold/index synchronously, so reacting
     * to our own `.npn/` writes would turn every metadata edit into a redundant full rescan.
     * Other devices' journal segments and sidecars (arriving via file sync) are NOT self-writes
     * and must keep triggering a refresh.
     */
    fun isOwnDeviceWrite(
        relPath: String,
        deviceId: String,
    ): Boolean {
        val norm = normalize(relPath)
        val ownJournal = journalDeviceDir(deviceId)
        if (norm == ownJournal || norm.startsWith("$ownJournal/")) return true
        if (!norm.startsWith("$OCR_DIR/")) return false
        // Own sidecars: legacy `<hash>.<deviceId>.json` or engine-qualified
        // `<hash>.<deviceId>.<engine>.json`.
        if (norm.endsWith(".$deviceId.json")) return true
        val stem = norm.substringAfterLast('/').removeSuffix(".json")
        val writerKey = stem.substringAfter('.', "")
        return writerKey.startsWith("$deviceId.")
    }

    /** True when any path segment begins with a dot (hidden file/dir), e.g. `.npn`, `a/.b/c`. */
    fun isHidden(relPath: String): Boolean = normalize(relPath).split('/').any { it.isNotEmpty() && it.startsWith('.') }

    /** True when [name] has a `.pdf` extension (case-insensitive). */
    fun isPdf(name: String): Boolean = name.substringAfterLast('.', "").equals("pdf", ignoreCase = true)

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
     * and dots are trimmed to avoid hidden files and Windows-reserved names.
     */
    fun sanitizeSegment(input: String): String {
        val cleaned = StringBuilder(input.length)
        for (char in input) {
            when {
                char.code < 0x20 || char in invalidSegmentChars -> cleaned.append('_')
                else -> cleaned.append(char)
            }
        }
        return cleaned.toString().trim { it.isWhitespace() || it == '.' }
    }

    /**
     * Sanitize a full file name, preserving its extension. The part after the LAST dot is treated
     * as an extension only when a non-empty base precedes it and a non-empty extension follows it;
     * otherwise the whole string is the base (so trailing-dot names like `x.` cannot come back).
     * The base is sanitized via [sanitizeSegment]; Windows-reserved device names (CON, PRN, AUX,
     * NUL, COM1-9, LPT1-9) get a '_' prefix. Identity for already-valid names.
     */
    fun sanitizeFileName(name: String): String {
        val dot = name.lastIndexOf('.')
        val hasExtension = dot > 0 && dot < name.lastIndex
        val base = if (hasExtension) name.substring(0, dot) else name
        val ext = if (hasExtension) name.substring(dot) else ""
        val cleanBase = sanitizeSegment(base)
        val safeBase = if (cleanBase.uppercase() in reservedWindowsDeviceNames) "_$cleanBase" else cleanBase
        return if (ext.isEmpty()) safeBase else "$safeBase$ext"
    }

    /**
     * Detects sync-conflict files created by external sync tools. Families covered:
     * - Syncthing: `*.sync-conflict-YYYYMMDD-HHMMSS-XXXXXXX.*` (matched by the `.sync-conflict-` marker)
     * - Dropbox: `* (conflicted copy*` and `* (Case Conflict*`
     * - Nextcloud / ownCloud: `* (conflicted copy *)`
     *
     * These files must be excluded from the index; the UI offers keep/discard/import-as-new.
     */
    fun isSyncConflict(name: String): Boolean {
        if (name.contains(MARKER_SYNCTHING)) return true
        // Match leniently (without the leading space) so variants are still detected.
        if (name.contains(MARKER_CONFLICTED_COPY.trimStart())) return true
        if (name.contains(MARKER_CASE_CONFLICT.trimStart())) return true
        return false
    }

    /**
     * Detects sync-tool *temporary* files that should be ignored entirely (never indexed,
     * never surfaced): syncthing's `~syncthing~*` temp files and `.syncthing.*` hidden temps.
     */
    fun isSyncTemp(name: String): Boolean {
        if (name.startsWith("~syncthing~")) return true
        if (name.startsWith(".syncthing.")) return true
        return false
    }

    /**
     * True when a file should be indexed as a document: a PDF that is not app-internal, not
     * hidden, not a sync-conflict file, and not a sync temp file.
     */
    fun indexable(meta: FileMeta): Boolean {
        if (meta.isDirectory) return false
        if (!isPdf(meta.name)) return false
        if (isInternal(meta.relPath)) return false
        if (isHidden(meta.relPath)) return false
        if (isSyncConflict(meta.name)) return false
        if (isSyncTemp(meta.name)) return false
        return true
    }

    /** Normalize a library-relative path: strip leading/trailing slashes, collapse repeats. */
    fun normalize(relPath: String): String = relPath.split('/').filter { it.isNotEmpty() }.joinToString("/")
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
    val slash = path.lastIndexOf('/')
    val dot = path.lastIndexOf('.')
    val (base, ext) =
        if (dot > slash + 1) {
            path.substring(0, dot) to path.substring(dot)
        } else {
            path to ""
        }
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
