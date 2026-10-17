package dev.npn.core.journal

import dev.npn.core.model.SystemProperty
import kotlin.random.Random

/**
 * Generates causally-plausible random event histories across several simulated devices.
 *
 * Simulation model:
 * - Each device has its own [HlcWatermarkAllocator] and per-device monotonic seq.
 * - Devices interleave in a random order. When a device produces an event it may first
 *   "receive" a random prefix of the other devices' already-produced events, merging their
 *   HLCs via [HlcWatermarkAllocator.observe], which keeps HLCs causally sensible.
 * - tag-remove only lists dots that already exist at generation time (observed-remove), so
 *   removes never reference future adds.
 *
 * The output is a single flat list of every event produced, in generation order. Tests then
 * shuffle it and/or partition it into segments to check order independence.
 */
class EventHistoryGen(
    seed: Long,
    deviceCount: Int,
    private val eventCount: Int,
) {
    private val rng = Random(seed)
    private val devices = (0 until deviceCount).map { "dev$it" }
    private val clocks = devices.associateWith { HlcWatermarkAllocator(VirtualClock(rng)) }
    private val seqs = HashMap<String, Long>().apply { devices.forEach { this[it] = 0L } }

    private val docs =
        (0 until (2 + rng.nextInt(3))).map { d ->
            // valid 64-hex doc id
            d.toString().padStart(64, ('a' + d).coerceAtMost('f'))
        }
    private val tagVocab = listOf("invoice", "tax", "receipt", "medical", "contract")

    private val produced = ArrayList<JournalEvent>()

    // live tag-add dots per (doc, tag) for building plausible removes
    private val liveDots = HashMap<Pair<String, String>, MutableList<Dot>>()

    fun generate(): List<JournalEvent> {
        repeat(eventCount) {
            val dev = devices[rng.nextInt(devices.size)]
            // maybe observe a few remote events first (causal receive)
            observeSomeRemote(dev)
            produceOne(dev)
        }
        return produced
    }

    private fun observeSomeRemote(dev: String) {
        if (produced.isEmpty()) return
        val clock = clocks.getValue(dev)
        val n = rng.nextInt(3)
        repeat(n) {
            val e = produced[rng.nextInt(produced.size)]
            if (e.dev != dev) clock.observe(e.hlc)
        }
    }

    private fun produceOne(dev: String) {
        val clock = clocks.getValue(dev)
        val seq = seqs.getValue(dev) + 1
        seqs[dev] = seq
        val hlc = clock.next(dev)
        val doc = docs[rng.nextInt(docs.size)]
        val choice = rng.nextInt(100)
        // A library-wide op is addressed to no document.
        val (target, op) =
            when {
                choice < 30 -> { // tag-add
                    val tag = tagVocab[rng.nextInt(tagVocab.size)]
                    liveDots.getOrPut(doc to tag) { ArrayList() }.add(Dot(dev, seq))
                    doc to JournalOp.TagAdd(tag)
                }

                choice < 45 -> { // tag-remove of existing dots
                    val tag = tagVocab[rng.nextInt(tagVocab.size)]
                    val existing = liveDots[doc to tag]?.toList().orEmpty()
                    val chosen = existing.filter { rng.nextBoolean() }
                    chosen.forEach { liveDots[doc to tag]?.remove(it) }
                    doc to JournalOp.TagRemove(tag, chosen)
                }

                choice < 60 -> { // prop-set
                    val key = SystemProperty.entries[rng.nextInt(SystemProperty.entries.size)]
                    val value = if (rng.nextInt(5) == 0) null else "v${rng.nextInt(1000)}"
                    doc to JournalOp.PropSet(key, value)
                }

                choice < 68 -> {
                    doc to JournalOp.DocTrash("/path/${rng.nextInt(100)}.pdf")
                }

                choice < 74 -> {
                    doc to JournalOp.DocRestore
                }

                choice < 76 -> {
                    doc to JournalOp.DocDelete
                }

                choice < 77 -> {
                    doc to JournalOp.DocUndelete
                }

                choice < 82 -> { // relink to another doc id
                    val relinked = docs[rng.nextInt(docs.size)]
                    doc to JournalOp.Relink(relinked)
                }

                choice < 88 -> { // library-wide rename
                    val from = tagVocab[rng.nextInt(tagVocab.size)]
                    val to = tagVocab[rng.nextInt(tagVocab.size)]
                    null to JournalOp.TagRename(from, to)
                }

                choice < 92 -> {
                    val from = tagVocab[rng.nextInt(tagVocab.size)]
                    val to = tagVocab[rng.nextInt(tagVocab.size)]
                    null to JournalOp.TagMerge(from, to)
                }

                choice < 96 -> {
                    // library-wide synced setting (reviewMode) LWW
                    val value = if (rng.nextBoolean()) "inbox-workflow" else "untagged-only"
                    null to JournalOp.SettingSet("reviewMode", value)
                }

                else -> {
                    val tag = tagVocab[rng.nextInt(tagVocab.size)]
                    null to JournalOp.TagStyle(tag, if (rng.nextBoolean()) "#abc" else null, null)
                }
            }
        addEvent(dev, seq, hlc, target, op)
    }

    private fun addEvent(
        dev: String,
        seq: Long,
        hlc: Hlc,
        doc: String?,
        op: JournalOp,
    ) {
        produced.add(JournalEvent(1, seq, hlc, dev, doc, op))
    }

    /** Wall clock that mostly advances but occasionally jumps, to exercise HLC merge/clamp. */
    private class VirtualClock(
        private val rng: Random,
    ) : EpochClock {
        private var now = 1_700_000_000_000L

        override fun nowMillis(): Long {
            now += rng.nextInt(0, 5).toLong()
            if (rng.nextInt(20) == 0) now += rng.nextLong(1, 10_000)
            return now
        }
    }
}

/**
 * Assertions that two folded states are equivalent for every observable view. Kept as a
 * standalone helper so both the shuffle and compaction properties reuse it.
 */
object FoldedStateEquivalence {
    fun assertEquivalent(
        a: FoldedState,
        b: FoldedState,
        allDocs: Collection<String>,
        allTags: Collection<String>,
    ) {
        for (doc in allDocs) {
            require(a.effectiveTags(doc) == b.effectiveTags(doc)) {
                "tags differ for $doc: ${a.effectiveTags(doc)} vs ${b.effectiveTags(doc)}"
            }
            for (key in SystemProperty.entries) {
                require(a.effectiveProp(doc, key) == b.effectiveProp(doc, key)) { "$key differs for $doc" }
                require(a.propVersion(doc, key) == b.propVersion(doc, key)) { "$key version differs for $doc" }
            }
            require(a.isTrashed(doc) == b.isTrashed(doc)) { "trash differs for $doc" }
            require(a.isDeleted(doc) == b.isDeleted(doc)) { "delete differs for $doc" }
            require(a.trashOrigPath(doc) == b.trashOrigPath(doc)) { "trash path differs for $doc" }
            require(a.resolveDocId(doc) == b.resolveDocId(doc)) { "relink resolve differs for $doc" }
        }
        for (tag in allTags) {
            require(a.tagStyle(tag) == b.tagStyle(tag)) { "tag style differs for $tag" }
        }
        // Library-wide synced settings must also converge.
        require(a.effectiveSetting("reviewMode") == b.effectiveSetting("reviewMode")) {
            "reviewMode setting differs"
        }
    }
}
