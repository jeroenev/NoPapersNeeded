package dev.npn.core.ocr

import dev.npn.core.util.runCatchingCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The OCR versions stored for documents: the sidecars in `.npn/ocr/`, each named by its writer key
 * (`<deviceId>.<engine>`, `<deviceId>.r<runMillis>.<engine>` for a re-run, or a legacy bare
 * `<deviceId>`), and which of a document's versions is the one to index.
 *
 * Sidecars are write-once, so what is learned by reading one (a legacy sidecar's engine, whether an
 * `embedded` one has a text layer on every page) holds for good and is remembered.
 *
 * @param minEmbeddedCharsPerPage the trimmed length from which a page's embedded text counts as a
 *  usable text layer ([OcrPipeline.MIN_EMBEDDED_CHARS_PER_PAGE]).
 */
class OcrVersions internal constructor(
    private val sidecars: OcrSidecarFiles,
    private val minEmbeddedCharsPerPage: Int,
) {
    /** One stored OCR version of a document: a sidecar identified by its writer key. */
    data class Version(
        val writerKey: String,
        val deviceId: String,
        val engine: String,
        /** Run stamp of a re-run ([OcrSidecarName.rerunWriterKey]); 0 for a device's first run. */
        val run: Long = 0,
    )

    /** A fully decoded stored version, for UI listing. */
    data class StoredVersion(
        val writerKey: String,
        val deviceId: String,
        val engine: String,
        val lang: String,
        val createdAt: Long,
    )

    /** Engine label per `<hash>.<writerKey>`, so legacy sidecars are decoded at most once. */
    private val engineCache = SidecarFacts<String>()

    /** Whether an `embedded` sidecar has a usable text layer on every page, per `<hash>.<writerKey>`. */
    private val fullTextLayerCache = SidecarFacts<Boolean>()

    /** Which writer keys `.npn/ocr/` holds per hash, so a drain lists the directory once, not per document. */
    private val index = SidecarIndex()

    /**
     * Hash -> writer keys for every sidecar in `.npn/ocr/` named by a document hash, from a fresh
     * listing, which also becomes what [of] answers from: a reconcile, which every rescan and every
     * change under `.npn/ocr/` starts, brings in what other devices published.
     */
    suspend fun writerKeysByHash(): Map<String, List<String>> = listWriterKeys().also { index.replace(it) }

    private suspend fun listWriterKeys(): Map<String, List<String>> =
        sidecars
            .list()
            .mapNotNull { meta -> OcrSidecarName.parse(meta.name)?.takeIf { it.hasDocumentHash } }
            .groupBy({ it.hash }, { it.writerKey })

    /** The readable versions of [hash] among [writerKeys]. */
    suspend fun of(
        hash: String,
        writerKeys: List<String>,
    ): List<Version> = writerKeys.mapNotNull { versionFor(hash, it) }

    /**
     * Every readable version of [hash] as of the last listing of `.npn/ocr/`, plus what this device
     * published since. The first call after [forget] lists the directory again.
     */
    suspend fun of(hash: String): List<Version> = of(hash, index.keysFor(hash) { listWriterKeys() })

    /**
     * Drop the listing [of] answers from, so the next call lists `.npn/ocr/` again: at the end of a
     * drain, so the next one sees what arrived meanwhile, and when a key it named could not be read.
     */
    suspend fun forget(hash: String? = null) {
        index.forget(hash)
    }

    /**
     * Publish [json] as [hash]'s sidecar under [writerKey], write-once, and return whether this call
     * wrote it. One already published under that key and readable is kept, and counts as not written;
     * a write that fails otherwise throws. Either way [of] knows the key from here on.
     */
    suspend fun publish(
        hash: String,
        writerKey: String,
        json: String,
    ): Boolean {
        val written =
            runCatchingCancellable { sidecars.writeOnce(hash, writerKey, json) }.fold(
                onSuccess = { true },
                onFailure = { failure -> if (read(hash, writerKey) == null) throw failure else false },
            )
        index.add(hash, writerKey)
        return written
    }

    /**
     * All stored OCR versions of [docId], decoded, best-ranked first. For UI listing, so read from a
     * listing of its own rather than the one a drain shares.
     */
    suspend fun list(docId: String): List<StoredVersion> =
        of(docId, sidecars.listForHash(docId))
            .mapNotNull { v ->
                read(docId, v.writerKey)?.let {
                    StoredVersion(v.writerKey, v.deviceId, it.engine, it.lang, it.createdAt)
                }
            }.sortedWith(compareByDescending<StoredVersion> { OcrEngineId.rank(it.engine) }.thenBy { it.writerKey })

    /**
     * Pick the version to index: among versions matching the [override] engine (all versions
     * when no override or no match), the highest-ranked engine, then the newest re-run of it, then
     * the lowest writer key, so every device converges on the same choice.
     */
    fun select(
        versions: List<Version>,
        override: String?,
    ): Version? {
        val pool = versions.filter { it.engine == override }.ifEmpty { versions }
        return pool.maxWithOrNull(
            compareBy<Version> { OcrEngineId.rank(it.engine) }
                .thenBy { it.run }
                .thenByDescending { it.writerKey },
        )
    }

    /**
     * The writer key [deviceId]'s new sidecar of [engine] is published under: its first run's key,
     * or a re-run key once [stored] holds a version of that engine from it. The re-run is stamped
     * [runMillis], but always above that device and engine's latest run, so it is the newest
     * ([select]) and its key is new even when the clock stood still or went back.
     */
    fun newWriterKey(
        stored: List<Version>,
        deviceId: String,
        engine: String,
        runMillis: Long,
    ): String {
        val own = stored.filter { it.engine == engine && it.deviceId == deviceId }
        if (own.isEmpty()) return OcrSidecarName.writerKey(deviceId, engine)
        val run = maxOf(runMillis, own.maxOf { it.run } + 1)
        return OcrSidecarName.rerunWriterKey(deviceId, run, engine)
    }

    /** Whether [version] of [docId] is an `embedded` sidecar with a usable text layer on every page. */
    suspend fun hasFullTextLayer(
        docId: String,
        version: Version,
    ): Boolean =
        version.engine == OcrEngineId.EMBEDDED.column &&
            fullTextLayerCache.getOrCompute("$docId.${version.writerKey}") {
                read(docId, version.writerKey)?.let { sidecar ->
                    sidecar.pages.all { it.trim().length >= minEmbeddedCharsPerPage }
                }
            } == true

    /**
     * Read and decode one sidecar, or null when missing/corrupt. Cancellation is rethrown: read as
     * "unreadable", it would charge the document a failed attempt for a drain that was only
     * stopped.
     */
    suspend fun read(
        hash: String,
        writerKey: String,
    ): OcrSidecar? = runCatchingCancellable { OcrSidecarCodec.decode(sidecars.read(hash, writerKey)) }.getOrNull()

    /**
     * Resolve a writer key to a [Version]. Engine-qualified keys resolve without I/O; legacy keys
     * decode the sidecar once to learn its engine (cached). Returns null for unreadable sidecars.
     */
    private suspend fun versionFor(
        hash: String,
        writerKey: String,
    ): Version? {
        val writer = OcrSidecarName.writer(writerKey)
        val engine =
            writer.engine
                ?: engineCache.getOrCompute("$hash.$writerKey") { read(hash, writerKey)?.engine }
                ?: return null
        return Version(writerKey, writer.deviceId, engine, writer.run)
    }
}

/**
 * A memo of facts about write-once sidecars, shared by every coroutine that drives the pipeline:
 * the queue drain, and the reconcile that rescans, watch events and engine pins run alongside it.
 * The map is only touched under [lock]; the fact itself is computed outside it, so a slow read
 * never holds up the others. Two callers computing the same fact at once get the same answer,
 * since the sidecar it comes from never changes.
 */
private class SidecarFacts<V : Any> {
    private val lock = Mutex()
    private val facts = HashMap<String, V>()

    /** The fact under [key], computing and remembering it first; null (not remembered) if [compute] has none. */
    suspend fun getOrCompute(
        key: String,
        compute: suspend () -> V?,
    ): V? {
        lock.withLock { facts[key] }?.let { return it }
        val fact = compute() ?: return null
        lock.withLock { facts[key] = fact }
        return fact
    }
}

/**
 * Writer keys per hash as one listing of `.npn/ocr/` found them, plus the keys this device published
 * since, which a listing that started before the write may have missed. Sidecars are write-once and
 * the app never deletes one, so keys only come and go with a new listing.
 */
private class SidecarIndex {
    private val lock = Mutex()
    private var listed: Map<String, List<String>>? = null
    private val published = HashMap<String, MutableSet<String>>()

    suspend fun keysFor(
        hash: String,
        list: suspend () -> Map<String, List<String>>,
    ): List<String> {
        val snapshot = lock.withLock { listed } ?: list().also { fresh -> lock.withLock { listed = listed ?: fresh } }
        return lock.withLock { (snapshot[hash].orEmpty() + published[hash].orEmpty()).distinct() }
    }

    suspend fun replace(fresh: Map<String, List<String>>) {
        lock.withLock { listed = fresh }
    }

    suspend fun add(
        hash: String,
        writerKey: String,
    ) {
        lock.withLock { published.getOrPut(hash) { HashSet() }.add(writerKey) }
    }

    /** Drop the listing; with a [hash], also what was published for it, as a key it named is gone. */
    suspend fun forget(hash: String?) {
        lock.withLock {
            listed = null
            if (hash != null) published.remove(hash)
        }
    }
}
