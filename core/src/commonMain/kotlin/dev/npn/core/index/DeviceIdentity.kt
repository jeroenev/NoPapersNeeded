package dev.npn.core.index

import dev.npn.core.db.KeyValueDao
import dev.npn.core.journal.ForkCheck
import dev.npn.core.journal.LocalJournalState
import dev.npn.core.journal.SegmentWriter
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Manages this installation's `deviceId` (PLAN.md sec. 2, "Device identity").
 *
 * The id is an install-generated UUID stored in the local `kv` table (device-private, never in
 * the synced library). On startup the app must verify the newest segment in its own journal
 * directory is one it wrote; if the store contains an own segment the local state does not know
 * about, another installation shares this id (backup restore / clone) and we fork a fresh id to
 * preserve the single-writer invariant.
 */
@OptIn(ExperimentalUuidApi::class)
class DeviceIdentity(private val kv: KeyValueDao) {

    /** Get the stored device id, generating and persisting a fresh one on first use. */
    suspend fun getOrCreate(): String {
        kv.get(KEY_DEVICE_ID)?.let { return it }
        val fresh = Uuid.random().toString()
        kv.put(KEY_DEVICE_ID, fresh)
        return fresh
    }

    /**
     * Run the journal fork check for [writer]. If a fork is needed (a foreign own-device segment
     * appeared), generate and persist a fresh device id, reset [localState] so the new id starts
     * clean, and return it. Otherwise return the current id unchanged.
     *
     * [localState] is the same state the [writer] uses; it must be reset on fork so the new device
     * id does not inherit the old device's `lastSeq` or known-segment set (which belong to the id
     * that was cloned). The caller is expected to build a new [SegmentWriter] with the returned id
     * and this now-reset [localState] when the id differs.
     */
    suspend fun ensureSingleWriter(writer: SegmentWriter, localState: LocalJournalState): String {
        writer.recover()
        return when (writer.checkSingleWriter()) {
            is ForkCheck.Ok -> writer.deviceId
            is ForkCheck.ForkNeeded -> {
                val fresh = Uuid.random().toString()
                kv.put(KEY_DEVICE_ID, fresh)
                localState.reset()
                fresh
            }
        }
    }

    companion object {
        const val KEY_DEVICE_ID = "device.id"
    }
}
