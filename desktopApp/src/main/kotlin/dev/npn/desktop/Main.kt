package dev.npn.desktop

import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragData
import androidx.compose.ui.draganddrop.dragData
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberDialogState
import androidx.compose.ui.window.rememberWindowState
import dev.npn.app.App
import dev.npn.app.di.createAppEnvironment
import dev.npn.app.ui.common.AlreadyRunningNotice
import dev.npn.app.ui.common.DesktopBridge
import dev.npn.core.app.DesktopLog
import dev.npn.core.app.ImportOutcome
import dev.npn.core.app.PlatformDirs
import dev.npn.core.app.SettingsRepositoryImpl
import dev.npn.core.fs.ExternalSource
import dev.npn.core.util.info
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_app_name
import org.jetbrains.compose.resources.stringResource
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

private const val EXT_PDF = ".pdf"
private const val FILE_URI_PREFIX = "file:"
private const val FALLBACK_DROPPED_PDF = "dropped.pdf"
private const val WINDOW_ICON = "icon.png"

/** How long closing the window waits for the library session to shut down before exiting anyway. */
private val SESSION_CLOSE_TIMEOUT = 10.seconds

private fun log(message: String) = DesktopLog.info(message)

/** Held by the running instance; see [SingleInstance]. Per user, so beside every library's data. */
private fun instanceLockFile(): Path = Path.of(PlatformDirs.dataHome(), "no-papers-needed", "instance.lock")

fun main() {
    if (SingleInstance.claim(instanceLockFile(), ::log)) runApp() else showAlreadyRunning()
}

/** The second instance's whole life: say the app is already open, then exit. */
private fun showAlreadyRunning() =
    application {
        val settings = remember { SettingsRepositoryImpl() }
        DialogWindow(
            onCloseRequest = ::exitApplication,
            title = stringResource(Res.string.common_app_name),
            icon = painterResource(WINDOW_ICON),
            resizable = false,
            state = rememberDialogState(size = DpSize(440.dp, 180.dp)),
        ) {
            AlreadyRunningNotice(settings, onClose = ::exitApplication)
        }
    }

@OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
)
private fun runApp() =
    application {
        // Process-wide environment. The desktop LibrarySession self-starts its WatchService rescan loop
        // and OCR worker in start() (called from openLibrary), so no extra background loop wiring is
        // needed here.
        val env = remember { createAppEnvironment() }
        val scope = rememberCoroutineScope()
        var closing by remember { mutableStateOf(false) }

        val appName = stringResource(Res.string.common_app_name)
        Window(
            onCloseRequest = {
                if (!closing) {
                    closing = true
                    scope.launch {
                        // Closing the session stops OCR (and its tesseract processes) and lets a write
                        // in progress finish; a close that hangs must not keep the app from quitting.
                        withTimeoutOrNull(SESSION_CLOSE_TIMEOUT) { env.closeLibrary() }
                        exitApplication()
                    }
                }
            },
            visible = !closing,
            title = appName,
            // Same PNG jpackage installs as the desktop-entry icon; see .mise/tasks/icons.
            icon = painterResource(WINDOW_ICON),
            state = rememberWindowState(width = 1200.dp, height = 800.dp),
        ) {
            // Enforce a sensible minimum window size (jpackage/AWT window).
            window.minimumSize = java.awt.Dimension(800, 600)

            DisposableEffect(window) {
                val monitorMoveWorkaround = MonitorMoveSurfaceWorkaround.install(window)
                onDispose(monitorMoveWorkaround::close)
            }

            // Mirror the shared UI's window-title flow into the native window title.
            LaunchedEffect(Unit) {
                DesktopBridge.windowTitle.collect { window.title = it ?: appName }
            }

            val dropTarget = remember { pdfDropTarget(env, scope) }

            Box(
                Modifier.fillMaxSize().dragAndDropTarget(
                    shouldStartDragAndDrop = { event -> event.dragData() is DragData.FilesList },
                    target = dropTarget,
                ),
            ) {
                // NoDocumentScanner is the default for LocalDocumentScanner, so no provider needed on
                // desktop (v1 has no camera capture).
                App(env)
            }
        }
    }

/** Drop target that imports dropped PDF files through the [env]'s session on [scope]. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
private fun pdfDropTarget(
    env: dev.npn.app.di.AppEnvironment,
    scope: kotlinx.coroutines.CoroutineScope,
): DragAndDropTarget =
    object : DragAndDropTarget {
        override fun onDrop(event: DragAndDropEvent): Boolean {
            val data = event.dragData()
            if (data !is DragData.FilesList) return false
            val paths =
                data
                    .readFiles()
                    .mapNotNull { toPath(it) }
                    .filter { it.toString().endsWith(EXT_PDF, ignoreCase = true) }
            if (paths.isEmpty()) return false
            scope.launch { importDroppedFiles(env, paths) }
            return true
        }
    }

private suspend fun importDroppedFiles(
    env: dev.npn.app.di.AppEnvironment,
    paths: List<Path>,
) {
    val session =
        env.session.value ?: run {
            log("drag-drop import ignored: no library configured")
            return
        }
    withContext(Dispatchers.IO) {
        for (path in paths) {
            if (!Files.isRegularFile(path)) continue
            val name = path.fileName?.toString() ?: FALLBACK_DROPPED_PDF
            log(importLogLine(name, session.ingest.importPdf(ExternalSource(path), name)))
        }
    }
}

private fun importLogLine(
    name: String,
    outcome: ImportOutcome,
): String =
    when (outcome) {
        is ImportOutcome.Imported -> "imported $name -> ${outcome.relPath}"
        is ImportOutcome.Duplicate -> "duplicate $name (already ${outcome.existingPath})"
        is ImportOutcome.Failed -> "failed $name: ${outcome.reason}"
    }

/** Convert a dropped file reference (a `file:` URI or a plain path) to a [Path]. */
private fun toPath(fileRef: String): Path? =
    runCatching {
        if (fileRef.startsWith(FILE_URI_PREFIX)) {
            Path.of(URI(fileRef))
        } else {
            Path.of(fileRef)
        }
    }.getOrNull()
