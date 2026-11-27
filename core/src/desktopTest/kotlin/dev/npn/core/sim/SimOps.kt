package dev.npn.core.sim

import dev.npn.core.app.BakeOutcome
import dev.npn.core.app.ImportOutcome
import dev.npn.core.app.LibraryFilter
import dev.npn.core.app.LibrarySessionImpl
import dev.npn.core.app.SavedView
import dev.npn.core.app.SplitOutcome
import dev.npn.core.app.TrashReceipt
import dev.npn.core.fs.NpnPaths
import dev.npn.core.fs.sha256Bytes
import dev.npn.core.journal.PendingOp
import dev.npn.core.journal.TagRetention
import dev.npn.core.model.FieldDef
import dev.npn.core.model.FieldKeys
import dev.npn.core.model.FieldType
import dev.npn.core.model.ReviewMode
import dev.npn.core.rules.AutoMatchRule
import dev.npn.core.rules.MatchAlgorithm
import dev.npn.core.rules.RuleField
import dev.npn.core.watch.WatchedFolderImporter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlin.random.Random

/**
 * Every kind of step the generator can take, with its weight: how often it is picked relative to
 * the others.
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
    CREATE_EMPTY_TAG(2),
    DELETE_EMPTY_TAG(2),
    RENAME_TAG(2),
    MERGE_TAG(2),
    LEGACY_UNUSED_STYLE(3),
    LEGACY_RENAME_TAG(1),
    DEFINE_FIELD(1),
    CUSTOMIZE_SEEDED_FIELD(1),
    LIBRARY_SETTING(2),
    STORAGE_PATH_RULE(1),
    SAVED_VIEW(1),
    RESCAN(3),
    OCR_COMPLETE(4),

    // The app's content operations: bake in place, multi-page imports and split, and the relink of
    // a missing document to other bytes.
    BAKE(4),
    BAKE_RESCANNED(1),
    BAKE_RESTORE_REFUSED(1),
    IMPORT_MULTIPAGE(3),
    SPLIT(4),
    SPLIT_SEEN_FAILING(1),
    RELINK_MISSING(2),

    // The auto-match rules, whose field sets drive the storage-path rule, which moves documents on
    // every replica.
    RULE_SAVE(4),
    RULE_DELETE(1),
    RULE_TOGGLE(1),

    // The watched inbox folder, where the seed gives the replica one: new, slowly
    // written, known, overwritten and removed files, and a sweep beside the importer the host runs.
    WATCHED_DROP(4),
    WATCHED_CHUNKED(1),
    WATCHED_KNOWN(1),
    WATCHED_OVERWRITE(1),
    WATCHED_REMOVE(1),
    WATCHED_SWEEP(2),

    // Several of the app's writes at once, interleaving at their suspension points, with what other
    // processes do landing while they run; or one left running while the next steps go on.
    CONCURRENT(10),
    IN_BACKGROUND(5),

    // Time.
    ADVANCE_SHORT(5),
    ADVANCE_TICK(2),
    ADVANCE_DAY(1),

    // What other processes do to the folder.
    EXT_DROP_PDF(3),
    EXT_CHUNKED_WRITE(3),
    EXT_OVERWRITE(2),
    EXT_TOUCH(2),
    EXT_DELETE(2),
    EXT_MOVE(2),
    EXT_BACKUP(1),
    EXT_RESTORE_BACKUP(1),
    EXT_SYNC_TEMP(2),

    // Storage failures, and the process's own end: a crash cancels its scope, a hard crash kills it.
    FAULT(3),
    CRASH(1),
    HARD_CRASH(2),
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

    /**
     * Something done to the world (time, files, faults, the process). An [instant] one acts at one
     * instant without running the scheduler, so it may also land in the middle of a [Group].
     */
    class Control(
        override val text: String,
        val instant: Boolean = false,
        val body: () -> String,
    ) : Planned

    /** Several [Act]s started together, with [during] landing the given virtual millis after the start. */
    class Group(
        val acts: List<Act>,
        val during: List<Pair<Long, Control>> = emptyList(),
    ) : Planned {
        override val text: String
            get() =
                "concurrently: " + acts.joinToString(" || ") { it.text } +
                    during.joinToString("") { (at, control) -> " || at +${at}ms ${control.text}" }
    }

    /** An [Act] started and left running: later steps go on while it does. */
    class Background(
        val act: Act,
    ) : Planned {
        override val text: String get() = "in background: ${act.text}"
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
 * Plans steps from a seeded [random]: which [OpKind] (by weight), and its arguments from the
 * [Snapshot]. Everything it chooses goes through [random], so a seed replays the same plan as long
 * as the library reaches the same states.
 */
internal class SimGenerator(
    private val random: Random,
    private val sim: Simulation,
    /** Where this generator numbers its PDFs from, so several generators never make the same one. */
    labelBase: Int,
    /** This replica's watched folder, or null where the seed gave it none. */
    private val watched: SimWatchedFolder?,
    /** Whether the watched folder's importer deletes what it imported. */
    private val deleteAfterImport: Boolean,
    /** The folder sync tool scanning this replica's folder now, or null where none syncs it. */
    private val syncScan: (() -> Int)? = null,
) {
    private var nextLabel = labelBase
    private val receipts = ArrayList<TrashReceipt>()
    private val backups = ArrayList<InMemoryLibraryFileSystem.Backup>()
    private val syncTemps = ArrayList<String>()

    /** The kinds this replica can take: the watched folder's only where it has one. */
    private val kinds = OpKind.entries.filter { watched != null || it !in WATCHED_KINDS }
    private val totalWeight = kinds.sumOf { it.weight }

    /**
     * Shared by every importer of this replica's folder in one process, so two sweeps never
     * overlap. A process's own: a killed one may die holding it.
     */
    private var sweepLock = Mutex()

    fun next(snapshot: Snapshot): Planned {
        repeat(ATTEMPTS) {
            plan(pickKind(), snapshot)?.let { planned -> return planned }
        }
        return importNew()
    }

    private fun pickKind(): OpKind {
        var roll = random.nextInt(totalWeight)
        for (kind in kinds) {
            roll -= kind.weight
            if (roll < 0) return kind
        }
        return kinds.last()
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
            OpKind.CREATE_EMPTY_TAG -> emptyTag(random, create = true)
            OpKind.DELETE_EMPTY_TAG -> emptyTag(random, create = false)
            OpKind.RENAME_TAG -> retag(random, sim, s, merge = false)
            OpKind.MERGE_TAG -> retag(random, sim, s, merge = true)
            OpKind.LEGACY_UNUSED_STYLE -> legacyUnusedStyle(random)
            OpKind.LEGACY_RENAME_TAG -> legacyRenameTag(random)
            OpKind.DEFINE_FIELD -> defineField()
            OpKind.CUSTOMIZE_SEEDED_FIELD -> customizeSeededField(s)
            OpKind.LIBRARY_SETTING -> librarySetting()
            OpKind.STORAGE_PATH_RULE -> storagePathRule()
            OpKind.SAVED_VIEW -> savedView(s)
            OpKind.RESCAN -> rescan()
            OpKind.OCR_COMPLETE -> ocrComplete()
            OpKind.BAKE -> s.live.pickOrNull()?.let(::bake)
            OpKind.BAKE_RESCANNED -> s.live.pickOrNull()?.let { bakeRescanned(sim, it, bake(it)) }
            OpKind.BAKE_RESTORE_REFUSED -> s.live.pickOrNull()?.let { bakeRestoreRefused(sim, it, bake(it)) }
            OpKind.IMPORT_MULTIPAGE -> importMultipage()
            OpKind.SPLIT -> splitAny(s)
            OpKind.SPLIT_SEEN_FAILING -> splitAny(s)?.let { splitSeenFailing(random, sim, syncScan, it) }
            OpKind.RELINK_MISSING -> relinkMissing(s)
            OpKind.RULE_SAVE -> saveRule()
            OpKind.RULE_DELETE -> ruleAct("delete") { session, id -> session.rules.delete(id) }
            OpKind.RULE_TOGGLE -> ruleToggle()
            OpKind.WATCHED_DROP -> watchedDrop(newPdf())
            OpKind.WATCHED_CHUNKED -> watchedChunked()
            OpKind.WATCHED_KNOWN -> watchedKnown()
            OpKind.WATCHED_OVERWRITE -> watched?.names?.pickOrNull()?.let(::watchedOverwrite)
            OpKind.WATCHED_REMOVE -> watched?.names?.pickOrNull()?.let(::watchedRemove)
            OpKind.WATCHED_SWEEP -> watchedSweep()
            OpKind.CONCURRENT -> concurrent(s)
            OpKind.IN_BACKGROUND -> background(s)
            OpKind.ADVANCE_SHORT -> advance(random.nextLong(50, SHORT_MAX_MILLIS))
            OpKind.ADVANCE_TICK -> advance(random.nextLong(TICK_MIN_MILLIS, TICK_MAX_MILLIS))
            OpKind.ADVANCE_DAY -> advance(DAY_MILLIS + random.nextLong(0, TICK_MAX_MILLIS))
            OpKind.EXT_DROP_PDF -> extDrop()
            OpKind.EXT_CHUNKED_WRITE -> extChunked(s)
            OpKind.EXT_OVERWRITE -> s.live.pickOrNull()?.let(::extOverwrite)
            OpKind.EXT_TOUCH -> s.live.pickOrNull()?.let(::extTouch)
            OpKind.EXT_DELETE -> extDelete(s)
            OpKind.EXT_MOVE -> s.live.pickOrNull()?.let(::extMove)
            OpKind.EXT_BACKUP -> extBackup()
            OpKind.EXT_RESTORE_BACKUP -> backups.pickOrNull()?.let(::extRestore)
            OpKind.EXT_SYNC_TEMP -> extSyncTemp(s)
            OpKind.FAULT -> fault()
            OpKind.CRASH -> Planned.Control("crash and reopen") { sim.crashAndReopen() }
            OpKind.HARD_CRASH -> Planned.Control("hard crash (killed) and reopen") { sim.hardCrashAndReopen() }
            OpKind.CLOSE_REOPEN -> Planned.Control("close and reopen") { sim.closeAndReopen() }
            OpKind.DROP_INDEX -> dropIndex()
        }

    private fun splitAny(s: Snapshot) =
        s.live
            .filter { pagesOf(it) > 1 }
            .pickOrNull()
            ?.let(::split)

    private fun watchedKnown() =
        sim.known.keys
            .sorted()
            .pickOrNull()
            ?.let { watchedDrop(sim.known.getValue(it)) }

    private fun dropIndex() =
        Planned.Control("drop index.db and reopen") { sim.dropIndexAndReopen(crash = random.chance(50)) }

    // ---- the app's writes -----------------------------------------------------------------------

    private fun newPdf(pages: Int = 1): ByteArray {
        val n = nextLabel++
        val label = "doc-$n"
        // Half carry a text layer; the rest are scans only OCR can read. Where storage reports no
        // mtimes, all share a few sizes, so a replacement often has the size of the file it replaces
        // and only a hash tells it apart. Elsewhere each has a size of its own: two files written in
        // the same virtual millisecond, one then renamed over the other, would have one stat, and an
        // mtime-preserving overwrite of the same size is out of scope (docs/SIMULATION.md).
        val size = if (sim.config.mtimes) SIZE_BASE + n else SIZE_BASE + random.nextInt(SHARED_SIZES)
        val text = if (random.chance(50)) "text of $label ${random.pick(WORDS)}" else null
        val bytes = SimPdf.bytes(label, text, size, pages = pages)
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
        return Planned.Act("import $what ${doc.docId.take(ID)} as '$name'") { session ->
            session.importPdfBytes(bytes, name).describe()
        }
    }

    private fun trash(doc: SimDoc) =
        Planned.Act("trash ${doc.short()}") { session ->
            session.docLifecycle
                .trashDoc(doc.docId)
                ?.also { receipts += it }
                .toString()
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
        Planned.Act("empty trash (${docs.size})") { session ->
            session.docLifecycle.deleteDocsPermanently(docs.map(SimDoc::docId)).toString()
        }

    private fun rename(doc: SimDoc): Planned.Act {
        val name = pickName()
        return Planned.Act("rename ${doc.short()} to '$name'") { session ->
            session.docLifecycle.renameFile(doc.docId, name).toString()
        }
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

    /**
     * Relabel one of the well-known fields the library seeds, or remove it: the changes a user makes
     * to the defaults, which a device joining the library later must not undo with its own seeding.
     */
    private fun customizeSeededField(s: Snapshot): Planned.Act? {
        val def = s.fields.filter { it.key in FieldKeys.WELL_KNOWN }.pickOrNull() ?: return null
        val world = sim.world
        return if (random.nextInt(REMOVE_SEEDED_ONE_IN) == 0) {
            Planned.Act("remove seeded field ${def.key}") { session ->
                session.fieldRegistry.remove(def.key)
                world.customizedSeeds += def.key
                "done"
            }
        } else {
            val label = "${def.key} custom ${random.nextInt(LABELS)}"
            Planned.Act("relabel seeded field ${def.key} '$label'") { session ->
                session.fieldRegistry.upsert(def.copy(label = label))
                world.customizedSeeds += def.key
                "done"
            }
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
        return Planned.Control("let $pages OCR page(s) finish", instant = true) {
            val done = (1..pages).count { sim.replica.ocr.complete() }
            "$done finished, ${sim.replica.ocr.inFlight} waiting"
        }
    }

    private fun concurrent(s: Snapshot): Planned? {
        val acts =
            (1..random.nextInt(2, CONCURRENCY + 1)).mapNotNull {
                plan(random.pick(CONCURRENT_KINDS), s) as? Planned.Act
            }
        val during =
            (0 until random.nextInt(0, DURING + 1)).mapNotNull {
                val control = (plan(random.pick(DURING_KINDS), s) as? Planned.Control)?.takeIf(Planned.Control::instant)
                control?.let { random.nextLong(0, DURING_MILLIS) to control }
            }
        return if (acts.isNotEmpty() && acts.size + during.size >= 2) Planned.Group(acts, during) else null
    }

    private fun background(s: Snapshot): Planned? =
        (plan(random.pick(CONCURRENT_KINDS), s) as? Planned.Act)?.let(Planned::Background)

    private fun advance(millis: Long) =
        Planned.Control("advance ${millis}ms") {
            sim.world.advance(millis)
            "t=${sim.world.scheduler.currentTime}"
        }

    // ---- the external world ---------------------------------------------------------------------

    private fun extDrop(): Planned.Control {
        val bytes = newPdf()
        val path = pathIn(random.pick(DIRS), pickName())
        return Planned.Control("external: write ${hash(bytes)} at '$path' in one step", instant = true) {
            sim.fs.externalWriteAtomic(path, bytes)
            "done"
        }
    }

    private fun extChunked(s: Snapshot): Planned.Control {
        val bytes = newPdf()
        val path =
            s.live
                .takeIf { random.chance(30) }
                ?.pickOrNull()
                ?.relPath ?: pathIn(random.pick(DIRS), pickName())
        val chunks = random.nextInt(2, CHUNKS)
        val interval = random.nextLong(100, CHUNK_INTERVAL_MILLIS)
        val text = "external: write ${hash(bytes)} at '$path' in place, $chunks chunks ${interval}ms apart"
        return Planned.Control(text, instant = true) {
            sim.world.launch { sim.fs.externalWriteInChunks(path, bytes, chunks, interval) }
            "started"
        }
    }

    private fun extOverwrite(doc: SimDoc): Planned.Control {
        val bytes = newPdf()
        val inPlace = random.nextBoolean()
        val how = if (inPlace) "in place" else "by rename"
        return Planned.Control("external: overwrite ${doc.short()} with ${hash(bytes)} $how", instant = true) {
            if (inPlace) {
                sim.fs.externalOverwriteInPlace(doc.relPath, bytes)
            } else {
                sim.fs.externalWriteAtomic(doc.relPath, bytes)
            }
            "done"
        }
    }

    private fun extTouch(doc: SimDoc) =
        Planned.Control("external: touch ${doc.short()}, its bytes unchanged", instant = true) {
            sim.fs.externalTouch(doc.relPath)
            "done"
        }

    private fun extDelete(s: Snapshot): Planned.Control? {
        val path =
            if (random.chance(25)) {
                random.pick(DIRS).takeIf { it.isNotEmpty() } ?: return null
            } else {
                s.live.pickOrNull()?.relPath ?: return null
            }
        return Planned.Control("external: delete '$path'", instant = true) {
            sim.fs.externalDelete(path)
            "done"
        }
    }

    private fun extMove(doc: SimDoc): Planned.Control {
        val to = pathIn(random.pick(DIRS), pickName())
        return Planned.Control("external: move ${doc.short()} to '$to'", instant = true) {
            sim.fs.externalMove(doc.relPath, to)
            "done"
        }
    }

    private fun extBackup(): Planned.Control {
        val root = random.pick(DIRS + listOf(NpnPaths.NPN_DIR))
        return Planned.Control("external: back up '$root'", instant = true) {
            val backup = sim.fs.externalBackup(root)
            backups += backup
            "${backup.files.size} files"
        }
    }

    private fun extRestore(backup: InMemoryLibraryFileSystem.Backup) =
        Planned.Control("external: put '${backup.root}' back from a backup (${backup.files.size} files)", true) {
            sim.fs.externalRestore(backup)
            "done"
        }

    private fun extSyncTemp(s: Snapshot): Planned.Control {
        if (syncTemps.isNotEmpty() && random.chance(40)) {
            val temp = syncTemps.removeAt(random.nextInt(syncTemps.size))
            return Planned.Control("external: sync tool removes '$temp'", instant = true) {
                sim.fs.externalDelete(temp)
                "done"
            }
        }
        val beside = s.live.pickOrNull()?.relPath ?: pathIn(random.pick(DIRS), pickName())
        val bucket = "${NpnPaths.JOURNAL_DIR}/objects-v2-${random.pick(BUCKETS)}"
        val dir = if (random.chance(30)) bucket else NpnPaths.parentDir(beside)
        val path =
            when (random.nextInt(4)) {
                0 -> {
                    SimNames.syncTemp(pathIn(dir, NpnPaths.fileName(beside)), SyncTool.SYNCTHING)
                }

                1 -> {
                    SimNames.syncTemp(pathIn(dir, NpnPaths.fileName(beside)), SyncTool.SYNCTHING, older = true)
                }

                2 -> {
                    SimNames.syncTemp(
                        pathIn(dir, NpnPaths.fileName(beside)),
                        SyncTool.NEXTCLOUD,
                        random.nextInt(0x10000, 0xfffff),
                    )
                }

                else -> {
                    SimNames.syncTemp(pathIn(dir, NpnPaths.fileName(beside)), SyncTool.RESILIO)
                }
            }
        syncTemps += path
        return Planned.Control("external: sync tool writes '$path'", instant = true) {
            sim.fs.externalWriteAtomic(path, SimPdf.bytes("partial").copyOf(PARTIAL))
            "done"
        }
    }

    // ---- content --------------------------------------------------------------------------------

    private fun bake(doc: SimDoc) =
        Planned.Act("bake ${doc.short()} in place") { session ->
            when (val outcome = session.content.bakeInPlace(doc.docId)) {
                BakeOutcome.Baked -> "baked"
                is BakeOutcome.Failed -> "failed: ${outcome.reason}"
            }
        }

    private fun importMultipage(): Planned.Act {
        val bytes = newPdf(pages = random.nextInt(2, MAX_PAGES + 1))
        val name = pickName()
        return Planned.Act("import ${SimPdf.pagesOf(bytes)}-page ${hash(bytes)} as '$name'") { session ->
            session.importPdfBytes(bytes, name).describe()
        }
    }

    private fun pagesOf(doc: SimDoc): Int = sim.known[doc.docId]?.let(SimPdf::pagesOf) ?: 1

    private fun split(doc: SimDoc): Planned.Act {
        val pages = SimPdf.pagesOf(sim.known.getValue(doc.docId))
        val cuts = (1 until pages).filter { random.nextBoolean() }.ifEmpty { listOf(1 + random.nextInt(pages - 1)) }
        val bounds = listOf(0) + cuts + listOf(pages)
        val segments = bounds.zipWithNext { from, to -> from until to }
        return Planned.Act("split ${doc.short()} into $segments") { session ->
            when (val outcome = session.maintenance.splitPdf(doc.docId, segments)) {
                is SplitOutcome.Done -> "parts ${outcome.partDocIds.joinToString { it.take(ID) }}"
                is SplitOutcome.Failed -> "failed: ${outcome.reason}"
            }
        }
    }

    private fun relinkMissing(s: Snapshot): Planned.Act? {
        val missing =
            sim.world
                .read("missing rows") {
                    sim.replica.indexDb.rows(
                        "SELECT docId FROM documents WHERE missing = 1 AND trashed = 0 AND deleted = 0 ORDER BY docId",
                    )
                }.map { it[0]!! }
        val old = missing.pickOrNull() ?: return null
        val target = s.live.pickOrNull() ?: return null
        return Planned.Act("relink missing ${old.take(ID)} to ${target.short()}") { session ->
            session.maintenance.applyRelink(old, target.docId)
            "done"
        }
    }

    // ---- auto-match rules -----------------------------------------------------------------------

    private fun saveRule(): Planned.Act {
        val id = "rule-${random.nextInt(RULES)}"
        val onName = random.chance(30)
        val rule =
            AutoMatchRule(
                id = id,
                name = "Rule $id ${random.nextInt(LABELS)}",
                enabled = random.chance(85),
                field = if (onName) RuleField.FileName else RuleField.Content,
                algorithm =
                    if (onName) {
                        MatchAlgorithm.ANY_WORD
                    } else {
                        random.pick(listOf(MatchAlgorithm.ANY_WORD, MatchAlgorithm.EXACT))
                    },
                pattern = if (onName) random.pick(listOf("scan", "invoice", "Letter")) else random.pick(WORDS),
                tags = listOfNotNull(random.pick(TAGS).takeIf { random.chance(60) }),
                sets =
                    if (random.chance(60)) {
                        mapOf(
                            random.pick(listOf(FieldKeys.CORRESPONDENT, FieldKeys.DOC_TYPE, "project")) to
                                random.pick(WORDS),
                        )
                    } else {
                        emptyMap()
                    },
                position = random.nextInt(RULES),
            )
        return Planned.Act("save rule $rule") { session ->
            session.rules.save(rule)
            "saved"
        }
    }

    private fun ruleToggle(): Planned.Act {
        val on = random.nextBoolean()
        return ruleAct("enabled=$on") { session, id -> session.rules.setEnabled(id, on) }
    }

    private fun ruleAct(
        what: String,
        body: suspend (LibrarySessionImpl, String) -> Unit,
    ): Planned.Act {
        val id = "rule-${random.nextInt(RULES)}"
        return Planned.Act("rule $id $what") { session ->
            body(session, id)
            "done"
        }
    }

    // ---- the watched folder ---------------------------------------------------------------------

    /** The continuous importer a host runs beside the session, started in its scope. */
    fun startImporter(
        session: LibrarySessionImpl,
        scope: CoroutineScope,
    ) {
        sweepLock = Mutex()
        val folder = watched ?: return
        importer(session, folder).start(scope, POLL_MILLIS, sim.world.scheduler.timeSource)
    }

    private fun importer(
        session: LibrarySessionImpl,
        folder: SimWatchedFolder,
    ) = WatchedFolderImporter(
        ingest = folder.proving(session),
        folder = folder,
        deleteAfterImport = { deleteAfterImport },
        log = sim.replica.log,
        sweepLock = sweepLock,
        clock = sim.world.clock,
    )

    private fun watchedDrop(bytes: ByteArray): Planned.Control? {
        val folder = watched ?: return null
        val name = random.pick(NAMES)
        return Planned.Control("watched folder: write ${hash(bytes)} as '$name'", instant = true) {
            folder.write(name, bytes)
            "done"
        }
    }

    private fun watchedChunked(): Planned.Control? {
        val folder = watched ?: return null
        val bytes = newPdf()
        val name = random.pick(NAMES)
        val chunks = random.nextInt(2, CHUNKS)
        val interval = random.nextLong(200, CHUNK_INTERVAL_MILLIS)
        val text = "watched folder: write ${hash(bytes)} as '$name' in $chunks chunks ${interval}ms apart"
        return Planned.Control(text, instant = true) {
            sim.world.launch { folder.writeInChunks(name, bytes, chunks, interval) }
            "started"
        }
    }

    private fun watchedOverwrite(name: String): Planned.Control {
        val bytes = newPdf()
        return Planned.Control("watched folder: overwrite '$name' with ${hash(bytes)}", instant = true) {
            watched?.write(name, bytes)
            "done"
        }
    }

    private fun watchedRemove(name: String) =
        Planned.Control("watched folder: remove '$name'", instant = true) {
            watched?.remove(name)
            "done"
        }

    private fun watchedSweep(): Planned.Act? {
        val folder = watched ?: return null
        return Planned.Act("sweep the watched folder once (deleteAfterImport=$deleteAfterImport)") { session ->
            importer(session, folder).scanOnce().toString()
        }
    }

    // ---- faults ---------------------------------------------------------------------------------

    private fun fault(): Planned.Control {
        val method = random.pick(if (random.chance(70)) FsMethod.entries.filter { it.writes } else FsMethod.entries)
        val prefix = random.pick(FAULT_PREFIXES)
        val point = if (method.writes && random.chance(30)) SimFaults.Point.AFTER else SimFaults.Point.BEFORE
        val rule = SimFaults.Rule(method, prefix, point, times = random.nextInt(1, FAULT_TIMES))
        return Planned.Control("arm fault $rule", instant = true) {
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
}

// [SimGenerator]'s limits, and the names, words and kinds it draws from.
private const val ATTEMPTS = 20
private const val SIZE_BASE = 400
private const val SHARED_SIZES = 3
private const val ID = 8
private const val CLEAR_PERCENT = 20
private const val SHORT_MAX_MILLIS = 6_000L
private const val TICK_MIN_MILLIS = 60_000L
private const val TICK_MAX_MILLIS = 20L * 60L * 1000L
private const val DAY_MILLIS = 25L * 60L * 60L * 1000L
private const val CHUNKS = 6
private const val CHUNK_INTERVAL_MILLIS = 2_500L
private const val FAULT_TIMES = 4
private const val OCR_BURST = 4
private const val CONCURRENCY = 3
private const val DURING = 2
private const val DURING_MILLIS = 600L

/** How many times, and within how many virtual millis of its start, the sync tool scans during a failing split. */
private const val SPLIT_SCANS = 2
private const val SPLIT_SCAN_MILLIS = 1_500L
private const val RETENTION_MAX_DAYS = 60
private const val LABELS = 5

/** One in how many changes to a seeded field removes it rather than relabels it. */
private const val REMOVE_SEEDED_ONE_IN = 3
private const val VIEWS = 3
private const val NAMED = 12
private const val PARTIAL = 12
private const val MAX_PAGES = 4
private const val RULES = 4
private const val POLL_MILLIS = 60_000L

private val NAMES = listOf("scan.pdf", "invoice.pdf", "Letter.pdf", "report 2024.pdf", "scan (1).pdf")
private val DIRS = listOf("", "", NpnPaths.DEFAULT_INBOX, "archive", "archive/2024", "Tax")
private val WORDS = listOf("ACME", "Globex", "acme", "Initech", "Umbrella Corp", "Q3 report")
private val DATES = listOf("2024-01-15", "2023-12-31", "2024-02-29")
private val NUMBERS = listOf("7", "007", "42", "-3")
private val DECIMALS = listOf("1.5", "1.50", "0.25", "12")
private val TAGS = listOf("tax", "bank", "Tax", "receipts", "todo")
private val BUCKETS = listOf("00", "3f", "a1")
private val TEMPLATES =
    listOf(
        "{correspondent}/{title}.pdf",
        "{doc_date:yyyy}/{title}.pdf",
        "{tags}/{title}.pdf",
        "{field:project}/{title}.pdf",
    )
private val FAULT_PREFIXES =
    listOf(
        null,
        null,
        NpnPaths.JOURNAL_DIR,
        NpnPaths.TRASH_DIR,
        NpnPaths.OCR_DIR,
        NpnPaths.TMP_DIR,
        NpnPaths.DEFAULT_INBOX,
    )
private val CUSTOM_FIELDS =
    listOf(
        "amount" to FieldType.DECIMAL,
        "pages" to FieldType.NUMBER,
        "due" to FieldType.DATE,
        "project" to FieldType.TEXT,
        FieldKeys.CORRESPONDENT to FieldType.TEXT,
    )
private val DURING_KINDS =
    listOf(
        OpKind.EXT_DROP_PDF,
        OpKind.EXT_CHUNKED_WRITE,
        OpKind.EXT_OVERWRITE,
        OpKind.EXT_TOUCH,
        OpKind.EXT_DELETE,
        OpKind.EXT_MOVE,
        OpKind.EXT_RESTORE_BACKUP,
        OpKind.EXT_SYNC_TEMP,
        OpKind.FAULT,
        OpKind.OCR_COMPLETE,
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
        OpKind.BAKE,
        OpKind.SPLIT,
        OpKind.RULE_SAVE,
        OpKind.RELINK_MISSING,
        OpKind.WATCHED_SWEEP,
    )
private val WATCHED_KINDS =
    setOf(
        OpKind.WATCHED_DROP,
        OpKind.WATCHED_CHUNKED,
        OpKind.WATCHED_KNOWN,
        OpKind.WATCHED_OVERWRITE,
        OpKind.WATCHED_REMOVE,
        OpKind.WATCHED_SWEEP,
    )

private fun rescan() =
    Planned.Act("rescan") { session ->
        val report = session.rescan()
        "added ${report.added}, retry ${report.retryAfterMillis}"
    }

private val EMPTY_TAGS = List(3) { "empty-$it" }

/**
 * [bake] of [doc] whose move of the baked bytes into place fails, and so does the move of the
 * original back from its recovery name: the bake fails with the original kept there, which the
 * next rescan indexes, and the storage's refusal is the reason it reports.
 */
private fun bakeRestoreRefused(
    sim: Simulation,
    doc: SimDoc,
    bake: Planned.Act,
): Planned.Group {
    // Both moves land at the document's path; the move aside, to a recovery name, does not.
    val rule = SimFaults.Rule(FsMethod.MOVE, doc.relPath, SimFaults.Point.BEFORE, times = 2)
    val fault =
        Planned.Control("arm fault $rule", instant = true) {
            sim.fs.faults.arm(rule)
            "armed"
        }
    return Planned.Group(listOf(bake), listOf(0L to fault))
}

/**
 * [bake] of [doc], whose move of the original aside lands but reports a failure, so the bake puts
 * it back, while the app rescans back to back, as its watcher does after another change in the
 * folder: a rescan that lists the original under its recovery name sees the document there, and
 * must leave the file to the bake rather than move it on to the document's path meanwhile.
 */
private fun bakeRescanned(
    sim: Simulation,
    doc: SimDoc,
    bake: Planned.Act,
): Planned.Group {
    val folder = NpnPaths.parentDir(doc.relPath)
    val aside = (if (folder.isEmpty()) "" else "$folder/") + NpnPaths.Temp.RECOVERY_PREFIX
    val rule = SimFaults.Rule(FsMethod.MOVE, aside, SimFaults.Point.AFTER, times = 1)
    val fault =
        Planned.Control("arm fault $rule", instant = true) {
            sim.fs.faults.arm(rule)
            "armed"
        }
    val baking = CompletableDeferred<Unit>()
    val baked =
        Planned.Act(bake.text) { session ->
            try {
                bake.body(session)
            } finally {
                baking.complete(Unit)
            }
        }
    val rescans =
        Planned.Act("rescan until the bake ends") { session ->
            var count = 0
            while (!baking.isCompleted) {
                session.rescan()
                count++
            }
            "$count rescan(s)"
        }
    return Planned.Group(listOf(baked, rescans), listOf(0L to fault))
}

/**
 * [split], whose last step, the trash of its original, fails, so it takes its parts back, while the
 * sync tool scans the replica part-way through ([scan]; null where none syncs it, and no step): a
 * part it carries to the other devices before the split takes it back is a document there.
 */
private fun splitSeenFailing(
    random: Random,
    sim: Simulation,
    scan: (() -> Int)?,
    split: Planned.Act,
): Planned.Group? {
    if (scan == null) return null
    val rule = SimFaults.Rule(FsMethod.MOVE, NpnPaths.TRASH_DIR, SimFaults.Point.BEFORE, times = 1)
    val fault =
        Planned.Control("arm fault $rule", instant = true) {
            sim.fs.faults.arm(rule)
            "armed"
        }
    val scans =
        List(SPLIT_SCANS) {
            random.nextLong(0, SPLIT_SCAN_MILLIS) to
                Planned.Control("sync: scan this device", instant = true) { "${scan()} path(s) changed" }
        }
    return Planned.Group(listOf(split), listOf(0L to fault) + scans)
}

private fun emptyTag(
    random: Random,
    create: Boolean,
): Planned.Act {
    val tag = random.pick(EMPTY_TAGS)
    // A created tag needs no style: its marker keeps it.
    val color = "#123456".takeIf { random.nextBoolean() }
    return Planned.Act(
        "${if (create) "create" else "delete"} empty tag $tag${color?.let { " ($it)" }.orEmpty()}",
    ) { session ->
        if (create) session.tagAdmin.createTag(tag, color = color) else session.tagAdmin.deleteTag(tag)
        "updated $tag"
    }
}

/**
 * A released build (0.1.0) renaming a created tag: it writes the rename alone, leaving the marker
 * and the style under the old name. The old name must not come back as an empty tag.
 */
private fun legacyRenameTag(random: Random): Planned.Act {
    val n = random.nextInt(3)
    val from = "legacy-created-$n"
    val to = "legacy-renamed-$n"
    return Planned.Act("legacy rename of created tag $from -> $to") { session ->
        session.tagAdmin.createTag(from, color = "#654321")
        session.coordinator.commitAndApply(listOf(PendingOp.tagRename(from, to)))
        val tags = session.tags.observeTags().first()
        check(tags.none { it.name == from && it.count == 0 }) { "legacy rename left $from behind as an empty tag" }
        check(tags.any { it.name == to }) { "legacy rename lost the created tag $to" }
        "renamed $from"
    }
}

/**
 * Rename or merge a tag, documents carry it or not, onto a name documents may already carry.
 * A created tag merged into one documents carry stays a tag only while they do: the target is
 * not kept by the source's marker. That is checked when a live document carried the target and
 * still does after the write, and the target had no marker of its own.
 */
private fun retag(
    random: Random,
    sim: Simulation,
    s: Snapshot,
    merge: Boolean,
): Planned.Act? {
    fun List<String>.pickOrNull(): String? = if (isEmpty()) null else this[random.nextInt(size)]
    val from = (s.tags + EMPTY_TAGS).distinct().pickOrNull() ?: return null
    val to = (if (merge) s.tags + EMPTY_TAGS else TAGS + EMPTY_TAGS).filter { it != from }.pickOrNull() ?: return null
    val verb = if (merge) "merge" else "rename"
    return Planned.Act("$verb tag $from -> $to") { session ->
        val keptBefore = session.keptTag(to)
        val carriersBefore = liveCarriers(sim, to)
        if (merge) session.tagAdmin.mergeTag(from, to) else session.tagAdmin.renameTag(from, to)
        val stillCarried = carriersBefore.intersect(liveCarriers(sim, to)).isNotEmpty()
        check(keptBefore || !stillCarried || !session.keptTag(to)) {
            "$verb of $from made $to, which documents carry, a kept tag"
        }
        "done"
    }
}

/** The live documents [sim]'s index shows carrying [tag] (a name of [TAGS] or [EMPTY_TAGS]). */
private suspend fun liveCarriers(
    sim: Simulation,
    tag: String,
): Set<String> =
    sim.replica.indexDb
        .rows(
            "SELECT dt.docId FROM document_tags dt JOIN documents d ON d.docId = dt.docId " +
                "WHERE dt.tag = '$tag' AND d.trashed = 0 AND d.deleted = 0 AND d.missing = 0",
        ).mapTo(HashSet()) { it[0]!! }

/** Whether [tag] carries the explicitly-created marker in [this] session's fold. */
private fun LibrarySessionImpl.keptTag(tag: String): Boolean = coordinator.setting(TagRetention.key(tag)) == "1"

// A released build can leave this style after deleting or renaming its last document's tag.
private fun legacyUnusedStyle(random: Random): Planned.Act {
    val tag = "legacy-unused-${random.nextInt(3)}"
    return Planned.Act("legacy unused style $tag") { session ->
        session.tagAdmin.setTagStyle(tag, "#123456", null)
        check(
            session.tags
                .observeTags()
                .first()
                .none { it.name == tag },
        ) {
            "legacy unused style resurrected $tag"
        }
        "styled $tag"
    }
}
