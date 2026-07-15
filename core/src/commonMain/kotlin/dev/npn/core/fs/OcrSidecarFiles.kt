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
            if (!meta.name.startsWith(filePrefix)) return@mapNotNull null
            if (!meta.name.endsWith(".json")) return@mapNotNull null
            val middle = meta.name.removePrefix(filePrefix).removeSuffix(".json")
            if (middle.isEmpty()) null else middle
        }
    }

    suspend fun delete(sha256: String, deviceId: String) {
        fs.delete(NpnPaths.ocrSidecarPath(sha256, deviceId))
    }
}
