package dev.npn.core.fs

/**
 * File-level adapter for OCR sidecars (PLAN "OCR sidecars"), named as [OcrSidecarName] describes.
 * Single-writer by construction (writer key in the name) and write-once. Devices adopt one
 * another's sidecars instead of re-OCRing with the same or a worse engine.
 */
class OcrSidecarFiles(
    private val fs: LibraryFileSystem,
) {
    /** All sidecar files in `.npn/ocr/` (files only). Walks that directory alone, not the library. */
    suspend fun list(): List<FileMeta> {
        val prefix = "${NpnPaths.OCR_DIR}/"
        return fs.listAllUnder(NpnPaths.OCR_DIR).filter { meta ->
            if (meta.isDirectory || !meta.relPath.startsWith(prefix)) return@filter false
            // Only direct children of .npn/ocr are sidecars (no nested dirs).
            !meta.relPath.removePrefix(prefix).contains('/')
        }
    }

    /** Read the sidecar for [sha256] written under [writerKey]. */
    suspend fun read(
        sha256: String,
        writerKey: String,
    ): String = fs.readBytes(OcrSidecarName(sha256, writerKey).path).decodeToString()

    /** Write-once publish of a sidecar under [writerKey]. */
    suspend fun writeOnce(
        sha256: String,
        writerKey: String,
        json: String,
    ) {
        fs.writeBytesOnce(OcrSidecarName(sha256, writerKey).path, json.encodeToByteArray())
    }

    /** Writer keys that have a sidecar for [sha256]. */
    suspend fun listForHash(sha256: String): List<String> =
        list().mapNotNull { meta -> OcrSidecarName.parse(meta.name)?.takeIf { it.hash == sha256 }?.writerKey }
}
