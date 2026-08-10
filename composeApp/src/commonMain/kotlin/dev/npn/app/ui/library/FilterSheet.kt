@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package dev.npn.app.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.npn.app.ui.common.DatePickerField
import dev.npn.core.app.LibraryFilter
import dev.npn.core.app.TagInfo
import dev.npn.core.model.ReviewMode
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_cancel
import no_papers_needed.composeapp.generated.resources.common_needs_review
import no_papers_needed.composeapp.generated.resources.common_reviewed
import no_papers_needed.composeapp.generated.resources.library_any
import no_papers_needed.composeapp.generated.resources.library_apply_filters
import no_papers_needed.composeapp.generated.resources.library_clear_all
import no_papers_needed.composeapp.generated.resources.library_correspondent_filter
import no_papers_needed.composeapp.generated.resources.library_document_date_filter
import no_papers_needed.composeapp.generated.resources.library_document_type_filter
import no_papers_needed.composeapp.generated.resources.library_filter_tags_help
import no_papers_needed.composeapp.generated.resources.library_filters
import no_papers_needed.composeapp.generated.resources.library_from
import no_papers_needed.composeapp.generated.resources.library_no_tags_yet
import no_papers_needed.composeapp.generated.resources.library_reviewed_filter
import no_papers_needed.composeapp.generated.resources.library_search_correspondent
import no_papers_needed.composeapp.generated.resources.library_search_document_type
import no_papers_needed.composeapp.generated.resources.library_search_tags
import no_papers_needed.composeapp.generated.resources.library_tag_filter_all
import no_papers_needed.composeapp.generated.resources.library_tag_filter_any
import no_papers_needed.composeapp.generated.resources.library_tag_filter_none
import no_papers_needed.composeapp.generated.resources.library_to
import no_papers_needed.composeapp.generated.resources.library_untagged_only
import org.jetbrains.compose.resources.stringResource

/** Max height of the tag multi-select list before it becomes internally scrollable. */
private val TagListMaxHeight = 200.dp

/**
 * Filter panel. Tag multi-select is split into three sections (AND/OR/NOT); cycling a tag
 * chip moves it OFF -> ALL -> ANY -> NONE -> OFF so each tag lands in exactly one bucket.
 */
@Composable
fun FilterSheet(
    filter: LibraryFilter,
    tags: List<TagInfo>,
    correspondents: List<String>,
    docTypes: List<String>,
    reviewMode: ReviewMode,
    onApply: (LibraryFilter) -> Unit,
    onClearAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        FilterPanel(
            filter = filter,
            tags = tags,
            correspondents = correspondents,
            docTypes = docTypes,
            reviewMode = reviewMode,
            onApply = onApply,
            onClearAll = onClearAll,
            onDismiss = onDismiss,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
@Suppress("LongMethod")
fun FilterPanel(
    filter: LibraryFilter,
    tags: List<TagInfo>,
    correspondents: List<String>,
    docTypes: List<String>,
    reviewMode: ReviewMode,
    onApply: (LibraryFilter) -> Unit,
    onClearAll: () -> Unit,
    onDismiss: (() -> Unit)? = null,
    fillHeight: Boolean = false,
    modifier: Modifier = Modifier,
) {
    var draft by remember { mutableStateOf(filter) }

    Surface(modifier = modifier, tonalElevation = 3.dp) {
        Column(if (fillHeight) Modifier.fillMaxSize() else Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(Res.string.library_filters), style = MaterialTheme.typography.titleLarge)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {
                        draft = LibraryFilter()
                        onClearAll()
                    }) { Text(stringResource(Res.string.library_clear_all)) }
                    onDismiss?.let {
                        IconButton(onClick = it) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(Res.string.common_cancel))
                        }
                    }
                }
            }

            Column(
                Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TagFilterSection(tags, draft) { draft = it }

                // The stored reviewed marker is only maintained in the inbox workflow; outside it,
                // review state is derived from tags (reviewed == tagged) and the marker is stale.
                if (reviewMode == ReviewMode.INBOX_WORKFLOW) {
                    FilterFieldRow(stringResource(Res.string.library_reviewed_filter)) {
                        EnumDropdown(
                            current = ReviewFilter.from(draft.reviewed),
                            options = ReviewFilter.entries,
                            label = ::reviewFilterLabel,
                            onSelect = { draft = draft.copy(reviewed = it.value) },
                        )
                    }
                }

                FilterFieldRow(stringResource(Res.string.library_correspondent_filter)) {
                    EnumDropdown(
                        current = optionalFilterValue(draft.correspondent),
                        options = optionalFilterOptions(correspondents),
                        label = ::optionalFilterLabel,
                        onSelect = { draft = draft.copy(correspondent = it.valueOrNull()) },
                        searchLabel = stringResource(Res.string.library_search_correspondent),
                        searchText = { if (it is OptionalFilterValue.Value) it.value else "" },
                    )
                }

                FilterFieldRow(stringResource(Res.string.library_document_type_filter)) {
                    EnumDropdown(
                        current = optionalFilterValue(draft.docType),
                        options = optionalFilterOptions(docTypes),
                        label = ::optionalFilterLabel,
                        onSelect = { draft = draft.copy(docType = it.valueOrNull()) },
                        searchLabel = stringResource(Res.string.library_search_document_type),
                        searchText = { if (it is OptionalFilterValue.Value) it.value else "" },
                    )
                }

                SectionLabel(stringResource(Res.string.library_document_date_filter))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    DatePickerField(
                        value = draft.docDateFrom,
                        label = stringResource(Res.string.library_from),
                        onCommit = { draft = draft.copy(docDateFrom = it) },
                        modifier = Modifier.weight(1f),
                    )
                    DatePickerField(
                        value = draft.docDateTo,
                        label = stringResource(Res.string.library_to),
                        onCommit = { draft = draft.copy(docDateTo = it) },
                        modifier = Modifier.weight(1f),
                    )
                }

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(Res.string.library_untagged_only))
                    Switch(
                        checked = draft.untaggedOnly,
                        onCheckedChange = { draft = draft.copy(untaggedOnly = it) },
                    )
                }
            }

            HorizontalDivider()
            OutlinedButton(
                onClick = { onApply(draft) },
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            ) { Text(stringResource(Res.string.library_apply_filters)) }
        }
    }
}

@Composable
private fun TagFilterSection(
    tags: List<TagInfo>,
    filter: LibraryFilter,
    onFilterChange: (LibraryFilter) -> Unit,
) {
    SectionLabel(stringResource(Res.string.library_filter_tags_help))
    var tagQuery by remember { mutableStateOf("") }
    OutlinedTextField(
        value = tagQuery,
        onValueChange = { tagQuery = it },
        label = { Text(stringResource(Res.string.library_search_tags)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    val visibleTags = tags.filter { tagQuery.isBlank() || it.name.contains(tagQuery.trim(), ignoreCase = true) }
    // Cap the list so a large tag set cannot push the rest of the sheet out of view; the search box
    // above narrows the list when the cap clips it.
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = TagListMaxHeight)
            .verticalScroll(rememberScrollState())
            .padding(bottom = 4.dp),
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            visibleTags.forEach { tag ->
                val bucket = tagBucket(filter, tag.name)
                FilterChip(
                    selected = bucket != TagBucket.OFF,
                    onClick = { onFilterChange(cycleTag(filter, tag.name)) },
                    label = { Text(tagFilterLabel(bucket, tag.name)) },
                )
            }
            if (tags.isEmpty()) {
                Text(
                    stringResource(Res.string.library_no_tags_yet),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}

/**
 * One filter row: the field label on the left and its selector on the right, so the reviewer,
 * correspondent and document-type fields read as a compact form instead of stacked sections.
 */
@Composable
private fun FilterFieldRow(
    label: String,
    selector: @Composable () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        selector()
    }
}

@Composable
private fun <T> EnumDropdown(
    current: T,
    options: List<T>,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    searchLabel: String? = null,
    searchText: (T) -> String = { it.toString() },
) {
    var expanded by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    // Only long lists (e.g. correspondents/doc types) get the in-menu search box; the small
    // reviewed dropdown stays clean.
    val searchable = searchLabel != null && options.size > 8
    LaunchedEffect(expanded) {
        if (expanded) searchQuery = ""
    }
    Column {
        OutlinedButton(onClick = { expanded = true }) {
            Text(label(current))
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (searchable) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text(searchLabel) },
                    singleLine = true,
                    modifier = Modifier.width(280.dp).padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
            val shown =
                if (searchable) {
                    options.filter { searchText(it).contains(searchQuery.trim(), ignoreCase = true) }
                } else {
                    options
                }
            shown.forEach { opt ->
                DropdownMenuItem(
                    text = { Text(label(opt)) },
                    onClick = {
                        onSelect(opt)
                        expanded = false
                    },
                )
            }
        }
    }
}

private enum class ReviewFilter(
    val value: Boolean?,
) {
    ANY(null),
    REVIEWED(true),
    NEEDS_REVIEW(false),
    ;

    companion object {
        fun from(value: Boolean?): ReviewFilter = entries.first { it.value == value }
    }
}

@Composable
private fun reviewFilterLabel(value: ReviewFilter): String =
    when (value) {
        ReviewFilter.ANY -> stringResource(Res.string.library_any)
        ReviewFilter.REVIEWED -> stringResource(Res.string.common_reviewed)
        ReviewFilter.NEEDS_REVIEW -> stringResource(Res.string.common_needs_review)
    }

private sealed interface OptionalFilterValue {
    data object Any : OptionalFilterValue

    data class Value(
        val value: String,
    ) : OptionalFilterValue
}

private fun optionalFilterValue(value: String?): OptionalFilterValue =
    value?.let(OptionalFilterValue::Value) ?: OptionalFilterValue.Any

private fun optionalFilterOptions(values: List<String>): List<OptionalFilterValue> =
    listOf(OptionalFilterValue.Any) + values.map(OptionalFilterValue::Value)

@Composable
private fun optionalFilterLabel(value: OptionalFilterValue): String =
    when (value) {
        OptionalFilterValue.Any -> stringResource(Res.string.library_any)
        is OptionalFilterValue.Value -> value.value
    }

private fun OptionalFilterValue.valueOrNull(): String? =
    when (this) {
        OptionalFilterValue.Any -> null
        is OptionalFilterValue.Value -> value
    }

private enum class TagBucket { OFF, ALL, ANY, NONE }

private fun tagBucket(
    f: LibraryFilter,
    tag: String,
): TagBucket =
    when (tag) {
        in f.tagsAll -> TagBucket.ALL
        in f.tagsAny -> TagBucket.ANY
        in f.tagsNone -> TagBucket.NONE
        else -> TagBucket.OFF
    }

@Composable
private fun tagFilterLabel(
    bucket: TagBucket,
    tag: String,
): String =
    when (bucket) {
        TagBucket.OFF -> tag
        TagBucket.ALL -> stringResource(Res.string.library_tag_filter_all, tag)
        TagBucket.ANY -> stringResource(Res.string.library_tag_filter_any, tag)
        TagBucket.NONE -> stringResource(Res.string.library_tag_filter_none, tag)
    }

private fun cycleTag(
    f: LibraryFilter,
    tag: String,
): LibraryFilter {
    val stripped =
        f.copy(
            tagsAll = f.tagsAll - tag,
            tagsAny = f.tagsAny - tag,
            tagsNone = f.tagsNone - tag,
        )
    return when (tagBucket(f, tag)) {
        TagBucket.OFF -> stripped.copy(tagsAll = stripped.tagsAll + tag)
        TagBucket.ALL -> stripped.copy(tagsAny = stripped.tagsAny + tag)
        TagBucket.ANY -> stripped.copy(tagsNone = stripped.tagsNone + tag)
        TagBucket.NONE -> stripped
    }
}
