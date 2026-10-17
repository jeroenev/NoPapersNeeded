package dev.npn.desktop

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
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
import dev.npn.core.fs.ExternalSource
import dev.npn.core.settings.PlatformDirs
import dev.npn.core.settings.SettingsRepositoryImpl
import dev.npn.core.util.info
import kotlinx.coroutines.launch
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_app_name
import org.jetbrains.compose.resources.stringResource
import java.awt.Dimension
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
private fun instanceLockFile(): Path = Path.of(PlatformDirs.appDataDir(), "instance.lock")

fun main() {
    if (SingleInstance.claim(instanceLockFile(), ::log)) runApp() else showAlreadyRunning()
}

/** The second instance's whole life: say the app is already open, then exit. */
private fun showAlreadyRunning() =
    application {
        val settings = remember { SettingsRepositoryImpl(DesktopLog) }
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

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
private fun runApp() =
    application {
        // Process-wide environment. The desktop LibrarySession self-starts its WatchService rescan loop
        // and OCR worker in start() (called from openLibrary), so no extra background loop wiring is
        // needed here.
        val env = remember { createAppEnvironment() }
        val scope = rememberCoroutineScope()
        var closing by remember { mutableStateOf(false) }

        // The shared UI publishes the title for the screen it shows, in the app's language; the
        // window is its one writer. The app's name stands in only until the first screen has.
        val title by DesktopBridge.windowTitle.collectAsState()
        val appName = stringResource(Res.string.common_app_name)
        Window(
            onCloseRequest = {
                if (!closing) {
                    closing = true
                    // Closing the session stops OCR (and its tesseract processes) and lets a write in
                    // progress finish.
                    scope.launch {
                        closeThenExit(
                            close = env::closeLibrary,
                            timeout = SESSION_CLOSE_TIMEOUT,
                            log = DesktopLog,
                            exit = ::exitApplication,
                        )
                    }
                }
            },
            visible = !closing,
            title = title ?: appName,
            // Same PNG jpackage installs as the desktop-entry icon; see .mise/tasks/icons.
            icon = painterResource(WINDOW_ICON),
            state = rememberWindowState(width = 1200.dp, height = 800.dp),
        ) {
            DisposableEffect(window) {
                // A sensible minimum size for the AWT window; set once, not on every recomposition.
                window.minimumSize = Dimension(800, 600)
                val monitorMoveWorkaround = MonitorMoveSurfaceWorkaround.install(window)
                onDispose(monitorMoveWorkaround::close)
            }

            val dropTarget = remember { pdfDropTarget() }

            Box(
                Modifier.fillMaxSize().dragAndDropTarget(
                    shouldStartDragAndDrop = { event -> event.dragData() is DragData.FilesList },
                    target = dropTarget,
                ),
            ) {
                // No LocalDocumentScanner on desktop: it has no camera capture.
                App(env)
            }
        }
    }

/**
 * Drop target for PDF files. The shared UI imports them into the open library and says what became
 * of them, so a drop is reported like any other import.
 */
@OptIn(ExperimentalComposeUiApi::class)
private fun pdfDropTarget(): DragAndDropTarget =
    object : DragAndDropTarget {
        override fun onDrop(event: DragAndDropEvent): Boolean {
            val data = event.dragData()
            if (data !is DragData.FilesList) return false
            val files =
                data
                    .readFiles()
                    .mapNotNull { toPath(it) }
                    .filter { it.toString().endsWith(EXT_PDF, ignoreCase = true) && Files.isRegularFile(it) }
                    .map { ExternalSource(it) to (it.fileName?.toString() ?: FALLBACK_DROPPED_PDF) }
            if (files.isEmpty()) return false
            DesktopBridge.droppedFiles.trySend(files)
            return true
        }
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
