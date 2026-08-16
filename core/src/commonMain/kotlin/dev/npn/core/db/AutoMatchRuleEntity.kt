package dev.npn.core.db

import androidx.room3.Dao
import androidx.room3.Delete
import androidx.room3.Entity
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.Upsert
import dev.npn.core.rules.AutoMatchRule
import dev.npn.core.rules.MatchAlgorithm
import dev.npn.core.rules.RuleField
import kotlinx.coroutines.flow.Flow

/**
 * Local-only rule storage. Rules are not part of the journal; they live in the disposable index
 * DB and are rebuilt by the user on each device.
 */
@Entity(tableName = "auto_match_rules")
data class AutoMatchRuleEntity(
    @PrimaryKey val id: String,
    val name: String,
    val enabled: Boolean,
    val field: String,
    val algorithm: String,
    val pattern: String,
    /** Comma-separated tag names to add when the rule matches. */
    val tags: String?,
    val correspondent: String?,
    val docType: String?,
    val position: Int,
)

@Dao
interface AutoMatchRuleDao {
    @Query("SELECT * FROM auto_match_rules ORDER BY position, id")
    fun observeAll(): Flow<List<AutoMatchRuleEntity>>

    @Query("SELECT * FROM auto_match_rules ORDER BY position, id")
    suspend fun getAll(): List<AutoMatchRuleEntity>

    @Upsert
    suspend fun upsert(rule: AutoMatchRuleEntity)

    @Delete
    suspend fun delete(rule: AutoMatchRuleEntity)

    @Query("DELETE FROM auto_match_rules WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE auto_match_rules SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(
        id: String,
        enabled: Boolean,
    )

    @Query("DELETE FROM auto_match_rules")
    suspend fun clearAll()
}

internal fun AutoMatchRuleEntity.toRule(): AutoMatchRule =
    AutoMatchRule(
        id = id,
        name = name,
        enabled = enabled,
        field = RuleField.entries.firstOrNull { it.name == field } ?: RuleField.CONTENT,
        algorithm = MatchAlgorithm.entries.firstOrNull { it.name == algorithm } ?: MatchAlgorithm.ANY_WORD,
        pattern = pattern,
        tags = tags?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList(),
        correspondent = correspondent,
        docType = docType,
        position = position,
    )

internal fun AutoMatchRule.toEntity(): AutoMatchRuleEntity =
    AutoMatchRuleEntity(
        id = id,
        name = name.trim(),
        enabled = enabled,
        field = field.name,
        algorithm = algorithm.name,
        pattern = pattern.trim(),
        tags =
            tags
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .joinToString(",")
                .takeIf { it.isNotEmpty() },
        correspondent = correspondent?.trim()?.ifEmpty { null },
        docType = docType?.trim()?.ifEmpty { null },
        position = position,
    )
