package dev.npn.core.app

import dev.npn.core.db.AiEnrichQueueDao
import dev.npn.core.db.AiEnrichedEntity
import dev.npn.core.index.RelinkService
import dev.npn.core.util.NpnLog
import dev.npn.core.util.runLogged

/**
 * The relink a bake in place ends with, and what the baked document keeps of its original besides the
 * metadata the relink carries. A bake rewrites the metadata a PDF carries, never a page, so the baked
 * bytes have the original's text: before the relink they get the original's OCR versions, published
 * again as this device's ([dev.npn.core.ocr.OcrPipeline.copyText]), and its enrichment mark; once it
 * stands, that text is indexed, so the baked document is searchable at once and is neither OCR'd nor
 * enriched again. Only a bake knows the pages are the same: a relink to other bytes
 * ([MaintenanceFacade.applyRelink]) hands on nothing. A step that fails is logged and the bake goes on;
 * the baked document then gets its text as a new one does.
 */
internal class BakeRelink(
    private val relink: RelinkService,
    private val ocr: OcrFacadeImpl,
    private val enrichment: AiEnrichQueueDao,
    private val log: NpnLog,
) {
    suspend fun relink(
        original: String,
        baked: String,
    ) {
        log.runLogged("Handing the text of $original on to its bake") { ocr.copyText(original, baked) }
        log.runLogged("Handing the enrichment mark of $original on to its bake") {
            enrichment.enrichedAt(original)?.let { enrichment.markEnriched(AiEnrichedEntity(baked, it)) }
        }
        relink.applyBakeRelink(original, baked)
        log.runLogged("Indexing the text of $baked") { ocr.reselect(baked) }
    }
}
