@file:OptIn(ExperimentalMaterial3Api::class)

package dev.npn.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import dev.npn.app.ui.common.BackTopBar
import dev.npn.app.ui.common.ConfirmDialog
import dev.npn.app.ui.common.HelpText
import dev.npn.app.ui.common.LocalWindowWidthClass
import dev.npn.app.ui.common.SearchField
import dev.npn.app.ui.common.rememberLibraryFolderPicker
import dev.npn.app.ui.common.rememberSnackbarMessages
import dev.npn.app.ui.detail.LocalDocumentDrafts
import dev.npn.core.app.LibrarySession
import dev.npn.core.settings.SettingsRepository
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_settings_search
import no_papers_needed.composeapp.generated.resources.common_settings_search_no_results
import no_papers_needed.composeapp.generated.resources.common_settings_title
import no_papers_needed.composeapp.generated.resources.detail_unsaved_changes
import no_papers_needed.composeapp.generated.resources.ux_library_draft_warning
import no_papers_needed.composeapp.generated.resources.ux_resume_draft
import org.jetbrains.compose.resources.stringResource

/** Width of the category list when it sits beside a pane. Matches the Material nav-drawer width. */
private val CategoryListWidth: Dp = 280.dp

private val PaneContentPadding: Dp = 16.dp
private val PaneGroupSpacing: Dp = 24.dp

/**
 * Settings, laid out as a table of contents plus one open pane.
 *
 * Which categories exist and what they contain lives entirely in [SettingsCategories]; this file
 * only decides where the list and the pane go. Wide windows show both side by side (the same
 * list-detail shape the library uses); compact windows show the list and drill down into a pane,
 * so Android's system back walks back to the list.
 *
 * [category] comes from the route, which makes a pane deep-linkable and survives process death.
 * A null category means "no pane chosen yet": the list on compact, the first category on wide,
 * where an empty right-hand pane would just be a hole.
 *
 * Changing the library hands the picked folder to [onOpenLibrary], which closes this library. A
 * document with unsaved changes would go with it, so the user is sent back to it first
 * ([onResumeDraft]) rather than shown the picker.
 */
@Composable
fun SettingsScreen(
    settings: SettingsRepository,
    session: LibrarySession,
    category: SettingsCategoryId?,
    onOpenCategory: (SettingsCategoryId) -> Unit,
    onBack: () -> Unit,
    onOpenLibrary: (location: String) -> Unit,
    onResumeDraft: (docId: String) -> Unit,
    onAutoMatchRules: () -> Unit,
    vm: SettingsViewModel = viewModel { SettingsViewModel(settings, session, saved = createSavedStateHandle()) },
) {
    val paneState by vm.paneState.collectAsStateWithLifecycle()

    val snackbar = rememberSnackbarMessages(vm.messages)

    val drafts = LocalDocumentDrafts.current
    var draftToResume by rememberSaveable { mutableStateOf<String?>(null) }
    val pickLibrary =
        rememberLibraryFolderPicker(
            useSaf = paneState.settings.snapshot.useSafStorage,
            onPick = onOpenLibrary,
            onUnusable = vm::folderNotOnDevice,
            onTemporaryAccess = vm::libraryAccessNotKept,
        )
    val onChangeLibrary = {
        // Resolved with the same predicate the dialog acts on, so "Continue editing" always has a
        // document to go to, even when the only unsaved work is a save still in flight.
        val pending = drafts.state.entries.firstOrNull { it.value.dirty || it.value.saving }
        if (pending != null) draftToResume = pending.key else pickLibrary()
    }

    // The viewmodel's writes, plus the two ways out of a pane; one object for the screen's life,
    // calling whichever callbacks the latest composition passed.
    val currentChangeLibrary by rememberUpdatedState(onChangeLibrary)
    val currentAutoMatchRules by rememberUpdatedState(onAutoMatchRules)
    val paneActions =
        remember(vm) {
            object : SettingsPaneActions, SettingsActions by vm {
                override fun changeLibrary() = currentChangeLibrary()

                override fun openAutoMatchRules() = currentAutoMatchRules()

                override fun openFieldDialog(dialog: FieldDialog?) = vm.openFieldDialog(dialog)
            }
        }

    val wide = LocalWindowWidthClass.current.isWide
    // Wide layouts keep the list on screen, so switching panes is a selection, not navigation:
    // it must not stack back-stack entries, and it must not reset the search box by tearing
    // the screen down. Compact layouts drill down for real, which is what makes system back
    // return to the list. A pane picked while wide stays open when the window turns compact,
    // without a route of its own, so Back there closes it rather than leaving Settings. The
    // viewmodel holds it, so the rebuild a change of language makes keeps it, and saves it for a
    // restored process.
    val picked by vm.picked.collectAsStateWithLifecycle()
    val query by vm.categoryQuery.collectAsStateWithLifecycle()
    val closePicked = !wide && category == null && picked != null
    NavigationBackHandler(
        state = rememberNavigationEventState(NavigationEventInfo.None),
        isBackEnabled = closePicked,
        onBackCompleted = { vm.pick(null) },
    )
    SettingsScaffold(
        wide = wide,
        selected =
            if (wide) {
                picked ?: category ?: SettingsCategoryId.LIBRARY
            } else {
                category ?: picked
            },
        snackbar = snackbar,
        paneState = paneState,
        paneActions = paneActions,
        query = query,
        onQueryChange = vm::searchCategories,
        onSelect = { id -> if (wide) vm.pick(id) else onOpenCategory(id) },
        onBack = { if (closePicked) vm.pick(null) else onBack() },
    )
    draftToResume?.let { docId ->
        ConfirmDialog(
            title = stringResource(Res.string.detail_unsaved_changes),
            message = stringResource(Res.string.ux_library_draft_warning),
            confirmLabel = stringResource(Res.string.ux_resume_draft),
            onConfirm = {
                draftToResume = null
                onResumeDraft(docId)
            },
            onDismiss = { draftToResume = null },
        )
    }
}

@Composable
private fun SettingsScaffold(
    wide: Boolean,
    selected: SettingsCategoryId?,
    snackbar: SnackbarHostState,
    paneState: SettingsPaneState,
    paneActions: SettingsPaneActions,
    /** What is typed into the table of contents' search. */
    query: String,
    onQueryChange: (String) -> Unit,
    onSelect: (SettingsCategoryId) -> Unit,
    onBack: () -> Unit,
) {
    val openCategory = selected?.let(::settingsCategory)
    // On compact the pane replaces the list, so the bar has to say which pane is open.
    val paneTitle = if (wide) null else openCategory?.title
    // Settings is a top-level destination reached from the persistent shell, so the top level has
    // no back arrow — it would be a dead end duplicating the rail or bar. The one place a back
    // arrow belongs is the compact category drill-down, where it walks back to the category list.
    val drilledDown = !wide && openCategory != null
    // One composition of the open pane, moved between the two layouts rather than built again in
    // each: a dialog open over it and what is typed into it stay when the window crosses the
    // wide/compact breakpoint.
    val pane =
        remember {
            movableContentOf { args: SettingsPaneArgs ->
                SettingsPane(args.category, args.state, args.actions, args.showTitle, args.modifier)
            }
        }
    Scaffold(
        topBar = {
            SettingsTopBar(
                title = stringResource(paneTitle ?: Res.string.common_settings_title),
                onBack = if (drilledDown) onBack else null,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                wide -> {
                    Row(Modifier.fillMaxSize()) {
                        SettingsCategoryList(
                            selected = selected,
                            query = query,
                            onQueryChange = onQueryChange,
                            onSelect = onSelect,
                            modifier = Modifier.width(CategoryListWidth).fillMaxHeight(),
                        )
                        VerticalDivider()
                        pane(
                            SettingsPaneArgs(
                                openCategory,
                                paneState,
                                paneActions,
                                showTitle = true,
                                Modifier.weight(1f).fillMaxHeight(),
                            ),
                        )
                    }
                }

                openCategory != null -> {
                    pane(
                        SettingsPaneArgs(
                            openCategory,
                            paneState,
                            paneActions,
                            showTitle = false,
                            Modifier.fillMaxSize(),
                        ),
                    )
                }

                else -> {
                    SettingsCategoryList(
                        selected = null,
                        query = query,
                        onQueryChange = onQueryChange,
                        onSelect = onSelect,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsTopBar(
    title: String,
    onBack: (() -> Unit)?,
) {
    if (onBack != null) {
        BackTopBar(title = title, onBack = onBack)
    } else {
        TopAppBar(title = { Text(text = title) })
    }
}

/**
 * The table of contents, filtered by a search box. Search matches a category's title or the label
 * of any setting inside it, so "thumbnail" finds Library without every individual setting needing
 * to be a navigable destination of its own.
 */
@Composable
private fun SettingsCategoryList(
    selected: SettingsCategoryId?,
    query: String,
    onQueryChange: (String) -> Unit,
    onSelect: (SettingsCategoryId) -> Unit,
    modifier: Modifier = Modifier,
) {
    val labels = settingsCategoryLabels()
    val results = labels.filter { it.matches(query) }
    Column(modifier) {
        SearchField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            placeholder = stringResource(Res.string.common_settings_search),
        )
        if (results.isEmpty()) {
            Text(
                text = stringResource(Res.string.common_settings_search_no_results, query.trim()),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = PaneContentPadding),
            )
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            items(results, key = { it.category.id.name }) { entry ->
                SettingsCategoryRow(
                    entry = entry,
                    query = query,
                    selected = entry.category.id == selected,
                    onClick = { onSelect(entry.category.id) },
                )
            }
        }
    }
}

@Composable
private fun SettingsCategoryRow(
    entry: SettingsCategoryLabels,
    query: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    // While searching, name the setting that matched so the row explains why it is in the results.
    val matched = entry.matchedKeyword(query)?.takeIf { !entry.title.contains(query.trim(), ignoreCase = true) }
    NavigationDrawerItem(
        label = {
            Column {
                Text(text = entry.title)
                if (matched != null) {
                    HelpText(matched)
                }
            }
        },
        icon = { Icon(entry.category.icon, contentDescription = null) },
        selected = selected,
        onClick = onClick,
        modifier = Modifier.padding(horizontal = 12.dp),
    )
}

/** What [SettingsPane] is shown with, as one value its movable content takes. */
private class SettingsPaneArgs(
    val category: SettingsCategory?,
    val state: SettingsPaneState,
    val actions: SettingsPaneActions,
    val showTitle: Boolean,
    val modifier: Modifier,
)

/**
 * Hosts one category's controls. Panes emit a run of [SettingsSection]s and nothing else; the
 * scrolling, padding, and inter-group spacing all live here so no pane has to reproduce them.
 */
@Composable
private fun SettingsPane(
    category: SettingsCategory?,
    state: SettingsPaneState,
    actions: SettingsPaneActions,
    showTitle: Boolean,
    modifier: Modifier = Modifier,
) {
    if (category == null) return
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(PaneContentPadding),
        verticalArrangement = Arrangement.spacedBy(PaneGroupSpacing),
    ) {
        if (showTitle) {
            Text(text = stringResource(category.title), style = MaterialTheme.typography.headlineSmall)
        }
        category.content(state, actions)
    }
}
