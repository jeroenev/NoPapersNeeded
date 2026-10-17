package dev.npn.core.model

import java.util.Locale

actual fun systemLanguage(): String = Locale.getDefault().language
