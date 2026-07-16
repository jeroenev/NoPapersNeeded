package dev.npn.core.db

import androidx.room3.ConstructedBy
import androidx.room3.Database
import androidx.room3.RoomDatabase
import androidx.room3.RoomDatabaseConstructor

/**
 * Local index database. This is a disposable cache: a materialized view of the file library
 * plus the metadata journals (PLAN.md sec. 1). It lives in app-local storage, is never synced,
 * and is fully rebuildable from the folder alone. Because of that, destructive migration is the
 * correct fallback (see the platform builders): a schema bump just drops and rebuilds.
 */
@Database(
    // v2: documents.status (enum) replaced by documents.reviewed (boolean). Cache is rebuildable,
    // so the platform builders drop and rebuild via fallbackToDestructiveMigration.
    version = 2,
    exportSchema = true,
    entities = [
        DocumentEntity::class,
        TagEntity::class,
        DocumentTagEntity::class,
        FtsDocumentEntity::class,
        FoldedSegmentEntity::class,
        SavedViewEntity::class,
        ThumbnailEntity::class,
        DuplicateEntity::class,
        OcrQueueEntity::class,
        KeyValueEntity::class,
    ],
)
@ConstructedBy(NpnDatabaseConstructor::class)
abstract class NpnDatabase : RoomDatabase() {
    abstract fun documentDao(): DocumentDao
    abstract fun tagDao(): TagDao
    abstract fun ftsDao(): FtsDao
    abstract fun foldedSegmentDao(): FoldedSegmentDao
    abstract fun savedViewDao(): SavedViewDao
    abstract fun thumbnailDao(): ThumbnailDao
    abstract fun duplicateDao(): DuplicateDao
    abstract fun ocrQueueDao(): OcrQueueDao
    abstract fun keyValueDao(): KeyValueDao
}

/**
 * KSP generates the platform actuals for this constructor. Do not implement it manually.
 */
// The compiler expects the actual to be provided by the generated code.
@Suppress("KotlinNoActualForExpect")
expect object NpnDatabaseConstructor : RoomDatabaseConstructor<NpnDatabase> {
    override fun initialize(): NpnDatabase
}

/** Database file name used by the platform builders. */
const val NPN_DATABASE_NAME: String = "npn-index.db"
