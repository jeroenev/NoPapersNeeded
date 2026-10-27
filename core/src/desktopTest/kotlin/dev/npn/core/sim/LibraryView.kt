package dev.npn.core.sim

import androidx.room3.RoomDatabase
import androidx.room3.useReaderConnection
import dev.npn.core.app.LibrarySessionImpl
import dev.npn.core.app.SavedView
import dev.npn.core.db.NpnDatabase
import dev.npn.core.model.ReviewMode
import dev.npn.core.rules.AutoMatchRule
import kotlinx.coroutines.flow.first

/** One document as the library shows it: where it is and the metadata it carries. */
data class DocView(
    val relPath: String,
    val tags: Set<String>,
    val fields: Map<String, String>,
    val reviewed: Boolean,
)

/** Where a document the index has a row for stands, when the library does not show it. */
enum class Hidden { TRASHED, DELETED, MISSING }

/**
 * What a library is, as a replica's index and session show it, in the terms the rebuild oracle
 * compares. Device-local state is left out on purpose: the device id, the OCR, enrichment and edit
 * queues, enrichment marks, thumbnails, OCR state and page counts (this device's work, rebuilt by
 * running it again), when a document was first seen (`addedAt`) and its stat.
 */
internal data class LibraryView(
    /** Live documents by id. */
    val live: Map<String, DocView>,
    /** Documents the index holds but does not show, by id. */
    val hidden: Map<String, Hidden>,
    /** Documents in the trash by id, each with the path it was trashed from as its path. */
    val trashed: Map<String, DocView>,
    /** Field definitions by key, as `label|type|showByDefault|aiEnabled|position|hint|perDocument`. */
    val fieldDefs: Map<String, String>,
    /** Tag styles: a tag's color and icon, for tags that have one. */
    val tagStyles: Map<String, String>,
    val reviewMode: ReviewMode,
    val storagePath: String,
    val trashRetentionDays: Int,
    val savedViews: List<SavedView>,
    val rules: List<AutoMatchRule>,
) {
    companion object {
        suspend fun read(
            session: LibrarySessionImpl,
            db: NpnDatabase,
        ): LibraryView {
            val docs =
                db.rows("SELECT docId, relPath, trashed, deleted, missing, reviewed, trashedOrigPath FROM documents")
            val tags = db.rows("SELECT docId, tag FROM document_tags").groupBy({ it[0]!! }, { it[1]!! })
            val fields =
                db.rows("SELECT docId, fieldKey, fieldValue FROM document_fields").groupBy({ it[0]!! }, {
                    it[1]!! to
                        it[2]!!
                })
            val live = HashMap<String, DocView>()
            val hidden = HashMap<String, Hidden>()
            val trashed = HashMap<String, DocView>()
            docs.forEach { row ->
                val docId = row[0]!!

                fun view(relPath: String) =
                    DocView(
                        relPath = relPath,
                        tags = tags[docId].orEmpty().toSortedSet(),
                        fields = fields[docId].orEmpty().toMap().toSortedMap(),
                        reviewed = row[5] == "1",
                    )
                when {
                    row[3] == "1" -> {
                        hidden[docId] = Hidden.DELETED
                    }

                    row[2] == "1" -> {
                        hidden[docId] = Hidden.TRASHED
                        trashed[docId] = view(row[6].orEmpty())
                    }

                    row[4] == "1" -> {
                        hidden[docId] = Hidden.MISSING
                    }

                    else -> {
                        live[docId] = view(row[1]!!)
                    }
                }
            }
            val defs =
                db
                    .rows(
                        "SELECT fieldKey, label, type, showByDefault, aiEnabled, position, hint, perDocument " +
                            "FROM field_defs",
                    ).associate { it[0]!! to it.drop(1).joinToString("|") }
                    .toSortedMap()
            val styles =
                db
                    .rows("SELECT name, color, icon FROM tags WHERE color IS NOT NULL OR icon IS NOT NULL")
                    .associate { it[0]!! to "${it[1]}|${it[2]}" }
                    .toSortedMap()
            val storage = session.storagePath.storagePath.first()
            return LibraryView(
                live = live.toSortedMap(),
                hidden = hidden.toSortedMap(),
                trashed = trashed.toSortedMap(),
                fieldDefs = defs,
                tagStyles = styles,
                reviewMode = session.reviewConfig.reviewMode.first(),
                storagePath = "${storage.template}|${storage.enabled}",
                trashRetentionDays = session.trashRetention.trashRetentionDays.first(),
                savedViews =
                    session.savedViews
                        .observeSavedViews()
                        .first()
                        .sortedBy { it.id },
                rules =
                    session.rules
                        .observeRules()
                        .first()
                        .sortedBy { it.id },
            )
        }
    }
}

/** Every row [sql] selects, each column as text (null for SQL NULL). For test reads only. */
internal suspend fun RoomDatabase.rows(sql: String): List<List<String?>> =
    useReaderConnection { connection ->
        connection.usePrepared(sql) { statement ->
            buildList {
                while (statement.step()) {
                    add(
                        (0 until statement.getColumnCount()).map {
                            if (statement.isNull(
                                    it,
                                )
                            ) {
                                null
                            } else {
                                statement.getText(it)
                            }
                        },
                    )
                }
            }
        }
    }

/**
 * How [rebuilt] (a fresh index over the same files) differs from [live], one line per difference;
 * empty when they agree. What may legitimately differ is left out by [LibraryView] itself, and
 * [hiddenOnlyLive] lets the caller pass over hidden rows a rebuild cannot know of (see its use).
 * Where several files hold one document's bytes, which of them is its path depends on the order the
 * library saw them in (the others are duplicates), so a live document compares by path only when
 * the two indexes picked paths that do not both hold its bytes.
 */
internal fun diff(
    live: LibraryView,
    rebuilt: LibraryView,
    hiddenOnlyLive: (String, Hidden) -> Boolean,
    /** Whether both paths hold the document's bytes: then either is a fair pick of its path. */
    bothHold: (docId: String, String, String) -> Boolean,
): List<String> {
    val out = ArrayList(liveDifferences(live, rebuilt, bothHold))
    (live.hidden.keys + rebuilt.hidden.keys).toSortedSet().forEach { id ->
        val a = live.hidden[id]
        val b = rebuilt.hidden[id]
        when {
            a == b || live.live.containsKey(id) || rebuilt.live.containsKey(id) -> Unit
            b == null && a != null && hiddenOnlyLive(id, a) -> Unit
            else -> out += "doc ${id.take(ID)} hidden state differs: live $a, rebuilt $b"
        }
    }
    // A document in the trash carries its metadata and the path it was trashed from in both.
    live.trashed.keys.intersect(rebuilt.trashed.keys).sorted().forEach { id ->
        val a = live.trashed.getValue(id)
        val b = rebuilt.trashed.getValue(id)
        if (a != b) out += "trashed doc ${id.take(ID)} differs:\n    live:    $a\n    rebuilt: $b"
    }

    fun <T> same(
        what: String,
        a: T,
        b: T,
    ) {
        if (a != b) out += "$what differs:\n    live:    $a\n    rebuilt: $b"
    }
    same("field definitions", live.fieldDefs, rebuilt.fieldDefs)
    // A live or trashed document keeps its tags' rows (TagDao.pruneUnreferenced), and so does a row
    // hidden as missing that only the live index remembers: the styles of the tags the first two use
    // compare.
    val inUse =
        listOf(live.live, live.trashed, rebuilt.live, rebuilt.trashed)
            .flatMap { it.values }
            .flatMap { it.tags }
            .toSet()
    same("tag styles", live.tagStyles.filterKeys(inUse::contains), rebuilt.tagStyles.filterKeys(inUse::contains))
    same("review mode", live.reviewMode, rebuilt.reviewMode)
    same("storage path", live.storagePath, rebuilt.storagePath)
    same("trash retention", live.trashRetentionDays, rebuilt.trashRetentionDays)
    same("saved views", live.savedViews, rebuilt.savedViews)
    same("auto-match rules", live.rules, rebuilt.rules)
    return out
}

private const val ID = 10

/** [diff] of the documents [live] or [rebuilt] shows. */
private fun liveDifferences(
    live: LibraryView,
    rebuilt: LibraryView,
    bothHold: (docId: String, String, String) -> Boolean,
): List<String> {
    val out = ArrayList<String>()
    (live.live.keys + rebuilt.live.keys).toSortedSet().forEach { id ->
        val a = live.live[id]
        val b = rebuilt.live[id]
        when {
            a == null -> {
                out +=
                    "doc ${id.take(ID)} is live only in the rebuild (live index: ${live.hidden[id] ?: "no row"}): $b"
            }

            b == null -> {
                out +=
                    "doc ${id.take(ID)} is live only in the live index (rebuild: ${rebuilt.hidden[id] ?: "no row"}): $a"
            }

            a.copy(relPath = b.relPath) != b || (a.relPath != b.relPath && !bothHold(id, a.relPath, b.relPath)) -> {
                out += "doc ${id.take(ID)} differs:\n    live:    $a\n    rebuilt: $b"
            }
        }
    }
    return out
}
