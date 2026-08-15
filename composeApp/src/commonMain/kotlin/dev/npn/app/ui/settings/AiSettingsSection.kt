package dev.npn.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.npn.app.ui.common.NumericField
import dev.npn.core.ai.AiProvider
import dev.npn.core.ai.AiReasoningLevel
import dev.npn.core.app.AiTagging
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_settings_ai_auto_on_ocr
import no_papers_needed.composeapp.generated.resources.common_settings_ai_auto_on_ocr_help
import no_papers_needed.composeapp.generated.resources.common_settings_ai_help
import no_papers_needed.composeapp.generated.resources.common_settings_ai_provider
import no_papers_needed.composeapp.generated.resources.common_settings_ai_provider_help
import no_papers_needed.composeapp.generated.resources.common_settings_ai_request_help
import no_papers_needed.composeapp.generated.resources.common_settings_api_key
import no_papers_needed.composeapp.generated.resources.common_settings_api_key_help
import no_papers_needed.composeapp.generated.resources.common_settings_api_key_help_provider
import no_papers_needed.composeapp.generated.resources.common_settings_enable_ai
import no_papers_needed.composeapp.generated.resources.common_settings_load_models
import no_papers_needed.composeapp.generated.resources.common_settings_max_new_tags
import no_papers_needed.composeapp.generated.resources.common_settings_max_new_tags_help
import no_papers_needed.composeapp.generated.resources.common_settings_max_suggested_tags
import no_papers_needed.composeapp.generated.resources.common_settings_max_suggested_tags_help
import no_papers_needed.composeapp.generated.resources.common_settings_model
import no_papers_needed.composeapp.generated.resources.common_settings_model_help
import no_papers_needed.composeapp.generated.resources.common_settings_model_pick_help
import no_papers_needed.composeapp.generated.resources.common_settings_models_error
import no_papers_needed.composeapp.generated.resources.common_settings_models_loading
import no_papers_needed.composeapp.generated.resources.common_settings_reasoning_assistant
import no_papers_needed.composeapp.generated.resources.common_settings_reasoning_default
import no_papers_needed.composeapp.generated.resources.common_settings_reasoning_edit
import no_papers_needed.composeapp.generated.resources.common_settings_reasoning_help
import no_papers_needed.composeapp.generated.resources.common_settings_reasoning_high
import no_papers_needed.composeapp.generated.resources.common_settings_reasoning_low
import no_papers_needed.composeapp.generated.resources.common_settings_reasoning_medium
import no_papers_needed.composeapp.generated.resources.common_settings_reasoning_none
import no_papers_needed.composeapp.generated.resources.common_settings_server_url
import no_papers_needed.composeapp.generated.resources.common_settings_server_url_help
import no_papers_needed.composeapp.generated.resources.common_settings_server_url_ollama_help
import org.jetbrains.compose.resources.stringResource

/**
 * The "AI assistant" settings section: a provider picker (Koog-backed providers first, the
 * fully manual OpenAI-compatible option last), provider-appropriate endpoint fields, a model
 * picker fed by the provider's live model list, and per-feature reasoning levels.
 *
 * Collapsed to just the enable switch until AI is turned on. Every change persists
 * immediately through [vm]; there is no explicit save.
 */
@Composable
internal fun AiAssistantSection(
    state: SettingsUiState,
    models: AiModelListState,
    vm: SettingsViewModel,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(Res.string.common_settings_enable_ai),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    stringResource(Res.string.common_settings_ai_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = state.aiEnabled, onCheckedChange = vm::setAiEnabled)
        }
        if (state.aiEnabled) {
            AiProviderDropdown(current = state.aiProvider, onSelect = vm::setAiProvider)
            Text(
                stringResource(Res.string.common_settings_ai_provider_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.aiProvider.editableBaseUrl) {
                AiBaseUrlField(
                    provider = state.aiProvider,
                    baseUrl = state.aiBaseUrl,
                    onSetBaseUrl = vm::setAiBaseUrl,
                )
            }
            if (state.aiProvider != AiProvider.OLLAMA) {
                AiApiKeyField(
                    provider = state.aiProvider,
                    apiKey = state.aiApiKey,
                    onSetApiKey = vm::setAiApiKey,
                )
            }
            AiModelField(
                provider = state.aiProvider,
                model = state.aiModel,
                models = models,
                onSetModel = vm::setAiModel,
                onLoadModels = vm::loadAiModels,
            )
            AiReasoningDropdown(
                labelRes = { level -> stringResource(Res.string.common_settings_reasoning_assistant, level) },
                current = state.aiReasoningAssistant,
                onSelect = vm::setAiReasoningAssistant,
            )
            AiReasoningDropdown(
                labelRes = { level -> stringResource(Res.string.common_settings_reasoning_edit, level) },
                current = state.aiReasoningEdit,
                onSelect = vm::setAiReasoningEdit,
            )
            Text(
                stringResource(Res.string.common_settings_reasoning_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AiTagCapDefaults(
                maxTagsPerDoc = state.aiMaxTagsPerDoc,
                onSetMaxTagsPerDoc = vm::setAiMaxTagsPerDoc,
                maxNewTagsPerDoc = state.aiMaxNewTagsPerDoc,
                onSetMaxNewTagsPerDoc = vm::setAiMaxNewTagsPerDoc,
            )
            AiAutoOnOcrToggle(enabled = state.aiAutoOnOcr, onToggle = vm::setAiAutoOnOcr)
            Text(
                stringResource(Res.string.common_settings_ai_request_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The unattended post-OCR enrichment switch. Nested under the AI section (and therefore only
 * reachable once AI is on) because it spends the same endpoint; the help text spells out that it
 * writes without a review step, which is what separates it from the AI tagging page.
 */
@Composable
private fun AiAutoOnOcrToggle(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(Res.string.common_settings_ai_auto_on_ocr),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                stringResource(Res.string.common_settings_ai_auto_on_ocr_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = enabled, onCheckedChange = onToggle)
    }
}

/** Provider names are product names, so [AiProvider.displayName] is deliberately not localized. */
@Composable
private fun AiProviderDropdown(
    current: AiProvider,
    onSelect: (AiProvider) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        OutlinedButton(onClick = { expanded = true }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(Res.string.common_settings_ai_provider, current.displayName))
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            AiProvider.entries.forEach { provider ->
                DropdownMenuItem(
                    text = { Text(provider.displayName) },
                    onClick = {
                        onSelect(provider)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun AiBaseUrlField(
    provider: AiProvider,
    baseUrl: String,
    onSetBaseUrl: (String) -> Unit,
) {
    OutlinedTextField(
        value = baseUrl,
        onValueChange = onSetBaseUrl,
        label = { Text(stringResource(Res.string.common_settings_server_url)) },
        supportingText = {
            Text(
                when (provider) {
                    AiProvider.OLLAMA -> {
                        stringResource(
                            Res.string.common_settings_server_url_ollama_help,
                            provider.defaultBaseUrl.orEmpty(),
                        )
                    }

                    else -> {
                        stringResource(
                            Res.string.common_settings_server_url_help,
                            "https://api.openai.com",
                            "http://localhost:11434",
                            "/v1/chat/completions",
                        )
                    }
                },
            )
        },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun AiApiKeyField(
    provider: AiProvider,
    apiKey: String,
    onSetApiKey: (String) -> Unit,
) {
    OutlinedTextField(
        value = apiKey,
        onValueChange = onSetApiKey,
        label = { Text(stringResource(Res.string.common_settings_api_key)) },
        visualTransformation = PasswordVisualTransformation(),
        supportingText = {
            Text(
                if (provider.requiresApiKey) {
                    stringResource(Res.string.common_settings_api_key_help_provider)
                } else {
                    stringResource(Res.string.common_settings_api_key_help)
                },
            )
        },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * Model id with a dropdown fed by the provider's live model list ([AiModelListState]); loading
 * starts on demand via the trailing arrow so no request fires before the user asks for it.
 * Typing a model id manually always works — a fetch failure is only surfaced as a hint.
 */
@Composable
private fun AiModelField(
    provider: AiProvider,
    model: String,
    models: AiModelListState,
    onSetModel: (String) -> Unit,
    onLoadModels: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        OutlinedTextField(
            value = model,
            onValueChange = onSetModel,
            label = { Text(stringResource(Res.string.common_settings_model)) },
            trailingIcon = {
                IconButton(onClick = {
                    onLoadModels()
                    expanded = true
                }) {
                    Icon(
                        Icons.Filled.ArrowDropDown,
                        contentDescription = stringResource(Res.string.common_settings_load_models),
                    )
                }
            },
            supportingText = {
                when {
                    models.error != null -> {
                        Text(
                            stringResource(Res.string.common_settings_models_error, models.error),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    provider == AiProvider.CUSTOM -> {
                        Text(
                            stringResource(
                                Res.string.common_settings_model_help,
                                "/v1/models",
                                "gpt-4.1-mini",
                                "qwen3:8b",
                            ),
                        )
                    }

                    else -> {
                        Text(stringResource(Res.string.common_settings_model_pick_help))
                    }
                }
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        DropdownMenu(
            expanded = expanded && (models.loading || models.models.isNotEmpty()),
            onDismissRequest = { expanded = false },
        ) {
            if (models.loading) {
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.common_settings_models_loading)) },
                    enabled = false,
                    onClick = {},
                )
            }
            models.models.forEach { id ->
                DropdownMenuItem(
                    text = { Text(id) },
                    onClick = {
                        onSetModel(id)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun AiReasoningDropdown(
    labelRes: @Composable (String) -> String,
    current: AiReasoningLevel,
    onSelect: (AiReasoningLevel) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        OutlinedButton(onClick = { expanded = true }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(labelRes(reasoningLevelLabel(current)))
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            AiReasoningLevel.entries.forEach { level ->
                DropdownMenuItem(
                    text = { Text(reasoningLevelLabel(level)) },
                    onClick = {
                        onSelect(level)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun reasoningLevelLabel(level: AiReasoningLevel): String =
    when (level) {
        AiReasoningLevel.DEFAULT -> stringResource(Res.string.common_settings_reasoning_default)
        AiReasoningLevel.NONE -> stringResource(Res.string.common_settings_reasoning_none)
        AiReasoningLevel.LOW -> stringResource(Res.string.common_settings_reasoning_low)
        AiReasoningLevel.MEDIUM -> stringResource(Res.string.common_settings_reasoning_medium)
        AiReasoningLevel.HIGH -> stringResource(Res.string.common_settings_reasoning_high)
    }

/** Default per-run tag caps for the AI assistant (overridable on the AI tagging page). */
@Composable
private fun AiTagCapDefaults(
    maxTagsPerDoc: Int,
    onSetMaxTagsPerDoc: (Int) -> Unit,
    maxNewTagsPerDoc: Int,
    onSetMaxNewTagsPerDoc: (Int) -> Unit,
) {
    NumericField(
        value = maxTagsPerDoc,
        onValueChange = onSetMaxTagsPerDoc,
        range = AiTagging.RANGE,
        label = stringResource(Res.string.common_settings_max_suggested_tags),
        supportingText =
            stringResource(
                Res.string.common_settings_max_suggested_tags_help,
                AiTagging.MIN_TAGS_PER_DOC,
                AiTagging.MAX_TAGS_PER_DOC,
            ),
        maxDigits = 2,
    )
    NumericField(
        value = maxNewTagsPerDoc,
        onValueChange = onSetMaxNewTagsPerDoc,
        range = AiTagging.NEW_TAGS_RANGE,
        label = stringResource(Res.string.common_settings_max_new_tags),
        supportingText =
            stringResource(
                Res.string.common_settings_max_new_tags_help,
                AiTagging.MIN_NEW_TAGS_PER_DOC,
                AiTagging.MAX_NEW_TAGS_PER_DOC,
            ),
        maxDigits = 2,
    )
}
