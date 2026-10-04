package dev.npn.core.fs

/**
 * An OCR sidecar's name, `.npn/ocr/<sha256>.<writerKey>.json`, and the one place that reads one.
 *
 * The writer key is `<deviceId>.<engine>` for a device's first run of an engine (one per
 * device+engine, letting several OCR versions co-exist), `<deviceId>.r<runMillis>.<engine>` for a
 * deliberate re-run of an engine that device already published (sidecars are write-once), or a bare
 * `<deviceId>` for a sidecar written before engines were named.
 *
 * Kept lenient for the sidecars older builds wrote: the hash ends at the first dot and the writer
 * key runs on to `.json`, so a legacy device id may hold dots; and the hash is not checked to be a
 * SHA-256 here, which lookups by a known document hash do not need ([hasDocumentHash] is for
 * callers that discover hashes).
 */
data class OcrSidecarName(
    val hash: String,
    val writerKey: String,
) {
    /** Where the sidecar lives in the library. */
    val path: String get() = "${NpnPaths.OCR_DIR}/$hash.$writerKey$SUFFIX"

    /** Whether [hash] is as long as a document's SHA-256, so it can key a document. */
    val hasDocumentHash: Boolean get() = hash.length == NpnPaths.SHA256_HEX_LENGTH

    /**
     * A writer key's parts: the device that wrote it, the engine (null in a legacy key), and the
     * re-run stamp (0 for a first run).
     */
    data class Writer(
        val deviceId: String,
        val engine: String?,
        val run: Long = 0,
    )

    companion object {
        private const val SUFFIX = ".json"

        /** The device part of a re-run writer key: `<deviceId>.r<runMillis>`. */
        private val rerunWriter = Regex("""(.+)\.r(\d+)""")

        /** The sidecar a file [name] in `.npn/ocr/` stands for, or null when it is no sidecar's name. */
        fun parse(name: String): OcrSidecarName? {
            if (!name.endsWith(SUFFIX)) return null
            val stem = name.removeSuffix(SUFFIX)
            val dot = stem.indexOf('.')
            if (dot <= 0 || dot == stem.lastIndex) return null
            return OcrSidecarName(stem.substring(0, dot), stem.substring(dot + 1))
        }

        /** Writer key of [deviceId]'s first sidecar of [engine]. */
        fun writerKey(
            deviceId: String,
            engine: String,
        ): String = "$deviceId.$engine"

        /**
         * Writer key of a re-run of [engine] on a device that already published one. The run stamp
         * orders runs of one engine, newest first; builds that predate it read the key as one more
         * device's version of that engine.
         */
        fun rerunWriterKey(
            deviceId: String,
            runMillis: Long,
            engine: String,
        ): String = "$deviceId.r$runMillis.$engine"

        /**
         * The parts of [writerKey]. A key whose last part [isEngine] names is engine-qualified;
         * anything else is a legacy key, all of it the device id, whose engine only its sidecar's
         * content tells.
         */
        fun writer(
            writerKey: String,
            isEngine: (String) -> Boolean,
        ): Writer {
            val engine = writerKey.substringAfterLast('.', "")
            if (!isEngine(engine)) return Writer(writerKey, engine = null)
            val device = writerKey.removeSuffix(".$engine")
            val rerun = rerunWriter.matchEntire(device)?.groupValues
            val run = rerun?.get(2)?.toLongOrNull()
            return if (rerun == null || run == null) Writer(device, engine) else Writer(rerun[1], engine, run)
        }

        /** Whether [writerKey] is one [deviceId] writes under: its legacy key, or any of its engine keys. */
        fun isWrittenBy(
            writerKey: String,
            deviceId: String,
        ): Boolean = writerKey == deviceId || writerKey.startsWith("$deviceId.")
    }
}
