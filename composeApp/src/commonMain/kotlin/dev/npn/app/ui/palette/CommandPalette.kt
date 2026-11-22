package dev.npn.app.ui.palette

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.MoveToInbox
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.Sell
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.npn.app.platform.LocalDocumentScanner
import dev.npn.app.platform.platformFileToExternalSource
import dev.npn.app.ui.common.FocusOnEntry
import dev.npn.app.ui.settings.settingsCategoryLabels
import dev.npn.core.app.LibrarySession
import dev.npn.core.model.ReviewMode
import dev.npn.core.model.reviewMarkerIsAuthoritative
import dev.npn.core.sync.CloudSyncConfig
import io.github.vinceglb.filekit.dialogs.FileKitMode
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.name
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.palette_action_assistant
import no_papers_needed.composeapp.generated.resources.palette_action_import
import no_papers_needed.composeapp.generated.resources.palette_action_inbox
import no_papers_needed.composeapp.generated.resources.palette_action_scan
import no_papers_needed.composeapp.generated.resources.palette_action_settings
import no_papers_needed.composeapp.generated.resources.palette_action_sync_now
import no_papers_needed.composeapp.generated.resources.palette_action_tags
import no_papers_needed.composeapp.generated.resources.palette_action_untagged
import no_papers_needed.composeapp.generated.resources.palette_action_watch_folder
import no_papers_needed.composeapp.generated.resources.palette_group_actions
import no_papers_needed.composeapp.generated.resources.palette_group_documents
import no_papers_needed.composeapp.generated.resources.palette_group_recent
import no_papers_needed.composeapp.generated.resources.palette_group_settings
import no_papers_needed.composeapp.generated.resources.palette_group_tags
import no_papers_needed.composeapp.generated.resources.palette_group_views
import no_papers_needed.composeapp.generated.resources.palette_hint
import no_papers_needed.composeapp.generated.resources.palette_no_matches_body
import no_papers_needed.composeapp.generated.resources.palette_no_matches_title
import no_papers_needed.composeapp.generated.resources.palette_search_hint
import no_papers_needed.composeapp.generated.resources.palette_tag_documents
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The command palette's shell wiring: it labels the actions and settings panes the palette offers
 * in the app's language, starts the platform file picker and scanner the palette asks for, and
 * shows the overlay. Its viewmodel lives in the library's store, so the palette's recents and query
 * survive a recreated window.
 */
@Composable
internal fun CommandPaletteHost(
    session: LibrarySession,
    aiConfigured: Boolean,
    vm: CommandPaletteViewModel,
    modifier: Modifier = Modifier,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val reviewMode by remember(session) { session.reviewConfig.reviewMode }
        .collectAsStateWithLifecycle(ReviewMode.DEFAULT)
    val syncConfig by remember(session) { session.sync.config }
        .collectAsStateWithLifecycle(CloudSyncConfig.DISABLED)
    val scanner = LocalDocumentScanner.current

    val commands =
        buildList {
            add(PaletteCommand(PaletteCommandId.IMPORT, stringResource(Res.string.palette_action_import)))
            if (scanner != null) {
                add(PaletteCommand(PaletteCommandId.SCAN, stringResource(Res.string.palette_action_scan)))
            }
            add(PaletteCommand(PaletteCommandId.WATCH_FOLDER, stringResource(Res.string.palette_action_watch_folder)))
            if (syncConfig.configured) {
                add(PaletteCommand(PaletteCommandId.SYNC_NOW, stringResource(Res.string.palette_action_sync_now)))
            }
            if (aiConfigured) {
                add(PaletteCommand(PaletteCommandId.ASSISTANT, stringResource(Res.string.palette_action_assistant)))
            }
            add(
                PaletteCommand(
                    PaletteCommandId.INBOX,
                    stringResource(
                        if (reviewMode.reviewMarkerIsAuthoritative) {
                            Res.string.palette_action_inbox
                        } else {
                            Res.string.palette_action_untagged
                        },
                    ),
                ),
            )
            add(PaletteCommand(PaletteCommandId.TAGS, stringResource(Res.string.palette_action_tags)))
            add(PaletteCommand(PaletteCommandId.SETTINGS, stringResource(Res.string.palette_action_settings)))
        }
    val settings =
        settingsCategoryLabels().map { labels ->
            PaletteSettingsEntry(
                id = labels.category.id,
                title = labels.title,
                keywords = labels.keywords,
                advancedKeywords = labels.advancedKeywords,
            )
        }
    LaunchedEffect(commands, settings) { vm.setSources(commands, settings) }

    val picker =
        rememberFilePickerLauncher(type = FileKitType.File("pdf"), mode = FileKitMode.Multiple()) { files ->
            if (!files.isNullOrEmpty()) {
                vm.importFiles(files.map { platformFileToExternalSource(it) to it.name })
            }
        }
    val scan = scanner?.rememberLauncher(vm::importScan)
    val currentPicker by rememberUpdatedState(picker)
    val currentScan by rememberUpdatedState(scan)
    LaunchedEffect(vm) {
        vm.requests.collect { request ->
            when (request) {
                PaletteRequest.PickFiles -> currentPicker.launch()
                PaletteRequest.Scan -> currentScan?.invoke()
            }
        }
    }

    if (state.open) PaletteDialog(state, vm, modifier)
}

@Composable
private fun PaletteDialog(
    state: PaletteState,
    actions: CommandPaletteActions,
    modifier: Modifier = Modifier,
) {
    Dialog(
        onDismissRequest = actions::close,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        BoxWithConstraints(
            modifier.fillMaxSize().background(SCRIM).padding(horizontal = 16.dp, vertical = 24.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            // A fixed share of the window, capped, so the card never jumps as results arrive and
            // still fits a compact window.
            val cardHeight = (maxHeight * 0.82f).coerceAtMost(560.dp)
            Surface(
                Modifier
                    .fillMaxWidth()
                    .widthIn(max = 640.dp)
                    .height(cardHeight)
                    .onPreviewKeyEvent { event -> handlePaletteKey(event, actions) },
                shape = MaterialTheme.shapes.large,
                tonalElevation = 3.dp,
                shadowElevation = 8.dp,
            ) {
                Column {
                    PaletteSearchField(state.query, actions)
                    HorizontalDivider()
                    PaletteResults(state, actions, Modifier.weight(1f))
                    HorizontalDivider()
                    Text(
                        stringResource(Res.string.palette_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun PaletteSearchField(
    query: String,
    actions: CommandPaletteActions,
) {
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    FocusOnEntry(focus)
    LaunchedEffect(Unit) {
        withFrameNanos { }
        keyboard?.show()
    }
    OutlinedTextField(
        value = query,
        onValueChange = actions::onQueryChange,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .focusRequester(focus),
        singleLine = true,
        placeholder = { Text(stringResource(Res.string.palette_search_hint)) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
        keyboardActions = KeyboardActions(onGo = { actions.runSelected() }),
    )
}

/** The palette's keys, bound on the dialog so they work wherever focus is inside it. */
private fun handlePaletteKey(
    event: KeyEvent,
    actions: CommandPaletteActions,
): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    return when {
        event.key == Key.DirectionDown -> {
            actions.moveSelection(1)
            true
        }

        event.key == Key.DirectionUp -> {
            actions.moveSelection(-1)
            true
        }

        event.key == Key.Enter || event.key == Key.NumPadEnter -> {
            actions.runSelected()
            true
        }

        event.key == Key.Escape -> {
            actions.close()
            true
        }

        event.key == Key.K && (event.isCtrlPressed || event.isMetaPressed) -> {
            actions.close()
            true
        }

        else -> false
    }
}

private sealed interface PaletteRow {
    data class Header(
        val kind: PaletteGroupKind,
    ) : PaletteRow

    data class Entry(
        val item: PaletteItem,
        val index: Int,
    ) : PaletteRow
}

private fun rowsOf(state: PaletteState): List<PaletteRow> {
    val rows = ArrayList<PaletteRow>()
    var index = 0
    for (group in state.groups) {
        if (group.items.isEmpty()) continue
        rows += PaletteRow.Header(group.kind)
        for (item in group.items) rows += PaletteRow.Entry(item, index++)
    }
    return rows
}

@Composable
private fun PaletteResults(
    state: PaletteState,
    actions: CommandPaletteActions,
    modifier: Modifier = Modifier,
) {
    if (!state.hasResults) {
        Column(
            modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                Icons.Outlined.SearchOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(Res.string.palette_no_matches_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                stringResource(Res.string.palette_no_matches_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 4.dp),
            )
        }
        return
    }

    val listState = rememberLazyListState()
    val rows = rowsOf(state)
    val selectedRow = rows.indexOfFirst { it is PaletteRow.Entry && it.index == state.selected }
    LaunchedEffect(selectedRow) {
        if (selectedRow < 0) return@LaunchedEffect
        val visible = listState.layoutInfo.visibleItemsInfo.any { it.index == selectedRow }
        if (!visible) listState.animateScrollToItem(selectedRow)
    }
    LazyColumn(state = listState, modifier = modifier.fillMaxWidth()) {
        rows.forEach { row ->
            when (row) {
                is PaletteRow.Header ->
                    item(key = "header:${row.kind.name}") {
                        GroupHeader(row.kind)
                    }

                is PaletteRow.Entry ->
                    item(key = row.item.key) {
                        ResultRow(
                            item = row.item,
                            selected = row.index == state.selected,
                            onClick = { actions.run(row.item) },
                        )
                    }
            }
        }
    }
}

@Composable
private fun GroupHeader(kind: PaletteGroupKind) {
    Text(
        stringResource(kind.label()),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun ResultRow(
    item: PaletteItem,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            item.icon(),
            contentDescription = null,
            tint =
                if (selected) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
        )
        Column(Modifier.weight(1f)) {
            Text(
                item.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val subtitle = item.subtitle()
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun PaletteItem.subtitle(): String? =
    snippet ?: subtitle ?: count?.let { pluralStringResource(Res.plurals.palette_tag_documents, it, it) }

private fun PaletteGroupKind.label(): StringResource =
    when (this) {
        PaletteGroupKind.RECENT -> Res.string.palette_group_recent
        PaletteGroupKind.DOCUMENTS -> Res.string.palette_group_documents
        PaletteGroupKind.TAGS -> Res.string.palette_group_tags
        PaletteGroupKind.VIEWS -> Res.string.palette_group_views
        PaletteGroupKind.SETTINGS -> Res.string.palette_group_settings
        PaletteGroupKind.ACTIONS -> Res.string.palette_group_actions
    }

private fun PaletteItem.icon(): ImageVector =
    when (val target = target) {        is PaletteTarget.Doc -> Icons.Outlined.Description
        is PaletteTarget.Tag -> Icons.Outlined.Sell
        is PaletteTarget.View -> Icons.Outlined.BookmarkBorder
        is PaletteTarget.Settings -> Icons.Outlined.Settings
        is PaletteTarget.Action ->
            when (target.id) {
                PaletteCommandId.IMPORT -> Icons.Outlined.UploadFile
                PaletteCommandId.SCAN -> Icons.Outlined.DocumentScanner
                PaletteCommandId.WATCH_FOLDER -> Icons.Outlined.FolderOpen
                PaletteCommandId.SYNC_NOW -> Icons.Outlined.CloudSync
                PaletteCommandId.ASSISTANT -> Icons.Outlined.AutoAwesome
                PaletteCommandId.INBOX -> Icons.Outlined.MoveToInbox
                PaletteCommandId.TAGS -> Icons.Outlined.Sell
                PaletteCommandId.SETTINGS -> Icons.Outlined.Settings
            }
    }
