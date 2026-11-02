package dev.npn.app.ui.onboarding

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import dev.npn.app.FakeLibrarySession
import dev.npn.app.FakeSettingsRepository
import dev.npn.app.platform.OcrModelSource
import dev.npn.app.platform.OcrModels
import dev.npn.app.ui.click
import dev.npn.app.ui.closing
import dev.npn.app.ui.common.TestTags
import dev.npn.app.ui.has
import dev.npn.app.ui.hostedScene
import dev.npn.app.ui.onUiThread
import dev.npn.app.ui.texts
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * First run once its library is open, on desktop: the document languages, then the first document
 * with its drop zone, which takes the window's drops while it shows; skipping ends on the library.
 */
class FirstRunScreenTest {
    private val finished = mutableListOf<FirstRunExit>()
    private var dropTarget: Any? = null

    @Test
    fun aNewLibraryAsksForItsLanguagesThenItsFirstDocument() =
        onUiThread {
            scene().closing {
                settle()
                has(text("Which languages are your documents in?")) shouldBe true

                click(tagged(TestTags.FIRST_RUN_CONTINUE))
                settle()
                has(text("Add your first document")) shouldBe true
                has(text("Or drop PDF files anywhere on this window")) shouldBe true
                (dropTarget != null) shouldBe true

                click(tagged(TestTags.FIRST_RUN_SKIP))
                settle()
                finished shouldContainExactly listOf(FirstRunExit.Library)
            }
        }

    private fun scene(): ImageComposeScene =
        hostedScene(WIDTH, HEIGHT) {
            var step by remember { mutableIntStateOf(0) }
            FirstRunScreen(
                session = FakeLibrarySession(),
                settings = FakeSettingsRepository(),
                readOcrModels = { OcrModels(setOf("eng"), OcrModelSource.APT) },
                path = FirstRunPath.NEW,
                step = step,
                onStep = { step = it },
                onFinish = { finished += it },
                snackbar = remember { SnackbarHostState() },
                onDropTarget = { dropTarget = it },
            )
        }

    private fun ImageComposeScene.settle() = repeat(FRAMES) { render() }

    private fun tagged(tag: String): (SemanticsNode) -> Boolean =
        { it.config.getOrNull(SemanticsProperties.TestTag) == tag }

    private fun text(value: String): (SemanticsNode) -> Boolean = { value in it.texts() }

    private companion object {
        const val WIDTH = 800
        const val HEIGHT = 1600
        const val FRAMES = 5
    }
}
