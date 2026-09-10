package dev.npn.app.ui.common

import androidx.compose.runtime.saveable.Saver

/** Store enum UI state by its stable constant name while keeping call sites strongly typed. */
inline fun <reified T : Enum<T>> enumNameSaver(): Saver<T, String> =
    Saver(
        save = { it.name },
        restore = { name -> enumValues<T>().firstOrNull { it.name == name } },
    )
