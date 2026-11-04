package dev.npn.core.sync

import dev.npn.core.util.runCatchingCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/**
 * How many transfers of each kind a pass keeps in flight against one remote ([RemoteStore.limits]).
 * One at a time, every file costs a full round trip to the provider, and Dropbox's upload batching
 * (rclone's default `sync` batch mode) then commits a batch of one after its idle timeout. Several
 * at once share that cost, up to where the provider starts throttling.
 */
data class TransferLimits(
    /** Journal objects, OCR sidecars, device markers: a few KB each, so all latency. */
    val metadataDownloads: Int,
    val metadataUploads: Int,
    /** PDFs, both ways: each is staged whole on the device while it is in flight. */
    val documents: Int,
) {
    companion object {
        /**
         * Measured against Dropbox from a phone (96 sidecars, 24 PDFs a run): sidecar downloads took
         * 10.4 s at 8 at once, 5.6 s at 16 and 17.8 s at 24, where Dropbox starts throttling (files
         * stalled about 11 s); sidecar uploads 34.6 s at 8 and 12.2 s at 32, within half a second of
         * one rclone folder copy, with rclone's `--transfers` at 32 so a commit takes them all; PDFs
         * halved from 4 at once to 8 both ways.
         */
        val DROPBOX = TransferLimits(metadataDownloads = 16, metadataUploads = 32, documents = 8)

        /**
         * Everything else, unmeasured: rclone's own default of 4 for PDFs, and 8 for metadata, as
         * Google Drive takes about 2 new files a second whatever the concurrency and OneDrive
         * throttles file creation per account (its other clients included).
         */
        val DEFAULT = TransferLimits(metadataDownloads = 8, metadataUploads = 8, documents = 4)

        /** The most of any kind: rclone runs with `--transfers` at it, which sizes a Dropbox commit. */
        val MOST: Int = maxOf(DROPBOX.metadataUploads, DEFAULT.metadataUploads)
    }
}

/**
 * Run [transfer] for each of [items], at most [concurrency] at once, and [settle] each outcome
 * (the transfer's result, or what it threw) one at a time, in the order they finish. The transfers
 * touch nothing a pass shares; everything that records (the report, the manifest, publishing into
 * the library) belongs in [settle], which never runs beside another. Cancellation is never an
 * outcome: it cancels every transfer still under way.
 */
internal suspend fun <T, R> transferEach(
    items: List<T>,
    concurrency: Int,
    transfer: suspend (T) -> R,
    settle: suspend (item: T, outcome: Result<R>) -> Unit,
) {
    if (items.isEmpty()) return
    val permits = Semaphore(concurrency)
    val settling = Mutex()
    coroutineScope {
        for (item in items) {
            launch {
                val outcome = permits.withPermit { runCatchingCancellable { transfer(item) } }
                settling.withLock { settle(item, outcome) }
            }
        }
    }
}
