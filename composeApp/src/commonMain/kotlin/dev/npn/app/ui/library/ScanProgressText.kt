package dev.npn.app.ui.library

import dev.npn.app.ui.common.UiText
import dev.npn.app.ui.common.uiText
import dev.npn.core.index.IndexProgress
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.library_scan_folding
import no_papers_needed.composeapp.generated.resources.library_scan_hashing
import no_papers_needed.composeapp.generated.resources.library_scan_listing
import no_papers_needed.composeapp.generated.resources.library_scan_reconciling

/** What the scan banner says about where a rescan is: a sentence per phase, hashing with its count. */
internal fun IndexProgress.bannerText(): UiText =
    when (phase) {
        IndexProgress.Phase.LISTING -> uiText(Res.string.library_scan_listing)
        IndexProgress.Phase.HASHING -> uiText(Res.string.library_scan_hashing, hashed, total)
        IndexProgress.Phase.RECONCILING -> uiText(Res.string.library_scan_reconciling)
        IndexProgress.Phase.FOLDING -> uiText(Res.string.library_scan_folding)
    }
