package dev.npn.core.ai

/**
 * Whether this replica may spend the user's AI budget *without being asked*.
 *
 * Two paths in the app call a paid endpoint with nobody watching: automatic vision OCR
 * (`OcrEngineSelector`, one call per page of every document this replica OCRs) and automatic
 * enrichment (`AiAutoEnricher`, one call per document it just OCR'd). Each used to read its own
 * toggle straight off [AiConfig]; this is the one home for that decision, so that when the answer
 * grows beyond a device-local switch — a synced "the cloud does this" setting, a per-library
 * server policy, a quota — it changes here and both consumers follow.
 *
 * Deliberately *only* the unattended question. Whether the endpoint can be called at all
 * ([AiConfig.configured]) gates every AI feature and is asked separately; a user-requested run
 * (a queued edit run, "OCR with AI" on one document) is a decision someone made and does not
 * consult this policy.
 *
 * Both answers are read per call, never captured, so flipping a setting takes effect on the
 * next document.
 */
interface UnattendedAiPolicy {
    /**
     * May the vision model be this replica's automatic OCR engine? The engine's own
     * availability (`OcrEngine.available`) is checked by the selector; this is purely the
     * permission to spend.
     */
    suspend fun autoOcr(): Boolean

    /** May a document this replica just OCR'd be enriched (tags, empty fields) without review? */
    suspend fun autoEnrich(): Boolean

    companion object {
        /**
         * The device policy: the user's own [AiConfig.ocrAuto] / [AiConfig.autoOnOcr] toggles,
         * and only while the endpoint is configured — an unconfigured endpoint spends nothing,
         * whatever the toggles say.
         */
        fun fromConfig(readConfig: suspend () -> AiConfig): UnattendedAiPolicy =
            object : UnattendedAiPolicy {
                override suspend fun autoOcr(): Boolean = readConfig().let { it.configured && it.ocrAuto }

                override suspend fun autoEnrich(): Boolean = readConfig().let { it.configured && it.autoOnOcr }
            }

        /** Never spends unattended. The right default for a host that has not decided yet. */
        val NEVER: UnattendedAiPolicy =
            object : UnattendedAiPolicy {
                override suspend fun autoOcr(): Boolean = false

                override suspend fun autoEnrich(): Boolean = false
            }
    }
}
