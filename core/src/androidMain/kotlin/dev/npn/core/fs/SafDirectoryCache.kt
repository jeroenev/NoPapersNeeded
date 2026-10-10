package dev.npn.core.fs

import android.provider.DocumentsContract
import java.util.concurrent.ConcurrentHashMap

/** A resolved SAF document: the provider's opaque id plus the columns callers read off it. */
internal data class Entry(
    val docId: String,
    val name: String,
    val mime: String,
    val size: Long,
    val mtime: Long,
) {
    val isDir: Boolean get() = mime == DocumentsContract.Document.MIME_TYPE_DIR
}

/**
 * Path-to-docId resolution for [SafLibraryFileSystem], memoized per directory.
 *
 * SAF addresses documents by opaque docId and offers no path lookup, so resolving `a/b/c.pdf`
 * means listing `a`, then `a/b` — one [android.content.ContentResolver] query (a binder round-trip
 * into the provider) per ancestor, every time, including the library root with every top-level PDF
 * in it. Uncached, opening a library walked the root three times over before the UI appeared, and
 * every per-document read (OCR sidecar, journal object, hash) re-paid the whole chain.
 *
 * Only DIRECTORY levels are memoized, and only positive lookups. A missing path is never
 * remembered, so a file or folder that arrives via sync is always seen.
 *
 * A cached docId can lie two ways: its directory was deleted (perhaps recreated) outside the app,
 * which a listing shows ([resolveDir]), or renamed, at a provider whose ids survive a rename (cloud
 * providers; ExternalStorageProvider's are path-derived), whose listing still looks fine. Only
 * [forgetRenamed] sees the second, for a caller that needs the path the tree has now.
 *
 * Split out of [SafLibraryFileSystem] because the interesting part is not SAF: it is deciding when
 * a negative result is real and when it is a cached docId lying (see [resolveDir]), and doing so
 * without handing back the ancestor re-listing the cache exists to remove.
 */
internal class SafDirectoryCache(
    private val rootDocId: String,
    private val listChildren: (parentDocId: String) -> List<Entry>,
    /** The display name the provider gives a document now, or null when it cannot say. */
    private val nameOf: (docId: String) -> String?,
) {
    private val dirDocIds = ConcurrentHashMap<String, String>()

    /**
     * A directory resolution.
     *
     * @property docId the resolved directory, or null when a segment is missing or is a file.
     * @property fromCache whether [docId] came straight out of the cache and so has not yet been
     *   proven to still address the directory we mean.
     * @property suspectPath the cached path whose docId may be stale, when a null [docId] might be
     *   an artifact of that rather than a real miss. Null whenever the result can be believed.
     */
    class ResolvedDir(
        val docId: String?,
        val fromCache: Boolean,
        val suspectPath: String?,
    )

    /** Memoize [docId] for the directory at [dirPath]. */
    fun remember(
        dirPath: String,
        docId: String,
    ) {
        dirDocIds[dirPath] = docId
    }

    /** The memoized docId for [dirPath], or null. */
    fun cached(dirPath: String): String? = dirDocIds[dirPath]

    /**
     * Forget [dirPath] and everything under it.
     *
     * Scoped rather than a whole-map clear on purpose: invalidation happens on ordinary events (a
     * not-yet-created journal shard, a directory move), and dropping every memoized ancestor each
     * time would give back the per-operation ancestor re-listing this cache exists to remove.
     */
    fun invalidateSubtree(dirPath: String) {
        if (dirPath.isEmpty()) {
            dirDocIds.clear()
            return
        }
        dirDocIds.keys.removeAll { it == dirPath || it.startsWith("$dirPath/") }
    }

    /**
     * Forget [dirSegments]'s cached chain from its first level whose directory is now called
     * something else: renamed away with its id kept, it would go on serving the old path. One
     * single-document query per cached level and no listing, however big the directories. A name
     * the provider cannot say is no evidence of a rename.
     */
    fun forgetRenamed(dirSegments: List<String>) {
        val levels = dirSegments.indices.map { dirSegments.subList(0, it + 1).joinToString("/") }
        val renamed =
            dirSegments.indices.firstOrNull { index ->
                val name = dirDocIds[levels[index]]?.let(nameOf)
                name != null && name != dirSegments[index]
            } ?: return
        invalidateSubtree(levels[renamed])
    }

    /**
     * Resolve a directory path to its docId, retrying once past a docId that looks stale.
     *
     * A cached docId is the only thing in the walk that can lie: its directory may have been
     * deleted (and perhaps recreated) outside the app. [walk] decides per level whether a negative
     * result is actually attributable to that, so the retry fires on genuine suspicion instead of
     * on every miss.
     */
    fun resolveDir(dirSegments: List<String>): ResolvedDir {
        val resolved = walk(dirSegments)
        val suspect = resolved.suspectPath ?: return resolved
        invalidateSubtree(suspect)
        // The second walk resolves the suspect level from a real listing, so its answer is final.
        return walk(dirSegments)
    }

    /**
     * List a directory's children, tolerating the one stale-docId symptom [walk] cannot see for
     * itself: the *final* directory's docId came from the cache, so nothing in the walk has proven
     * it still addresses the directory we mean.
     *
     * A refused or empty listing is the tell — but both are also legitimate (a journal shard whose
     * objects were compacted away really is empty), so this re-resolves that subtree and believes
     * the second answer rather than treating an empty directory as cache damage.
     */
    fun childrenOfDir(dirSegments: List<String>): List<Entry>? {
        val resolved = resolveDir(dirSegments)
        val docId = resolved.docId ?: return null
        if (!resolved.fromCache) return listChildren(docId)
        val path = dirSegments.joinToString("/")
        val children = listOrNullWhenCached(docId, path)
        if (!children.isNullOrEmpty()) return children
        invalidateSubtree(path)
        return resolveDir(dirSegments).docId?.let(listChildren)
    }

    /**
     * One pass down a directory path, consulting and filling the cache level by level.
     *
     * The subtle part is deciding when a miss is real. A listing that came back with entries but
     * without the name we wanted is proof: the parent docId clearly still addresses a live
     * directory, so the name is genuinely absent. That is the common case — probing for a
     * not-yet-created journal shard, or for a free filename — and it must not cost an
     * invalidation. Only a listing that failed or came back EMPTY under a docId taken from the
     * cache is ambiguous, and only that reports a [ResolvedDir.suspectPath].
     */
    private fun walk(dirSegments: List<String>): ResolvedDir {
        var parentId = rootDocId
        // Non-null only while parentId came out of the cache, i.e. is not yet proven live. The
        // tree root always is, and so is any level resolved from a listing below.
        var parentKey: String? = null
        for (index in dirSegments.indices) {
            val key = dirSegments.subList(0, index + 1).joinToString("/")
            val cached = dirDocIds[key]
            if (cached != null) {
                parentId = cached
                parentKey = key
                continue
            }
            val siblings = listOrNullWhenCached(parentId, parentKey)
            // Null only comes back for a cached parent, so parentKey is the path to blame.
            if (siblings == null) return ResolvedDir(null, true, checkNotNull(parentKey))
            val match = siblings.firstOrNull { it.name == dirSegments[index] }
            if (match == null || !match.isDir) {
                return ResolvedDir(null, parentKey != null, parentKey?.takeIf { siblings.isEmpty() })
            }
            parentId = match.docId
            parentKey = null
            dirDocIds[key] = parentId
        }
        return ResolvedDir(parentId, parentKey != null, null)
    }

    /**
     * [listChildren], returning null instead of throwing when the provider refuses a listing for a
     * docId that came from the cache ([cachedAs] non-null). A freshly resolved docId does not go
     * bad between two calls, so a failure there is real and propagates.
     */
    private fun listOrNullWhenCached(
        parentDocId: String,
        cachedAs: String?,
    ): List<Entry>? =
        try {
            listChildren(parentDocId)
        } catch (refused: Exception) {
            if (cachedAs == null) throw refused
            null
        }
}
