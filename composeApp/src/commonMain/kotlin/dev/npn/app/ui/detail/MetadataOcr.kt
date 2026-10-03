@file:OptIn(ExperimentalMaterial3Api::class)

package dev.npn.app.ui.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.npn.app.ui.common.HighlightText
import dev.npn.app.ui.common.formatEpochDate
import dev.npn.app.ui.common.parseQueryHighlight
import dev.npn.core.app.OcrVersion
import dev.npn.core.ocr.OcrEngineId
import dev.npn.core.ocr.OcrEngineRef
import dev.npn.core.ocr.OcrState
import kotlinx.coroutines.delay
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_apply
import no_papers_needed.composeapp.generated.resources.common_cancel
import no_papers_needed.composeapp.generated.resources.common_clear_search
import no_papers_needed.composeapp.generated.resources.detail_automatic_engine
import no_papers_needed.composeapp.generated.resources.detail_choose_ocr_engine
import no_papers_needed.composeapp.generated.resources.detail_engine
import no_papers_needed.composeapp.generated.resources.detail_engine_ai
import no_papers_needed.composeapp.generated.resources.detail_engine_embedded
import no_papers_needed.composeapp.generated.resources.detail_engine_mlkit
import no_papers_needed.composeapp.generated.resources.detail_engine_source
import no_papers_needed.composeapp.generated.resources.detail_engine_tesseract
import no_papers_needed.composeapp.generated.resources.detail_find_in_text
import no_papers_needed.composeapp.generated.resources.detail_find_next
import no_papers_needed.composeapp.generated.resources.detail_find_position
import no_papers_needed.composeapp.generated.resources.detail_find_previous
import no_papers_needed.composeapp.generated.resources.detail_no_matches_found
import no_papers_needed.composeapp.generated.resources.detail_not_available
import no_papers_needed.composeapp.generated.resources.detail_ocr_adopted
import no_papers_needed.composeapp.generated.resources.detail_ocr_done
import no_papers_needed.composeapp.generated.resources.detail_ocr_failed
import no_papers_needed.composeapp.generated.resources.detail_ocr_not_processed
import no_papers_needed.composeapp.generated.resources.detail_ocr_queued
import no_papers_needed.composeapp.generated.resources.detail_pinned_engine
import no_papers_needed.composeapp.generated.resources.detail_search_text_engine
import no_papers_needed.composeapp.generated.resources.detail_text_failed
import no_papers_needed.composeapp.generated.resources.detail_text_not_extracted
import no_papers_needed.composeapp.generated.resources.detail_text_queued
import no_papers_needed.composeapp.generated.resources.detail_text_unavailable
import org.jetbrains.compose.resources.stringResource

/**
 * Read-only extracted text (OCR / embedded), selectable and query-highlighted. A find bar above the
 * text highlights every match and emphasizes the current one; [focusRequester] lets the host
 * (MetadataPanel's Ctrl+F) move focus into the find field when this tab is active.
 */
@Composable
internal fun OcrTextTab(
    text: String?,
    ocrState: OcrState,
    highlightTerms: List<String>,
    focusRequester: FocusRequester,
    focusRequest: Int = 0,
    modifier: Modifier = Modifier,
) {
    if (text == null) {
        Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text(
                when (ocrState) {
                    OcrState.PENDING -> stringResource(Res.string.detail_text_queued)

                    OcrState.FAILED -> stringResource(Res.string.detail_text_failed)

                    OcrState.NONE -> stringResource(Res.string.detail_text_not_extracted)

                    // Indexed, but the sidecar could not be read back right now.
                    OcrState.DONE, OcrState.ADOPTED -> stringResource(Res.string.detail_text_unavailable)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    val visibleText = text.take(MAX_OCR_DISPLAY_CHARS)
    var query by remember { mutableStateOf("") }
    // Scanning + re-highlighting the whole (up to 200k char) text is main-thread work, so the
    // expensive part runs on a debounced copy of the query rather than on every keystroke.
    var activeQuery by remember { mutableStateOf("") }
    LaunchedEffect(query) {
        if (query.isEmpty()) {
            activeQuery = ""
        } else {
            delay(FIND_DEBOUNCE_MS)
            activeQuery = query
        }
    }
    var current by remember { mutableStateOf(0) }
    val matches = remember(visibleText, activeQuery) { findMatchStarts(visibleText, activeQuery) }
    LaunchedEffect(matches) { current = 0 }
    val effectiveCurrent = if (matches.isEmpty()) 0 else current.coerceIn(0, matches.size - 1)

    val scrollState = rememberScrollState()
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val textPadding = with(LocalDensity.current) { OCR_TEXT_PADDING.toPx() }
    // Bring the active match into view; without this prev/next only recolours off-screen text.
    LaunchedEffect(effectiveCurrent, matches, layout) {
        val result = layout
        if (result == null || matches.isEmpty()) return@LaunchedEffect
        val line = result.getLineForOffset(matches[effectiveCurrent])
        scrollState.animateScrollTo((result.getLineTop(line) + textPadding).toInt().coerceAtLeast(0))
    }

    Column(modifier) {
        FindBar(
            query = query,
            searching = activeQuery.isNotEmpty(),
            matchCount = matches.size,
            current = effectiveCurrent,
            focusRequester = focusRequester,
            onQueryChange = { query = it },
            onNext = {
                if (matches.isNotEmpty()) current = (current + 1) % matches.size
            },
            onPrev = {
                if (matches.isNotEmpty()) current = (current - 1 + matches.size) % matches.size
            },
            onClear = { query = "" },
        )
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .padding(OCR_TEXT_PADDING),
        ) {
            SelectionContainer {
                if (matches.isNotEmpty()) {
                    FindHighlightText(
                        segments = buildFindSegments(visibleText, matches, activeQuery.length, effectiveCurrent),
                        style = MaterialTheme.typography.bodySmall,
                        onTextLayout = { layout = it },
                    )
                } else if (highlightTerms.isNotEmpty()) {
                    HighlightText(
                        segments = parseQueryHighlight(visibleText, highlightTerms.joinToString(" ")),
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    Text(visibleText, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    // Ctrl+F while on another tab lands here after the tab switch; request focus once attached.
    LaunchedEffect(focusRequest) {
        if (focusRequest > 0) runCatching { focusRequester.requestFocus() }
    }
}

/**
 * Compact find-in-text bar: query field, prev/next navigation, match position (or "no matches" once
 * the debounced [searching] query has run and found nothing), and an action that clears the query.
 */
@Composable
private fun FindBar(
    query: String,
    searching: Boolean,
    matchCount: Int,
    current: Int,
    focusRequester: FocusRequester,
    onQueryChange: (String) -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onClear: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            label = { Text(stringResource(Res.string.detail_find_in_text)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onNext() }),
            modifier = Modifier.weight(1f).focusRequester(focusRequester),
        )
        IconButton(onClick = onPrev, enabled = matchCount > 0) {
            Icon(
                Icons.Filled.ArrowUpward,
                contentDescription = stringResource(Res.string.detail_find_previous),
            )
        }
        IconButton(onClick = onNext, enabled = matchCount > 0) {
            Icon(
                Icons.Filled.ArrowDownward,
                contentDescription = stringResource(Res.string.detail_find_next),
            )
        }
        // The find status changes as the user types; announce it politely so a screen reader
        // reports "3 / 12" or "No matches found" without the user leaving the field.
        val findStatusSemantics = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
        if (matchCount > 0) {
            Text(
                stringResource(Res.string.detail_find_position, current + 1, matchCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = findStatusSemantics,
            )
        } else if (searching) {
            Text(
                stringResource(Res.string.detail_no_matches_found),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = findStatusSemantics,
            )
        }
        IconButton(onClick = onClear, enabled = query.isNotEmpty()) {
            Icon(
                Icons.Filled.Close,
                contentDescription = stringResource(Res.string.common_clear_search),
            )
        }
    }
}

/** Case-insensitive start indices of every non-overlapping occurrence of [query] in [text]. */
private fun findMatchStarts(
    text: String,
    query: String,
): List<Int> {
    if (query.isEmpty()) return emptyList()
    val matches = mutableListOf<Int>()
    var from = 0
    while (from <= text.length - query.length) {
        if (text.regionMatches(from, query, 0, query.length, ignoreCase = true)) {
            matches += from
            from += query.length
        } else {
            from++
        }
    }
    return matches
}

/** One text segment with a find-match flag; [current] marks the active match. */
private data class FindSegment(
    val text: String,
    val match: Boolean,
    val current: Boolean,
)

/**
 * Splits [text] into segments so each match in [matches] (start indices) is flagged; the match at
 * [current] is marked separately so it can be drawn distinctly.
 */
private fun buildFindSegments(
    text: String,
    matches: List<Int>,
    matchLength: Int,
    current: Int,
): List<FindSegment> {
    if (matches.isEmpty() || matchLength <= 0) {
        return listOf(FindSegment(text, false, false))
    }
    val segments = mutableListOf<FindSegment>()
    var last = 0
    matches.forEachIndexed { index, start ->
        if (start > last) segments += FindSegment(text.substring(last, start), false, false)
        val end = (start + matchLength).coerceAtMost(text.length)
        segments += FindSegment(text.substring(start, end), true, index == current)
        last = end
    }
    if (last < text.length) segments += FindSegment(text.substring(last), false, false)
    return segments
}

/**
 * Renders [segments] with the current match on the primary color and every other match on the
 * container color used by [HighlightText].
 */
@Composable
private fun FindHighlightText(
    segments: List<FindSegment>,
    style: TextStyle,
    onTextLayout: (TextLayoutResult) -> Unit,
    modifier: Modifier = Modifier,
) {
    val matchColor = MaterialTheme.colorScheme.primaryContainer
    val currentColor = MaterialTheme.colorScheme.primary
    val onCurrentColor = MaterialTheme.colorScheme.onPrimary
    Text(
        text =
            buildAnnotatedString {
                segments.forEach { segment ->
                    when {
                        segment.current -> {
                            withStyle(SpanStyle(background = currentColor, color = onCurrentColor)) {
                                append(segment.text)
                            }
                        }

                        segment.match -> {
                            withStyle(SpanStyle(background = matchColor)) {
                                append(segment.text)
                            }
                        }

                        else -> {
                            append(segment.text)
                        }
                    }
                }
            },
        style = style,
        onTextLayout = onTextLayout,
        modifier = modifier,
    )
}

@Composable
internal fun OcrEngineRow(
    indexedEngine: OcrEngineRef?,
    pinned: Boolean,
    versions: List<OcrVersion>,
    onSetEngine: (String?) -> Unit,
) {
    var showPicker by remember { mutableStateOf(false) }
    val hasVersions = versions.isNotEmpty()
    Row(
        Modifier.fillMaxWidth().then(if (hasVersions) Modifier.clickable { showPicker = true } else Modifier),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(Res.string.detail_engine),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 4.dp),
        )
        Text(
            indexedEngine?.let { engineLabel(it) } ?: stringResource(Res.string.detail_not_available),
            style = MaterialTheme.typography.bodySmall,
        )
        if (pinned) {
            Icon(
                Icons.Filled.PushPin,
                contentDescription = stringResource(Res.string.detail_pinned_engine),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
        }
        if (hasVersions) {
            Icon(
                Icons.Filled.ArrowDropDown,
                contentDescription = stringResource(Res.string.detail_choose_ocr_engine),
                modifier = Modifier.size(20.dp),
            )
        }
    }
    if (showPicker) {
        OcrEnginePickerDialog(
            versions = versions,
            pinnedEngine = if (pinned) indexedEngine?.label else null,
            onConfirm = {
                onSetEngine(it)
                showPicker = false
            },
            onDismiss = { showPicker = false },
        )
    }
}

@Composable
private fun OcrEnginePickerDialog(
    versions: List<OcrVersion>,
    pinnedEngine: String?,
    onConfirm: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val engines = remember(versions) { versions.distinctBy { it.engine } }
    var selected by remember { mutableStateOf(pinnedEngine) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.detail_search_text_engine)) },
        text = {
            Column {
                OcrEngineOption(
                    label = stringResource(Res.string.detail_automatic_engine),
                    secondary = null,
                    selected = selected == null,
                    onClick = { selected = null },
                )
                engines.forEach { version ->
                    OcrEngineOption(
                        label = engineLabel(OcrEngineRef.of(version.engine)),
                        secondary =
                            stringResource(
                                Res.string.detail_engine_source,
                                version.deviceId,
                                formatEpochDate(version.createdAt),
                            ),
                        selected = selected == version.engine,
                        onClick = { selected = version.engine },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selected) }) { Text(stringResource(Res.string.common_apply)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.common_cancel)) }
        },
    )
}

@Composable
private fun OcrEngineOption(
    label: String,
    secondary: String?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            if (secondary != null) {
                Text(
                    secondary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private const val MAX_OCR_DISPLAY_CHARS = 200_000

/** Padding around the extracted text; also the offset applied when scrolling to a find match. */
private val OCR_TEXT_PADDING = 16.dp

/** Debounce before re-scanning/re-highlighting the (potentially very long) text while typing. */
private const val FIND_DEBOUNCE_MS = 200L

/** Human label for an OCR state; exhaustive, so a new [OcrState] cannot ship unlabelled. */
@Composable
internal fun ocrLabel(state: OcrState): String =
    stringResource(
        when (state) {
            OcrState.NONE -> Res.string.detail_ocr_not_processed
            OcrState.PENDING -> Res.string.detail_ocr_queued
            OcrState.DONE -> Res.string.detail_ocr_done
            OcrState.FAILED -> Res.string.detail_ocr_failed
            OcrState.ADOPTED -> Res.string.detail_ocr_adopted
        },
    )

/**
 * Label for any stored engine reference. An engine this build has no [OcrEngineId] for (a legacy
 * "mixed" sidecar, one written by a newer build) shows its raw label rather than being hidden —
 * the document really is indexed with it, so claiming otherwise would be a lie.
 */
@Composable
private fun engineLabel(engine: OcrEngineRef): String =
    when (engine) {
        is OcrEngineRef.Known -> knownEngineLabel(engine.id)
        is OcrEngineRef.Unknown -> engine.label
    }

/** Human label for a known engine; exhaustive, so a new [OcrEngineId] cannot ship unlabelled. */
@Composable
private fun knownEngineLabel(engine: OcrEngineId): String =
    stringResource(
        when (engine) {
            OcrEngineId.TESSERACT -> Res.string.detail_engine_tesseract
            OcrEngineId.MLKIT -> Res.string.detail_engine_mlkit
            OcrEngineId.AI -> Res.string.detail_engine_ai
            OcrEngineId.EMBEDDED -> Res.string.detail_engine_embedded
        },
    )
