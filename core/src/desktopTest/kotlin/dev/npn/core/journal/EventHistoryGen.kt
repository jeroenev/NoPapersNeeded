package dev.npn.core.journal

import dev.npn.core.model.DocProperty
import kotlin.random.Random

/**
 * Generates causally-plausible random event histories across several simulated devices.
 *
 * Simulation model:
 * - Each device has its own [HlcClock] and per-device monotonic seq.
 * - Devices interleave in a random order. When a device produces an event it may first
 *   "receive" a random prefix of the other devices' already-produced events, merging their
 *   HLCs via [HlcClock.observe], which keeps HLCs causally sensible.
 * - tag-remove only lists dots that already exist at generation time (observed-remove), so
 *   removes never reference future adds.
 *
 * The output is a single flat list of every event produced, in generation order. Tests then
 * shuffle it and/or partition it into segments to check order independence.
 */
class EventHistoryGen(
    private val seed: Long,
    private val deviceCount: Int,
    private val eventCount: Int,
) {
    private val rng = Random(seed)
    private val devices = (0 until deviceCount).map { "dev$it" }
    private val clocks = devices.associateWith { HlcClock(it, VirtualClock(rng)) }
    private val seqs = HashMap<String, Long>().apply { devices.forEach { this[it] = 0L } }

    // pool of doc ids
    private val docs = (0 until (2 + rng.nextInt(3))).map { d ->
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
        val hlc = clock.next()
        val doc = docs[rng.nextInt(docs.size)]
        val choice = rng.nextInt(100)
        val op: JournalOp = when {
            choice < 30 -> { // tag-add
                val tag = tagVocab[rng.nextInt(tagVocab.size)]
                liveDots.getOrPut(doc to tag) { ArrayList() }.add(Dot(dev, seq))
                JournalOp.TagAdd(tag)
            }
            choice < 45 -> { // tag-remove of existing dots
                val tag = tagVocab[rng.nextInt(tagVocab.size)]
                val existing = liveDots[doc to tag]?.toList().orEmpty()
                val chosen = existing.filter { rng.nextBoolean() }
                chosen.forEach { liveDots[doc to tag]?.remove(it) }
                JournalOp.TagRemove(tag, chosen)
            }
            choice < 60 -> { // prop-set
                val key = DocProperty.entries[rng.nextInt(DocProperty.entries.size)]
                val value = if (rng.nextInt(5) == 0) null else "v${rng.nextInt(1000)}"
                JournalOp.PropSet(key, value)
            }
            choice < 68 -> JournalOp.DocTrash("/path/${rng.nextInt(100)}.pdf")
            choice < 74 -> JournalOp.DocRestore
            choice < 77 -> JournalOp.DocDelete
            choice < 82 -> { // relink to another doc id
                val target = docs[rng.nextInt(docs.size)]
                return@produceOne addEvent(dev, seq, hlc, doc, JournalOp.Relink(target))
            }
            choice < 88 -> { // library-wide rename
                val from = tagVocab[rng.nextInt(tagVocab.size)]
                val to = tagVocab[rng.nextInt(tagVocab.size)]
                return@produceOne addEvent(dev, seq, hlc, null, JournalOp.TagRename(from, to))
            }
            choice < 92 -> {
                val from = tagVocab[rng.nextInt(tagVocab.size)]
                val to = tagVocab[rng.nextInt(tagVocab.size)]
                return@produceOne addEvent(dev, seq, hlc, null, JournalOp.TagMerge(from, to))
            }
            choice < 96 -> {
                // library-wide synced setting (reviewMode) LWW
                val value = if (rng.nextBoolean()) "inbox-workflow" else "untagged-only"
                return@produceOne addEvent(dev, seq, hlc, null, JournalOp.SettingSet("reviewMode", value))
            }
            else -> {
                val tag = tagVocab[rng.nextInt(tagVocab.size)]
                return@produceOne addEvent(
                    dev, seq, hlc, null,
                    JournalOp.TagStyle(tag, if (rng.nextBoolean()) "#abc" else null, null),
                )
            }
        }
        addEvent(dev, seq, hlc, doc, op)
    }

    private fun addEvent(dev: String, seq: Long, hlc: Hlc, doc: String?, op: JournalOp) {
        produced.add(JournalEvent(1, seq, hlc, dev, doc, op))
    }

    /** Wall clock that mostly advances but occasionally jumps, to exercise HLC merge/clamp. */
    private class VirtualClock(private val rng: Random) : EpochClock {
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
    fun assertEquivalent(a: FoldedState, b: FoldedState, allDocs: Collection<String>, allTags: Collection<String>) {
        for (doc in allDocs) {
            require(a.effectiveTags(doc) == b.effectiveTags(doc)) {
                "tags differ for $doc: ${a.effectiveTags(doc)} vs ${b.effectiveTags(doc)}"
            }
            require(a.allProps(doc) == b.allProps(doc)) {
                "props differ for $doc: ${a.allProps(doc)} vs ${b.allProps(doc)}"
            }
            require(a.isTrashed(doc) == b.isTrashed(doc)) { "trash differs for $doc" }
            require(a.isDeleted(doc) == b.isDeleted(doc)) { "delete differs for $doc" }
            require(a.trashOrigPath(doc) == b.trashOrigPath(doc)) { "trash path differs for $doc" }
            require(a.resolveDocId(doc) == b.resolveDocId(doc)) { "relink resolve differs for $doc" }
        }
        require(a.relinkLosers().toSet() == b.relinkLosers().toSet()) { "relink losers differ" }
        for (tag in allTags) {
            require(a.tagStyle(tag) == b.tagStyle(tag)) { "tag style differs for $tag" }
        }
        // Library-wide synced settings must also converge.
        require(a.effectiveSetting("reviewMode") == b.effectiveSetting("reviewMode")) {
            "reviewMode setting differs"
        }
    }
}
