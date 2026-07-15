package dev.npn.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import dev.npn.app.di.AppEnvironment
import dev.npn.app.ui.detail.DocDetailScreen
import dev.npn.app.ui.library.LibraryScreen
import dev.npn.app.ui.nav.Route
import dev.npn.app.ui.onboarding.OnboardingScreen
import dev.npn.app.ui.review.ReviewQueueScreen
import dev.npn.app.ui.settings.SettingsScreen
import dev.npn.app.ui.tags.TagManagementScreen
import dev.npn.app.ui.theme.NpnTheme
import dev.npn.app.ui.trash.TrashScreen
import io.github.vinceglb.filekit.dialogs.compose.rememberDirectoryPickerLauncher
import kotlinx.coroutines.launch

@Composable
fun App(env: AppEnvironment) {
    NpnTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            AppRoot(env)
        }
    }
}

@Composable
private fun AppRoot(env: AppEnvironment) {
    val session by env.session.collectAsStateWithLifecycle()
    val opening by env.opening.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var restoreError by remember { mutableStateOf<String?>(null) }
    var restoreAttempted by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        restoreError = null
        runCatching { env.openPersistedLibrary() }
            .onFailure { restoreError = it.message ?: "Could not open the saved library." }
        restoreAttempted = true
    }

    val current = session
    when {
        restoreError != null && current == null && !opening -> {
            RestoreErrorScreen(
                message = restoreError!!,
                onRetry = {
                    scope.launch {
                        restoreError = null
                        runCatching { env.openPersistedLibrary() }
                            .onFailure { restoreError = it.message ?: "Could not open the saved library." }
                    }
                },
                onRepickReturned = { location ->
                    scope.launch {
                        restoreError = null
                        runCatching { env.openLibrary(location) }
                            .onFailure { restoreError = it.message ?: "Could not open the folder." }
                    }
                },
            )
        }

        opening || !restoreAttempted -> LoadingScreen()

        current == null -> OnboardingScreen(env)

        else -> MainNav(env = env, session = current)
    }
}

@Composable
private fun MainNav(env: AppEnvironment, session: dev.npn.core.app.LibrarySession) {
    val nav = rememberNavController()

    NavHost(navController = nav, startDestination = Route.Library) {
        composable<Route.Library> {
            LibraryScreen(
                session = session,
                env = env,
                onOpenDoc = { nav.navigate(Route.DocDetail(it)) },
                onReviewQueue = { nav.navigate(Route.ReviewQueue) },
                onTrash = { nav.navigate(Route.Trash) },
                onTags = { nav.navigate(Route.TagManagement) },
                onKanban = { nav.navigate(Route.Kanban) },
                onSettings = { nav.navigate(Route.Settings) },
            )
        }
        composable<Route.DocDetail> { entry ->
            val route = entry.toRoute<Route.DocDetail>()
            DocDetailScreen(
                docId = route.docId,
                session = session,
                onBack = { nav.popBackStack() },
            )
        }
        composable<Route.ReviewQueue> {
            ReviewQueueScreen(
                session = session,
                onOpenDoc = { nav.navigate(Route.DocDetail(it)) },
                onBack = { nav.popBackStack() },
            )
        }
        composable<Route.Trash> {
            TrashScreen(
                session = session,
                onBack = { nav.popBackStack() },
            )
        }
        composable<Route.TagManagement> {
            TagManagementScreen(
                session = session,
                onBack = { nav.popBackStack() },
            )
        }
        composable<Route.Kanban> {
            dev.npn.app.ui.kanban.KanbanScreen(
                session = session,
                onOpenDoc = { nav.navigate(Route.DocDetail(it)) },
                onBack = { nav.popBackStack() },
            )
        }
        composable<Route.Settings> {
            val scope = rememberCoroutineScope()
            val changePicker = rememberDirectoryPickerLauncher { dir ->
                if (dir != null) {
                    scope.launch {
                        val location = dev.npn.app.ui.common.persistDirectoryAccess(dir)
                        runCatching { env.openLibrary(location) }
                    }
                }
            }
            SettingsScreen(
                env = env,
                onBack = { nav.popBackStack() },
                onChangeLibrary = { changePicker.launch() },
            )
        }
    }
}

@Composable
private fun LoadingScreen() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Text("Opening library...", Modifier.padding(top = 16.dp))
        }
    }
}

@Composable
private fun RestoreErrorScreen(
    message: String,
    onRetry: () -> Unit,
    onRepickReturned: (String) -> Unit,
) {
    val picker = rememberDirectoryPickerLauncher { dir ->
        if (dir != null) {
            onRepickReturned(dev.npn.app.ui.common.persistDirectoryAccess(dir))
        }
    }
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(Icons.Filled.Warning, contentDescription = null)
            Text("Could not open your library", style = MaterialTheme.typography.titleMedium)
            Text(
                message,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Button(onClick = onRetry) { Text("Retry") }
            OutlinedButton(onClick = { picker.launch() }) { Text("Pick folder again") }
        }
    }
}
