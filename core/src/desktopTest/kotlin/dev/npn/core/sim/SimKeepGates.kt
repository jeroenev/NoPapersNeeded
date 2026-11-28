package dev.npn.core.sim

import dev.npn.core.fs.NpnPaths

/**
 * After a full sync every replica's keep gate ([dev.npn.core.app.KeepGate]) waits only for a device
 * that published no acknowledgment record in any of [folders] (a session cut short before its
 * first, whose marker alone tells of it): every replica runs this build, which lists `keep-v1`, and
 * the sync carries every device's records. A gate held shut by a device that did write records is
 * one the sync failed to carry them to (cloud sync once uploaded none, and the gate never opened in
 * a cloud-synced library). Returns the replica and why, or null.
 */
internal fun keepGatesWaitingOnRecords(
    world: SimWorld,
    folders: List<InMemoryLibraryFileSystem>,
    replicas: List<Pair<String, SimReplica>>,
): Pair<String, String>? {
    val recorded =
        folders.flatMapTo(HashSet()) { fs ->
            fs
                .allFiles()
                .keys
                .filter { it.startsWith("${NpnPaths.ACKS_DIR}/") }
                .map { it.split('/')[2] }
        }
    return replicas.firstNotNullOfOrNull { (name, replica) ->
        val session = replica.session ?: return@firstNotNullOfOrNull null
        world.read("$name's keep gate") { session.keepGate.refresh() }
        session.keepGate.waiting.value.waiting
            .intersect(recorded)
            .takeIf { it.isNotEmpty() }
            ?.let { waiting ->
                name to
                    "$name's keep gate still waits after a full sync for devices that published " +
                    "acknowledgment records: ${waiting.sorted().joinToString { it.take(ID_CHARS) }}"
            }
    }
}

private const val ID_CHARS = 10
