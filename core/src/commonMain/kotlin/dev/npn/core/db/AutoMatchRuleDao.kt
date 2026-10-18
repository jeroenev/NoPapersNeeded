package dev.npn.core.db

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Upsert
import dev.npn.core.rules.AutoMatchRule
import dev.npn.core.rules.MatchAlgorithm
import dev.npn.core.rules.RuleField
import dev.npn.core.util.NpnJson
import kotlinx.coroutines.flow.Flow

@Dao
interface AutoMatchRuleDao {
    @Query("SELECT * FROM auto_match_rules ORDER BY position, id")
    fun observeAll(): Flow<List<AutoMatchRuleEntity>>

    @Query("SELECT * FROM auto_match_rules ORDER BY position, id")
    suspend fun getAll(): List<AutoMatchRuleEntity>

    @Upsert
    suspend fun upsertAll(rules: List<AutoMatchRuleEntity>)

    /**
     * Replace this mirror of the persisted rule list in one transaction, so the rules Room flow
     * sees a single invalidation rather than one per rule.
     */
    @Transaction
    suspend fun replaceAll(rules: List<AutoMatchRuleEntity>) {
        clearAll()
        if (rules.isNotEmpty()) upsertAll(rules)
    }

    @Query("DELETE FROM auto_match_rules")
    suspend fun clearAll()
}

internal fun AutoMatchRuleEntity.toRule(): AutoMatchRule =
    AutoMatchRule(
        id = id,
        name = name,
        enabled = enabled,
        field = RuleField.fromWire(field) ?: RuleField.Content,
        algorithm = MatchAlgorithm.entries.firstOrNull { it.name == algorithm } ?: MatchAlgorithm.ANY_WORD,
        pattern = pattern,
        tags = tags?.let(::cachedTags).orEmpty(),
        sets = runCatching { cacheJson.decodeFromString<Map<String, String>>(sets) }.getOrDefault(emptyMap()),
        position = position,
    )

/** The row caching [this] rule, which comes [AutoMatchRule.normalized] from the journal's list. */
internal fun AutoMatchRule.toEntity(): AutoMatchRuleEntity =
    AutoMatchRuleEntity(
        id = id,
        name = name,
        enabled = enabled,
        field = field.wire,
        algorithm = algorithm.name,
        pattern = pattern,
        tags = cacheJson.encodeToString(tags),
        sets = cacheJson.encodeToString(sets),
        position = position,
    )

/**
 * The tags a row caches, a JSON list. Earlier builds cached them comma-joined, which split a tag
 * holding a comma; such a row reads that way until the journal's list is mirrored over it.
 */
private fun cachedTags(stored: String): List<String> =
    runCatching { cacheJson.decodeFromString<List<String>>(stored) }
        .getOrElse { stored.split(',').map(String::trim).filter(String::isNotEmpty) }

private val cacheJson = NpnJson.Persisted
