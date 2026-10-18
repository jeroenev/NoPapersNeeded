package dev.npn.core.journal

import dev.npn.core.fs.FileMeta
import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.fs.NpnPaths

/** The paths of the files under the library's journal that [JournalPaths.classify] says are a [T]. */
internal suspend inline fun <reified T : JournalPath> LibraryFileSystem.journalPaths(): List<String> =
    listAllUnder(NpnPaths.JOURNAL_DIR).map { it.relPath }.filter { JournalPaths.classify(it) is T }

/** The library's published storage-v2 journal objects. */
internal suspend fun LibraryFileSystem.journalObjects(): List<FileMeta> =
    listAllUnder(NpnPaths.JOURNAL_DIR).filter { JournalPaths.classify(it.relPath) is JournalPath.ObjectV2 }

/** Each published journal object's text. */
internal suspend fun LibraryFileSystem.journalObjectTexts(): List<String> =
    journalObjects().map { readBytes(it.relPath).decodeToString() }

/** Every event the published journal objects hold. */
internal suspend fun LibraryFileSystem.journalObjectEvents(): List<JournalEvent> =
    journalObjectTexts().flatMap { it.lines() }.mapNotNull { JournalCodec.decodeLineOrNull(it) }
