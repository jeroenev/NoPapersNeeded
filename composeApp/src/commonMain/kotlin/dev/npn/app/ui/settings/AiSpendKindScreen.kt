package dev.npn.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.npn.app.ui.common.BackTopBar
import dev.npn.app.ui.common.HelpText
import dev.npn.app.ui.common.SettingsSection
import dev.npn.app.ui.common.resolve
import dev.npn.core.app.LibrarySession
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_settings_ai_spend_all_time
import no_papers_needed.composeapp.generated.resources.common_settings_ai_spend_empty
import no_papers_needed.composeapp.generated.resources.common_settings_ai_spend_estimated
import no_papers_needed.composeapp.generated.resources.common_settings_ai_spend_last30
import org.jetbrains.compose.resources.stringResource

/**
 * The spend of one kind of work ([AiSpendKinds]), opened from the AI settings pane: its requests,
 * tokens and cost per provider and model, over the last 30 days and all time.
 */
@Composable
internal fun AiSpendKindScreen(
    session: LibrarySession,
    kind: String,
    onBack: () -> Unit,
    vm: AiSpendKindViewModel = viewModel(key = kind) { AiSpendKindViewModel(session, kind) },
) {
    val state by vm.state.collectAsStateWithLifecycle()
    Scaffold(topBar = { BackTopBar(title = state.title.resolve(), onBack = onBack) }) { padding ->
        AiSpendKindContent(state, Modifier.padding(padding).fillMaxSize())
    }
}

@Composable
private fun AiSpendKindContent(
    state: AiSpendKindState,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(ContentPadding),
        verticalArrangement = Arrangement.spacedBy(SectionGap),
    ) {
        SpendWindowSection(stringResource(Res.string.common_settings_ai_spend_last30), state.last30Days)
        SpendWindowSection(stringResource(Res.string.common_settings_ai_spend_all_time), state.allTime)
        HelpText(stringResource(Res.string.common_settings_ai_spend_estimated))
    }
}

@Composable
private fun SpendWindowSection(
    title: String,
    window: SpendWindowText,
) {
    SettingsSection(title = title) {
        val total = window.total
        if (total == null) {
            HelpText(stringResource(Res.string.common_settings_ai_spend_empty))
        } else {
            window.lines.forEach { line -> Text(line.resolve(), style = MaterialTheme.typography.bodyMedium) }
            Text(total.resolve(), style = MaterialTheme.typography.titleSmall)
        }
    }
}

private val ContentPadding = 16.dp
private val SectionGap = 24.dp
