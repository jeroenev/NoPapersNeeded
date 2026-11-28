package dev.npn.core.sim

import dev.npn.core.fs.NpnPaths
import dev.npn.core.fs.sha256Bytes
import dev.npn.core.journal.JournalPath
import dev.npn.core.journal.JournalPaths

/** Journal objects and snapshots, under their canonical names, whose name is not the hash of their bytes. */
internal fun InMemoryLibraryFileSystem.misnamedObjects(): List<String> =
    allFiles().filterKeys { it.startsWith("${NpnPaths.JOURNAL_DIR}/") }.mapNotNull { (path, bytes) ->
        val expected =
            when (val kind = JournalPaths.classify(path)) {
                is JournalPath.ObjectV2 -> kind.expectedHash
                is JournalPath.SnapshotV2 -> kind.expectedHash
                else -> null
            }
        path.takeIf {
            expected != null && !isBeingWritten(path) && NpnPaths.fileName(path) == "$expected.jsonl" &&
                sha256Bytes(bytes) != expected
        }
    }
