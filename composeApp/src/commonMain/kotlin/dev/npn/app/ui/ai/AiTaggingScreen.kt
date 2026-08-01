@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package dev.npn.app.ui.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.npn.app.ui.common.DocThumbnail
import dev.npn.core.app.AiTagging
import dev.npn.core.app.DocContentFacade
import dev.npn.core.app.LibrarySession
import dev.npn.core.app.SettingsRepository

@Composable
fun AiTaggingScreen(
    session: LibrarySession,
    settings: SettingsRepository,
    onBack: () -> Unit,
) {
    val vm: AiTaggingViewModel = viewModel { AiTaggingViewModel(session, settings) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val count by vm.scopeCount.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(vm, snackbar) { vm.messages.collect { snackbar.showSnackbar(it) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI tagging") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            when (val phase = ui.phase) {
                is TaggingPhase.Idle -> IdlePhase(ui, count, vm)
                is TaggingPhase.Suggesting -> SuggestingPhase(phase.progress, vm::cancel)
                is TaggingPhase.Review -> ReviewPhase(phase, vm, session.content, vm::skipReview)
                is TaggingPhase.Applying -> CenteredProgress("Applying tags…")
                is TaggingPhase.Done -> DonePhase(phase.result, vm::nextBatch, onBack)
            }
        }
    }
}

// ---- Idle: scope + start ----

@Composable
private fun IdlePhase(ui: AiTaggingUiState, count: Int, vm: AiTaggingViewModel) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Suggest tags for a set of documents", style = MaterialTheme.typography.titleMedium)
        ScopeOption(TaggingScope.UNTAGGED, "Untagged documents", ui.scope, vm::setScope)
        if (ui.reviewQueueAvailable) {
            ScopeOption(TaggingScope.REVIEW_QUEUE, "Review queue", ui.scope, vm::setScope)
        }
        ScopeOption(TaggingScope.ALL, "All documents", ui.scope, vm::setScope)
        Text(scopeCountLabel(count, ui.batchSize), style = MaterialTheme.typography.bodyMedium)

        Text("Options", style = MaterialTheme.typography.titleSmall)
        NumericField(
            value = ui.maxTags,
            onSetValue = vm::setMaxTags,
            range = AiTagging.RANGE,
            label = "Tags per document",
            supportingText = "${AiTagging.MIN_TAGS_PER_DOC}-${AiTagging.MAX_TAGS_PER_DOC}",
        )
        NumericField(
            value = ui.maxNewTags,
            onSetValue = vm::setMaxNewTags,
            range = AiTagging.NEW_TAGS_RANGE,
            label = "New tags per document",
            supportingText = "${AiTagging.MIN_NEW_TAGS_PER_DOC}-${AiTagging.MAX_NEW_TAGS_PER_DOC} " +
                "(0 = existing only)",
        )
        NumericField(
            value = ui.batchSize,
            onSetValue = vm::setBatchSize,
            range = AiTagging.BATCH_RANGE,
            label = "Documents per batch",
            supportingText = "${AiTagging.MIN_BATCH_DOCS}-${AiTagging.MAX_BATCH_DOCS}",
        )

        Button(onClick = vm::start, enabled = count > 0) {
            Icon(Icons.Outlined.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
            Text("Start", Modifier.padding(start = 8.dp))
        }
    }
}

private fun scopeCountLabel(count: Int, batchSize: Int): String {
    val docs = if (count == 1) "document" else "documents"
    return if (count > batchSize) {
        "$count $docs — the first $batchSize run in this batch"
    } else {
        "$count $docs"
    }
}

/**
 * Numeric per-run override field. Keeps a local editable string so the user can clear it while
 * typing; commits (only when the parse falls inside [range]) on every valid parse and re-seeds from
 * [value] whenever it changes externally (e.g. clamped by the view model). Mirrors SettingsScreen's
 * private TagCapField; kept local by design rather than shared.
 */
@Composable
private fun NumericField(
    value: Int,
    onSetValue: (Int) -> Unit,
    range: IntRange,
    label: String,
    supportingText: String,
) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            text = raw.filter { it.isDigit() }.take(3)
            text.toIntOrNull()?.takeIf { it in range }?.let(onSetValue)
        },
        label = { Text(label) },
        supportingText = { Text(supportingText) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ScopeOption(
    scope: TaggingScope,
    label: String,
    selected: TaggingScope,
    onSelect: (TaggingScope) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = scope == selected) { onSelect(scope) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = scope == selected, onClick = { onSelect(scope) })
        Text(label, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyLarge)
    }
}

// ---- Suggesting: progress ----

@Composable
private fun SuggestingPhase(progress: SuggestProgress, onCancel: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "Suggesting tags — ${progress.done + 1} of ${progress.total}",
            style = MaterialTheme.typography.titleMedium,
        )
        val fraction = if (progress.total == 0) 0f else progress.done.toFloat() / progress.total
        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        Text(
            progress.currentLabel,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = onCancel) { Text("Cancel") }
    }
}

// ---- Review: per-doc chips + apply bar ----

@Composable
private fun ReviewPhase(
    phase: TaggingPhase.Review,
    vm: AiTaggingViewModel,
    content: DocContentFacade,
    onSkip: () -> Unit,
) {
    val selected = phase.docs.sumOf { doc -> doc.chips.count { it.selected } }
    val total = phase.docs.sumOf { doc -> doc.chips.size }
    val docsWithSelection = phase.docs.count { doc -> doc.chips.any { it.selected } }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { BatchSelectionHeader(selected, total, vm::setAllSelection) }
            items(phase.docs, key = { it.docId }) { doc ->
                ReviewRow(doc, content, vm::toggleChip, vm::setDocSelection)
            }
            if (phase.emptyDocs.isNotEmpty()) {
                item { NoSuggestionsSection(phase.emptyDocs) }
            }
        }
        ApplyBar(selected, docsWithSelection, vm::apply, onSkip)
    }
}

@Composable
private fun BatchSelectionHeader(selected: Int, total: Int, onSetAll: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "$selected of $total tags selected",
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = { onSetAll(true) }) { Text("Select all") }
        TextButton(onClick = { onSetAll(false) }) { Text("Clear") }
    }
}

@Composable
private fun ReviewRow(
    doc: ReviewDoc,
    content: DocContentFacade,
    onToggle: (String, String) -> Unit,
    onSetDoc: (String, Boolean) -> Unit,
) {
    val allSelected = doc.chips.all { it.selected }
    val noneSelected = doc.chips.none { it.selected }
    val toggleState = when {
        allSelected -> ToggleableState.On
        noneSelected -> ToggleableState.Off
        else -> ToggleableState.Indeterminate
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        DocThumbnail(docId = doc.docId, content = content, size = 56.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(doc.label, style = MaterialTheme.typography.titleSmall)
            doc.snippet?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                doc.chips.forEach { chip ->
                    FilterChip(
                        selected = chip.selected,
                        onClick = { onToggle(doc.docId, chip.tag) },
                        label = { ChipLabel(chip) },
                        leadingIcon = if (chip.selected) {
                            {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(FilterChipDefaults.IconSize),
                                )
                            }
                        } else {
                            null
                        },
                    )
                }
            }
        }
        TriStateCheckbox(
            state = toggleState,
            // On/Indeterminate → clear; Off → select all.
            onClick = { onSetDoc(doc.docId, !allSelected) },
        )
    }
}

@Composable
private fun ChipLabel(chip: ReviewChip) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(chip.tag)
        if (chip.isNew) {
            Text(
                "new",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
    }
}

@Composable
private fun NoSuggestionsSection(docs: List<ReviewDoc>) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        HorizontalDivider()
        Row(
            Modifier.fillMaxWidth()
                .selectable(selected = expanded) { expanded = !expanded }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "No suggestions (${docs.size})",
                Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Icon(
                if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = if (expanded) "Collapse" else "Expand",
            )
        }
        if (expanded) {
            docs.forEach { doc ->
                Text(
                    doc.label,
                    Modifier.padding(vertical = 2.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ApplyBar(selectedTags: Int, docCount: Int, onApply: () -> Unit, onSkip: () -> Unit) {
    Surface(tonalElevation = 3.dp) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(applySummary(selectedTags, docCount), Modifier.weight(1f))
            // Escape hatch: leave Review without writing (also the only way forward when nothing
            // is selected — all suggestions new/deselected or every doc had no suggestions).
            OutlinedButton(onClick = onSkip) { Text("Skip") }
            Button(onClick = onApply, enabled = selectedTags > 0) { Text("Apply") }
        }
    }
}

private fun applySummary(tags: Int, docs: Int): String {
    val tagWord = if (tags == 1) "tag" else "tags"
    val docWord = if (docs == 1) "document" else "documents"
    return "Add $tags $tagWord across $docs $docWord"
}

// ---- Done + shared ----

@Composable
private fun DonePhase(result: ApplyResult, onNextBatch: () -> Unit, onBack: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(doneSummary(result), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (result.moreRemain) {
            Button(onClick = onNextBatch) { Text("Next batch") }
        }
        OutlinedButton(onClick = onBack) { Text("Done") }
    }
}

private fun doneSummary(result: ApplyResult): String {
    if (result.docsTagged == 0) return "No documents to tag"
    val tagWord = if (result.tagsAdded == 1) "tag" else "tags"
    val docWord = if (result.docsTagged == 1) "document" else "documents"
    return "Added ${result.tagsAdded} $tagWord across ${result.docsTagged} $docWord"
}

@Composable
private fun CenteredProgress(label: String) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
