package dev.npn.app.ui.common

import dev.npn.core.app.FieldRegistry
import dev.npn.core.model.FieldType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * Autocomplete vocabulary for every single-line text field in the registry: key -> distinct
 * stored values. Follows the registry, so a newly defined TEXT field starts offering its values
 * as soon as documents carry any. Other types have no useful completion list and are left out.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun FieldRegistry.textFieldValues(): Flow<Map<String, List<String>>> =
    fields.flatMapLatest { defs ->
        val text = defs.filter { it.type == FieldType.TEXT }
        if (text.isEmpty()) {
            flowOf(emptyMap())
        } else {
            combine(text.map { def -> distinctValues(def.key).map { def.key to it } }) { it.toMap() }
        }
    }
