package dev.npn.app

import dev.npn.app.platform.PdfPrinter
import dev.npn.app.platform.PrintOutcome
import dev.npn.app.ui.detail.DocDetailViewModel
import kotlinx.coroutines.test.TestScope

// Fixtures for the DocDetailViewModel suites.

/** A detail viewmodel for [docId], with an AI endpoint configured unless [settings] says otherwise. */
internal fun docDetailVm(
    session: FakeLibrarySession = FakeLibrarySession(),
    docId: String = "doc-1",
    settings: FakeSettingsRepository = aiConfiguredSettings(),
    printer: PdfPrinter = PdfPrinter { _, _, _ -> PrintOutcome.Started },
): DocDetailViewModel = DocDetailViewModel(docId, session, settings, printer = printer)

/**
 * A [docDetailVm] with its panel state kept live for the whole test: [DocDetailViewModel.metadata]
 * shares while subscribed, so an assertion on `metadata.value` needs a standing collector.
 */
internal fun TestScope.liveDocDetailVm(
    session: FakeLibrarySession = FakeLibrarySession(),
    docId: String = "doc-1",
    settings: FakeSettingsRepository = aiConfiguredSettings(),
    printer: PdfPrinter = PdfPrinter { _, _, _ -> PrintOutcome.Started },
): DocDetailViewModel =
    docDetailVm(session, docId, settings, printer).also { model ->
        collecting(model.metadata)
    }
