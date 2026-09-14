package dev.npn.app.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

private val AlwaysGranted = StorageAccessState(required = false, granted = true, request = {})

@Composable
actual fun rememberStorageAccess(): StorageAccessState = remember { AlwaysGranted }
