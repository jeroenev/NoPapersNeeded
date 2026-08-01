package dev.npn.app.ui.common

fun documentDisplayTitle(
    title: String?,
    fileName: String,
): String = title?.takeIf { it.isNotBlank() } ?: fileName
