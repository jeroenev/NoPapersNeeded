package dev.npn.app.ui.common

import dev.npn.core.app.FieldRegistry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * The distinct stored values of every registry field whose type has a vocabulary
 * ([dev.npn.core.model.FieldType.hasVocabulary]): key -> values, for filters to pick from and editors to complete
 * from. Follows the registry, so a newly defined TEXT field starts offering its values as soon as
 * documents carry any.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun FieldRegistry.textFieldValues(): Flow<Map<String, List<String>>> =
    fields.flatMapLatest { defs ->
        val text = defs.filter { it.type.hasVocabulary }
        if (text.isEmpty()) {
            flowOf(emptyMap())
        } else {
            combine(text.map { def -> distinctValues(def.key).map { def.key to it } }) { it.toMap() }
        }
    }
