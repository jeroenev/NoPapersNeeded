package dev.npn.core.db

import androidx.room3.AutoMigration
import androidx.room3.ConstructedBy
import androidx.room3.Database
import androidx.room3.DeleteColumn
import androidx.room3.DeleteTable
import androidx.room3.RoomDatabase
import androidx.room3.RoomDatabaseConstructor
import androidx.room3.migration.AutoMigrationSpec

/**
 * Local index database. This is a disposable cache: a materialized view of the file library
 * plus the metadata journals. It lives in app-local storage, is never synced,
 * and is fully rebuildable from the folder alone. Because of that, destructive migration is the
 * correct fallback (see the platform builders): a schema bump just drops and rebuilds.
 */
@Database(
    // v2: documents.status (enum) replaced by documents.reviewed (boolean).
    // v3: added document_fields. v4 adds the persisted metadata source/event accumulator.
    // v5: drops the folded_segments cache table (dead JournalIndex fold pipeline removed).
    // v6: adds metadata_superseded to track compaction snapshot supersession.
    // v7: adds documents.ocrEngine (indexed OCR version) + documents.ocrEngineOverride.
    // v8: adds auto_match_rules. v9 adds journal-projected notes and their FTS column.
    // v10: indexes documents.addedAt for the default library sort.
    // v11: adds documents.aiEnrichedAt (post-OCR AI enrichment marker) + its ai_enrich_queue.
    // v12: adds ai_edit_jobs + ai_edit_job_docs (AI edit runs handed to the background).
    // v13: indexes metadata_events.documentId + adds the source-set fingerprint the open path
    //      uses to skip a whole-journal refold when nothing changed.
    // v14: adds field_defs (the projected field registry).
    // v15: document_fields becomes the authoritative projection of every user value (numValue +
    //      composite indices); FTS indexes one `fields` column instead of per-property columns.
    // v16: auto_match_rules sets any field (JSON `sets` column) instead of correspondent/docType.
    // v17: adds documents.ocrSource (the indexed sidecar's writer key) + ocr_queue.rerun.
    // v18: adds documents.trashedAt (when the winning doc-trash happened) for the trash auto-purge.
    // v19: adds ocr_queue.aiRequested (an AI OCR run asked for on this device, unlike the synced pin).
    // v20: adds ocr_queue.nextAttemptAt (retry backoff for failed OCR runs).
    // v21: adds ocr_page_cache (pages a failed OCR run read, so its retry does not pay for them).
    version = 21,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5, spec = NpnDatabase.DropFoldedSegments::class),
        AutoMigration(from = 5, to = 6),
        AutoMigration(from = 6, to = 7),
        AutoMigration(from = 7, to = 8),
        AutoMigration(from = 8, to = 9),
        AutoMigration(from = 9, to = 10),
        AutoMigration(from = 10, to = 11),
        AutoMigration(from = 11, to = 12),
        AutoMigration(from = 12, to = 13),
        AutoMigration(from = 13, to = 14),
        AutoMigration(from = 14, to = 15, spec = NpnDatabase.FtsFieldsColumn::class),
        AutoMigration(from = 15, to = 16, spec = NpnDatabase.RuleSetsColumn::class),
        AutoMigration(from = 16, to = 17),
        AutoMigration(from = 17, to = 18),
        AutoMigration(from = 18, to = 19),
        AutoMigration(from = 19, to = 20),
        AutoMigration(from = 20, to = 21),
    ],
    entities = [
        DocumentEntity::class,
        TagEntity::class,
        DocumentTagEntity::class,
        DocumentFieldEntity::class,
        FieldDefEntity::class,
        FtsDocumentEntity::class,
        SavedViewEntity::class,
        ThumbnailEntity::class,
        DuplicateEntity::class,
        OcrQueueEntity::class,
        OcrPageCacheEntity::class,
        AiEnrichQueueEntity::class,
        AiEditJobEntity::class,
        AiEditJobDocEntity::class,
        KeyValueEntity::class,
        MetadataSourceEntity::class,
        MetadataEventEntity::class,
        MetadataSourceEventEntity::class,
        MetadataTransactionActorEntity::class,
        MetadataProjectionStateEntity::class,
        MetadataSupersededEntity::class,
        AutoMatchRuleEntity::class,
    ],
)
@ConstructedBy(NpnDatabaseConstructor::class)
abstract class NpnDatabase : RoomDatabase() {
    abstract fun documentDao(): DocumentDao

    abstract fun tagDao(): TagDao

    abstract fun ftsDao(): FtsDao

    abstract fun documentFieldDao(): DocumentFieldDao

    abstract fun fieldDefDao(): FieldDefDao

    abstract fun savedViewDao(): SavedViewDao

    abstract fun thumbnailDao(): ThumbnailDao

    abstract fun duplicateDao(): DuplicateDao

    abstract fun ocrQueueDao(): OcrQueueDao

    abstract fun aiEnrichQueueDao(): AiEnrichQueueDao

    abstract fun aiEditJobDao(): AiEditJobDao

    abstract fun keyValueDao(): KeyValueDao

    abstract fun metadataProjectionDao(): MetadataProjectionDao

    abstract fun autoMatchRuleDao(): AutoMatchRuleDao

    /**
     * v4 -> v5: drop the `folded_segments` cache table. It backed the removed JournalIndex fold
     * pipeline; the metadata accumulator now owns source tracking. Dropping a pure cache table is
     * safe (the whole DB is rebuildable), but the explicit spec keeps existing installs from
     * crashing on upgrade instead of relying on destructive fallback.
     */
    @DeleteTable(tableName = "folded_segments")
    class DropFoldedSegments : AutoMigrationSpec

    /**
     * v14 -> v15: the per-property FTS columns give way to one `fields` column. The index is
     * rebuilt from the library on the next fold anyway; the explicit spec only keeps an existing
     * install from falling into destructive migration and losing device-local state with it.
     */
    @DeleteColumn.Entries(
        DeleteColumn(tableName = "fts_documents", columnName = "title"),
        DeleteColumn(tableName = "fts_documents", columnName = "correspondent"),
        DeleteColumn(tableName = "fts_documents", columnName = "docType"),
        DeleteColumn(tableName = "fts_documents", columnName = "notes"),
    )
    class FtsFieldsColumn : AutoMigrationSpec

    /**
     * v15 -> v16: a rule sets any field, so the two named columns give way to a JSON `sets`
     * column. The table is a mirror of the settings blob and is reconciled from it on open, so
     * the migrated rows' empty `sets` are overwritten before anything reads them.
     */
    @DeleteColumn.Entries(
        DeleteColumn(tableName = "auto_match_rules", columnName = "correspondent"),
        DeleteColumn(tableName = "auto_match_rules", columnName = "docType"),
    )
    class RuleSetsColumn : AutoMigrationSpec
}

/**
 * KSP generates the platform actuals for this constructor. Do not implement it manually.
 * The compiler expects the actual to be provided by the generated code.
 */
@Suppress("KotlinNoActualForExpect")
expect object NpnDatabaseConstructor : RoomDatabaseConstructor<NpnDatabase> {
    override fun initialize(): NpnDatabase
}

/** Database file name used by the platform builders. */
const val NPN_DATABASE_NAME: String = "npn-index.db"
