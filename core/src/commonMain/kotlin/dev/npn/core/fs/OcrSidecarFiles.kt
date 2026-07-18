package dev.npn.core.fs

/**
 * File-level adapter for OCR sidecars (PLAN "OCR sidecars"). Sidecars live at
 * `.npn/ocr/<sha256>.<deviceId>.json`, single-writer by construction (deviceId in the name)
 * and write-once. Devices adopt one another's sidecars instead of re-OCRing.
 */
class OcrSidecarFiles(private val fs: LibraryFileSystem) {

    /** All sidecar files in `.npn/ocr/` (files only). */
    suspend fun list(): List<FileMeta> {
        val prefix = "${NpnPaths.OCR_DIR}/"
        return fs.listAll().filter { meta ->
            if (meta.isDirectory || !meta.relPath.startsWith(prefix)) return@filter false
            // Only direct children of .npn/ocr are sidecars (no nested dirs).
            !meta.relPath.removePrefix(prefix).contains('/')
        }
    }

    suspend fun read(sha256: String, deviceId: String): String =
        fs.readBytes(NpnPaths.ocrSidecarPath(sha256, deviceId)).decodeToString()

    suspend fun writeOnce(sha256: String, deviceId: String, json: String) {
        fs.writeBytesOnce(NpnPaths.ocrSidecarPath(sha256, deviceId), json.encodeToByteArray())
    }

    /** Device ids that have a sidecar for [sha256], derived from `<sha256>.<deviceId>.json`. */
    suspend fun listForHash(sha256: String): List<String> {
        val filePrefix = "$sha256."
        return list().mapNotNull { meta ->
            // Preserve historical behaviour: match by exact hash prefix and treat the entire
            // middle (between "<sha256>." and ".json") as the deviceId, even if it contains
            // further dots. This is deliberately looser than [parseSidecarName]'s first-dot
            // split, which would truncate a dotted deviceId here; do not "fix" that or old
            // sidecars written by pre-migration devices could stop being discovered.
            if (!meta.name.startsWith(filePrefix)) return@mapNotNull null
            if (!meta.name.endsWith(".json")) return@mapNotNull null
            val middle = meta.name.removePrefix(filePrefix).removeSuffix(".json")
            if (middle.isEmpty()) null else middle
        }
    }

    companion object {
        /**
         * Parse a sidecar file name of the form `<sha256>.<deviceId>.json` into its hash and
         * deviceId parts, or null when the name is not a sidecar.
         *
         * Tolerance: this is the lenient union parser shared by callers. It splits on the FIRST
         * dot, so `<hash>` is everything before it and `<deviceId>` is everything between that dot
         * and the trailing `.json` (a deviceId may itself contain dots). It intentionally does NOT
         * enforce a 64-character hash — callers that need that stricter shape (e.g. foreign-sidecar
         * adoption) filter on `hash.length == 64` themselves, while lookups keyed by a known hash
         * do not, so nothing that parses today stops parsing. See [listForHash] for its own,
         * even-looser exact-prefix matching, kept for backward compatibility.
         */
        fun parseSidecarName(name: String): Pair<String, String>? {
            if (!name.endsWith(".json")) return null
            val stem = name.removeSuffix(".json")
            val dot = stem.indexOf('.')
            if (dot <= 0) return null
            // dot >= 1 guarantees a non-empty hash; only the deviceId can still be empty.
            val hash = stem.substring(0, dot)
            val dev = stem.substring(dot + 1)
            if (dev.isEmpty()) return null
            return hash to dev
        }
    }
}
