package dev.npn.core.db

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Upsert
import dev.npn.core.model.FieldDef
import dev.npn.core.model.FieldType
import kotlinx.coroutines.flow.Flow

/** The projected field registry ([FieldDefEntity]); written only by the fold. */
@Dao
interface FieldDefDao {
    @Query("SELECT * FROM field_defs ORDER BY position, fieldKey")
    fun observeAll(): Flow<List<FieldDefEntity>>

    @Query("SELECT * FROM field_defs ORDER BY position, fieldKey")
    suspend fun all(): List<FieldDefEntity>

    /** Distinct field keys carrying at least one value but no registry definition, with counts. */
    @Query(
        "SELECT f.fieldKey AS fieldKey, COUNT(*) AS count FROM document_fields f " +
            "LEFT JOIN field_defs d ON d.fieldKey = f.fieldKey " +
            "WHERE d.fieldKey IS NULL GROUP BY f.fieldKey ORDER BY f.fieldKey COLLATE NOCASE",
    )
    fun observeOrphanKeys(): Flow<List<FieldKeyCount>>

    @Upsert
    suspend fun upsertAll(defs: List<FieldDefEntity>)

    @Query("DELETE FROM field_defs WHERE fieldKey IN (:keys)")
    suspend fun deleteKeys(keys: List<String>)

    /**
     * Make the table equal to [defs]. Diffed: unchanged rows are not rewritten, so an unrelated
     * fold does not invalidate registry observers.
     */
    @Transaction
    suspend fun replaceAll(defs: List<FieldDef>) {
        val existing = all().associateBy { it.fieldKey }
        val wanted = defs.map { it.toEntity() }
        val changed = wanted.filter { existing[it.fieldKey] != it }
        if (changed.isNotEmpty()) upsertAll(changed)
        val stale = existing.keys - wanted.map { it.fieldKey }.toSet()
        if (stale.isNotEmpty()) deleteKeys(stale.toList())
    }
}

fun FieldDef.toEntity(): FieldDefEntity =
    FieldDefEntity(
        fieldKey = key,
        label = label,
        type = type.wire,
        showByDefault = showByDefault,
        aiEnabled = aiEnabled,
        position = position,
        hint = hint,
        perDocument = perDocument,
    )

/** Null when the stored type wire is unknown to this build (a newer client added a type). */
fun FieldDefEntity.toFieldDef(): FieldDef? {
    val type = FieldType.fromWire(type) ?: return null
    return FieldDef(
        key = fieldKey,
        label = label,
        type = type,
        showByDefault = showByDefault,
        aiEnabled = aiEnabled,
        position = position,
        hint = hint,
        perDocument = perDocument,
    )
}

/** Every registered field, in registry order, skipping types this build does not know. */
suspend fun FieldDefDao.defs(): List<FieldDef> = all().mapNotNull { it.toFieldDef() }

/** Every registered field's type by key, skipping types this build does not know. */
suspend fun FieldDefDao.fieldTypes(): Map<String, FieldType> = defs().associate { it.key to it.type }
