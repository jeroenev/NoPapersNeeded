package dev.npn.core.app

import dev.npn.core.fs.FsEvent
import dev.npn.core.fs.NpnPaths
import dev.npn.core.fs.debounced
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlin.time.TimeSource

/**
 * What the session does about one change its library watcher reported. [watchAction] decides it
 * without side effects; the session's watch loop acts on it in one place.
 */
internal sealed interface WatchAction {
    /** Nothing to do: this device's own write echoing back, or a journal write still being staged. */
    data object Ignore : WatchAction

    /**
     * One published journal object, ingested alone and at once: cheap, so it needs no settling,
     * and an OCR-engine pin it carries is re-selected right after.
     */
    data class IngestObject(
        val relPath: String,
    ) : WatchAction

    /** Work that waits until the folder has gone quiet, so a burst of changes costs one pass. */
    sealed interface Settled : WatchAction {
        /** One pass for a burst holding this and [next]: the same one, or a rescan, which covers every pass. */
        fun merge(next: Settled): Settled = if (next == this) this else Rescan
    }

    /**
     * A journal change the single-object path cannot take: a snapshot (it may supersede objects
     * already applied, and carries many writers' events), a legacy segment, or an unknown file.
     * Refolds the journal, then re-selects OCR.
     */
    data object ReconcileJournal : Settled

    /** Another device's OCR sidecar: re-select the version indexed for search. */
    data object ReconcileOcr : Settled

    /** Anything else, a PDF above all, or a watcher that lost track: rescan the library. */
    data object Rescan : Settled
}

/** What [event] calls for on the device [deviceId]; see [WatchAction]. */
internal fun watchAction(
    event: FsEvent,
    deviceId: String,
): WatchAction {
    val rel = event.relPath ?: return WatchAction.Rescan
    if (NpnPaths.isOwnDeviceWrite(rel, deviceId)) return WatchAction.Ignore
    return when (NpnPaths.journalPath(rel)) {
        is NpnPaths.JournalPath.ObjectV2 -> {
            WatchAction.IngestObject(rel)
        }

        NpnPaths.JournalPath.Staging -> {
            WatchAction.Ignore
        }

        is NpnPaths.JournalPath.SnapshotV2,
        is NpnPaths.JournalPath.Legacy,
        is NpnPaths.JournalPath.UnsupportedObjectStore,
        -> {
            WatchAction.ReconcileJournal
        }

        NpnPaths.JournalPath.Unknown -> {
            when {
                rel == NpnPaths.JOURNAL_DIR || rel.startsWith("${NpnPaths.JOURNAL_DIR}/") -> {
                    WatchAction.ReconcileJournal
                }

                rel.startsWith("${NpnPaths.OCR_DIR}/") -> {
                    WatchAction.ReconcileOcr
                }

                else -> {
                    WatchAction.Rescan
                }
            }
        }
    }
}

/**
 * Pass the actions that need no settling straight through, and hold [WatchAction.Settled] ones
 * until the folder has been quiet for [quietMillis] (or [maxWaitMillis] of continuous churn, on
 * [timeSource]), each burst merged into one ([WatchAction.Settled.merge]). Never holds the watcher
 * up: what the collector has not taken yet waits in an unbounded buffer, as the watcher's own
 * events do.
 */
internal fun Flow<WatchAction>.settled(
    quietMillis: Long = 500L,
    maxWaitMillis: Long = 5_000L,
    timeSource: TimeSource = TimeSource.Monotonic,
): Flow<WatchAction> =
    channelFlow {
        val held = Channel<WatchAction.Settled>(Channel.UNLIMITED)
        launch {
            held
                .receiveAsFlow()
                .debounced(quietMillis, maxWaitMillis, timeSource) { burst, next -> burst.merge(next) }
                .collect { send(it) }
        }
        collect { action -> if (action is WatchAction.Settled) held.send(action) else send(action) }
        held.close()
    }.buffer(Channel.UNLIMITED)
