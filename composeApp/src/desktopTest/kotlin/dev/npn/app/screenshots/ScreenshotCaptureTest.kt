@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)

package dev.npn.app.screenshots

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.scene.ComposeScenePointer
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import dev.npn.app.App
import dev.npn.app.FakeSettingsRepository
import dev.npn.app.di.DefaultAppEnvironment
import dev.npn.app.ui.click
import dev.npn.app.ui.contentDescription
import dev.npn.app.ui.hostedScene
import dev.npn.app.ui.nodes
import dev.npn.app.ui.press
import dev.npn.app.ui.texts
import dev.npn.app.ui.type
import dev.npn.core.app.LibrarySort
import dev.npn.core.app.SortField
import dev.npn.core.app.ViewType
import dev.npn.core.app.jvmSessionDeps
import dev.npn.core.model.FieldKeys
import dev.npn.core.settings.AppDirs
import dev.npn.core.settings.SessionSettings
import dev.npn.core.util.NpnLog
import kotlinx.coroutines.cancel
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assume.assumeTrue
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.fail

/**
 * Captures the README and Play Store screenshots from the app's own UI: the real composables, wired
 * to a throwaway copy of `design/demo-library/library`, rendered offscreen at exactly the pixel size
 * each file needs. Opt-in, since it writes files:
 *
 * ```
 * NPN_SCREENSHOTS=/tmp/npn-shots ./gradlew :composeApp:desktopTest \
 *     --tests 'dev.npn.app.screenshots.ScreenshotCaptureTest' --rerun
 * ```
 *
 * The copy keeps the fixture's journal untouched: opening a library gives it this device's journal
 * device, and the index goes to the throwaway [AppDirs].
 */
class ScreenshotCaptureTest {
    private val out: Path? = System.getenv("NPN_SCREENSHOTS")?.let { Path.of(it) }

    private val work: Path = Files.createTempDirectory("npn-screenshots")
    private lateinit var env: DefaultAppEnvironment
    private lateinit var scene: ImageComposeScene

    @AfterTest
    fun tearDown() {
        if (::scene.isInitialized && !closed) onEdt { scene.close() }
        if (::env.isInitialized) env.appScope.cancel()
        work.toFile().deleteRecursively()
    }

    @Test
    fun capture() {
        assumeTrue("Set NPN_SCREENSHOTS=<dir> to capture the screenshots", out != null)
        val library = work.resolve("library")
        copyTree(fixture(), library)
        val settings = demoSettings(library)
        env = environment(settings)

        desktop("library.png") { captureLibrary() }
        desktop("search.png", cropHeight = 1040) { captureSearch() }
        desktop("preview.png") { capturePreview() }
        desktop("details.png") { captureDetails() }
        desktop("filters.png") { captureFilters() }
        phone("phone-library.png") { captureLibrary() }
        phone("phone-details.png") { capturePhoneDetails() }
        phone("phone-tags.png") { capturePhoneTags() }
    }

    /** The library as it opens: the grid, sorted by document date, with the demo metadata. */
    private fun captureLibrary() = settle()

    private fun captureSearch() {
        // Ctrl+F is the app's own way to put the caret in the search, which draws its focus ring.
        onEdt { scene.press(Key.F, ctrl = true) }
        onEdt { scene.type("October") }
        await("the search results") { scene.texts().any { it.startsWith("9 documents") } }
        // Escape closes the suggestions; the query stays, as the field keeps the library's Escape.
        onEdt { scene.press(Key.Escape) }
        settle()
    }

    /** A document opened in the side pane, its page rendered. */
    private fun capturePreview() {
        openDocument()
        capturePage()
    }

    /**
     * The expanded document view, with the panes split as the README's details shot has them: the
     * form wider than the page, so the title, fields and tags fit above the fold.
     */
    private fun captureDetails() {
        openDocument()
        clickDescription("Expand document workspace")
        await("the expanded document's panes") {
            scene.nodes().any { it.config.getOrNull(SemanticsActions.SetProgress) != null }
        }
        onEdt {
            val divider = scene.nodes().first { it.config.getOrNull(SemanticsActions.SetProgress) != null }
            divider.config[SemanticsActions.SetProgress].action?.invoke(DETAILS_PREVIEW_FRACTION)
        }
        settle()
        capturePage()
    }

    /**
     * The filter panel with two tags included, matching any of them. The panel's edits are a draft
     * until "Apply filters", which closes it: the shot shows the panel reopened over what it applied.
     */
    private fun captureFilters() {
        clickDescription("Filters")
        await("the filter panel") { scene.texts().contains("Include tags") }
        clickPanelText("Match any")
        clickPanelText("Bills.Energy")
        clickPanelText("Bills.Water")
        clickPanelText("Apply filters")
        await("the filtered results") { scene.texts().any { it.startsWith("3 documents") } }
        clickDescription("Filters")
        await("the filter panel again") { scene.texts().contains("Include tags") }
        settle()
    }

    /** The same library on a phone, opened from the grid so the detail screen counts all of them. */
    private fun capturePhoneDetails() {
        scrollUntil("Account statement")
        clickText("Account statement")
        await("the document's tabs") { scene.texts().contains("Details") }
        clickText("Details")
        settle()
    }

    private fun capturePhoneTags() {
        clickText("Tags")
        await("the tags screen") { scene.texts().any { it.contains("namespace only") } }
        settle()
    }

    private fun openDocument() {
        await("the Account statement card") { scene.texts().any { it.contains("Account statement") } }
        clickText("Account statement")
        await("the document's page") { scene.nodes().any { it.contentDescription() == "Page 1" } }
    }

    /** Wait for the page's bitmap, decoded off the UI thread, to be on screen, then for it to settle. */
    private fun capturePage() {
        await("the page to settle") { scene.nodes().any { it.contentDescription() == "Page 1" } }
        settle()
    }

    private fun desktop(
        name: String,
        cropHeight: Int? = null,
        drive: () -> Unit,
    ) = shot(name, DESKTOP_WIDTH, DESKTOP_HEIGHT, cropHeight, drive)

    private fun phone(
        name: String,
        drive: () -> Unit,
    ) = shot(name, PHONE_WIDTH, PHONE_HEIGHT, null, drive)

    private fun shot(
        name: String,
        width: Int,
        height: Int,
        cropHeight: Int?,
        drive: () -> Unit,
    ) {
        openScene(width, height)
        try {
            drive()
            capture(name, cropHeight)
        } finally {
            onEdt { scene.close() }
            closed = true
        }
    }

    private var closed = false

    /** The scene's clock, advanced a frame per render: animations only move when it does. */
    private var clock = 0L

    /** A fresh scene of the app over the open library: every shot starts from the same state. */
    private fun openScene(
        width: Int,
        height: Int,
    ) {
        if (::scene.isInitialized && !closed) onEdt { scene.close() }
        onEdt { scene = hostedScene(width, height, density = Density(2f)) { App(env) } }
        closed = false
        await("the library") { scene.texts().any { it.contains("documents · Document date") } }
        await("its documents") { scene.texts().any { it.contains("Dental invoice") } }
        settle()
    }

    private fun clickText(text: String) =
        onEdt {
            scene.click { node -> node.texts().any { it.contains(text) } }
            scene.settleFrames()
        }

    /**
     * Click [text] in the open filter panel. The library behind the panel shows the same tag names,
     * and comes first in the semantics tree, so the search starts at the panel's first heading.
     */
    private fun clickPanelText(text: String) =
        onEdt {
            val nodes = scene.nodes()
            val panel = nodes.indexOfFirst { node -> node.texts().any { it == "Include tags" } }
            check(panel >= 0) { "The filter panel is not open" }
            val chip = nodes.drop(panel).first { node -> node.texts().any { it.contains(text) } }
            generateSequence(chip) { it.parent }
                .first { it.config.getOrNull(SemanticsActions.OnClick) != null }
                .config[SemanticsActions.OnClick]
                .action
                ?.invoke()
            scene.settleFrames()
        }

    private fun clickDescription(description: String) =
        onEdt {
            scene.click {
                it.config
                    .getOrNull(SemanticsProperties.ContentDescription)
                    ?.any { value -> value.contains(description) } == true
            }
            scene.settleFrames()
        }

    /** Bring [text] into the composed part of the grid by swiping up, as a thumb would. */
    private fun scrollUntil(text: String) {
        repeat(12) {
            if (onEdt { scene.texts().any { it.contains(text) } }) return
            onEdt {
                val from = Offset(PHONE_WIDTH / 2f, PHONE_HEIGHT * 0.8f)
                val by = Offset(0f, -PHONE_HEIGHT * 0.5f)

                fun touch(
                    type: PointerEventType,
                    at: Offset,
                    pressed: Boolean,
                ) = scene.sendPointerEvent(
                    type,
                    listOf(ComposeScenePointer(PointerId(0), at, pressed, PointerType.Touch)),
                )
                touch(PointerEventType.Press, from, pressed = true)
                repeat(SWIPE_STEPS) { step ->
                    touch(PointerEventType.Move, from + by * ((step + 1f) / SWIPE_STEPS), pressed = true)
                    scene.frame()
                }
                touch(PointerEventType.Release, from + by, pressed = false)
                scene.settleFrames()
            }
            Thread.sleep(100)
        }
        fail("Swiping never brought $text on screen")
    }

    /** Render a few frames, so animations and the caret are past where they start. */
    private fun settle() = onEdt { scene.settleFrames() }

    private fun ImageComposeScene.settleFrames() =
        repeat(FRAMES) {
            clock += FRAME_NANOS
            render(clock)
        }

    private fun ImageComposeScene.frame() = render(clock.also { clock += FRAME_NANOS })

    /** Every text the scene shows, dialogs and popups included. */
    private fun ImageComposeScene.texts(): List<String> = nodes().flatMap { it.texts() }

    /** Render on the UI thread (where the scene composes) until [predicate] holds there. */
    private fun await(
        what: String,
        timeoutMs: Long = 60_000,
        predicate: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (onEdt {
                    scene.frame()
                    predicate()
                }
            ) {
                return
            }
            Thread.sleep(100)
        }
        val shown =
            onEdt {
                scene.frame()
                scene.texts()
            }
        fail("Timed out after ${timeoutMs}ms waiting for $what. The scene shows: $shown")
    }

    private fun capture(
        name: String,
        cropHeight: Int? = null,
    ) {
        onEdt {
            val image = scene.render(clock.also { clock += FRAME_NANOS })
            val data = image.encodeToData(EncodedImageFormat.PNG) ?: error("PNG encoding failed")
            val bytes = if (cropHeight == null) data.bytes else crop(data.bytes, cropHeight)
            val file = out!!.resolve(name)
            Files.createDirectories(file.parent)
            Files.write(file, bytes)
        }
    }

    /** [png] cut to its top [height] pixels, as the README's search shot is. */
    private fun crop(
        png: ByteArray,
        height: Int,
    ): ByteArray {
        val source: BufferedImage = ImageIO.read(ByteArrayInputStream(png))
        val cropped = source.getSubimage(0, 0, source.width, height)
        val result = BufferedImage(cropped.width, cropped.height, BufferedImage.TYPE_INT_ARGB)
        result.createGraphics().apply {
            drawImage(cropped, 0, 0, null)
            dispose()
        }
        return ByteArrayOutputStream().also { ImageIO.write(result, "png", it) }.toByteArray()
    }

    private fun <T> onEdt(block: () -> T): T {
        var result: Result<T>? = null
        SwingUtilities.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun demoSettings(library: Path): FakeSettingsRepository =
        FakeSettingsRepository(ViewType.GRID).apply {
            updateSettings {
                it.copy(
                    libraryLocation = library.toString(),
                    librarySort = LibrarySort(SortField.Field(FieldKeys.DOC_DATE), ascending = false),
                    // The README shots show the library, not the app's own onboarding furniture.
                    gettingStartedDismissed = true,
                    searchTipShown = true,
                    updateCheckEnabled = false,
                )
            }
        }

    private fun environment(settings: FakeSettingsRepository): DefaultAppEnvironment {
        val dirs =
            AppDirs(
                dbDir = work.resolve("db").toString(),
                thumbsDir = work.resolve("thumbs").toString(),
            )
        return DefaultAppEnvironment(
            settings = settings,
            watchedFolder = { null },
            log = NpnLog.None,
            factory = { location, scope ->
                jvmSessionDeps(
                    libraryRoot = Path.of(location),
                    dirs = dirs,
                    scope = scope,
                    settings = SessionSettings.from(settings),
                    installationId = settings.settings.value.installationId,
                    log = NpnLog.None,
                )
            },
        )
    }

    /** The demo library fixture, from wherever Gradle started this test JVM. */
    private fun fixture(): Path {
        var dir: Path? = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        while (dir != null) {
            val candidate = dir.resolve("design/demo-library/library")
            if (Files.isDirectory(candidate)) return candidate
            dir = dir.parent
        }
        error("design/demo-library/library not found above ${System.getProperty("user.dir")}")
    }

    private fun copyTree(
        from: Path,
        to: Path,
    ) {
        Files.walk(from).use { paths ->
            paths.forEach { source ->
                val target = to.resolve(from.relativize(source).toString())
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target)
                } else {
                    Files.copy(source, target)
                }
            }
        }
    }

    private companion object {
        const val DESKTOP_WIDTH = 2560
        const val DESKTOP_HEIGHT = 1600
        const val PHONE_WIDTH = 824
        const val PHONE_HEIGHT = 1800

        /** The page's share of the expanded view; the form gets the rest. */
        const val DETAILS_PREVIEW_FRACTION = 0.3452f

        const val FRAMES = 60
        const val FRAME_NANOS = 16_000_000L
        const val SWIPE_STEPS = 20
    }
}
