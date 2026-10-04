package dev.npn.core.ai

/**
 * Whether this replica may spend the user's AI budget *without being asked*.
 *
 * Two paths in the app call a paid endpoint with nobody watching: automatic vision OCR
 * (`OcrEngineSelector`, one call per page of every document this replica OCRs) and automatic
 * enrichment (`AiAutoEnricher`, one call per document it just OCR'd). This is the one home for
 * that decision, so neither reads its toggle straight off [AiConfig].
 *
 * The answer is the user's own [AiConfig.ocrAuto] / [AiConfig.autoOnOcr] toggles, and only while
 * the endpoint is configured — an unconfigured endpoint spends nothing, whatever the toggles say.
 *
 * Deliberately *only* the unattended question. Whether the endpoint can be called at all
 * ([AiConfig.configured]) gates every AI feature and is asked separately; a user-requested run
 * (a queued edit run, "OCR with AI" on one document) is a decision someone made and does not
 * consult this policy — on the device it was made on. Another replica that syncs the resulting
 * engine pin was asked nothing, so for it that document is unattended work like any other.
 *
 * Both answers are read per call, never captured, so flipping a setting takes effect on the
 * next document.
 */
class UnattendedAiPolicy(
    private val readConfig: suspend () -> AiConfig,
) {
    /**
     * May the vision model be this replica's automatic OCR engine? The engine's own
     * availability (`OcrEngine.available`) is checked by the selector; this is purely the
     * permission to spend.
     */
    suspend fun autoOcr(): Boolean = readConfig().let { it.configured && it.ocrAuto }

    /** May a document this replica just OCR'd be enriched (tags, empty fields) without review? */
    suspend fun autoEnrich(): Boolean = readConfig().let { it.configured && it.autoOnOcr }
}
