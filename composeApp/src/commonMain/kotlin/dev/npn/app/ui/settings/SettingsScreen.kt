@file:OptIn(ExperimentalMaterial3Api::class)

package dev.npn.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.npn.app.di.AppEnvironment
import dev.npn.app.ui.common.SnackbarMessages
import dev.npn.app.ui.common.WideLayoutBreakpoint
import dev.npn.core.app.LibrarySession
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_back
import no_papers_needed.composeapp.generated.resources.common_clear_search
import no_papers_needed.composeapp.generated.resources.common_settings_search
import no_papers_needed.composeapp.generated.resources.common_settings_search_no_results
import no_papers_needed.composeapp.generated.resources.common_settings_title
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
 */
@Composable
fun SettingsScreen(
    env: AppEnvironment,
    session: LibrarySession,
    category: SettingsCategoryId?,
    onOpenCategory: (SettingsCategoryId) -> Unit,
    onBack: () -> Unit,
    onChangeLibrary: () -> Unit,
    onAutoMatchRules: () -> Unit,
) {
    val vm: SettingsViewModel = viewModel { SettingsViewModel(env.settings, session) }
    val state by vm.state.collectAsStateWithLifecycle()
    val aiModels by vm.aiModels.collectAsStateWithLifecycle()
    val aiSelfTest by vm.aiSelfTest.collectAsStateWithLifecycle()
    val reviewMode by vm.reviewMode.collectAsStateWithLifecycle()
    val fields by vm.fields.collectAsStateWithLifecycle()
    val fieldUsage by vm.fieldUsage.collectAsStateWithLifecycle()

    val snackbar = remember { SnackbarHostState() }
    SnackbarMessages(vm.messages, snackbar)

    val context =
        SettingsPaneContext(
            vm = vm,
            state = state,
            aiModels = aiModels,
            aiSelfTest = aiSelfTest,
            reviewMode = reviewMode,
            fields = fields,
            fieldUsage = fieldUsage,
            onChangeLibrary = onChangeLibrary,
            onAutoMatchRules = onAutoMatchRules,
        )

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth > WideLayoutBreakpoint
        // Wide layouts keep the list on screen, so switching panes is a selection, not navigation:
        // it must not stack back-stack entries, and it must not reset the search box by tearing
        // the screen down. Compact layouts drill down for real, which is what makes system back
        // return to the list.
        var picked by rememberSaveable { mutableStateOf<String?>(null) }
        SettingsScaffold(
            wide = wide,
            selected =
                if (wide) {
                    SettingsCategoryId.fromRouteKey(picked) ?: category ?: SettingsCategoryId.LIBRARY
                } else {
                    category
                },
            snackbar = snackbar,
            context = context,
            onSelect = { id -> if (wide) picked = id.routeKey else onOpenCategory(id) },
            onBack = onBack,
        )
    }
}

@Composable
private fun SettingsScaffold(
    wide: Boolean,
    selected: SettingsCategoryId?,
    snackbar: SnackbarHostState,
    context: SettingsPaneContext,
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
                            onSelect = onSelect,
                            modifier = Modifier.width(CategoryListWidth).fillMaxHeight(),
                        )
                        VerticalDivider()
                        SettingsPane(openCategory, context, showTitle = true, Modifier.weight(1f).fillMaxHeight())
                    }
                }

                openCategory != null -> {
                    SettingsPane(openCategory, context, showTitle = false, Modifier.fillMaxSize())
                }

                else -> {
                    SettingsCategoryList(
                        selected = null,
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
    TopAppBar(
        title = { Text(text = title) },
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(Res.string.common_back),
                    )
                }
            }
        },
    )
}

/**
 * The table of contents, filtered by a search box. Search matches a category's title or the label
 * of any setting inside it, so "thumbnail" finds Library without every individual setting needing
 * to be a navigable destination of its own.
 */
@Composable
private fun SettingsCategoryList(
    selected: SettingsCategoryId?,
    onSelect: (SettingsCategoryId) -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val labels = settingsCategoryLabels()
    val results = labels.filter { it.matches(query) }
    Column(modifier) {
        SettingsSearchField(query = query, onQueryChange = { query = it })
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
private fun SettingsSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        label = { Text(text = stringResource(Res.string.common_settings_search)) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(Res.string.common_clear_search))
                }
            }
        },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
    )
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
                    Text(
                        text = matched,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        icon = { Icon(entry.category.icon, contentDescription = null) },
        selected = selected,
        onClick = onClick,
        modifier = Modifier.padding(horizontal = 12.dp),
    )
}

/**
 * Hosts one category's controls. Panes emit a run of [SettingsGroup]s and nothing else; the
 * scrolling, padding, and inter-group spacing all live here so no pane has to reproduce them.
 */
@Composable
private fun SettingsPane(
    category: SettingsCategory?,
    context: SettingsPaneContext,
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
        category.content(context)
    }
}
