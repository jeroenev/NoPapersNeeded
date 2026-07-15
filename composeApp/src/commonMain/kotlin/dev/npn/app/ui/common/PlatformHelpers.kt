package dev.npn.app.ui.common

import androidx.compose.ui.graphics.ImageBitmap
import dev.npn.core.fs.ExternalSource
import io.github.vinceglb.filekit.PlatformFile

/**
 * Decode an image file at [path] into an [ImageBitmap]. Returns null on failure.
 * commonMain cannot touch java.io, so actuals read the file in their own source set
 * (Android: BitmapFactory, desktop: skia Image.makeFromEncoded).
 */
expect suspend fun loadImageBitmap(path: String): ImageBitmap?

/**
 * Convert a FileKit [PlatformFile] into a core [ExternalSource] the session can import.
 * Desktop wraps the nio path; Android wraps the content uri.
 */
expect fun platformFileToExternalSource(file: PlatformFile): ExternalSource

/**
 * Persist directory access for a picked library folder and return the location string used
 * by [dev.npn.app.di.AppEnvironment.openLibrary]. Desktop returns the absolute path; Android
 * takes a persistable uri permission and returns the tree uri string.
 */
expect fun persistDirectoryAccess(dir: PlatformFile): String
