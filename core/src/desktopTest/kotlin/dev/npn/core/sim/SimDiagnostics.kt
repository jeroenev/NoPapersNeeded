package dev.npn.core.sim

import dev.npn.core.db.NpnDatabase

/**
 * For a document two indexes show differently, which journal events one has folded and the other
 * not, with the source each came from and how the other index holds that source: what tells a
 * missed object from a fold that read it otherwise.
 */
internal suspend fun eventsDiffer(
    docId: String,
    live: NpnDatabase,
    rebuilt: NpnDatabase,
    fs: InMemoryLibraryFileSystem,
    /** The rebuild's copy of the folder, where what the rebuild's own session wrote is. */
    copy: InMemoryLibraryFileSystem,
): String {
    require(docId.all { it.isLetterOrDigit() }) { "Not a document id: $docId" }

    suspend fun events(db: NpnDatabase) =
        db
            .rows(
                "SELECT e.deviceId, e.sequence, e.operation, e.canonicalJson, s.sourcePath FROM metadata_events e " +
                    "LEFT JOIN metadata_source_events s " +
                    "ON s.eventDeviceId = e.deviceId AND s.eventSequence = e.sequence " +
                    "WHERE e.documentId = '$docId'",
            ).associateBy({ "${it[0]}#${it[1]}" }, { "${it[2]} ${it[3]} from ${it[4]}" })

    suspend fun source(
        db: NpnDatabase,
        path: String,
    ) = db
        .rows("SELECT status, diagnostic FROM metadata_sources WHERE path = '${path.replace("'", "''")}'")
        .firstOrNull()
        ?.joinToString("/") ?: "no row"
    val a = events(live)
    val b = events(rebuilt)
    return buildString {
        (b.keys - a.keys).forEach { key ->
            val path = b.getValue(key).substringAfter(" from ")
            val origin =
                when {
                    path in copy.appPublished -> REBUILD_WROTE
                    path in fs.appPublished -> "published here"
                    else -> "arrived from outside"
                }
            appendLine(
                "    only the rebuild folded $key: ${b.getValue(key)} ($origin, mtime ${fs.mtimeAt(path)}; " +
                    "the live index's source row: ${source(live, path)})",
            )
        }
        (a.keys - b.keys).forEach { key ->
            val path = a.getValue(key).substringAfter(" from ")
            appendLine(
                "    only the live index folded $key: ${a.getValue(
                    key,
                )} (the rebuild's source row: ${source(rebuilt, path)})",
            )
        }
    }
}

/** What [eventsDiffer] says of an event the rebuild's own session wrote as it indexed the folder. */
internal const val REBUILD_WROTE = "written by the rebuild itself"

/** A document's lifecycle events (trash, restore, delete, undelete, relink) as [db] folded them, oldest first. */
internal suspend fun lifecycleEvents(
    docId: String,
    db: NpnDatabase,
): String {
    require(docId.all { it.isLetterOrDigit() }) { "Not a document id: $docId" }
    return db
        .rows(
            "SELECT hlcPhysical, hlcCounter, operation, canonicalJson FROM metadata_events " +
                "WHERE documentId = '$docId' " +
                "AND operation IN ('doc-trash', 'doc-restore', 'doc-delete', 'doc-undelete', 'relink') " +
                "ORDER BY hlcPhysical, hlcCounter",
        ).joinToString("") { "    ${it[0]}.${it[1]} ${it[2]} ${it[3]?.take(EVENT_CHARS)}\n" }
}

private const val EVENT_CHARS = 260
