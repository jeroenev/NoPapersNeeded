package dev.npn.core.fs

import android.provider.DocumentsContract
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/** [SafDirectoryCache] over a provider whose ids survive moves and renames, as cloud providers' do. */
class SafDirectoryCacheTest {
    private val tree = Tree()
    private val cache = SafDirectoryCache(ROOT, tree::children)

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
        cache.refresh(BUCKET) shouldBe new

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
        cache.refresh(BUCKET) shouldBe null

        cache.cached(".npn") shouldBe npn
        cache.cached(".npn/journal") shouldBe null
        cache.cached(BUCKET.joinToString("/")) shouldBe null
    }

    /** A refresh pays for the actual parent chain once; ordinary reads keep reusing it afterwards. */
    @Test
    fun aRefreshedChainKeepsOrdinaryReadsCached() {
        val bucket = tree.journal()
        cache.resolveDir(BUCKET)
        val listings = tree.listings

        cache.refresh(BUCKET) shouldBe bucket

        tree.listings shouldBe listings + BUCKET.size
        cache.resolveDir(BUCKET).docId shouldBe bucket
        tree.listings shouldBe listings + BUCKET.size
        cache.cached(BUCKET.joinToString("/")) shouldBe bucket
    }

    /** Moving a directory can preserve both its name and id while changing the path it serves. */
    @Test
    fun aMovedBucketNoLongerServesItsOldPath() {
        val bucket = tree.journal()
        cache.resolveDir(BUCKET)
        val elsewhere = tree.add(ROOT, "elsewhere")
        tree.move(bucket, elsewhere)

        cache.refresh(BUCKET) shouldBe null

        cache.cached(BUCKET.joinToString("/")) shouldBe null
    }

    /** A replacement at the same path must supply its own id, even when the old one still exists. */
    @Test
    fun aMovedParentAndItsDescendantsAreReplacedByTheCurrentChain() {
        val old = tree.journal()
        cache.resolveDir(BUCKET)
        val elsewhere = tree.add(ROOT, "elsewhere")
        tree.move(tree.idOf(ROOT, ".npn"), elsewhere)
        val current = tree.journal()

        cache.refresh(BUCKET) shouldBe current

        cache.resolveDir(BUCKET).docId shouldBe current
        (current == old) shouldBe false
    }

    /** A provider tree whose document ids stay the same across moves and renames. */
    private class Tree {
        private class Node(
            val id: String,
            var parent: String?,
            var name: String,
            val dir: Boolean,
        )

        private val nodes = LinkedHashMap<String, Node>()
        var listings = 0

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

        fun move(
            id: String,
            parent: String,
        ) {
            checkNotNull(nodes[id]).parent = parent
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
    }

    private companion object {
        const val ROOT = "root"
        val BUCKET = listOf(".npn", "journal", "objects-v2-ab")
    }
}
