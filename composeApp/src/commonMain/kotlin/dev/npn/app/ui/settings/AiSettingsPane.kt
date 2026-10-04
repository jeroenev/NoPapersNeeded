package dev.npn.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import dev.npn.app.ui.common.resolve
import dev.npn.core.ai.AiCheck
import dev.npn.core.ai.AiCheckOutcome
import dev.npn.core.ai.AiCheckStatus
import dev.npn.core.ai.AiProvider
import dev.npn.core.ai.AiReasoningLevel
import dev.npn.core.app.AiTagging
import dev.npn.core.ocr.OcrEngineSelector
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_settings_ai_auto_on_ocr
import no_papers_needed.composeapp.generated.resources.common_settings_ai_auto_on_ocr_help
import no_papers_needed.composeapp.generated.resources.common_settings_ai_check_api
import no_papers_needed.composeapp.generated.resources.common_settings_ai_check_failed
import no_papers_needed.composeapp.generated.resources.common_settings_ai_check_messages
import no_papers_needed.composeapp.generated.resources.common_settings_ai_check_passed
import no_papers_needed.composeapp.generated.resources.common_settings_ai_check_skipped
import no_papers_needed.composeapp.generated.resources.common_settings_ai_check_vision
import no_papers_needed.composeapp.generated.resources.common_settings_ai_help
import no_papers_needed.composeapp.generated.resources.common_settings_ai_ocr_auto
import no_papers_needed.composeapp.generated.resources.common_settings_ai_ocr_auto_help
import no_papers_needed.composeapp.generated.resources.common_settings_ai_provider
import no_papers_needed.composeapp.generated.resources.common_settings_ai_provider_help
import no_papers_needed.composeapp.generated.resources.common_settings_ai_request_help
import no_papers_needed.composeapp.generated.resources.common_settings_ai_test
import no_papers_needed.composeapp.generated.resources.common_settings_ai_test_again
import no_papers_needed.composeapp.generated.resources.common_settings_ai_test_help
import no_papers_needed.composeapp.generated.resources.common_settings_ai_test_ocr_disabled
import no_papers_needed.composeapp.generated.resources.common_settings_ai_test_running
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
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

private const val TAG_CAP_MAX_DIGITS = 2

private val SelfTestSpacing = 8.dp
private val CheckIconSize = 20.dp

/**
 * The AI assistant pane: a provider picker (Koog-backed providers first, the fully manual
 * OpenAI-compatible option last), provider-appropriate endpoint fields, a model picker fed by the
 * provider's live model list, and per-feature reasoning levels.
 *
 * Collapsed to just the enable switch until AI is turned on: the only progressive disclosure in
 * settings that is worth its click, because none of the configuration below means anything with
 * the feature off. Every change persists immediately; there is no explicit save.
 */
@Composable
internal fun AiSettingsPane(context: SettingsPaneContext) {
    val vm = context.vm
    val state = context.state
    SettingsGroup(title = null) {
        SettingsSwitchRow(
            title = stringResource(Res.string.common_settings_enable_ai),
            help = stringResource(Res.string.common_settings_ai_help),
            checked = state.aiEnabled,
            onCheckedChange = { on -> vm.edit { it.copy(aiEnabled = on) } },
        )
        if (state.aiEnabled) {
            AiEndpointSettings(state = state, models = context.aiModels, vm = vm)
            AiBehaviourSettings(state = state, vm = vm)
            // Last, because it is a verdict on everything above it.
            AiSelfTestSection(state = state, selfTest = context.aiSelfTest, onRun = vm::runAiSelfTest)
        }
    }
}

/** Which service the requests go to, and with what credentials. */
@Composable
private fun AiEndpointSettings(
    state: SettingsUiState,
    models: AiModelListState,
    vm: SettingsViewModel,
) {
    // setAiProvider stays a named method (it clears the stale model list), unlike the plain
    // field edits below.
    SettingsDropdown(
        buttonLabel = stringResource(Res.string.common_settings_ai_provider, state.aiProvider.displayName),
        options = AiProvider.entries,
        // Provider names are product names, so displayName is deliberately not localized.
        optionLabel = { it.displayName },
        onSelect = vm::setAiProvider,
    )
    SettingsHelpText(stringResource(Res.string.common_settings_ai_provider_help))
    if (state.aiProvider.editableBaseUrl) {
        AiBaseUrlField(
            provider = state.aiProvider,
            baseUrl = state.aiBaseUrl,
            onSetBaseUrl = { url -> vm.edit { it.copy(aiBaseUrl = url) } },
        )
    }
    if (state.aiProvider != AiProvider.OLLAMA) {
        AiApiKeyField(
            provider = state.aiProvider,
            apiKey = state.aiApiKey,
            onSetApiKey = { key -> vm.edit { it.copy(aiApiKey = key) } },
        )
    }
    AiModelField(
        provider = state.aiProvider,
        model = state.aiModel,
        models = models,
        onSetModel = { model -> vm.edit { it.copy(aiModel = model) } },
        onLoadModels = vm::loadAiModels,
    )
}

/** How hard the model thinks, how many tags it may propose, and whether it runs unattended. */
@Composable
private fun AiBehaviourSettings(
    state: SettingsUiState,
    vm: SettingsViewModel,
) {
    AiReasoningDropdown(
        label = { level -> stringResource(Res.string.common_settings_reasoning_assistant, level) },
        current = state.aiReasoningAssistant,
        onSelect = { level -> vm.edit { it.copy(aiReasoningAssistant = level) } },
    )
    AiReasoningDropdown(
        label = { level -> stringResource(Res.string.common_settings_reasoning_edit, level) },
        current = state.aiReasoningEdit,
        onSelect = { level -> vm.edit { it.copy(aiReasoningEdit = level) } },
    )
    SettingsHelpText(stringResource(Res.string.common_settings_reasoning_help))
    AiTagCapDefaults(
        maxTagsPerDoc = state.aiMaxTagsPerDoc,
        onSetMaxTagsPerDoc = { value -> vm.edit { it.copy(aiMaxTagsPerDoc = value) } },
        maxNewTagsPerDoc = state.aiMaxNewTagsPerDoc,
        onSetMaxNewTagsPerDoc = { value -> vm.edit { it.copy(aiMaxNewTagsPerDoc = value) } },
    )
    // The unattended post-OCR enrichment switch. It lives here (and is therefore only reachable
    // once AI is on) because it spends the same endpoint; the help text spells out that it writes
    // without a review step, which is what separates it from the AI tagging page.
    SettingsSwitchRow(
        title = stringResource(Res.string.common_settings_ai_auto_on_ocr),
        help = stringResource(Res.string.common_settings_ai_auto_on_ocr_help),
        checked = state.aiAutoOnOcr,
        onCheckedChange = { on -> vm.edit { it.copy(aiAutoOnOcr = on) } },
    )
    // Making the vision model the default OCR engine. Kept next to the switch above because both
    // spend the endpoint unattended, but this one is charged per PAGE rather than per document, and
    // it also re-transcribes documents a local engine already did — so the help text leads with the
    // cost, and "OCR with AI" on a single document stays available whether this is on or off.
    SettingsSwitchRow(
        title = stringResource(Res.string.common_settings_ai_ocr_auto),
        help =
            stringResource(
                Res.string.common_settings_ai_ocr_auto_help,
                OcrEngineSelector.DEFAULT_LARGE_DOCUMENT_PAGES,
            ),
        checked = state.aiOcrAuto,
        onCheckedChange = { on -> vm.edit { it.copy(aiOcrAuto = on) } },
    )
    SettingsHelpText(stringResource(Res.string.common_settings_ai_request_help))
}

/**
 * Diagnosis of the setup above: one button, and one line per check once a run has finished.
 *
 * Three lines rather than one verdict because the three failures have three different fixes (wrong
 * key, wrong model id, model without vision), and a single "AI is not working" would leave the user
 * guessing which. Disabled until the endpoint is complete, since there is nothing to test yet.
 */
@Composable
private fun AiSelfTestSection(
    state: SettingsUiState,
    selfTest: AiSelfTestState,
    onRun: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(SelfTestSpacing)) {
        OutlinedButton(onClick = onRun, enabled = state.aiConfigured && !selfTest.running) {
            Text(
                text =
                    stringResource(
                        when {
                            selfTest.running -> Res.string.common_settings_ai_test_running
                            selfTest.report != null -> Res.string.common_settings_ai_test_again
                            else -> Res.string.common_settings_ai_test
                        },
                    ),
            )
        }
        if (selfTest.running) LinearProgressIndicator(Modifier.fillMaxWidth())
        selfTest.report?.outcomes?.forEach { AiCheckRow(it) }
        if (selfTest.aiOcrTurnedOff) {
            Text(
                text =
                    stringResource(
                        Res.string.common_settings_ai_test_ocr_disabled,
                        stringResource(Res.string.common_settings_ai_ocr_auto),
                    ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        SettingsHelpText(stringResource(Res.string.common_settings_ai_test_help))
    }
}

/**
 * One check's verdict. [AiCheckOutcome.detail] is quoted from the endpoint (an error message, or
 * the answer that was wrong), so it is shown verbatim and untranslated — it is evidence, not copy.
 */
@Composable
private fun AiCheckRow(outcome: AiCheckOutcome) {
    val (icon, tint) =
        when (outcome.status) {
            AiCheckStatus.PASSED -> Icons.Outlined.CheckCircle to MaterialTheme.colorScheme.primary
            AiCheckStatus.FAILED -> Icons.Outlined.ErrorOutline to MaterialTheme.colorScheme.error
            AiCheckStatus.SKIPPED -> Icons.Outlined.RemoveCircleOutline to MaterialTheme.colorScheme.outline
        }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Icon(
            imageVector = icon,
            contentDescription = stringResource(checkStatusLabel(outcome.status)),
            tint = tint,
            modifier = Modifier.padding(end = SelfTestSpacing).size(CheckIconSize),
        )
        Column(Modifier.weight(1f)) {
            Text(text = stringResource(checkLabel(outcome.check)), style = MaterialTheme.typography.bodyMedium)
            SettingsHelpText(
                buildString {
                    append(stringResource(checkStatusLabel(outcome.status)))
                    outcome.detail?.let { append(" — $it") }
                },
            )
        }
    }
}

private fun checkLabel(check: AiCheck): StringResource =
    when (check) {
        AiCheck.API -> Res.string.common_settings_ai_check_api
        AiCheck.MESSAGES -> Res.string.common_settings_ai_check_messages
        AiCheck.VISION -> Res.string.common_settings_ai_check_vision
    }

private fun checkStatusLabel(status: AiCheckStatus): StringResource =
    when (status) {
        AiCheckStatus.PASSED -> Res.string.common_settings_ai_check_passed
        AiCheckStatus.FAILED -> Res.string.common_settings_ai_check_failed
        AiCheckStatus.SKIPPED -> Res.string.common_settings_ai_check_skipped
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
        label = { Text(text = stringResource(Res.string.common_settings_server_url)) },
        supportingText = {
            Text(
                text =
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
        label = { Text(text = stringResource(Res.string.common_settings_api_key)) },
        visualTransformation = PasswordVisualTransformation(),
        supportingText = {
            Text(
                text =
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
 * Typing a model id manually always works; a fetch failure is only surfaced as a hint.
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
            label = { Text(text = stringResource(Res.string.common_settings_model)) },
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
            supportingText = { AiModelSupportingText(provider, models) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        DropdownMenu(
            expanded = expanded && (models.loading || models.models.isNotEmpty()),
            onDismissRequest = { expanded = false },
        ) {
            if (models.loading) {
                DropdownMenuItem(
                    text = { Text(text = stringResource(Res.string.common_settings_models_loading)) },
                    enabled = false,
                    onClick = {},
                )
            }
            models.models.forEach { id ->
                DropdownMenuItem(
                    text = { Text(text = id) },
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
private fun AiModelSupportingText(
    provider: AiProvider,
    models: AiModelListState,
) {
    when {
        models.error != null -> {
            Text(
                text = stringResource(Res.string.common_settings_models_error, models.error.resolve()),
                color = MaterialTheme.colorScheme.error,
            )
        }

        provider == AiProvider.CUSTOM -> {
            Text(
                text =
                    stringResource(
                        Res.string.common_settings_model_help,
                        "/v1/models",
                        "gpt-4.1-mini",
                        "qwen3:8b",
                    ),
            )
        }

        else -> {
            Text(text = stringResource(Res.string.common_settings_model_pick_help))
        }
    }
}

@Composable
private fun AiReasoningDropdown(
    label: @Composable (String) -> String,
    current: AiReasoningLevel,
    onSelect: (AiReasoningLevel) -> Unit,
) {
    SettingsDropdown(
        buttonLabel = label(reasoningLevelLabel(current)),
        options = AiReasoningLevel.entries,
        optionLabel = { reasoningLevelLabel(it) },
        onSelect = onSelect,
    )
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
        maxDigits = TAG_CAP_MAX_DIGITS,
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
        maxDigits = TAG_CAP_MAX_DIGITS,
    )
}
