package dev.npn.desktop

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
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
import dev.npn.app.ui.common.ImportImage
import dev.npn.app.ui.common.ImportRequest
import dev.npn.core.app.DesktopLog
import dev.npn.core.fs.ExternalSource
import dev.npn.core.settings.PlatformDirs
import dev.npn.core.settings.SettingsRepositoryImpl
import dev.npn.core.util.error
import dev.npn.core.util.info
import dev.npn.core.util.runCatchingCancellable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_app_name
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.skia.Image
import java.awt.AWTEvent
import java.awt.Dimension
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.Toolkit
import java.awt.event.AWTEventListener
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

private const val EXT_PDF = ".pdf"

/**
 * The images a drop accepts, made into one PDF together as a share of photos is on Android: what the
 * desktop PDF engine reads (JPEG, and PNG through ImageIO). WebP, which Android also takes, it cannot.
 */
private val IMAGE_EXTENSIONS = listOf(".png", ".jpg", ".jpeg")
private const val FILE_URI_PREFIX = "file:"
private const val FALLBACK_DROPPED_PDF = "dropped.pdf"
private const val FALLBACK_DROPPED_IMAGE = "dropped.png"
private const val WINDOW_ICON = "icon.png"

/** How long closing the window waits for the library session to shut down before exiting anyway. */
private val SESSION_CLOSE_TIMEOUT = 10.seconds

/** How often the desktop app checks whether it has sat idle long enough to lock. */
private const val LOCK_IDLE_TICK_MILLIS = 5_000L

private fun log(message: String) = DesktopLog.info(message)

/** Held by the running instance; see [SingleInstance]. Per user, so beside every library's data. */
private fun instanceLockFile(): Path = Path.of(PlatformDirs.appDataDir(), "instance.lock")

/**
 * The window icon, read from the classpath rather than Compose resources: jpackage installs this
 * same PNG as the desktop-entry icon, so it stays a plain resource at a fixed path.
 */
@Composable
private fun windowIcon(): Painter =
    remember {
        val bytes =
            checkNotNull(Thread.currentThread().contextClassLoader.getResourceAsStream(WINDOW_ICON)) {
                "Missing resource $WINDOW_ICON"
            }.use { it.readBytes() }
        BitmapPainter(Image.makeFromEncoded(bytes).toComposeImageBitmap())
    }

fun main() {
    installCrashHandler()
    if (SingleInstance.claim(instanceLockFile(), ::log)) runApp() else showAlreadyRunning()
}

/** Put an uncaught exception in `npn.log` before the JVM dies, then let the old handler have it. */
private fun installCrashHandler() {
    val previous = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, failure ->
        DesktopLog.error("Uncaught exception on ${thread.name}", failure)
        previous?.uncaughtException(thread, failure)
    }
}

/** The second instance's whole life: say the app is already open, then exit. */
private fun showAlreadyRunning() =
    application {
        val settings = remember { SettingsRepositoryImpl(DesktopLog) }
        DialogWindow(
            onCloseRequest = ::exitApplication,
            title = stringResource(Res.string.common_app_name),
            icon = windowIcon(),
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
            icon = windowIcon(),
            state = rememberWindowState(width = 1200.dp, height = 800.dp),
        ) {
            DisposableEffect(window) {
                // A sensible minimum size for the AWT window; set once, not on every recomposition.
                window.minimumSize = Dimension(800, 600)
                val monitorMoveWorkaround = MonitorMoveSurfaceWorkaround.install(window)
                onDispose(monitorMoveWorkaround::close)
            }

            // The app lock's desktop half: input keeps it awake, minimizing is its background, and
            // a long enough silence locks it. Android gets the same rules from its activity lifecycle.
            DisposableEffect(window, env.lock) {
                val lock = env.lock
                val focusManager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
                val keys =
                    KeyEventDispatcher { event ->
                        if (event.id == KeyEvent.KEY_PRESSED) lock.onInteraction()
                        false
                    }
                focusManager.addKeyEventDispatcher(keys)
                // A window listener never sees pointer events Compose consumes; the toolkit listener
                // sees every mouse event in the process, whatever component it lands on.
                val toolkit = Toolkit.getDefaultToolkit()
                val mouse = AWTEventListener { event -> if (event is MouseEvent) lock.onInteraction() }
                toolkit.addAWTEventListener(
                    mouse,
                    AWTEvent.MOUSE_EVENT_MASK or AWTEvent.MOUSE_MOTION_EVENT_MASK or AWTEvent.MOUSE_WHEEL_EVENT_MASK,
                )
                val windowEvents =
                    object : WindowAdapter() {
                        override fun windowIconified(event: WindowEvent) = lock.onBackground()

                        override fun windowDeiconified(event: WindowEvent) = lock.onForeground()
                    }
                window.addWindowListener(windowEvents)
                onDispose {
                    focusManager.removeKeyEventDispatcher(keys)
                    toolkit.removeAWTEventListener(mouse)
                    window.removeWindowListener(windowEvents)
                }
            }
            LaunchedEffect(env.lock) {
                while (true) {
                    delay(LOCK_IDLE_TICK_MILLIS)
                    env.lock.checkIdle()
                }
            }

            val dropTarget = remember { importDropTarget() }

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
 * Drop target for PDF files and images. The shared UI imports them into the open library and says
 * what became of them, so a drop is reported like any other import; while files are dragged over the
 * window, it shows the window as the drop target ([DesktopBridge.dragging]).
 */
@OptIn(ExperimentalComposeUiApi::class)
private fun importDropTarget(): DragAndDropTarget =
    object : DragAndDropTarget {
        override fun onEntered(event: DragAndDropEvent) {
            DesktopBridge.dragging.value = true
        }

        override fun onExited(event: DragAndDropEvent) {
            DesktopBridge.dragging.value = false
        }

        override fun onEnded(event: DragAndDropEvent) {
            DesktopBridge.dragging.value = false
        }

        override fun onDrop(event: DragAndDropEvent): Boolean {
            DesktopBridge.dragging.value = false
            val data = event.dragData()
            if (data !is DragData.FilesList) return false
            val request = importRequest(data.readFiles().mapNotNull { toPath(it) }.filter { Files.isRegularFile(it) })
            if (request.pdfs.isEmpty() && request.images.isEmpty()) return false
            DesktopBridge.droppedFiles.trySend(request)
            return true
        }
    }

/** What a drop of [files] imports: each PDF on its own, the images as one PDF; anything else is left out. */
private fun importRequest(
    files: List<Path>,
    io: CoroutineDispatcher = Dispatchers.IO,
): ImportRequest {
    fun Path.name(fallback: String) = fileName?.toString() ?: fallback

    fun Path.hasExtension(extensions: List<String>) = extensions.any { toString().endsWith(it, ignoreCase = true) }
    return ImportRequest(
        pdfs =
            files.filter { it.hasExtension(listOf(EXT_PDF)) }.map {
                ExternalSource(it) to
                    it.name(FALLBACK_DROPPED_PDF)
            },
        images =
            files.filter { it.hasExtension(IMAGE_EXTENSIONS) }.map { path ->
                ImportImage(path.name(FALLBACK_DROPPED_IMAGE)) {
                    // An image that cannot be read counts as failed in the import's message.
                    withContext(io) { runCatchingCancellable { Files.readAllBytes(path) }.getOrNull() }
                }
            },
    )
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
