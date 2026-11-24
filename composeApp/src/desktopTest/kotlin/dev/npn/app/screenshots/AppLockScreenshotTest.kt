@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.npn.app.screenshots

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import dev.npn.app.ui.lock.LockActions
import dev.npn.app.ui.lock.LockScreen
import dev.npn.app.ui.lock.LockUiState
import dev.npn.app.ui.theme.NpnTheme
import dev.npn.core.security.AppLockState
import dev.npn.core.security.LockTimeout
import org.jetbrains.skia.EncodedImageFormat
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test

/** Renders the app-lock screen for the PR/design asset; opt in with NPN_APP_LOCK_SHOT=<file.png>. */
class AppLockScreenshotTest {
    @Test
    fun captureLockScreen() {
        val out = System.getenv("NPN_APP_LOCK_SHOT") ?: return
        val state =
            LockUiState(
                lock = AppLockState(enabled = true, locked = true, timeout = LockTimeout.FIVE_MINUTES),
            )
        val actions =
            object : LockActions {
                override fun unlock(secret: String) = Unit

                override fun unlockWithDevice() = Unit

                override fun retryRemainingMillis(): Long = 0L
            }
        val scene =
            ImageComposeScene(width = 1000, height = 820, density = Density(2f)) {
                NpnTheme(darkTheme = false) { LockScreen(state = state, actions = actions) }
            }
        try {
            val image = scene.render()
            val data = image.encodeToData(EncodedImageFormat.PNG) ?: error("PNG encoding failed")
            Files.createDirectories(Path.of(out).parent)
            Files.write(Path.of(out), data.bytes)
        } finally {
            scene.close()
        }
    }
}
