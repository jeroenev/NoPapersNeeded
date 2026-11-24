package dev.npn.app.platform

import android.app.Activity
import java.lang.ref.WeakReference

/** The foreground activity is held weakly and cleared when it leaves the foreground. */
object PrintActivity {
    private var current = WeakReference<Activity>(null)

    fun resumed(activity: Activity) {
        current = WeakReference(activity)
    }

    fun paused(activity: Activity) {
        if (current.get() === activity) current.clear()
    }

    internal fun current(): Activity? = current.get()?.takeUnless { it.isFinishing || it.isDestroyed }
}
