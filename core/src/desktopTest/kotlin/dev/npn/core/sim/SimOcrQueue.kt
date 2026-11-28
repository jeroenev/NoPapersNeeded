package dev.npn.core.sim

import dev.npn.core.db.DeviceDatabase
import dev.npn.core.db.NpnDatabase
import dev.npn.core.ocr.OcrState

/**
 * At rest, every live document the index has as waiting for OCR is on this device's queue: the row
 * says a run is owed, and only the queue, in `device.db`, owes it. The two are separate databases,
 * so a process cut short between their writes (the drain dropping a hidden document's row, say)
 * leaves one without the other; the rescan's reconcile must mend it, or the run is never made and
 * only a rebuild, queueing every document afresh, runs it (and the rules its text sets off). Returns
 * why, or null.
 */
internal suspend fun pendingOcrNotQueued(
    index: NpnDatabase,
    device: DeviceDatabase,
): String? {
    val pending =
        index
            .rows(
                "SELECT docId FROM documents WHERE ocrState = '${OcrState.PENDING.column}' " +
                    "AND trashed = 0 AND deleted = 0 AND missing = 0",
            ).mapNotNull { it[0] }
    if (pending.isEmpty()) return null
    val queued = device.rows("SELECT docId FROM ocr_queue").mapNotNullTo(HashSet()) { it[0] }
    val unqueued = pending.filter { it !in queued }
    return if (unqueued.isEmpty()) {
        null
    } else {
        "live documents wait for OCR with no run queued for them: ${unqueued.joinToString { it.take(ID_PREFIX) }}"
    }
}

private const val ID_PREFIX = 10
