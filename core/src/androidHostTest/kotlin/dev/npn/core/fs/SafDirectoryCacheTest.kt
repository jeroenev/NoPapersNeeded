package dev.npn.core.fs

import android.provider.DocumentsContract
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/** [SafDirectoryCache] over a provider whose ids survive a rename, as cloud providers' do. */
class SafDirectoryCacheTest {
    private val tree = Tree()
    private val cache = SafDirectoryCache(ROOT, tree::children, tree::nameOf)

    /**
     * Another app renames `.npn` away, keeping its id and its children, and makes a new one: once its
     * chain's names are checked, the bucket path resolves to the new tree's bucket, as a fresh listing
     * would, not to the renamed one.
     */
    @Test
    fun aDirectoryRenamedAwayNoLongerServesItsOldPath() {
        val old = tree.journal()
        cache.resolveDir(BUCKET).docId shouldBe old

        tree.rename(tree.idOf(ROOT, ".npn"), ".npn-backup")
        val new = tree.journal()
        cache.forgetRenamed(BUCKET)

        cache.resolveDir(BUCKET).docId shouldBe new
    }

    /** A rename deeper down drops that level and those below it, and keeps the levels above. */
    @Test
    fun aRenamedLevelGoesWithWhatIsBelowItOnly() {
        tree.journal()
        cache.resolveDir(BUCKET)
        val npn = cache.cached(".npn")
        val journal = tree.idOf(checkNotNull(npn), "journal")

        tree.rename(journal, "journal-old")
        cache.forgetRenamed(BUCKET)

        cache.cached(".npn") shouldBe npn
        cache.cached(".npn/journal") shouldBe null
        cache.cached(BUCKET.joinToString("/")) shouldBe null
    }

    /** Checking names costs one single-document lookup per cached level and no listing at all. */
    @Test
    fun checkingAChainListsNothing() {
        val bucket = tree.journal()
        cache.resolveDir(BUCKET)
        val listings = tree.listings

        cache.forgetRenamed(BUCKET)

        tree.listings shouldBe listings
        tree.lookups shouldBe BUCKET.size
        cache.cached(BUCKET.joinToString("/")) shouldBe bucket
    }

    /** A name the provider cannot say is no evidence of a rename: the cached chain stays. */
    @Test
    fun aNameTheProviderCannotSayKeepsTheChain() {
        val bucket = tree.journal()
        cache.resolveDir(BUCKET)
        tree.namesKnown = false

        cache.forgetRenamed(BUCKET)

        cache.cached(BUCKET.joinToString("/")) shouldBe bucket
    }

    /** A provider tree whose document ids stay the same across a rename. */
    private class Tree {
        private class Node(
            val id: String,
            val parent: String?,
            var name: String,
            val dir: Boolean,
        )

        private val nodes = LinkedHashMap<String, Node>()
        var listings = 0
        var lookups = 0
        var namesKnown = true

        init {
            nodes[ROOT] = Node(ROOT, null, "", dir = true)
        }

        fun add(
            parent: String,
            name: String,
            dir: Boolean = true,
        ): String {
            val id = "doc-${nodes.size}"
            nodes[id] = Node(id, parent, name, dir)
            return id
        }

        /** A new `.npn/journal/objects-v2-ab` with one object in it; returns the bucket's id. */
        fun journal(): String {
            val bucket = add(add(add(ROOT, ".npn"), "journal"), "objects-v2-ab")
            add(bucket, "object.jsonl", dir = false)
            return bucket
        }

        fun rename(
            id: String,
            name: String,
        ) {
            checkNotNull(nodes[id]).name = name
        }

        fun idOf(
            parent: String,
            name: String,
        ): String = nodes.values.single { it.parent == parent && it.name == name }.id

        fun children(parent: String): List<Entry> {
            listings++
            return nodes.values.filter { it.parent == parent }.map {
                val mime = if (it.dir) DocumentsContract.Document.MIME_TYPE_DIR else "application/json"
                Entry(it.id, it.name, mime, 0L, 0L)
            }
        }

        fun nameOf(id: String): String? {
            lookups++
            return if (namesKnown) nodes[id]?.name else null
        }
    }

    private companion object {
        const val ROOT = "root"
        val BUCKET = listOf(".npn", "journal", "objects-v2-ab")
    }
}
