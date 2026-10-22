package dev.npn.core.app

import dev.npn.core.fs.FsEvent
import dev.npn.core.fs.NpnPaths
import dev.npn.core.journal.JournalPath
import dev.npn.core.journal.JournalPaths
import dev.npn.core.ocr.OcrSidecarName
import dev.npn.core.util.debounced
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
    /**
     * Nothing to do: this device's own write echoing back, a journal object still being staged or
     * transferred by a sync tool, or a name the library cannot address ([NpnPaths.isSafe]), which a
     * rescan passes over too.
     */
    data object Ignore : WatchAction

    /**
     * One published journal object, ingested alone and at once: cheap, so it needs no settling. An
     * OCR-engine pin it carries is re-selected by the [ReconcileOcr] that [settled] holds for it, so
     * a burst of objects costs one pass over the sidecars rather than one each.
     */
    data class IngestObject(
        val relPath: String,
    ) : WatchAction

    /**
     * Work that waits until the folder has gone quiet, so a burst of changes costs one pass. Each
     * pass does what every pass of a lower [rank] does, and more.
     */
    sealed interface Settled : WatchAction {
        /** Where this pass stands: [ReconcileOcr], then [ReconcileJournal], then [Rescan]. */
        val rank: Int

        /** One pass for a burst holding this and [next]: whichever of the two covers the other. */
        fun merge(next: Settled): Settled = if (next.rank > rank) next else this
    }

    /**
     * A journal change the single-object path cannot take: a snapshot (it may supersede objects
     * already applied, and carries many writers' events), or an unknown file.
     * Refolds the journal, then re-selects OCR.
     */
    data object ReconcileJournal : Settled {
        override val rank = 1
    }

    /** Another device's OCR sidecar, or a journal object's pin: re-select the version indexed for search. */
    data object ReconcileOcr : Settled {
        override val rank = 0
    }

    /**
     * Anything else, a PDF above all, or a watcher that lost track: rescan the library, which
     * reconciles the journal and re-selects OCR on the way.
     */
    data object Rescan : Settled {
        override val rank = 2
    }
}

/** What [event] calls for on the device [deviceId]; see [WatchAction]. */
internal fun watchAction(
    event: FsEvent,
    deviceId: String,
): WatchAction {
    val rel = event.relPath ?: return WatchAction.Rescan
    if (!NpnPaths.isSafe(rel) || OcrSidecarName.isOwnWrite(rel, deviceId)) return WatchAction.Ignore
    return when (JournalPaths.classify(rel)) {
        is JournalPath.ObjectV2 -> {
            WatchAction.IngestObject(rel)
        }

        JournalPath.Staging, JournalPath.SyncTemp -> {
            WatchAction.Ignore
        }

        is JournalPath.SnapshotV2,
        is JournalPath.UnsupportedObjectStore,
        -> {
            WatchAction.ReconcileJournal
        }

        JournalPath.Unknown -> {
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
 * [timeSource]), each burst merged into one ([WatchAction.Settled.merge]). A journal object also
 * holds a [WatchAction.ReconcileOcr], for the pin it may carry. Never holds the watcher
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
        collect { action ->
            when (action) {
                is WatchAction.Settled -> {
                    held.send(action)
                }

                is WatchAction.IngestObject -> {
                    send(action)
                    held.send(WatchAction.ReconcileOcr)
                }

                WatchAction.Ignore -> {
                    send(action)
                }
            }
        }
        held.close()
    }.buffer(Channel.UNLIMITED)
