package dev.npn.baselineprofile

import android.os.Environment
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Records the code a user hits on the way in: cold start, the library grid, scrolling it, and
 * opening a document. Run with `mise baseline-profile` on a device running Android 13 or newer.
 *
 * The benchmark build is its own package (applicationId suffix `.benchmark`, see
 * androidApp/build.gradle.kts), so it proposes its own library folder,
 * `Documents/NoPapersNeeded-benchmark`, and never touches the real library on the device.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    private val packageName =
        InstrumentationRegistry.getArguments().getString("targetAppId")
            ?: error("targetAppId not passed as an instrumentation runner argument")

    /** Cold start up to a loaded library: also the startup profile, which orders the DEX. */
    @Test
    fun startup() =
        rule.collect(packageName = packageName, includeInStartupProfile = true) {
            prepareDevice(packageName)
            pressHome()
            startActivityAndWait()
            passOnboarding()
            awaitLibrary()
        }

    /** What follows: scrolling the grid and opening a document. */
    @Test
    fun browse() =
        rule.collect(packageName = packageName) {
            prepareDevice(packageName)
            pressHome()
            startActivityAndWait()
            passOnboarding()
            browseLibrary()
        }

    /** The demo documents go to the folder the benchmark build proposes. */
    private fun MacrobenchmarkScope.prepareDevice(packageName: String) {
        val suffix = packageName.substringAfterLast('.')
        val documents = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        val library = File(documents, "NoPapersNeeded-$suffix")
        device.executeShellCommand("mkdir -p ${library.absolutePath}")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val assets = instrumentation.context.assets
        // The test APK can write its own external cache without a permission; the shell copies on.
        val staging = File(instrumentation.context.externalCacheDir, "library").apply { mkdirs() }
        for (name in assets.list(ASSET_DIR).orEmpty()) {
            val staged = File(staging, name)
            assets.open("$ASSET_DIR/$name").use { input -> staged.outputStream().use(input::copyTo) }
            device.executeShellCommand("cp -n ${staged.absolutePath} ${library.absolutePath}/$name")
        }
    }

    /**
     * First launch only: get started, grant all-files access, accept the proposed library folder
     * and go past the first document (the demo library has its own). The grant goes through the
     * system settings switch, as a user would flip it, because some OEM builds (OxygenOS) refuse
     * `appops set` from the shell.
     */
    private fun MacrobenchmarkScope.passOnboarding() {
        device.wait(Until.findObject(By.res(GET_STARTED)), SHORT_WAIT_MS)?.click()
        device.wait(Until.findObject(By.res(STORAGE_ACCESS_GRANT)), SHORT_WAIT_MS)?.let { allow ->
            allow.click()
            device.wait(Until.findObject(By.checkable(true)), LONG_WAIT_MS)?.click()
                ?: error("No all-files access switch")
            device.waitForIdle()
            device.pressBack()
        }
        device.wait(Until.findObject(By.res(USE_RECOMMENDED_FOLDER)), SHORT_WAIT_MS)?.let { use ->
            use.click()
            device.wait(Until.findObject(By.res(FIRST_RUN_SKIP)), LONG_WAIT_MS)?.click()
        }
    }

    private fun MacrobenchmarkScope.awaitLibrary() =
        device.wait(Until.findObject(By.textContains(PDF)), LONG_WAIT_MS) ?: error("Library did not load")

    private fun MacrobenchmarkScope.browseLibrary() {
        val document = awaitLibrary()
        device.findObject(By.scrollable(true))?.let { grid ->
            grid.setGestureMargin(device.displayWidth / GESTURE_MARGIN_DIVISOR)
            grid.fling(Direction.DOWN)
            device.waitForIdle()
            grid.fling(Direction.UP)
            device.waitForIdle()
        }
        (device.findObject(By.textContains(PDF)) ?: document).click()
        device.wait(Until.hasObject(By.res(PREVIEW_TAB)), LONG_WAIT_MS)
        device.waitForIdle()
        device.pressBack()
        device.wait(Until.hasObject(By.textContains(PDF)), LONG_WAIT_MS)
    }

    private companion object {
        const val ASSET_DIR = "library"
        const val PDF = ".pdf"
        const val SHORT_WAIT_MS = 5_000L
        const val LONG_WAIT_MS = 30_000L
        const val GESTURE_MARGIN_DIVISOR = 5

        // Test tags from composeApp's TestTags, which the app exposes as resource ids, so the
        // generator works in any device language.
        const val GET_STARTED = "get_started"
        const val STORAGE_ACCESS_GRANT = "storage_access_grant"
        const val USE_RECOMMENDED_FOLDER = "use_recommended_folder"
        const val FIRST_RUN_SKIP = "first_run_skip"
        const val PREVIEW_TAB = "panel_tab_preview"
    }
}
