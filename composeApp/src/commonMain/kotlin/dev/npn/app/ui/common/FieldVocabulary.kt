package dev.npn.app.ui.common

import dev.npn.core.app.FieldRegistry
import dev.npn.core.model.FieldDef
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * The distinct stored values of every registry field whose type has a vocabulary
 * ([dev.npn.core.model.FieldType.hasVocabulary]): key -> values, for filters to pick from. Follows
 * the registry, so a newly defined TEXT field starts offering its values as soon as documents carry
 * any.
 */
fun FieldRegistry.textFieldValues(): Flow<Map<String, List<String>>> = valuesOf { it.type.hasVocabulary }

/**
 * The values an editor offers for reuse: [textFieldValues] of only the fields whose documents share
 * them ([FieldDef.sharesVocabulary]). A [FieldDef.perDocument] field, such as the title, offers none.
 */
fun FieldRegistry.reusableFieldValues(): Flow<Map<String, List<String>>> = valuesOf { it.sharesVocabulary }

@OptIn(ExperimentalCoroutinesApi::class)
private fun FieldRegistry.valuesOf(include: (FieldDef) -> Boolean): Flow<Map<String, List<String>>> =
    fields.flatMapLatest { defs ->
        val listed = defs.filter(include)
        if (listed.isEmpty()) {
            flowOf(emptyMap())
        } else {
            combine(listed.map { def -> distinctValues(def.key).map { def.key to it } }) { it.toMap() }
        }
    }
