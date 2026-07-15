@file:OptIn(ExperimentalLayoutApi::class)

package dev.npn.app.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.npn.core.app.LibraryFilter
import dev.npn.core.model.DocStatus

/** Removable chips for each active filter facet under the search bar. */
@Composable
fun ActiveFilters(
    filter: LibraryFilter,
    onFilterChange: (LibraryFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    val chips = buildList {
        filter.tagsAll.forEach { t -> add("AND $t" to { onFilterChange(filter.copy(tagsAll = filter.tagsAll - t)) }) }
        filter.tagsAny.forEach { t -> add("OR $t" to { onFilterChange(filter.copy(tagsAny = filter.tagsAny - t)) }) }
        filter.tagsNone.forEach { t -> add("NOT $t" to { onFilterChange(filter.copy(tagsNone = filter.tagsNone - t)) }) }
        filter.status?.let { s -> add(statusLabel(s) to { onFilterChange(filter.copy(status = null)) }) }
        filter.correspondent?.let { c -> add(c to { onFilterChange(filter.copy(correspondent = null)) }) }
        filter.docType?.let { d -> add(d to { onFilterChange(filter.copy(docType = null)) }) }
        if (filter.untaggedOnly) add("Untagged" to { onFilterChange(filter.copy(untaggedOnly = false)) })
    }

    if (chips.isEmpty()) return

    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        chips.forEach { (label, remove) ->
            InputChip(
                selected = false,
                onClick = remove,
                label = { Text(label) },
                trailingIcon = {
                    Icon(Icons.Filled.Clear, contentDescription = "Remove filter")
                },
            )
        }
    }
}

private fun statusLabel(s: DocStatus): String = when (s) {
    DocStatus.INBOX -> "Inbox"
    DocStatus.REVIEWED -> "Reviewed"
    DocStatus.FILED -> "Filed"
}
