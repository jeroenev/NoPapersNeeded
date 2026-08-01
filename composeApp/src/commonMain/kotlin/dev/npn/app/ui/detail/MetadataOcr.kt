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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.npn.app.ui.common.HighlightText
import dev.npn.app.ui.common.formatEpochDate
import dev.npn.app.ui.common.parseQueryHighlight
import dev.npn.core.app.OcrVersion
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_apply
import no_papers_needed.composeapp.generated.resources.common_cancel
import no_papers_needed.composeapp.generated.resources.detail_automatic_engine
import no_papers_needed.composeapp.generated.resources.detail_choose_ocr_engine
import no_papers_needed.composeapp.generated.resources.detail_engine
import no_papers_needed.composeapp.generated.resources.detail_engine_ai
import no_papers_needed.composeapp.generated.resources.detail_engine_embedded
import no_papers_needed.composeapp.generated.resources.detail_engine_mlkit
import no_papers_needed.composeapp.generated.resources.detail_engine_source
import no_papers_needed.composeapp.generated.resources.detail_engine_tesseract
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

/** Read-only extracted text (OCR / embedded), selectable and query-highlighted. */
@Composable
internal fun OcrTextTab(
    text: String?,
    ocrState: String,
    highlightTerms: List<String>,
    modifier: Modifier = Modifier,
) {
    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
        if (text != null) {
            SelectionContainer {
                val visibleText = text.take(MAX_OCR_DISPLAY_CHARS)
                if (highlightTerms.isNotEmpty()) {
                    HighlightText(
                        segments = parseQueryHighlight(visibleText, highlightTerms.joinToString(" ")),
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    Text(visibleText, style = MaterialTheme.typography.bodySmall)
                }
            }
        } else {
            Text(
                when (ocrState) {
                    "pending" -> stringResource(Res.string.detail_text_queued)
                    "failed" -> stringResource(Res.string.detail_text_failed)
                    "none" -> stringResource(Res.string.detail_text_not_extracted)
                    else -> stringResource(Res.string.detail_text_unavailable)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun OcrEngineRow(
    indexedEngine: String?,
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
            pinnedEngine = if (pinned) indexedEngine else null,
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
                        label = engineLabel(version.engine),
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

@Composable
internal fun ocrLabel(state: String): String =
    when (state) {
        "none" -> stringResource(Res.string.detail_ocr_not_processed)
        "pending" -> stringResource(Res.string.detail_ocr_queued)
        "done" -> stringResource(Res.string.detail_ocr_done)
        "failed" -> stringResource(Res.string.detail_ocr_failed)
        "adopted" -> stringResource(Res.string.detail_ocr_adopted)
        else -> state
    }

@Composable
private fun engineLabel(engine: String): String =
    when (engine) {
        "tesseract" -> stringResource(Res.string.detail_engine_tesseract)
        "mlkit" -> stringResource(Res.string.detail_engine_mlkit)
        "ai" -> stringResource(Res.string.detail_engine_ai)
        "embedded" -> stringResource(Res.string.detail_engine_embedded)
        else -> engine
    }
