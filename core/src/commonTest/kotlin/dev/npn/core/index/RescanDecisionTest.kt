// checkAll's per-test PropTestConfig overload is still marked experimental by kotest.
@file:OptIn(ExperimentalKotest::class)

package dev.npn.core.index

import dev.npn.core.db.DocumentEntity
import dev.npn.core.db.PathMeta
import dev.npn.core.db.missingSentinel
import dev.npn.core.fs.FileMeta
import dev.npn.core.journal.propTest
import io.kotest.common.ExperimentalKotest
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.Exhaustive
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import io.kotest.property.exhaustive.collection
import kotlin.random.Random
import kotlin.test.Test

/**
 * What a rescan decides about the files it hashed ([RescanDecision]): every combination of the row
 * at a file's path, the row of its bytes, their stats and flags and what the journal says, for one
 * file; and small random libraries for a whole pass.
 */
class RescanDecisionTest {
    private val now = 9_000L
    private val hash = "h-file"
    private val path = "inbox/a.pdf"
    private val meta = FileMeta(path, "a.pdf", size = 10, mtimeMillis = 1_000, isDirectory = false)

    private fun row(
        docId: String,
        relPath: String,
        size: Long = 10,
        mtime: Long = 1_000,
        trashed: Boolean = false,
        deleted: Boolean = false,
        missing: Boolean = false,
    ) = DocumentEntity(
        docId = docId,
        relPath = relPath,
        fileName = relPath.substringAfterLast('/'),
        size = size,
        mtime = mtime,
        addedAt = 1,
        trashed = trashed,
        deleted = deleted,
        missing = missing,
    )

    /** Every row another document could have at the file's path: none, shown, missing, trashed, deleted. */
    private val holders =
        listOf(
            null,
            row("other", path),
            row("other", path, missing = true),
            row("other", path, trashed = true),
            row("other", path, deleted = true),
        )

    /**
     * Every row the file's bytes could have: none; at this path with its stat or another, missing or
     * not; elsewhere, missing or not, a deleted one included, with that path still holding the
     * bytes or not.
     */
    private val knowns: List<Pair<DocumentEntity?, Boolean>> =
        buildList {
            add(null to false)
            for (missing in listOf(false, true)) {
                for (stat in listOf(10L to 1_000L, 10L to 2_000L, 11L to 1_000L)) {
                    add(row(hash, path, size = stat.first, mtime = stat.second, missing = missing) to false)
                }
                for (deleted in listOf(false, true)) {
                    for (stillOnDisk in listOf(false, true)) {
                        add(row(hash, "old/a.pdf", missing = missing, deleted = deleted) to stillOnDisk)
                    }
                }
            }
        }

    private val allFacts =
        buildList {
            for (holder in holders) {
                for (holderHidden in listOf(false, true)) {
                    // The row's own flags hide it whatever the journal says.
                    if (holder == null && holderHidden) continue
                    if (holder != null && (holder.trashed || holder.deleted) && !holderHidden) continue
                    for (superseded in listOf(false, true)) {
                        for ((known, stillOnDisk) in knowns) {
                            add(FileFacts(meta, hash, holder, holderHidden, superseded, known, stillOnDisk))
                        }
                    }
                }
            }
        }

    @Test
    fun theRowAtAPathOfOtherBytesGivesItUpAndGoesMissingOnlyWhenItWasShown() =
        propTest {
            checkAll(Exhaustive.collection(allFacts)) { facts ->
                val displaced = RescanDecision.file(facts, now).displaced
                val holder = facts.holder
                if (holder == null) {
                    displaced shouldBe null
                } else {
                    displaced shouldBe
                        holder.copy(
                            relPath = missingSentinel(holder.docId),
                            missing =
                                holder.missing || !facts.holderHidden,
                        )
                }
            }
        }

    @Test
    fun bytesARelinkSupersededGetNoRowOfTheirOwn() =
        propTest {
            checkAll(Exhaustive.collection(allFacts.filter { it.superseded })) { facts ->
                RescanDecision.file(facts, now).outcome shouldBe FileOutcome.Superseded
            }
        }

    /**
     * The document of a file's bytes ends at the file, with the file's stat and not missing, unless
     * its own path still holds them, which makes the file a duplicate. Only a row that already
     * stands so is left unwritten.
     */
    @Test
    fun theDocumentOfAFileEndsAtItWithItsStatOrTheFileIsADuplicate() =
        propTest {
            checkAll(Exhaustive.collection(allFacts.filter { !it.superseded && it.known != null })) { facts ->
                val known = checkNotNull(facts.known)
                val atTheFile =
                    known.copy(
                        relPath = path,
                        fileName = meta.name,
                        size = 10,
                        mtime = 1_000,
                        missing = false,
                    )
                when (val outcome = RescanDecision.file(facts, now).outcome) {
                    is FileOutcome.Duplicate -> {
                        facts.knownStillOnDisk shouldBe true
                        outcome.report shouldBe DuplicateReport(hash, path)
                    }

                    is FileOutcome.Moved -> {
                        known.relPath shouldBe "old/a.pdf"
                        outcome.row shouldBe atTheFile
                    }

                    is FileOutcome.Refreshed -> {
                        known.relPath shouldBe path
                        outcome.row shouldBe atTheFile
                    }

                    FileOutcome.Unchanged -> {
                        known shouldBe atTheFile
                    }

                    else -> {
                        error("Unexpected $outcome for $facts")
                    }
                }
                if (facts.knownStillOnDisk) {
                    RescanDecision.file(facts, now).outcome shouldBe
                        FileOutcome.Duplicate(DuplicateReport(hash, path))
                }
            }
        }

    /** Bytes no row holds are a new document, proposed as an edit of the shown document it replaced. */
    @Test
    fun bytesNoRowHoldsAreANewDocumentAndAnEditOnlyOfAShownOne() =
        propTest {
            checkAll(Exhaustive.collection(allFacts.filter { !it.superseded && it.known == null })) { facts ->
                val outcome = RescanDecision.file(facts, now).outcome
                outcome shouldBe
                    FileOutcome.Added(
                        DocumentEntity(hash, path, "a.pdf", size = 10, mtime = 1_000, addedAt = now),
                        facts.holder?.takeUnless { facts.holderHidden }?.let {
                            RelinkCandidate(
                                it.docId,
                                hash,
                                setOf(RelinkSignal.REPLACED_IN_PLACE),
                                RelinkPairing.SCORE_PATH_MATCH,
                            )
                        },
                    )
            }
        }

    @Test
    fun aFileIsHashedUnlessTheShownRowAtItsPathStoresItsStat() {
        val file = FileMeta(path, "a.pdf", size = 10, mtimeMillis = 1_000, isDirectory = false)
        RescanDecision.needsHash(file, null) shouldBe true
        RescanDecision.needsHash(file, PathMeta(path, "d", 10, 1_000)) shouldBe false
        RescanDecision.needsHash(file, PathMeta(path, "d", 10, 1_000, hidden = true)) shouldBe true
        RescanDecision.needsHash(file, PathMeta(path, "d", 10, 2_000)) shouldBe true
        RescanDecision.needsHash(file, PathMeta(path, "d", 11, 1_000)) shouldBe true
        // Storage that reports no mtimes proves nothing by a stat.
        val noMtime = file.copy(mtimeMillis = 0)
        RescanDecision.needsHash(noMtime, PathMeta(path, "d", 10, 0)) shouldBe true
    }

    /** One random library, as a rescan finds it, and the plan it decides. */
    private class Library(
        seed: Long,
    ) {
        private val random = Random(seed)
        val paths = listOf("a.pdf", "b.pdf", "inbox/c.pdf", "inbox/d.pdf", "e.pdf")
        val contents = listOf("h1", "h2", "h3", "h4", "h5")

        /** Every row the index holds, deleted ones included; one per path, as the schema has it. */
        val rows: List<DocumentEntity>
        val files: Map<String, String>
        val listing: List<FileMeta>
        val hashed: List<FileMeta>
        val hashes: Map<String, String>
        val hiddenByJournal: Set<String>
        val successor: Map<String, String>

        init {
            val taken = HashSet<String>()
            rows =
                contents.filter { random.nextInt(3) > 0 }.mapNotNull { docId ->
                    val relPath =
                        if (random.nextInt(4) == 0) missingSentinel(docId) else paths.random(random)
                    if (!taken.add(relPath)) return@mapNotNull null
                    val flag = random.nextInt(6)
                    DocumentEntity(
                        docId = docId,
                        relPath = relPath,
                        fileName = relPath.substringAfterLast('/'),
                        size = docId.length.toLong(),
                        mtime = 100L * random.nextInt(1, 3),
                        addedAt = 1,
                        trashed = flag == 1,
                        deleted = flag == 2,
                        missing = flag == 3,
                    )
                }
            files = paths.filter { random.nextBoolean() }.associateWith { contents.random(random) }
            listing =
                files.map { (relPath, content) ->
                    FileMeta(
                        relPath,
                        relPath.substringAfterLast('/'),
                        content.length.toLong(),
                        100L * random.nextInt(1, 3),
                        false,
                    )
                }
            val projected = rows.filter { !it.deleted }.associateBy { it.relPath }
            // Hashed as a rescan would: whatever the shown row at its path does not vouch for, and
            // now and then one it does, as a file the pass could not skip.
            hashed =
                listing.filter { file ->
                    RescanDecision.needsHash(file, projected[file.relPath]?.pathMeta()) || random.nextInt(4) == 0
                }
            // A file written to while it was read gets no hash, and is left for a later pass.
            hashes = hashed.filter { random.nextInt(5) > 0 }.associate { it.relPath to files.getValue(it.relPath) }
            hiddenByJournal = contents.filter { random.nextInt(4) == 0 }.toSet()
            successor = contents.filter { random.nextInt(5) == 0 }.associateWith { "relinked-$it" }
        }

        val projection: List<PathMeta> get() = rows.filter { !it.deleted }.map { it.pathMeta() }

        private fun DocumentEntity.pathMeta() = PathMeta(relPath, docId, size, mtime, hidden = trashed || missing)

        val journal =
            object : RescanJournal {
                override fun successorOf(docId: String) = successor[docId] ?: docId

                override fun hides(docId: String) = docId in hiddenByJournal
            }

        fun plan(): RescanPlan {
            val shown = rows.filter { !it.deleted }
            val tombstones = rows.filter { it.deleted && it.docId in RescanDecision.tombstoneIds(hashes, shown) }
            return RescanDecision.plan(
                hashed = hashed,
                hashes = hashes,
                projection = projection,
                rows = shown,
                tombstones = tombstones,
                diskPaths = listing.map { it.relPath }.toSet(),
                journal = journal,
                now = 7,
            )
        }

        /** The rows once [plan] is written, in order, each replacing its document's. */
        fun after(plan: RescanPlan): Map<String, DocumentEntity> {
            val out = rows.associateByTo(LinkedHashMap()) { it.docId }
            for (row in plan.writes + plan.nowMissing) out[row.docId] = row
            return out
        }
    }

    @Test
    fun aPassLeavesEveryHashedFileToItsDocumentOrAsADuplicateAndNoOtherRowOnIt() =
        propTest {
            checkAll(PropTestConfig(iterations = 2_000), Arb.long()) { seed ->
                val library = Library(seed)
                val plan = library.plan()
                val after = library.after(plan)
                for ((relPath, content) in library.hashes) {
                    val file = library.listing.single { it.relPath == relPath }
                    val others = after.values.filter { it.relPath == relPath && it.docId != content && !it.deleted }
                    check(others.isEmpty()) { "seed $seed: $relPath holds $content, yet rows $others stay on it" }
                    if (library.journal.successorOf(content) != content) {
                        // Its row may only give a path up, never take one.
                        val placed = plan.writes.filter { it.docId == content && it.relPath != missingSentinel(content) }
                        check(placed.isEmpty()) { "seed $seed: superseded $content got a row at $placed" }
                        continue
                    }
                    val row = after[content]
                    val there =
                        row != null && row.relPath == relPath && row.size == file.size && row.mtime == file.mtimeMillis
                    val copy = DuplicateReport(content, relPath) in plan.duplicates
                    check(
                        there || copy,
                    ) { "seed $seed: $relPath of $content is neither its row ($row) nor a duplicate" }
                    if (there) check(!row.missing) { "seed $seed: $content found at $relPath stays missing" }
                }
            }
        }

    @Test
    fun aPassLeavesOnePathToOneRowAndMarksMissingOnlyShownRowsWhoseFileIsGone() =
        propTest {
            checkAll(PropTestConfig(iterations = 2_000), Arb.long()) { seed ->
                val library = Library(seed)
                val plan = library.plan()
                val after = library.after(plan)
                val held =
                    after.values
                        .filter { !it.deleted }
                        .groupBy { it.relPath }
                        .filterValues { it.size > 1 }
                check(held.isEmpty()) { "seed $seed: paths held by more than one row: $held" }
                val onDisk = library.listing.map { it.relPath }.toSet()
                for (row in plan.nowMissing) {
                    check(row.missing && row.relPath !in onDisk) { "seed $seed: $row marked missing" }
                    check(!row.trashed && !row.deleted && row.docId !in library.hiddenByJournal) {
                        "seed $seed: hidden $row marked missing"
                    }
                }
                val known = library.rows.mapTo(HashSet()) { it.docId }
                plan.added shouldBe plan.writes.map { it.docId }.distinct().count { it !in known }
                plan.duplicates.forEach { check(it.extraRelPath in library.hashes) { "seed $seed: unhashed copy $it" } }
            }
        }
}
