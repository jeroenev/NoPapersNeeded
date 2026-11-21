package dev.npn.core.index

import dev.npn.core.db.MetadataSourceEntity
import dev.npn.core.db.SourceStatus
import dev.npn.core.fs.sha256Bytes
import dev.npn.core.fs.toHexLower

/**
 * The fingerprint of the applied source set that a ready projection records, so the next open can
 * tell whether the journal moved ([MetadataAccumulator.reconcileOnOpen]). Kept up to date per
 * changed source rather than recomputed from every row: the table holds one row per journal object
 * ever written and never shrinks, while each commit changes one.
 *
 * The digest is the XOR of SHA-256(path, NUL, fingerprint) over the applied sources, so it does not
 * depend on their order, and a source's part can be taken out and put back in. Paths are unique, so
 * no two parts cancel. Any change to a source's status or fingerprint moves it, as the sorted digest
 * it replaced did.
 *
 * A change that never lands (the transaction that wrote it rolled back) cannot leave the digest
 * wrong: what a path contributed is captured as it first changes ([changing]), and what it
 * contributes is read back from the table when the fingerprint is asked for ([fingerprint]), so a
 * change that did not land cancels out. Until the first [fingerprint], and after [forget], the
 * digest is unknown and the next one reads every row.
 */
internal class AppliedSourceSet {
    private var digest: ByteArray? = null

    /** Path -> its part in [digest] before it first changed since [digest] was last brought up to date. */
    private val before = HashMap<String, ByteArray>()

    /** [path] is about to change; [current] is its row as recorded now, null for none. */
    fun changing(
        path: String,
        current: MetadataSourceEntity?,
    ) {
        if (digest != null && path !in before) before[path] = part(current)
    }

    /** [changing], reading the row through [current] only when the change must be captured. */
    suspend fun changing(
        path: String,
        current: suspend () -> MetadataSourceEntity?,
    ) {
        if (digest != null && path !in before) before[path] = part(current())
    }

    /** Every source is about to go, or may have: the next [fingerprint] reads them all again. */
    fun forget() {
        digest = null
        before.clear()
    }

    /**
     * The fingerprint of the applied sources as the table holds them now: from [all] of them when
     * the digest is unknown, else from the rows [at] the paths changed since.
     */
    suspend fun fingerprint(
        all: suspend () -> List<MetadataSourceEntity>,
        at: suspend (List<String>) -> List<MetadataSourceEntity>,
    ): String {
        val known = digest
        val value =
            if (known == null) {
                ByteArray(DIGEST_BYTES).also { sum -> all().forEach { xorInto(sum, part(it)) } }
            } else {
                val now = if (before.isEmpty()) emptyMap() else at(before.keys.toList()).associateBy { it.path }
                for ((path, was) in before) {
                    xorInto(known, was)
                    xorInto(known, part(now[path]))
                }
                known
            }
        digest = value
        before.clear()
        return value.toHexLower()
    }

    private companion object {
        const val DIGEST_BYTES = 32
        const val HEX_RADIX = 16

        /** What [source] adds to the digest: nothing unless it is applied. */
        fun part(source: MetadataSourceEntity?): ByteArray {
            if (source == null || source.status != SourceStatus.APPLIED) return ByteArray(DIGEST_BYTES)
            val hex = sha256Bytes("${source.path}\u0000${source.fingerprint.value}".encodeToByteArray())
            return ByteArray(DIGEST_BYTES) { hex.substring(it * 2, it * 2 + 2).toInt(HEX_RADIX).toByte() }
        }

        fun xorInto(
            sum: ByteArray,
            part: ByteArray,
        ) {
            for (i in sum.indices) sum[i] = (sum[i].toInt() xor part[i].toInt()).toByte()
        }
    }
}
