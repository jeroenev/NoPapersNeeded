@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package dev.npn.app.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.npn.app.ui.common.DatePickerField
import dev.npn.app.ui.common.rememberFieldLocale
import dev.npn.app.ui.common.rememberFieldText
import dev.npn.core.app.LibraryFilter
import dev.npn.core.app.TagInfo
import dev.npn.core.model.FieldDef
import dev.npn.core.model.FieldParse
import dev.npn.core.model.FieldType
import dev.npn.core.model.FieldValues
import dev.npn.core.model.ReviewMode
import dev.npn.core.util.NpnJson
import kotlinx.serialization.encodeToString
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_cancel
import no_papers_needed.composeapp.generated.resources.common_field_invalid_decimal
import no_papers_needed.composeapp.generated.resources.common_field_invalid_number
import no_papers_needed.composeapp.generated.resources.common_needs_review
import no_papers_needed.composeapp.generated.resources.common_reviewed
import no_papers_needed.composeapp.generated.resources.library_any
import no_papers_needed.composeapp.generated.resources.library_apply_filters
import no_papers_needed.composeapp.generated.resources.library_clear_all
import no_papers_needed.composeapp.generated.resources.library_filters
import no_papers_needed.composeapp.generated.resources.library_from
import no_papers_needed.composeapp.generated.resources.library_no_tags_yet
import no_papers_needed.composeapp.generated.resources.library_reviewed_filter
import no_papers_needed.composeapp.generated.resources.library_search_field
import no_papers_needed.composeapp.generated.resources.library_search_tags
import no_papers_needed.composeapp.generated.resources.library_to
import no_papers_needed.composeapp.generated.resources.library_untagged_only
import no_papers_needed.composeapp.generated.resources.ux_exclude_tags
import no_papers_needed.composeapp.generated.resources.ux_include_tags
import no_papers_needed.composeapp.generated.resources.ux_match_all
import no_papers_needed.composeapp.generated.resources.ux_match_any
import no_papers_needed.composeapp.generated.resources.ux_mixed_tags
import no_papers_needed.composeapp.generated.resources.ux_no_matching_tags
import no_papers_needed.composeapp.generated.resources.ux_refine_tag_search
import org.jetbrains.compose.resources.stringResource

/**
 * How many tag chips one include/exclude group renders before it stops and points the user at the
 * search box instead. The whole panel scrolls as a single axis, so this bound keeps a large tag
 * vocabulary from pushing the rest of the filter form far off-screen without nesting a second
 * scroll region. Currently-selected tags are always shown (they are ordered first), so the cap only
 * ever hides *unselected* tags the user can still reach by searching. ~30 comfortably fills a couple
 * of screens of chips while staying well short of a wall of hundreds.
 */
private const val MAX_TAG_CHIPS_PER_GROUP = 30

/**
 * Explicit inclusion and exclusion controls, with a draft applied by the confirmation button.
 */
@Composable
fun FilterSheet(
    filter: LibraryFilter,
    tags: List<TagInfo>,
    fields: FieldFilterOptions,
    reviewMode: ReviewMode,
    onApply: (LibraryFilter) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        FilterPanel(
            filter = filter,
            tags = tags,
            fields = fields,
            reviewMode = reviewMode,
            onApply = onApply,
            onDismiss = onDismiss,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
fun FilterPanel(
    filter: LibraryFilter,
    tags: List<TagInfo>,
    fields: FieldFilterOptions,
    reviewMode: ReviewMode,
    onApply: (LibraryFilter) -> Unit,
    onDismiss: (() -> Unit)? = null,
    fillHeight: Boolean = false,
    modifier: Modifier = Modifier,
) {
    var draft by rememberSaveable(filter, stateSaver = LibraryFilterSaver) { mutableStateOf(filter) }
    // Keys of the numeric bounds currently holding text that does not parse. Apply is blocked while
    // any is present so an invalid entry can never be silently dropped and the stale value applied.
    var invalidBounds by remember { mutableStateOf(emptySet<String>()) }
    var resetCounter by remember { mutableStateOf(0) }
    val setBoundValidity: (String, Boolean) -> Unit = { boundKey, valid ->
        invalidBounds = if (valid) invalidBounds - boundKey else invalidBounds + boundKey
    }

    Surface(modifier = modifier, tonalElevation = 3.dp) {
        Column(if (fillHeight) Modifier.fillMaxSize() else Modifier.fillMaxWidth()) {
            FilterPanelHeader(
                onClearAll = {
                    draft = LibraryFilter()
                    invalidBounds = emptySet()
                    resetCounter++
                },
                onDismiss = onDismiss,
            )

            Column(
                Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                key(resetCounter) {
                    FilterFields(
                        draft = draft,
                        tags = tags,
                        fields = fields,
                        reviewMode = reviewMode,
                        onDraftChange = { draft = it },
                        onBoundValidity = setBoundValidity,
                    )
                }
            }

            HorizontalDivider()
            OutlinedButton(
                onClick = { onApply(draft) },
                enabled = invalidBounds.isEmpty(),
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            ) { Text(stringResource(Res.string.library_apply_filters)) }
        }
    }
}

/** Filter edits are a user-authored draft and must survive destination recreation until applied. */
private val LibraryFilterSaver =
    Saver<LibraryFilter, String>(
        save = { NpnJson.Persisted.encodeToString(it) },
        restore = { NpnJson.Persisted.decodeFromString(it) },
    )

/** Title row of the filter panel: the "Filters" heading, the clear-all button and optional close. */
@Composable
private fun FilterPanelHeader(
    onClearAll: () -> Unit,
    onDismiss: (() -> Unit)?,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(Res.string.library_filters), style = MaterialTheme.typography.titleLarge)
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onClearAll) { Text(stringResource(Res.string.library_clear_all)) }
            onDismiss?.let {
                IconButton(onClick = it) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(Res.string.common_cancel))
                }
            }
        }
    }
}

/**
 * The registry fields the filter sheet offers and the values each text field can be matched
 * against. Only "show on every document" fields get a row: they are the ones every document is
 * expected to carry, so filtering on them is meaningful library-wide.
 */
@Immutable
data class FieldFilterOptions(
    val fields: List<FieldDef> = emptyList(),
    val values: Map<String, List<String>> = emptyMap(),
)

/**
 * The editable filter fields (tags, reviewed, one row per default field, untagged-only). Edits
 * produce a new draft [LibraryFilter] via [onDraftChange]; the panel owns the draft state.
 */
@Composable
private fun FilterFields(
    draft: LibraryFilter,
    tags: List<TagInfo>,
    fields: FieldFilterOptions,
    reviewMode: ReviewMode,
    onDraftChange: (LibraryFilter) -> Unit,
    onBoundValidity: (String, Boolean) -> Unit,
) {
    TagFilterSection(tags, draft, onDraftChange)

    // The stored reviewed marker is only maintained in the inbox workflow; outside it,
    // review state is derived from tags (reviewed == tagged) and the marker is stale.
    if (reviewMode.reviewMarkerIsAuthoritative) {
        FilterFieldRow(stringResource(Res.string.library_reviewed_filter)) {
            EnumDropdown(
                current = ReviewFilter.from(draft.reviewed),
                options = ReviewFilter.entries,
                label = ::reviewFilterLabel,
                onSelect = { onDraftChange(draft.copy(reviewed = it.value)) },
            )
        }
    }

    for (def in fields.fields.filter { it.showByDefault }) {
        key(def.key) {
            FieldFilterRow(def, fields.values[def.key].orEmpty(), draft, onDraftChange, onBoundValidity)
        }
    }

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(Res.string.library_untagged_only))
        Switch(
            checked = draft.untaggedOnly,
            onCheckedChange = { onDraftChange(draft.copy(untaggedOnly = it)) },
        )
    }
}

/**
 * The filter control for one field, by its type: text picks one existing value (equality), dates
 * and numbers take a from/to range. Prose has no useful filter and gets no row.
 */
@Composable
private fun FieldFilterRow(
    def: FieldDef,
    values: List<String>,
    draft: LibraryFilter,
    onDraftChange: (LibraryFilter) -> Unit,
    onBoundValidity: (String, Boolean) -> Unit,
) {
    when (def.type) {
        FieldType.TEXT -> {
            FilterFieldRow(def.label) {
                EnumDropdown(
                    current = optionalFilterValue(draft.fieldEquals[def.key]),
                    options = optionalFilterOptions(values),
                    label = ::optionalFilterLabel,
                    onSelect = { onDraftChange(draft.withFieldEquals(def.key, it.valueOrNull())) },
                    searchLabel = stringResource(Res.string.library_search_field, def.label),
                    searchText = { if (it is OptionalFilterValue.Value) it.value else "" },
                )
            }
        }

        FieldType.DATE -> {
            SectionLabel(def.label)
            BoundsLayout(
                from = {
                    DatePickerField(
                        value = draft.fieldRanges[def.key]?.from,
                        label = stringResource(Res.string.library_from),
                        onCommit = { onDraftChange(draft.withFieldBounds(def.key, from = it)) },
                        modifier = it,
                    )
                },
                to = {
                    DatePickerField(
                        value = draft.fieldRanges[def.key]?.to,
                        label = stringResource(Res.string.library_to),
                        onCommit = { onDraftChange(draft.withFieldBounds(def.key, to = it)) },
                        modifier = it,
                    )
                },
            )
        }

        FieldType.NUMBER, FieldType.DECIMAL -> {
            SectionLabel(def.label)
            BoundsLayout(
                from = {
                    NumberBound(
                        def = def,
                        value = draft.fieldRanges[def.key]?.from,
                        label = stringResource(Res.string.library_from),
                        onCommit = { onDraftChange(draft.withFieldBounds(def.key, from = it)) },
                        onValidity = { valid -> onBoundValidity("${def.key}:from", valid) },
                        modifier = it,
                    )
                },
                to = {
                    NumberBound(
                        def = def,
                        value = draft.fieldRanges[def.key]?.to,
                        label = stringResource(Res.string.library_to),
                        onCommit = { onDraftChange(draft.withFieldBounds(def.key, to = it)) },
                        onValidity = { valid -> onBoundValidity("${def.key}:to", valid) },
                        modifier = it,
                    )
                },
            )
        }

        FieldType.LONG_TEXT -> {
            // Prose has no useful equality or range filter.
        }
    }
}

/**
 * Below which the two from/to fields stack. A single filter row needs enough width for two labelled
 * fields side by side; narrower than this they read better one above the other, and inline error
 * text under an invalid numeric field no longer squeezes its sibling.
 */
private val BoundsStackBelow = 360.dp

/**
 * Lays a from/to pair side by side when there is room, stacked vertically when narrow. Each slot
 * receives the [Modifier] it should apply so the side-by-side form can weight them equally while the
 * stacked form fills the width.
 */
@Composable
private fun BoundsLayout(
    from: @Composable (Modifier) -> Unit,
    to: @Composable (Modifier) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth < BoundsStackBelow) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                from(Modifier.fillMaxWidth())
                to(Modifier.fillMaxWidth())
            }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                from(Modifier.weight(1f))
                to(Modifier.weight(1f))
            }
        }
    }
}

/**
 * One numeric range bound: typed in locale form, stored canonical. An entry that does not parse
 * shows inline validation and reports itself invalid via [onValidity] so the panel can block Apply,
 * rather than silently keeping the stale bound. A blank field is valid (it clears the bound).
 */
@Composable
private fun NumberBound(
    def: FieldDef,
    value: String?,
    label: String,
    onCommit: (String?) -> Unit,
    onValidity: (Boolean) -> Unit,
    modifier: Modifier,
) {
    val locale = rememberFieldLocale()
    var text by rememberFieldText(value?.let { FieldValues.display(def.type, it, locale) }.orEmpty())
    val invalid = text.isNotBlank() && FieldValues.canonicalize(def.type, text, locale) is FieldParse.Invalid
    // Report validity on entry and whenever it changes, and clear the invalid flag when this field
    // leaves the composition (the row was removed) so a stale error never blocks Apply.
    LaunchedEffect(invalid) { onValidity(!invalid) }
    DisposableEffect(Unit) { onDispose { onValidity(true) } }
    val error =
        when {
            !invalid -> null
            def.type == FieldType.DECIMAL -> stringResource(Res.string.common_field_invalid_decimal)
            else -> stringResource(Res.string.common_field_invalid_number)
        }
    OutlinedTextField(
        value = text,
        onValueChange = { typed ->
            text = typed
            when (val parsed = FieldValues.canonicalize(def.type, typed, locale)) {
                is FieldParse.Ok -> onCommit(parsed.canonical)
                is FieldParse.Invalid -> Unit
            }
        },
        label = { Text(label) },
        isError = invalid,
        supportingText = error?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
private fun TagFilterSection(
    tags: List<TagInfo>,
    filter: LibraryFilter,
    onFilterChange: (LibraryFilter) -> Unit,
) {
    SectionLabel(stringResource(Res.string.ux_include_tags))
    val mixed = filter.tagsAll.isNotEmpty() && filter.tagsAny.isNotEmpty()
    var matchAll by remember { mutableStateOf(filter.tagsAny.isEmpty()) }
    val effectiveAll =
        when {
            filter.tagsAll.isNotEmpty() -> true
            filter.tagsAny.isNotEmpty() -> false
            else -> matchAll
        }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = !mixed && effectiveAll,
            onClick = {
                matchAll = true
                onFilterChange(filter.matchIncludedTags(true))
            },
            label = { Text(stringResource(Res.string.ux_match_all)) },
        )
        FilterChip(
            selected = !mixed && !effectiveAll,
            onClick = {
                matchAll = false
                onFilterChange(filter.matchIncludedTags(false))
            },
            label = { Text(stringResource(Res.string.ux_match_any)) },
        )
    }
    if (mixed) {
        Text(stringResource(Res.string.ux_mixed_tags), style = MaterialTheme.typography.bodySmall)
    }
    var tagQuery by remember { mutableStateOf("") }
    OutlinedTextField(
        value = tagQuery,
        onValueChange = { tagQuery = it },
        label = { Text(stringResource(Res.string.library_search_tags)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    // Include saved-view tags even if the current vocabulary no longer contains them.
    val allTags =
        (tags.map { it.name } + filter.tagsAll + filter.tagsAny + filter.tagsNone).distinct()
    val visibleTags = allTags.filter { it.contains(tagQuery.trim(), ignoreCase = true) }
    // The empty state is decided once for the whole section, not per group: showing "no tags" under
    // each of the two or three chip groups would repeat the message, and — worse — a search that
    // simply matches nothing is a different situation from an empty vocabulary and must say so.
    when {
        allTags.isEmpty() -> {
            Text(stringResource(Res.string.library_no_tags_yet))
        }

        visibleTags.isEmpty() -> {
            Text(stringResource(Res.string.ux_no_matching_tags))
        }

        else -> {
            // Only render the include/exclude chip groups (and the "Exclude tags" heading) when
            // there is actually something to put in them.
            if (mixed) {
                SectionLabel(stringResource(Res.string.ux_match_all))
                TagChoices(visibleTags, filter, TagBucket.ALL, onFilterChange)
                SectionLabel(stringResource(Res.string.ux_match_any))
                TagChoices(visibleTags, filter, TagBucket.ANY, onFilterChange)
            } else {
                TagChoices(visibleTags, filter, if (effectiveAll) TagBucket.ALL else TagBucket.ANY, onFilterChange)
            }
            SectionLabel(stringResource(Res.string.ux_exclude_tags))
            TagChoices(visibleTags, filter, TagBucket.NONE, onFilterChange)
        }
    }
}

@Composable
private fun TagChoices(
    tags: List<String>,
    filter: LibraryFilter,
    bucket: TagBucket,
    onFilterChange: (LibraryFilter) -> Unit,
) {
    // Tags already in this bucket come first so the cap below can never hide a selected chip:
    // a selected tag that scrolled past the limit would read as deselected and be unreachable.
    val ordered = tags.sortedByDescending { tagBucket(filter, it) == bucket }
    val shown = ordered.take(MAX_TAG_CHIPS_PER_GROUP)
    Column(Modifier.fillMaxWidth()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            shown.forEach { tag ->
                val selected = tagBucket(filter, tag) == bucket
                FilterChip(
                    selected = selected,
                    onClick = { onFilterChange(filter.withTag(tag, if (selected) TagBucket.OFF else bucket)) },
                    label = { Text(tag) },
                )
            }
        }
        // The group as a whole no longer scrolls, so a truncated list points at the search box
        // rather than silently dropping the overflow.
        if (ordered.size > shown.size) {
            Text(
                stringResource(Res.string.ux_refine_tag_search),
                style = MaterialTheme.typography.bodySmall,
            )
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
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
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
