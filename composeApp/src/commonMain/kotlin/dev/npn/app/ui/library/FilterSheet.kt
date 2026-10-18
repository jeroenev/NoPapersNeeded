@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package dev.npn.app.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.npn.app.ui.common.DatePickerField
import dev.npn.app.ui.common.Dropdown
import dev.npn.app.ui.common.NumberInputField
import dev.npn.app.ui.common.PanelHeader
import dev.npn.app.ui.common.SectionHeading
import dev.npn.app.ui.common.SwitchRow
import dev.npn.app.ui.common.rememberFieldLocale
import dev.npn.app.ui.common.rememberNumberInput
import dev.npn.core.app.LibraryFilter
import dev.npn.core.app.TagBucket
import dev.npn.core.app.TagInfo
import dev.npn.core.app.matchIncludedTags
import dev.npn.core.app.rebased
import dev.npn.core.app.tagBucket
import dev.npn.core.app.withTag
import dev.npn.core.model.FieldDef
import dev.npn.core.model.FieldParse
import dev.npn.core.model.FieldType
import dev.npn.core.model.ReviewMode
import dev.npn.core.model.reviewMarkerIsAuthoritative
import dev.npn.core.util.NpnJson
import kotlinx.serialization.encodeToString
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_cancel
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
    editor: FilterEditorState,
    tags: List<TagInfo>,
    fields: FieldFilterOptions,
    reviewMode: ReviewMode,
    onApply: (LibraryFilter) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        FilterPanel(
            editor = editor,
            tags = tags,
            fields = fields,
            reviewMode = reviewMode,
            onApply = onApply,
            onDismiss = onDismiss,
            fillHeight = false,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * The filter editor's draft, and the live filter it was last brought up to. On a wide window the
 * search and the chips beside the editor change the live filter while it is open: the draft takes
 * only what such a change touched, and keeps the edits not applied yet. The dialog holds it, above
 * the panel/sheet split, so a window crossing the wide/compact breakpoint with the editor open keeps
 * its unapplied edits (and their invalid-entry guard) instead of silently resetting them.
 */
@Stable
class FilterEditorState internal constructor(
    draft: LibraryFilter,
    live: LibraryFilter,
    tagQuery: String,
    matchAll: Boolean,
) {
    var draft by mutableStateOf(draft)
    var live by mutableStateOf(live)
    var tagQuery by mutableStateOf(tagQuery)
    var matchAll by mutableStateOf(matchAll)

    /**
     * Keys of the numeric bounds currently holding text that does not parse. Apply is blocked while
     * any is present so an invalid entry can never be silently dropped and the stale value applied.
     */
    var invalidBounds by mutableStateOf(emptySet<String>())
        private set
    var resetCounter by mutableIntStateOf(0)
        private set

    fun setBoundValidity(
        boundKey: String,
        valid: Boolean,
    ) {
        invalidBounds = if (valid) invalidBounds - boundKey else invalidBounds + boundKey
    }

    fun clearAll() {
        draft = LibraryFilter()
        invalidBounds = emptySet()
        resetCounter++
    }

    /** Bring the draft onto [filter], keeping the edits [filter] did not touch. */
    fun bringUpTo(filter: LibraryFilter) {
        if (filter != live) {
            draft = draft.rebased(live, filter)
            live = filter
        }
    }
}

/** The filter editor's draft state, kept across the panel/sheet split and destination recreation. */
@Composable
fun rememberFilterEditorState(filter: LibraryFilter): FilterEditorState {
    val editor =
        rememberSaveable(saver = FilterEditorStateSaver) {
            FilterEditorState(draft = filter, live = filter, tagQuery = "", matchAll = filter.tagsAny.isEmpty())
        }
    editor.bringUpTo(filter)
    return editor
}

@Composable
fun FilterPanel(
    editor: FilterEditorState,
    tags: List<TagInfo>,
    fields: FieldFilterOptions,
    reviewMode: ReviewMode,
    onApply: (LibraryFilter) -> Unit,
    onDismiss: () -> Unit,
    /** Fill the height it is given (the side panel), rather than wrap its content (the sheet). */
    fillHeight: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier, tonalElevation = 3.dp) {
        Column(if (fillHeight) Modifier.fillMaxSize() else Modifier.fillMaxWidth()) {
            PanelHeader(
                title = stringResource(Res.string.library_filters),
                closeLabel = stringResource(Res.string.common_cancel),
                onClose = onDismiss,
            ) {
                TextButton(onClick = editor::clearAll) { Text(stringResource(Res.string.library_clear_all)) }
            }

            Column(
                Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                key(editor.resetCounter) {
                    FilterFields(
                        editor = editor,
                        tags = tags,
                        fields = fields,
                        reviewMode = reviewMode,
                    )
                }
            }

            HorizontalDivider()
            OutlinedButton(
                onClick = { onApply(editor.draft) },
                enabled = editor.invalidBounds.isEmpty(),
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            ) { Text(stringResource(Res.string.library_apply_filters)) }
        }
    }
}

/** Filter edits are a user-authored draft and must survive destination recreation until applied. */
private val FilterEditorStateSaver =
    listSaver<FilterEditorState, String>(
        save = {
            listOf(
                NpnJson.Persisted.encodeToString(it.draft),
                NpnJson.Persisted.encodeToString(it.live),
                it.tagQuery,
                it.matchAll.toString(),
            )
        },
        restore = {
            FilterEditorState(
                draft = NpnJson.Persisted.decodeFromString(it[0]),
                live = NpnJson.Persisted.decodeFromString(it[1]),
                tagQuery = it[2],
                matchAll = it[3].toBoolean(),
            )
        },
    )

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
 * replace [FilterEditorState.draft]; the dialog's [editor] owns the draft and the tag section's
 * transient text.
 */
@Composable
private fun ColumnScope.FilterFields(
    editor: FilterEditorState,
    tags: List<TagInfo>,
    fields: FieldFilterOptions,
    reviewMode: ReviewMode,
) {
    val draft = editor.draft
    TagFilterSection(editor, tags)

    // The stored reviewed marker is only maintained in the inbox workflow; outside it,
    // review state is derived from tags (reviewed == tagged) and the marker is stale.
    if (reviewMode.reviewMarkerIsAuthoritative) {
        FilterFieldRow(stringResource(Res.string.library_reviewed_filter)) {
            Dropdown(
                selected = reviewFilterLabel(ReviewFilter.from(draft.reviewed)),
                options = ReviewFilter.entries,
                optionLabel = ::reviewFilterLabel,
                onSelect = { editor.draft = draft.copy(reviewed = it.value) },
            )
        }
    }

    for (def in fields.fields.filter { it.showByDefault }) {
        key(def.key) {
            FieldFilterRow(def, fields.values[def.key].orEmpty(), editor)
        }
    }

    SwitchRow(
        label = stringResource(Res.string.library_untagged_only),
        checked = draft.untaggedOnly,
        onCheckedChange = { editor.draft = draft.copy(untaggedOnly = it) },
    )
}

/**
 * The filter control for one field, by its type: text picks one existing value (equality), dates
 * and numbers take a from/to range. Prose has no useful filter and gets no row.
 */
@Composable
private fun FieldFilterRow(
    def: FieldDef,
    values: List<String>,
    editor: FilterEditorState,
) {
    val draft = editor.draft
    val onDraftChange = { filter: LibraryFilter -> editor.draft = filter }
    when (def.type) {
        FieldType.TEXT -> {
            // Null is "any value": the filter leaves the field alone.
            val anyValue = stringResource(Res.string.library_any)
            FilterFieldRow(def.label) {
                Dropdown<String?>(
                    selected = draft.fieldEquals[def.key] ?: anyValue,
                    options = listOf(null) + values,
                    optionLabel = { it ?: anyValue },
                    onSelect = { onDraftChange(draft.withFieldEquals(def.key, it)) },
                    searchLabel = stringResource(Res.string.library_search_field, def.label),
                    searchText = { it.orEmpty() },
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
                        onCommit = { bound -> onDraftChange(draft.withFieldBounds(def.key, from = bound)) },
                        modifier = it,
                    )
                },
                to = {
                    DatePickerField(
                        value = draft.fieldRanges[def.key]?.to,
                        label = stringResource(Res.string.library_to),
                        onCommit = { bound -> onDraftChange(draft.withFieldBounds(def.key, to = bound)) },
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
                        onCommit = { bound -> onDraftChange(draft.withFieldBounds(def.key, from = bound)) },
                        onValidity = { valid -> editor.setBoundValidity("${def.key}:from", valid) },
                        modifier = it,
                    )
                },
                to = {
                    NumberBound(
                        def = def,
                        value = draft.fieldRanges[def.key]?.to,
                        label = stringResource(Res.string.library_to),
                        onCommit = { bound -> onDraftChange(draft.withFieldBounds(def.key, to = bound)) },
                        onValidity = { valid -> editor.setBoundValidity("${def.key}:to", valid) },
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
 * text under an invalid numeric field does not squeeze its sibling.
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
    // Moving between layouts must keep each editor's composition, including partial input
    // that has no canonical value in the filter draft yet.
    val currentFrom by rememberUpdatedState(from)
    val currentTo by rememberUpdatedState(to)
    val fromSlot = remember { movableContentOf<Modifier> { currentFrom(it) } }
    val toSlot = remember { movableContentOf<Modifier> { currentTo(it) } }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth < BoundsStackBelow) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                fromSlot(Modifier.fillMaxWidth())
                toSlot(Modifier.fillMaxWidth())
            }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                fromSlot(Modifier.weight(1f))
                toSlot(Modifier.weight(1f))
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
    modifier: Modifier = Modifier,
) {
    val input = rememberNumberInput(def, value, rememberFieldLocale())
    // Report validity on entry and whenever it changes, and clear the invalid flag when this field
    // leaves the composition (the row was removed) so a stale error never blocks Apply.
    val reportValidity by rememberUpdatedState(onValidity)
    LaunchedEffect(input.invalid) { reportValidity(!input.invalid) }
    DisposableEffect(Unit) { onDispose { reportValidity(true) } }
    NumberInputField(
        input = input,
        label = label,
        onInput = { parsed -> if (parsed is FieldParse.Ok) onCommit(parsed.canonical) },
        modifier = modifier,
    )
}

@Composable
private fun ColumnScope.TagFilterSection(
    editor: FilterEditorState,
    tags: List<TagInfo>,
) {
    val filter = editor.draft
    val onFilterChange = { changed: LibraryFilter -> editor.draft = changed }
    SectionLabel(stringResource(Res.string.ux_include_tags))
    val mixed = filter.tagsAll.isNotEmpty() && filter.tagsAny.isNotEmpty()
    val matchAll = editor.matchAll
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
                editor.matchAll = true
                onFilterChange(filter.matchIncludedTags(true))
            },
            label = { Text(stringResource(Res.string.ux_match_all)) },
        )
        FilterChip(
            selected = !mixed && !effectiveAll,
            onClick = {
                editor.matchAll = false
                onFilterChange(filter.matchIncludedTags(false))
            },
            label = { Text(stringResource(Res.string.ux_match_any)) },
        )
    }
    if (mixed) {
        Text(stringResource(Res.string.ux_mixed_tags), style = MaterialTheme.typography.bodySmall)
    }
    val tagQuery = editor.tagQuery
    OutlinedTextField(
        value = tagQuery,
        onValueChange = { editor.tagQuery = it },
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
        // The group does not scroll, so a truncated list points at the search box rather than
        // silently dropping the overflow.
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
    SectionHeading(text, style = MaterialTheme.typography.labelLarge)
}

/** One filter row: the field's label with its selector right under it. */
@Composable
private fun FilterFieldRow(
    label: String,
    selector: @Composable () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        SectionLabel(label)
        selector()
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
