package dev.npn.core.index

import dev.npn.core.db.NpnDatabase
import dev.npn.core.db.SourceStatus
import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.fs.NpnPaths
import dev.npn.core.fs.publishStagedOnce
import dev.npn.core.fs.sha256Bytes
import dev.npn.core.journal.JournalPaths
import dev.npn.core.util.EpochClock
import dev.npn.core.util.IdSource
import dev.npn.core.util.NpnJson
import dev.npn.core.util.runCatchingCancellable
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException

/**
 * One device's acknowledgment record, `.npn/acks/<device>/<n>.json`, write-once: what the device
 * published since its previous record, and how far it had applied every other device's.
 */
@Serializable
internal data class AckRecord(
    val v: Int = 1,
    /** The journal device id ([DeviceIdentity]) whose record this is. */
    val device: String,
    /** Its place in the device's chain, from 1. */
    val n: Long,
    /** The SHA-256 of record `n - 1`'s bytes; null for the first. */
    val prev: String? = null,
    /** When it was written, by the writing device's clock. */
    val atMillis: Long,
    /** The journal objects, by hash, this device published since its previous record. */
    val authored: List<String> = emptyList(),
    /** For each other device, the last of its records whose whole chain this device had applied. */
    val seen: Map<String, Long> = emptyMap(),
    /** What this build reads and writes, for a format another build would refuse ([AckRecords.CAPS]). */
    val caps: List<String> = emptyList(),
)

/** How far this device has applied another's: its record [n], written at [atMillis], and every one before it. */
internal data class Coverage(
    val n: Long,
    val atMillis: Long,
)

/** Another device as the acknowledgment records show it. */
internal data class AckedDevice(
    val device: String,
    /** The last of its records whose whole chain this device applied, or null for none yet. */
    val covered: Coverage?,
    /** When it wrote its latest record, linked or not: how long it has been silent. */
    val lastHeardMillis: Long,
    /** What its latest record says its build reads and writes. */
    val caps: List<String>,
)

/**
 * The acknowledgment records of this library's devices: write-once objects under
 * [NpnPaths.ACKS_DIR] by which each device vouches for exactly the journal objects it published
 * ([AuthoredObjects]), and says how far it had applied every other device's. A device holding
 * another's record has applied everything that device published before it once it has applied every
 * object its chain lists: the causal fact the trash purge waits for ([TrashPurge]), where it used
 * to go by its own view alone.
 *
 * Builds from before them publish none, and their devices are unknown here: what waits for every
 * device's acknowledgment waits only for the devices that write one.
 *
 * Not safe for concurrent calls: the session runs [publish] under the writer lock.
 */
internal class AckRecords(
    private val fs: LibraryFileSystem,
    private val db: NpnDatabase,
    private val authored: AuthoredObjects,
    private val clock: EpochClock,
    /** This installation's journal device id, the chain it writes. */
    private val deviceId: String,
    private val installationId: String,
    private val ids: IdSource = IdSource.Random,
) {
    /** Records read so far, by path: each is write-once, so it is read once once it parses. */
    private val read = HashMap<String, Pair<AckRecord, String>>()

    /**
     * Write this device's next record: the first; else, a day or more after its last, when it
     * published something since or has applied more of another device's chain; else a week after
     * its last, whatever changed, so the purge, which waits for a record from after a trash's
     * retention, never waits on a device that is merely quiet. It vouches for the objects
     * [AuthoredObjects] noted that are in the folder, and then forgets them with those that never
     * were published. Under the writer lock, so no transaction is between noting its object and
     * publishing it. Returns the record written, or null for none.
     *
     * Records come at most daily: every device's record changes what every other has applied, and
     * a record for each such change would set the devices answering one another for good.
     */
    suspend fun publish(): AckRecord? {
        val chains = chains()
        // Numbered from the files there, not from what parsed: a record of its own that cannot be read
        // just now (a read failure) is still there, and a new one under its name would be refused.
        val ownTop =
            fs
                .listAllUnder("${NpnPaths.ACKS_DIR}/$deviceId")
                .filter { !it.isDirectory && it.name.endsWith(JSON) }
                .mapNotNull { it.name.removeSuffix(JSON).toLongOrNull() }
                .maxOrNull()
        val last = ownTop?.let { n -> record(path(deviceId, n)) ?: return null }
        val pending = authored.pending()
        val (published, never) = pending.partition { fs.exists(JournalPaths.objectPath(it)) }
        val applied = appliedObjects()
        val seen =
            chains
                .filterKeys { it != deviceId }
                .mapNotNull { (device, chain) -> covered(chain, applied)?.let { device to it.n } }
                .toMap()
        val now = clock.nowMillis()
        val changed = published.isNotEmpty() || seen != last?.first?.seen.orEmpty() || last?.first?.caps != CAPS
        val since = last?.first?.atMillis?.let { now - it }
        val due = since == null || since >= HEARTBEAT_MILLIS || (changed && since >= MIN_INTERVAL_MILLIS)
        if (!due) {
            if (never.isNotEmpty()) authored.forget(never)
            return null
        }
        val record =
            AckRecord(
                device = deviceId,
                n = (ownTop ?: 0L) + 1,
                prev = last?.second,
                atMillis = now,
                authored = published,
                seen = seen,
                caps = CAPS,
            )
        val bytes = NpnJson.Persisted.encodeToString(AckRecord.serializer(), record).encodeToByteArray()
        val path = path(deviceId, record.n)
        val staging = "${NpnPaths.parentDir(path)}/${NpnPaths.Temp.markerStagingName(installationId, ids.next())}"
        fs.publishStagedOnce(staging, path, sha256Bytes(bytes)) { fs.writeBytesOnce(staging, bytes) }
        authored.forget(pending)
        return record
    }

    /** When this device wrote its latest record, or null for none yet. */
    suspend fun lastWrittenMillis(): Long? = chains(includeUnlinked = true)[deviceId]?.last()?.first?.atMillis

    /**
     * Every other device an installation marker names ([NpnPaths.DEVICES_DIR],
     * `<deviceId>/<installationId>.json`): every device that ever opened the library with a build
     * that marks it, which is every build that may read or write it, records or not.
     */
    suspend fun markedDevices(): Set<String> =
        fs
            .listAllUnder(NpnPaths.DEVICES_DIR)
            .filter { !it.isDirectory && it.name.endsWith(JSON) && NpnPaths.Temp.kindOf(it.relPath) == null }
            .mapNotNull { meta ->
                meta.relPath
                    .removePrefix("${NpnPaths.DEVICES_DIR}/")
                    .split('/')
                    .takeIf { it.size == 2 }
                    ?.first()
            }.filterTo(HashSet()) { it != deviceId }

    /** Every other device that writes records, and how far this device has applied each. */
    suspend fun devices(): List<AckedDevice> {
        val applied = appliedObjects()
        return chains(includeUnlinked = true)
            .filterKeys { it != deviceId }
            .map { (device, chain) ->
                val latest = chain.last().first
                AckedDevice(device, covered(linked(chain), applied), latest.atMillis, latest.caps)
            }
    }

    /**
     * The last record of [chain] (linked, from 1) such that every object it and the records before it
     * vouch for is applied here ([applied]); null for none.
     */
    private fun covered(
        chain: List<Pair<AckRecord, String>>,
        applied: Set<String>,
    ): Coverage? =
        chain
            .takeWhile { (record, _) -> record.authored.all { JournalPaths.objectPath(it) in applied } }
            .lastOrNull()
            ?.first
            ?.let { Coverage(it.n, it.atMillis) }

    /** The journal objects this device has applied, by path. */
    private suspend fun appliedObjects(): Set<String> =
        db
            .metadataProjectionDao()
            .allSources()
            .filter { it.status == SourceStatus.APPLIED }
            .mapTo(HashSet()) { it.path }

    /**
     * Each device's records, by device, in order, each with the SHA-256 of its bytes: only the
     * linked prefix (record 1, then each whose `prev` is the one before), unless [includeUnlinked].
     */
    private suspend fun chains(includeUnlinked: Boolean = false): Map<String, List<Pair<AckRecord, String>>> {
        val listed =
            fs
                .listAllUnder(NpnPaths.ACKS_DIR)
                .filter { !it.isDirectory && NpnPaths.Temp.kindOf(it.relPath) == null && it.name.endsWith(JSON) }
        val records =
            listed.mapNotNull { meta ->
                record(meta.relPath)?.takeIf { (record, _) ->
                    meta.relPath == path(record.device, record.n)
                }
            }
        return records
            .groupBy { it.first.device }
            .mapValues { (_, chain) ->
                val sorted = chain.sortedBy { it.first.n }
                if (includeUnlinked) sorted else linked(sorted)
            }.filterValues { it.isNotEmpty() }
    }

    /** The linked prefix of [sorted]: record 1, then each whose `prev` is the hash of the one before. */
    private fun linked(sorted: List<Pair<AckRecord, String>>): List<Pair<AckRecord, String>> {
        val out = ArrayList<Pair<AckRecord, String>>()
        for (entry in sorted) {
            val (record, _) = entry
            val expected = out.lastOrNull()
            val links =
                if (expected == null) {
                    record.n == 1L && record.prev == null
                } else {
                    record.n == expected.first.n + 1 && record.prev == expected.second
                }
            if (!links) break
            out += entry
        }
        return out
    }

    /** The record at [path] with the hash of its bytes, read once it parses; null while it cannot be read or parsed. */
    private suspend fun record(path: String): Pair<AckRecord, String>? =
        read[path] ?: parse(path)?.also { read[path] = it }

    /** The record at [path] with the hash of its bytes, or null for one that cannot be read or parsed (yet). */
    private suspend fun parse(path: String): Pair<AckRecord, String>? {
        val bytes = runCatchingCancellable { fs.readBytes(path) }.getOrNull() ?: return null
        return try {
            NpnJson.Persisted.decodeFromString(AckRecord.serializer(), bytes.decodeToString()) to sha256Bytes(bytes)
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    companion object {
        /** This build writes acknowledgment records ([AckRecords]). */
        const val ACKS_V1 = "acks-v1"

        /** This build reads, and once every device does writes, the keep store ([dev.npn.core.fs.KeepStore]). */
        const val KEEP_V1 = "keep-v1"

        /** What this build reads and writes, as its records say. */
        val CAPS = listOf(ACKS_V1, KEEP_V1)

        private const val JSON = ".json"

        /** The least time between two records of one device. */
        const val MIN_INTERVAL_MILLIS = 24L * 60 * 60 * 1000

        /** The most time between two records of one device, whatever changed. */
        const val HEARTBEAT_MILLIS = 7 * MIN_INTERVAL_MILLIS

        /** Where [device]'s record [n] is. */
        fun path(
            device: String,
            n: Long,
        ): String = "${NpnPaths.ACKS_DIR}/$device/$n$JSON"
    }
}
