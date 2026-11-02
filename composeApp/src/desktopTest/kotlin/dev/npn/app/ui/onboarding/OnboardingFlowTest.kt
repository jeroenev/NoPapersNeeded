package dev.npn.app.ui.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import dev.npn.app.platform.defaultLibraryLocation
import dev.npn.app.ui.click
import dev.npn.app.ui.closing
import dev.npn.app.ui.common.TestTags
import dev.npn.app.ui.has
import dev.npn.app.ui.hostedScene
import dev.npn.app.ui.onUiThread
import dev.npn.app.ui.settings.CloudSetupActions
import dev.npn.app.ui.settings.CloudSetupState
import dev.npn.app.ui.texts
import dev.npn.core.sync.RcloneProvider
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * Onboarding before a library opens: the welcome's two ways in, the one-tap folder on this device,
 * the step kept through a restored process, and a cloud drive offered only by the providers set up
 * in the user's own terms.
 */
class OnboardingFlowTest {
    private val begun = mutableListOf<FirstRunPath>()
    private val recommended = mutableListOf<String>()

    @Test
    fun getStartedThenOneTapKeepsTheLibraryInTheProposedFolder() =
        onUiThread {
            scene().closing {
                settle()
                click(tagged(TestTags.GET_STARTED))
                settle()
                click(tagged(TestTags.USE_RECOMMENDED_FOLDER))
                settle()

                recommended shouldContainExactly listOf(checkNotNull(defaultLibraryLocation()))
                begun shouldContainExactly listOf(FirstRunPath.NEW)
            }
        }

    /** The step the user is on comes back with a restored process, not the welcome again. */
    @Test
    fun theStepSurvivesARestoredProcess() =
        onUiThread {
            var registry = SaveableStateRegistry(null) { true }
            scene(registry).closing {
                settle()
                click(tagged(TestTags.ALREADY_USE))
                settle()
                has(text("Connect to your library")) shouldBe true
                val saved = registry.performSave()
                registry = SaveableStateRegistry(saved) { true }
            }
            scene(registry).closing {
                settle()
                has(text("Connect to your library")) shouldBe true
                has(tagged(TestTags.GET_STARTED)) shouldBe false
            }
        }

    /** First run's cloud drives are the sign-in ones and WebDAV; the rest wait for Settings. */
    @Test
    fun theCloudDriveChoiceOffersOnlyPlainProviders() =
        onUiThread {
            val providers =
                listOf(
                    RcloneProvider("drive", "Google Drive", "", emptyList(), browserSignIn = true),
                    RcloneProvider("mega", "MEGA", "", emptyList()),
                    RcloneProvider("webdav", "WebDAV / Nextcloud", "", emptyList()),
                    RcloneProvider("s3", "Amazon S3 / compatible", "", emptyList(), advanced = true),
                )
            scene(cloudState = CloudSetupState.Providers(providers)).closing {
                settle()
                has(text("Sign in with Google Drive")) shouldBe true
                has(text("WebDAV / Nextcloud")) shouldBe true
                has(text("MEGA")) shouldBe false
                has(text("Amazon S3 / compatible")) shouldBe false
                has(text("More providers are in Settings under Cloud sync, once your library is open.")) shouldBe true
            }
        }

    private fun scene(
        registry: SaveableStateRegistry = SaveableStateRegistry(null) { true },
        cloudState: CloudSetupState? = null,
    ): ImageComposeScene =
        hostedScene(WIDTH, HEIGHT, LocalSaveableStateRegistry provides registry) { Onboarding(cloudState) }

    @Composable
    private fun Onboarding(cloudState: CloudSetupState?) {
        OnboardingScreen(
            failure = null,
            useSaf = false,
            onUseSafChange = {},
            onOpen = {},
            onCreate = { _, _ -> },
            onUseRecommendedFolder = { recommended += it },
            cloudState = cloudState,
            cloudSetup = NoCloudSetup,
            onBegin = { begun += it },
        )
    }

    private fun ImageComposeScene.settle() = repeat(FRAMES) { render() }

    private fun tagged(tag: String): (SemanticsNode) -> Boolean =
        { it.config.getOrNull(SemanticsProperties.TestTag) == tag }

    private fun text(value: String): (SemanticsNode) -> Boolean = { value in it.texts() }

    /** A wizard that goes nowhere: the screen only shows the step it is handed. */
    private object NoCloudSetup : CloudSetupActions {
        override fun start() = Unit

        override fun chooseProvider(provider: RcloneProvider) = Unit

        override fun setValue(
            option: String,
            value: String,
        ) = Unit

        override fun submit() = Unit

        override fun cancel() = Unit

        override fun openFolder(name: String) = Unit

        override fun openFolderUp() = Unit

        override fun createFolder(name: String) = Unit

        override fun useFolder() = Unit

        override fun changeFolder() = Unit

        override fun disconnect() = Unit
    }

    private companion object {
        const val WIDTH = 800
        const val HEIGHT = 1600
        const val FRAMES = 3
    }
}
