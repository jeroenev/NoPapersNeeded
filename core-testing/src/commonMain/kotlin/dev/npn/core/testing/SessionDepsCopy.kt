package dev.npn.core.testing

import dev.npn.core.app.SessionDeps
import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.pdf.PdfEngine
import dev.npn.core.util.EpochClock

/** These deps, on the same databases, with the pieces given here in place of their own. */
fun SessionDeps.copy(
    fs: LibraryFileSystem = this.fs,
    pdfEngine: PdfEngine = this.pdfEngine,
    clock: EpochClock = this.clock,
): SessionDeps =
    SessionDeps(
        fs = fs,
        db = db,
        deviceDb = deviceDb,
        pdfEngine = pdfEngine,
        ocrEngine = ocrEngine,
        xmpIO = xmpIO,
        thumbnailStore = thumbnailStore,
        renderParallelism = renderParallelism,
        scope = scope,
        settings = settings,
        log = log,
        clock = clock,
        installationId = installationId,
    )
