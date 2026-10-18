package dev.npn.core.sim

import dev.npn.core.app.ImportOutcome
import dev.npn.core.app.LibraryFilter
import dev.npn.core.app.LibrarySessionImpl
import dev.npn.core.app.SavedView
import dev.npn.core.app.TrashReceipt
import dev.npn.core.fs.NpnPaths
import dev.npn.core.fs.sha256Bytes
import dev.npn.core.journal.ContentAddressedJournalWriter
import dev.npn.core.journal.HlcWatermarkAllocator
import dev.npn.core.journal.PendingOp
import dev.npn.core.model.FieldDef
import dev.npn.core.model.FieldKeys
import dev.npn.core.model.FieldType
import dev.npn.core.model.ReviewMode
import dev.npn.core.util.EpochClock
import kotlin.random.Random

/**
 * Every kind of step the generator can take, with its weight: how often it is picked relative to
 * the others. An operation the simulation must not run for now (a known issue it would keep
 * finding) is left out through [SimConfig.disabled], never by deleting it here.
 */
enum class OpKind(
    val weight: Int,
) {
    // The app's own writes, through the session's facets.
    IMPORT_NEW(10),
    IMPORT_DUPLICATE(3),
    IMPORT_HIDDEN(3),
    TRASH(6),
    RESTORE(2),
    UNDO_TRASH(2),
    DELETE_FOR_GOOD(2),
    EMPTY_TRASH(1),
    RENAME(3),
    MOVE(2),
    SET_FIELD(6),
    ADD_TAG(4),
    REMOVE_TAG(3),
    DEFINE_FIELD(1),
    LIBRARY_SETTING(2),
    STORAGE_PATH_RULE(1),
    SAVED_VIEW(1),
    RESCAN(3),
    OCR_COMPLETE(4),

    // Several of the above at once, interleaving at their suspension points.
    CONCURRENT(7),

    // Time.
    ADVANCE_SHORT(5),
    ADVANCE_TICK(2),
    ADVANCE_DAY(1),

    // What other processes do to the folder.
    EXT_DROP_PDF(3),
    EXT_CHUNKED_WRITE(3),
    EXT_OVERWRITE(2),
    EXT_DELETE(2),
    EXT_MOVE(2),
    EXT_BACKUP(1),
    EXT_RESTORE_BACKUP(1),
    EXT_SYNC_TEMP(2),
    EXT_REMOTE_OBJECT(3),

    // Storage failures, and the process's own end.
    FAULT(3),
    CRASH(1),
    CLOSE_REOPEN(1),
    DROP_INDEX(1),
}

/** A step the generator planned: what the trace says it is, and what it does. */
internal sealed interface Planned {
    val text: String

    /** A call into the open session, which may run concurrently with others. */
    class Act(
        override val text: String,
        val body: suspend (LibrarySessionImpl) -> String,
    ) : Planned

    /** Something done to the world (time, files, faults, the process) at one instant. */
    class Control(
        override val text: String,
        val body: () -> String,
    ) : Planned

    /** Several [Act]s started together. */
    class Group(
        val acts: List<Act>,
    ) : Planned {
        override val text: String get() = "concurrently: " + acts.joinToString(" || ") { it.text }
    }
}

/** What the generator knows of the library at the start of a step, read from the live index. */
internal class Snapshot(
    val live: List<SimDoc>,
    val trashed: List<SimDoc>,
    val deleted: List<SimDoc>,
    val fields: List<FieldDef>,
    val tags: List<String>,
)

internal data class SimDoc(
    val docId: String,
    val relPath: String,
)

/**
 * Plans steps from a seeded [random]: which [OpKind] (by weight, among those [enabled]), and its
 * arguments from the [Snapshot]. Everything it chooses goes through [random], so a seed replays the
 * same plan as long as the library reaches the same states.
 */
internal class SimGenerator(
    private val random: Random,
    private val sim: Simulation,
    private val enabled: List<OpKind>,
) {
    private var nextLabel = 0
    private val receipts = ArrayList<TrashReceipt>()
    private val backups = ArrayList<InMemoryLibraryFileSystem.Backup>()
    private val syncTemps = ArrayList<String>()

    /** Another device's journal writer, its clock a little off ours, as a phone's may be. */
    private val remoteClock = EpochClock { sim.world.clock.nowMillis() - REMOTE_SKEW_MILLIS }
    private val remoteAllocator = HlcWatermarkAllocator(remoteClock)

    private val totalWeight = enabled.sumOf { it.weight }

    fun next(snapshot: Snapshot): Planned {
        repeat(ATTEMPTS) {
            plan(pickKind(), snapshot)?.let { return it }
        }
        return importNew()
    }

    private fun pickKind(): OpKind {
        var roll = random.nextInt(totalWeight)
        for (kind in enabled) {
            roll -= kind.weight
            if (roll < 0) return kind
        }
        return enabled.last()
    }

    private fun plan(
        kind: OpKind,
        s: Snapshot,
    ): Planned? =
        when (kind) {
            OpKind.IMPORT_NEW -> importNew()
            OpKind.IMPORT_DUPLICATE -> s.live.pickOrNull()?.let { doc -> importBytes("duplicate of", doc) }
            OpKind.IMPORT_HIDDEN -> (s.trashed + s.deleted).pickOrNull()?.let { doc -> importBytes("hidden", doc) }
            OpKind.TRASH -> s.live.pickOrNull()?.let(::trash)
            OpKind.RESTORE -> s.trashed.pickOrNull()?.let(::restore)
            OpKind.UNDO_TRASH -> undoTrash()
            OpKind.DELETE_FOR_GOOD -> s.trashed.pickOrNull()?.let(::deleteForGood)
            OpKind.EMPTY_TRASH -> s.trashed.takeIf { it.isNotEmpty() }?.let(::emptyTrash)
            OpKind.RENAME -> s.live.pickOrNull()?.let(::rename)
            OpKind.MOVE -> s.live.pickOrNull()?.let(::move)
            OpKind.SET_FIELD -> s.live.pickOrNull()?.let { setField(it, s.fields) }
            OpKind.ADD_TAG -> s.live.pickOrNull()?.let(::addTag)
            OpKind.REMOVE_TAG -> s.live.pickOrNull()?.let { removeTag(it, s.tags) }
            OpKind.DEFINE_FIELD -> defineField()
            OpKind.LIBRARY_SETTING -> librarySetting()
            OpKind.STORAGE_PATH_RULE -> storagePathRule()
            OpKind.SAVED_VIEW -> savedView(s)
            OpKind.RESCAN -> Planned.Act("rescan") { it.rescan().let { r -> "added ${r.added}, retry ${r.retryAfterMillis}" } }
            OpKind.OCR_COMPLETE -> ocrComplete()
            OpKind.CONCURRENT -> concurrent(s)
            OpKind.ADVANCE_SHORT -> advance(random.nextLong(50, SHORT_MAX_MILLIS))
            OpKind.ADVANCE_TICK -> advance(random.nextLong(TICK_MIN_MILLIS, TICK_MAX_MILLIS))
            OpKind.ADVANCE_DAY -> advance(DAY_MILLIS + random.nextLong(0, TICK_MAX_MILLIS))
            OpKind.EXT_DROP_PDF -> extDrop()
            OpKind.EXT_CHUNKED_WRITE -> extChunked(s)
            OpKind.EXT_OVERWRITE -> s.live.pickOrNull()?.let(::extOverwrite)
            OpKind.EXT_DELETE -> extDelete(s)
            OpKind.EXT_MOVE -> s.live.pickOrNull()?.let(::extMove)
            OpKind.EXT_BACKUP -> extBackup()
            OpKind.EXT_RESTORE_BACKUP -> backups.pickOrNull()?.let(::extRestore)
            OpKind.EXT_SYNC_TEMP -> extSyncTemp(s)
            OpKind.EXT_REMOTE_OBJECT -> s.live.pickOrNull()?.let { extRemoteObject(it, s.fields) }
            OpKind.FAULT -> fault()
            OpKind.CRASH -> Planned.Control("crash and reopen") { sim.crashAndReopen() }
            OpKind.CLOSE_REOPEN -> Planned.Control("close and reopen") { sim.closeAndReopen() }
            OpKind.DROP_INDEX -> Planned.Control("drop index.db and reopen") { sim.dropIndexAndReopen(crash = random.chance(50)) }
        }

    // ---- the app's writes -----------------------------------------------------------------------

    private fun newPdf(): ByteArray {
        val n = nextLabel++
        val label = "doc-$n"
        // Half carry a text layer; the rest are scans only OCR can read. Each has a size of its own
        // while KnownIssues.UNIQUE_PDF_SIZES holds (see there).
        val size = if (KnownIssues.UNIQUE_PDF_SIZES) UNIQUE_SIZE_BASE + n else null
        val bytes = SimPdf.bytes(label, if (random.chance(50)) "text of $label ${random.pick(WORDS)}" else null, size)
        sim.known[sha256Bytes(bytes)] = bytes
        return bytes
    }

    private fun importNew(): Planned.Act {
        val bytes = newPdf()
        val name = pickName()
        return Planned.Act("import new ${hash(bytes)} as '$name'") { it.importPdfBytes(bytes, name).describe() }
    }

    private fun importBytes(
        what: String,
        doc: SimDoc,
    ): Planned.Act? {
        val bytes = sim.known[doc.docId] ?: return null
        val name = if (random.chance(50)) NpnPaths.fileName(doc.relPath) else pickName()
        return Planned.Act("import $what ${doc.docId.take(ID)} as '$name'") { it.importPdfBytes(bytes, name).describe() }
    }

    private fun trash(doc: SimDoc) =
        Planned.Act("trash ${doc.short()}") { session ->
            session.docLifecycle.trashDoc(doc.docId)?.also { receipts += it }.toString()
        }

    private fun restore(doc: SimDoc) =
        Planned.Act("restore ${doc.short()}") { session ->
            session.docLifecycle.restoreDoc(doc.docId)
            "done"
        }

    private fun undoTrash(): Planned.Act? {
        if (receipts.isEmpty()) return null
        val receipt = receipts.removeAt(random.nextInt(receipts.size))
        return Planned.Act("undo trash of ${receipt.docId.take(ID)} (trashedAt ${receipt.trashedAt})") { session ->
            "restored ${session.docLifecycle.undoTrash(listOf(receipt))}"
        }
    }

    private fun deleteForGood(doc: SimDoc) =
        Planned.Act("delete for good ${doc.short()}") { it.docLifecycle.deleteDocPermanently(doc.docId).toString() }

    private fun emptyTrash(docs: List<SimDoc>) =
        Planned.Act("empty trash (${docs.size})") { it.docLifecycle.deleteDocsPermanently(docs.map(SimDoc::docId)).toString() }

    private fun rename(doc: SimDoc): Planned.Act {
        val name = pickName()
        return Planned.Act("rename ${doc.short()} to '$name'") { it.docLifecycle.renameFile(doc.docId, name).toString() }
    }

    private fun move(doc: SimDoc): Planned.Act {
        val dir = random.pick(DIRS)
        return Planned.Act("move ${doc.short()} to '$dir'") { it.docLifecycle.moveFile(doc.docId, dir).toString() }
    }

    private fun setField(
        doc: SimDoc,
        fields: List<FieldDef>,
    ): Planned.Act? {
        val def = fields.pickOrNull() ?: return null
        val value = if (random.chance(CLEAR_PERCENT)) null else valueFor(def.type)
        return Planned.Act("set ${def.key}=${value ?: "<clear>"} on ${doc.short()}") { session ->
            session.meta.setField(doc.docId, def.key, value)
            "done"
        }
    }

    private fun valueFor(type: FieldType): String =
        when {
            type.isDate -> random.pick(DATES)
            type == FieldType.NUMBER -> random.pick(NUMBERS)
            type.isNumeric -> random.pick(DECIMALS)
            else -> random.pick(WORDS)
        }

    private fun addTag(doc: SimDoc): Planned.Act {
        val tag = random.pick(TAGS)
        return Planned.Act("tag ${doc.short()} +$tag") { session ->
            session.meta.addTag(listOf(doc.docId), tag)
            "done"
        }
    }

    private fun removeTag(
        doc: SimDoc,
        tags: List<String>,
    ): Planned.Act? {
        val tag = tags.pickOrNull() ?: return null
        return Planned.Act("tag ${doc.short()} -$tag") { session ->
            session.meta.removeTag(listOf(doc.docId), tag)
            "done"
        }
    }

    private fun defineField(): Planned.Act {
        val (key, type) = random.pick(CUSTOM_FIELDS)
        val def =
            FieldDef(
                key = key,
                label = "$key ${random.nextInt(LABELS)}",
                type = type,
                showByDefault = random.nextBoolean(),
                aiEnabled = random.nextBoolean(),
                position = FieldDef.PLACE_LAST,
            )
        return Planned.Act("define field ${def.key}:${def.type.wire} '${def.label}'") { session ->
            session.fieldRegistry.upsert(def)
            "done"
        }
    }

    private fun librarySetting(): Planned.Act =
        if (random.nextBoolean()) {
            val mode = random.pick(ReviewMode.entries)
            Planned.Act("review mode $mode") { session ->
                session.reviewConfig.setReviewMode(mode)
                "done"
            }
        } else {
            val days = random.nextInt(1, RETENTION_MAX_DAYS)
            Planned.Act("trash retention $days days") { session ->
                session.trashRetention.setTrashRetentionDays(days)
                "done"
            }
        }

    private fun storagePathRule(): Planned.Act =
        if (random.chance(60)) {
            val template = random.pick(TEMPLATES)
            Planned.Act("storage path template '$template'") { session ->
                session.storagePath.setStoragePathTemplate(template)
                "done"
            }
        } else {
            val on = random.nextBoolean()
            Planned.Act("storage path enabled=$on") { session ->
                session.storagePath.setStoragePathEnabled(on)
                "done"
            }
        }

    private fun savedView(s: Snapshot): Planned.Act {
        val id = "view-${random.nextInt(VIEWS)}"
        return if (random.chance(70)) {
            val tag = s.tags.pickOrNull()
            val view = SavedView(id, "View ${random.nextInt(LABELS)}", LibraryFilter(tagsAll = listOfNotNull(tag)))
            Planned.Act("save view $id (${view.name}, tag $tag)") { session ->
                session.savedViews.save(view)
                "done"
            }
        } else {
            Planned.Act("delete view $id") { session ->
                session.savedViews.delete(id)
                "done"
            }
        }
    }

    private fun ocrComplete(): Planned.Control {
        val pages = random.nextInt(1, OCR_BURST)
        return Planned.Control("let $pages OCR page(s) finish") {
            val done = (1..pages).count { sim.replica.ocr.complete() }
            "$done finished, ${sim.replica.ocr.inFlight} waiting"
        }
    }

    private fun concurrent(s: Snapshot): Planned? {
        val acts =
            (1..random.nextInt(2, CONCURRENCY + 1)).mapNotNull {
                plan(random.pick(CONCURRENT_KINDS.filter { it in enabled }.ifEmpty { return null }), s) as? Planned.Act
            }
        return if (acts.size >= 2) Planned.Group(acts) else null
    }

    private fun advance(millis: Long) =
        Planned.Control("advance ${millis}ms") {
            sim.world.advance(millis)
            "t=${sim.world.scheduler.currentTime}"
        }

    // ---- the external world ---------------------------------------------------------------------

    private fun extDrop(): Planned.Control {
        val bytes = newPdf()
        val path = pathIn(random.pick(DIRS), pickName())
        return Planned.Control("external: write ${hash(bytes)} at '$path' in one step") {
            sim.fs.externalWriteAtomic(path, bytes)
            "done"
        }
    }

    private fun extChunked(s: Snapshot): Planned.Control {
        val bytes = newPdf()
        val path = s.live.takeIf { random.chance(30) }?.pickOrNull()?.relPath ?: pathIn(random.pick(DIRS), pickName())
        val chunks = random.nextInt(2, CHUNKS)
        val interval = random.nextLong(100, CHUNK_INTERVAL_MILLIS)
        return Planned.Control("external: write ${hash(bytes)} at '$path' in place, $chunks chunks ${interval}ms apart") {
            sim.world.launch { sim.fs.externalWriteInChunks(path, bytes, chunks, interval) }
            "started"
        }
    }

    private fun extOverwrite(doc: SimDoc): Planned.Control {
        val bytes = newPdf()
        val inPlace = random.nextBoolean()
        return Planned.Control("external: overwrite ${doc.short()} with ${hash(bytes)} ${if (inPlace) "in place" else "by rename"}") {
            if (inPlace) sim.fs.externalOverwriteInPlace(doc.relPath, bytes) else sim.fs.externalWriteAtomic(doc.relPath, bytes)
            "done"
        }
    }

    private fun extDelete(s: Snapshot): Planned.Control? {
        val path =
            if (random.chance(25)) {
                random.pick(DIRS).takeIf { it.isNotEmpty() } ?: return null
            } else {
                s.live.pickOrNull()?.relPath ?: return null
            }
        return Planned.Control("external: delete '$path'") {
            sim.fs.externalDelete(path)
            "done"
        }
    }

    private fun extMove(doc: SimDoc): Planned.Control {
        val to = pathIn(random.pick(DIRS), pickName())
        return Planned.Control("external: move ${doc.short()} to '$to'") {
            sim.fs.externalMove(doc.relPath, to)
            "done"
        }
    }

    private fun extBackup(): Planned.Control {
        val root = random.pick(DIRS + listOf(NpnPaths.NPN_DIR))
        return Planned.Control("external: back up '$root'") {
            val backup = sim.fs.externalBackup(root)
            backups += backup
            "${backup.files.size} files"
        }
    }

    private fun extRestore(backup: InMemoryLibraryFileSystem.Backup) =
        Planned.Control("external: put '${backup.root}' back from a backup (${backup.files.size} files)") {
            sim.fs.externalRestore(backup)
            "done"
        }

    private fun extSyncTemp(s: Snapshot): Planned.Control {
        if (syncTemps.isNotEmpty() && random.chance(40)) {
            val temp = syncTemps.removeAt(random.nextInt(syncTemps.size))
            return Planned.Control("external: sync tool removes '$temp'") {
                sim.fs.externalDelete(temp)
                "done"
            }
        }
        val beside = s.live.pickOrNull()?.relPath ?: pathIn(random.pick(DIRS), pickName())
        val dir = if (random.chance(30)) "${NpnPaths.JOURNAL_DIR}/objects-v2-${random.pick(BUCKETS)}" else NpnPaths.parentDir(beside)
        val name = NpnPaths.fileName(beside)
        val temp =
            when (random.nextInt(4)) {
                0 -> ".syncthing.$name.tmp"
                1 -> "~syncthing~$name.tmp"
                2 -> ".$name.~${random.nextInt(0x10000, 0xfffff).toString(16)}"
                else -> "$name.!sync"
            }
        val path = pathIn(dir, temp)
        syncTemps += path
        return Planned.Control("external: sync tool writes '$path'") {
            sim.fs.externalWriteAtomic(path, SimPdf.bytes("partial").copyOf(PARTIAL))
            "done"
        }
    }

    private fun extRemoteObject(
        doc: SimDoc,
        fields: List<FieldDef>,
    ): Planned.Control {
        val ops =
            when (random.nextInt(3)) {
                0 -> listOf(PendingOp.tagAdd(doc.docId, random.pick(TAGS)))
                1 -> fields.pickOrNull()?.let { listOf(PendingOp.fieldSet(doc.docId, it.key, valueFor(it.type))) }
                else -> listOf(PendingOp.tagStyle(random.pick(TAGS), random.pick(COLORS), null))
            } ?: listOf(PendingOp.tagAdd(doc.docId, random.pick(TAGS)))
        val transfer = random.nextLong(0, TRANSFER_MILLIS)
        return Planned.Control("external: another device's ${ops.joinToString { it.op::class.simpleName.orEmpty() }} on ${doc.short()} arrives over ${transfer}ms") {
            val (path, bytes) = remoteObject(ops)
            sim.world.launch { sim.fs.externalSyncDelivery(path, bytes, transfer) }
            path.substringAfterLast('/').take(ID)
        }
    }

    /** One object another device published, as its bytes and the path they belong at. */
    private fun remoteObject(ops: List<PendingOp>): Pair<String, ByteArray> {
        val scratch = InMemoryLibraryFileSystem(now = remoteClock::nowMillis)
        val writer = ContentAddressedJournalWriter(scratch, "remote-installation", remoteAllocator)
        val published = sim.world.read("another device's commit") { writer.commit(ops) }
        return published.relPath to scratch.bytesAt(published.relPath)!!
    }

    // ---- faults ---------------------------------------------------------------------------------

    private fun fault(): Planned.Control {
        val method = if (random.chance(70)) random.pick(FsMethod.entries.filter { it.writes }) else random.pick(FsMethod.entries)
        val prefix = random.pick(listOf(null, null, NpnPaths.JOURNAL_DIR, NpnPaths.TRASH_DIR, NpnPaths.OCR_DIR, NpnPaths.TMP_DIR, "inbox"))
        val point = if (method.writes && random.chance(30)) SimFaults.Point.AFTER else SimFaults.Point.BEFORE
        val rule = SimFaults.Rule(method, prefix, point, times = random.nextInt(1, FAULT_TIMES))
        return Planned.Control("arm fault $rule") {
            sim.fs.faults.arm(rule)
            "armed"
        }
    }

    // ---- helpers --------------------------------------------------------------------------------

    private fun pickName(): String = if (random.chance(60)) random.pick(NAMES) else "doc-${random.nextInt(NAMED)}.pdf"

    private fun pathIn(
        dir: String,
        name: String,
    ) = if (dir.isEmpty()) name else "$dir/$name"

    private fun <T> List<T>.pickOrNull(): T? = if (isEmpty()) null else this[random.nextInt(size)]

    private fun hash(bytes: ByteArray) = sha256Bytes(bytes).take(ID)

    private fun SimDoc.short() = "${docId.take(ID)}@'$relPath'"

    private fun ImportOutcome.describe(): String =
        when (this) {
            is ImportOutcome.Imported -> "imported ${docId.take(ID)} at '$relPath'"
            is ImportOutcome.Duplicate -> "duplicate of ${existingDocId.take(ID)} at '$existingPath'"
            is ImportOutcome.Failed -> "failed: $reason"
        }

    companion object {
        private const val ATTEMPTS = 20
        private const val UNIQUE_SIZE_BASE = 400
        private const val ID = 8
        private const val CLEAR_PERCENT = 20
        private const val SHORT_MAX_MILLIS = 6_000L
        private const val TICK_MIN_MILLIS = 60_000L
        private const val TICK_MAX_MILLIS = 20L * 60L * 1000L
        private const val DAY_MILLIS = 25L * 60L * 60L * 1000L
        private const val CHUNKS = 6
        private const val CHUNK_INTERVAL_MILLIS = 2_500L
        private const val TRANSFER_MILLIS = 3_000L
        private const val REMOTE_SKEW_MILLIS = 90_000L
        private const val FAULT_TIMES = 4
        private const val OCR_BURST = 4
        private const val CONCURRENCY = 3
        private const val RETENTION_MAX_DAYS = 60
        private const val LABELS = 5
        private const val VIEWS = 3
        private const val NAMED = 12
        private const val PARTIAL = 12

        private val NAMES = listOf("scan.pdf", "invoice.pdf", "Letter.pdf", "report 2024.pdf", "scan (1).pdf")
        private val DIRS = listOf("", "", NpnPaths.DEFAULT_INBOX, "archive", "archive/2024", "Tax")
        private val WORDS = listOf("ACME", "Globex", "acme", "Initech", "Umbrella Corp", "Q3 report")
        private val DATES = listOf("2024-01-15", "2023-12-31", "2024-02-29")
        private val NUMBERS = listOf("7", "007", "42", "-3")
        private val DECIMALS = listOf("1.5", "1.50", "0.25", "12")
        private val TAGS = listOf("tax", "bank", "Tax", "receipts", "todo")
        private val COLORS = listOf("#ff0000", "#00ff00", null)
        private val BUCKETS = listOf("00", "3f", "a1")
        private val TEMPLATES =
            listOf("{correspondent}/{title}.pdf", "{doc_date:yyyy}/{title}.pdf", "{tags}/{title}.pdf", "{field:project}/{title}.pdf")
        private val CUSTOM_FIELDS =
            listOf(
                "amount" to FieldType.DECIMAL,
                "pages" to FieldType.NUMBER,
                "due" to FieldType.DATE,
                "project" to FieldType.TEXT,
                FieldKeys.CORRESPONDENT to FieldType.TEXT,
            )
        private val CONCURRENT_KINDS =
            listOf(
                OpKind.IMPORT_NEW,
                OpKind.IMPORT_DUPLICATE,
                OpKind.IMPORT_HIDDEN,
                OpKind.TRASH,
                OpKind.RESTORE,
                OpKind.UNDO_TRASH,
                OpKind.DELETE_FOR_GOOD,
                OpKind.EMPTY_TRASH,
                OpKind.RENAME,
                OpKind.MOVE,
                OpKind.SET_FIELD,
                OpKind.ADD_TAG,
                OpKind.REMOVE_TAG,
                OpKind.DEFINE_FIELD,
                OpKind.STORAGE_PATH_RULE,
                OpKind.RESCAN,
            )
    }
}
