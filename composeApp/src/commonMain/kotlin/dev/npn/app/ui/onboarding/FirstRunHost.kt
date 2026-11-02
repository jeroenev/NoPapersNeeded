package dev.npn.app.ui.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

/** Where onboarding stands before a library is open. */
internal enum class OnboardingStep {
    /** What the app is for, and the two ways in. */
    WELCOME,

    /** Where a new library lives: on this device, or in a cloud drive. */
    LOCATION,

    /** Reach a library another device already uses. */
    CONNECT,
}

/** Saves an enum by its name, which a restored process reads back. */
internal inline fun <reified E : Enum<E>> enumSaver(): Saver<E, String> =
    Saver(save = { it.name }, restore = { enumValueOf<E>(it) })

/**
 * Where first run stands, as the app shell keeps it: above the language switch, through a recreated
 * window and a restored process. [path] is the way the user took in onboarding, set as they make the
 * choice that opens a library and cleared once first run is over; [step] is the step of that path's
 * [steps] the open library shows. [landing] is where first run left the user, for the library's
 * screens to take them to once they show.
 */
@Stable
internal class FirstRunHost(
    path: FirstRunPath?,
    step: Int,
) {
    var path by mutableStateOf(path)
        private set

    var step by mutableIntStateOf(step)

    var landing by mutableStateOf<FirstRunExit?>(null)

    /** The user made a choice in onboarding that opens a library: first run goes on from there. */
    fun begin(path: FirstRunPath) {
        this.path = path
        step = 0
    }

    /** First run is over, and leaves the user at [exit]. */
    fun finish(exit: FirstRunExit) {
        path = null
        step = 0
        landing = exit
    }

    companion object {
        val Saver: Saver<FirstRunHost, Any> =
            listSaver(
                save = { listOf(it.path?.name.orEmpty(), it.step) },
                restore = { saved ->
                    val name = saved[0] as String
                    FirstRunHost(name.takeIf(String::isNotEmpty)?.let(FirstRunPath::valueOf), saved[1] as Int)
                },
            )
    }
}

/** The app shell's [FirstRunHost], saved with the window's state. */
@Composable
internal fun rememberFirstRunHost(): FirstRunHost =
    rememberSaveable(saver = FirstRunHost.Saver) {
        FirstRunHost(null, 0)
    }
