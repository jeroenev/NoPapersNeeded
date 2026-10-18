package dev.npn.core.ocr

import dev.npn.core.fs.FileMeta
import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.fs.NpnPaths

/**
 * File-level adapter for OCR sidecars, named as [OcrSidecarName] describes.
 * Single-writer by construction (writer key in the name) and write-once. Devices adopt one
 * another's sidecars instead of re-OCRing with the same or a worse engine.
 */
class OcrSidecarFiles(
    private val fs: LibraryFileSystem,
) {
    /**
     * All sidecar files in `.npn/ocr/` (files only, at a path the library can address). Walks that
     * directory alone, not the library.
     */
    suspend fun list(): List<FileMeta> {
        val prefix = "${NpnPaths.OCR_DIR}/"
        return fs.listAllUnder(NpnPaths.OCR_DIR).filter { meta ->
            // Only direct children of .npn/ocr are sidecars (no nested dirs).
            !meta.isDirectory && NpnPaths.isSafe(meta.relPath) && !meta.relPath.removePrefix(prefix).contains('/')
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
}
