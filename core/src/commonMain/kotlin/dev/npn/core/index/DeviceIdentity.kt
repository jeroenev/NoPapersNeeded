package dev.npn.core.index

import dev.npn.core.db.KeyValueDao
import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.fs.NpnPaths
import dev.npn.core.fs.VerifiedPublicationUnavailableException
import dev.npn.core.fs.publishStagedOnce
import dev.npn.core.fs.sha256Bytes
import dev.npn.core.util.IdSource
import dev.npn.core.util.NpnLog
import dev.npn.core.util.info
import dev.npn.core.util.runCatchingCancellable
import dev.npn.core.util.warn
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * This installation's `deviceId` for one library, and the check that no other installation uses it.
 *
 * The id is a UUID kept in the device database's `kv` table (device-private, never in the synced
 * library, kept when the index is rebuilt). It names what this device writes under its own name:
 * its OCR sidecars, which the watcher also uses to skip its own writes. Journal transactions do not
 * carry it; each gets a fresh actor id.
 *
 * Two installations must never share it, and a restore or a copy of `device.db` (Android restores
 * its backup onto a new phone) carries it to another one. So the id is tied to the installation
 * ([installationId], which the app keeps out of every backup) in two places, and either one
 * disagreeing gives this installation a fresh id:
 * - `device.db` records which installation the id belongs to. A restored or copied `device.db`
 *   names another installation, so a reinstall or a new phone takes a fresh id at once, while
 *   keeping everything else `device.db` holds (queued AI edits, the OCR queue, chats).
 * - The library holds one write-once marker per installation that used the id,
 *   `.npn/devices/<deviceId>/<installationId>.json`. A marker from another installation means a
 *   second installation claims this id: a clone that predates the first check, or whose record
 *   came along with it. Both then move to fresh ids as each sees the other's marker.
 *
 * A fresh id costs nothing that matters: the old id's OCR sidecars read as another device's, which
 * the OCR pipeline adopts rather than runs again.
 *
 * Publishing the marker, and at every later open a throwaway object, is also where a library on a
 * filesystem that cannot publish without replacement is found out, a remount since the last open
 * included: [resolve] lets that [VerifiedPublicationUnavailableException] through so the library
 * refuses to open. Any other failure to publish is retried at the next open.
 */
internal class DeviceIdentity(
    private val kv: KeyValueDao,
    private val fs: LibraryFileSystem,
    private val installationId: String,
    private val log: NpnLog = NpnLog.None,
    private val ids: IdSource = IdSource.Random,
) {
    init {
        require(installationPattern.matches(installationId)) { "Unusable installation id: $installationId" }
    }

    /** The device id this installation uses for the library, minting a fresh one when it must. */
    suspend fun resolve(): String {
        val stored = kv.get(KEY_DEVICE_ID)
        val owner = kv.get(KEY_INSTALLATION)
        val id =
            when {
                stored == null -> {
                    mint()
                }

                owner != null && owner != installationId -> {
                    log.info("This device's state was restored from another installation; using a new device id")
                    mint()
                }

                claimedElsewhere(stored) -> {
                    log.info("Another installation uses this device id in the library; using a new device id")
                    mint()
                }

                else -> {
                    // A device.db from before installations were recorded: it is this one's.
                    if (owner == null) kv.put(KEY_INSTALLATION, installationId)
                    stored
                }
            }
        publishMarker(id)
        return id
    }

    private suspend fun mint(): String {
        val fresh = ids.next()
        kv.put(KEY_DEVICE_ID, fresh)
        kv.put(KEY_INSTALLATION, installationId)
        return fresh
    }

    /** Whether the library holds a marker for [deviceId] from an installation other than this one. */
    private suspend fun claimedElsewhere(deviceId: String): Boolean =
        fs
            .listAllUnder(markerDir(deviceId))
            .asSequence()
            .filter { !it.isDirectory }
            .mapNotNull { markerInstallation(it.name) }
            .any { it != installationId }

    /**
     * Publish this installation's marker for [deviceId] once, through the journal's own primitive,
     * and once it is there a throwaway object ([probePublication]): every open finds out whether the
     * storage can still publish without replacement. Only that refusal escapes; any other failure is
     * logged, and the next open tries again.
     */
    private suspend fun publishMarker(deviceId: String) {
        val path = markerPath(deviceId, installationId)
        runCatchingCancellable {
            if (fs.exists(path)) {
                probePublication()
            } else {
                val bytes = """{"v":1,"device":"$deviceId","installation":"$installationId"}""".encodeToByteArray()
                val staging = "${markerDir(deviceId)}/${NpnPaths.Temp.markerStagingName(installationId, nonce())}"
                publishOnce(staging, path, bytes)
            }
        }.onFailure { failure ->
            if (failure is VerifiedPublicationUnavailableException) throw failure
            log.warn("Publishing in the library at open failed; trying again at the next open", failure)
        }
    }

    /**
     * Publish a throwaway object under `.npn/tmp` and delete it, for storage that can lose its
     * primitive between opens ([LibraryFileSystem.publicationMayBeLost]): remounted without hard
     * links or an exclusive rename, it refuses the library here rather than at the first edit. Named
     * as an import's staging files are, so the temporary-file sweep takes what a crash leaves.
     */
    private suspend fun probePublication() {
        if (!fs.publicationMayBeLost) return
        val published = NpnPaths.Temp.importPath(nonce())
        try {
            publishOnce(NpnPaths.Temp.importPath(nonce()), published, PROBE_BYTES)
        } finally {
            withContext(NonCancellable) { runCatchingCancellable { fs.delete(published) } }
        }
    }

    /** Stage [bytes] at [staging] and publish them as [path] without replacement; the staging file goes either way. */
    private suspend fun publishOnce(
        staging: String,
        path: String,
        bytes: ByteArray,
    ) = fs.publishStagedOnce(staging, path, sha256Bytes(bytes)) { fs.writeBytesOnce(staging, bytes) }

    private fun nonce(): String = ids.next()

    companion object {
        const val KEY_DEVICE_ID = "device.id"

        /** The installation [KEY_DEVICE_ID] belongs to. */
        const val KEY_INSTALLATION = "device.installation"

        private const val MARKER_SUFFIX = ".json"

        /** What [probePublication] publishes; any bytes do. */
        private val PROBE_BYTES = "npn publication probe\n".encodeToByteArray()

        private val installationPattern = Regex("^[A-Za-z0-9_-]+$")

        private fun markerDir(deviceId: String): String = "${NpnPaths.DEVICES_DIR}/$deviceId"

        internal fun markerPath(
            deviceId: String,
            installationId: String,
        ): String = "${markerDir(deviceId)}/$installationId$MARKER_SUFFIX"

        /**
         * The installation a marker file name records, or null for anything else that can sit in a
         * marker directory: a staging file, a sync tool's conflict or temporary copy.
         */
        private fun markerInstallation(fileName: String): String? =
            fileName
                .takeIf { it.endsWith(MARKER_SUFFIX) && !NpnPaths.isSyncConflict(it) && !NpnPaths.isSyncTemp(it) }
                ?.removeSuffix(MARKER_SUFFIX)
                ?.takeIf(installationPattern::matches)
    }
}
