package dev.npn.desktop

import java.awt.Dimension
import java.awt.EventQueue
import java.awt.Rectangle
import java.awt.Window
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import javax.swing.Timer

/**
 * Refreshes the AWT/Skiko surface after crossing displays on Linux Wayland.
 *
 * Compose Multiplatform issue CMP-9528 can leave the client surface shifted beneath the native
 * decorations until the window is resized. The one-frame delay lets AWT finish applying the new
 * graphics configuration before the corrective resize.
 */
internal class MonitorMoveSurfaceWorkaround private constructor(
    private val window: Window,
) : AutoCloseable {
    private var screen = window.screenKey()
    private val refreshTimer =
        Timer(REFRESH_DELAY_MILLIS) {
            refreshSurface()
        }.apply { isRepeats = false }
    private val moveListener =
        object : ComponentAdapter() {
            override fun componentMoved(event: ComponentEvent) {
                val nextScreen = window.screenKey() ?: return
                val previousScreen = screen
                screen = nextScreen
                if (previousScreen != null && nextScreen != previousScreen) refreshTimer.restart()
            }
        }

    init {
        window.addComponentListener(moveListener)
    }

    override fun close() {
        refreshTimer.stop()
        window.removeComponentListener(moveListener)
    }

    private fun refreshSurface() {
        if (!window.isDisplayable) return
        val original = Dimension(window.size)
        val minimumWidth = window.minimumSize.width
        val nudgedWidth = if (original.width > minimumWidth) original.width - 1 else original.width + 1
        window.setSize(nudgedWidth, original.height)
        EventQueue.invokeLater {
            if (!window.isDisplayable) return@invokeLater
            window.size = original
            window.validate()
            window.repaint()
        }
    }

    private data class ScreenKey(
        val bounds: Rectangle,
        val scaleX: Double,
        val scaleY: Double,
    )

    private fun Window.screenKey(): ScreenKey? {
        val config = graphicsConfiguration ?: return null
        return ScreenKey(
            bounds = Rectangle(config.bounds),
            scaleX = config.defaultTransform.scaleX,
            scaleY = config.defaultTransform.scaleY,
        )
    }

    companion object {
        private const val REFRESH_DELAY_MILLIS = 16

        fun install(window: Window): AutoCloseable =
            if (isLinuxWayland()) MonitorMoveSurfaceWorkaround(window) else AutoCloseable {}

        private fun isLinuxWayland(): Boolean =
            System.getProperty("os.name").startsWith("Linux", ignoreCase = true) &&
                (
                    System.getenv("XDG_SESSION_TYPE").equals("wayland", ignoreCase = true) ||
                        !System.getenv("WAYLAND_DISPLAY").isNullOrBlank()
                )
    }
}
