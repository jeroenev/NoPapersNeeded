package dev.npn.core.fs

/**
 * Layout constants and pure path helpers for the library folder (PLAN section 2, "Library
 * folder layout"). No I/O here; everything is string manipulation on library-relative,
 * '/'-separated paths.
 */
object NpnPaths {
    const val NPN_DIR = ".npn"
    const val JOURNAL_DIR = ".npn/journal"
    const val OCR_DIR = ".npn/ocr"
    const val TRASH_DIR = ".npn/trash"
    const val DEFAULT_INBOX = "inbox"

    /** `.npn/journal/<deviceId>` */
    fun journalDeviceDir(deviceId: String): String = "$JOURNAL_DIR/$deviceId"

    /** `.npn/journal/<deviceId>/<fileName>` */
    fun journalSegmentPath(deviceId: String, fileName: String): String =
        "${journalDeviceDir(deviceId)}/$fileName"

    /** `.npn/ocr/<sha256>.<deviceId>.json` */
    fun ocrSidecarPath(sha256: String, deviceId: String): String =
        "$OCR_DIR/$sha256.$deviceId.json"

    /**
     * `.npn/trash/<baseName>__<first 8 hash chars>.<ext>`
     *
     * The hash suffix keeps trash filenames collision-resistant across devices while staying
     * write-once. Files without an extension are handled (no trailing dot).
     */
    fun trashPath(originalName: String, sha256: String): String {
        val hashPrefix = sha256.take(8)
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

    /** True when any path segment begins with a dot (hidden file/dir), e.g. `.npn`, `a/.b/c`. */
    fun isHidden(relPath: String): Boolean =
        normalize(relPath).split('/').any { it.isNotEmpty() && it.startsWith('.') }

    /** True when [name] has a `.pdf` extension (case-insensitive). */
    fun isPdf(name: String): Boolean = name.substringAfterLast('.', "").equals("pdf", ignoreCase = true)

    /**
     * Detects sync-conflict files created by external sync tools. Families covered:
     * - Syncthing: `*.sync-conflict-YYYYMMDD-HHMMSS-XXXXXXX.*` (matched by the `.sync-conflict-` marker)
     * - Dropbox: `* (conflicted copy*` and `* (Case Conflict*`
     * - Nextcloud / ownCloud: `* (conflicted copy *)`
     *
     * These files must be excluded from the index; the UI offers keep/discard/import-as-new.
     */
    fun isSyncConflict(name: String): Boolean {
        if (name.contains(".sync-conflict-")) return true
        if (name.contains("(conflicted copy")) return true
        if (name.contains("(Case Conflict")) return true
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
    fun normalize(relPath: String): String =
        relPath.split('/').filter { it.isNotEmpty() }.joinToString("/")
}
