package dev.npn.app.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.npn.app.ui.common.ExpandIcon
import dev.npn.app.ui.common.HelpText
import dev.npn.app.ui.common.LocalWindowWidthClass
import dev.npn.app.ui.common.TooltipIconButton
import dev.npn.app.ui.common.expander
import dev.npn.app.ui.settings.SettingsCategoryId
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.library_getting_started
import no_papers_needed.composeapp.generated.resources.library_getting_started_assistant
import no_papers_needed.composeapp.generated.resources.library_getting_started_assistant_note
import no_papers_needed.composeapp.generated.resources.library_getting_started_close
import no_papers_needed.composeapp.generated.resources.library_getting_started_device
import no_papers_needed.composeapp.generated.resources.library_getting_started_done
import no_papers_needed.composeapp.generated.resources.library_getting_started_inbox
import no_papers_needed.composeapp.generated.resources.library_getting_started_progress
import no_papers_needed.composeapp.generated.resources.library_getting_started_search
import no_papers_needed.composeapp.generated.resources.library_getting_started_tag
import no_papers_needed.composeapp.generated.resources.library_getting_started_to_do
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The library's "Getting started" card: a short list of what makes the app useful past the first
 * import, each step ticked off once it is taken ([steps]) and leading to where it is done. The
 * assistant step is optional, and says what connecting one means for privacy and cost. Collapsible,
 * open from the start on a wide window, where it has the room; closing it ([onDismiss]) is for good.
 * Tags are added on a document, which [onAddTag] opens; [onTrySearch] puts the cursor in the
 * search field, and [onOpenSettings] opens the pane the other steps are set in.
 */
@Composable
internal fun GettingStartedCard(
    steps: GettingStarted,
    onAddTag: () -> Unit,
    onTrySearch: () -> Unit,
    onOpenSettings: (SettingsCategoryId) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val wide = LocalWindowWidthClass.current.isWide
    var expanded by rememberSaveable { mutableStateOf(wide) }
    val required = listOf(steps.tagged, steps.searched, steps.anotherDevice, steps.inbox)
    OutlinedCard(modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f).expander(expanded) { expanded = !expanded }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(Res.string.library_getting_started), style = MaterialTheme.typography.titleSmall)
                HelpText(
                    stringResource(Res.string.library_getting_started_progress, required.count { it }, required.size),
                    modifier = Modifier.weight(1f),
                )
                ExpandIcon(expanded)
            }
            TooltipIconButton(
                Icons.Filled.Close,
                stringResource(Res.string.library_getting_started_close),
                onDismiss,
            )
        }
        if (expanded) {
            Column(Modifier.padding(bottom = 8.dp)) {
                GettingStartedStep(Res.string.library_getting_started_tag, steps.tagged, onAddTag)
                GettingStartedStep(Res.string.library_getting_started_search, steps.searched, onTrySearch)
                GettingStartedStep(
                    Res.string.library_getting_started_device,
                    steps.anotherDevice,
                    onClick = { onOpenSettings(SettingsCategoryId.CLOUD_SYNC) },
                )
                GettingStartedStep(
                    Res.string.library_getting_started_inbox,
                    steps.inbox,
                    onClick = { onOpenSettings(SettingsCategoryId.DOCUMENTS) },
                )
                GettingStartedStep(
                    Res.string.library_getting_started_assistant,
                    steps.assistant,
                    onClick = { onOpenSettings(SettingsCategoryId.AI) },
                    note = Res.string.library_getting_started_assistant_note,
                )
            }
        }
    }
}

/** One step of the card: ticked when [done], leading through [onClick] to where it is done. */
@Composable
private fun GettingStartedStep(
    label: StringResource,
    done: Boolean,
    onClick: () -> Unit,
    note: StringResource? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            if (done) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
            contentDescription =
                stringResource(
                    if (done) Res.string.library_getting_started_done else Res.string.library_getting_started_to_do,
                ),
            tint = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(label),
                style = MaterialTheme.typography.bodyMedium,
                color = if (done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
            note?.let { HelpText(stringResource(it)) }
        }
    }
}
