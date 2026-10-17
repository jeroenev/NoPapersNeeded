package dev.npn.core.ocr

import dev.npn.core.util.NpnJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * OCR sidecar payload, serialized to the file [OcrSidecarName] names, single-writer
 * and write-once. Compact by design (text only). Field names are the exact wire keys.
 *
 * Example:
 * {"v":1,"doc":"<sha256>","dev":"<deviceId>","engine":"embedded",
 *  "lang":"eng","createdAt":1720000000000,"pages":["page 1 text","..."]}
 */
@Serializable
data class OcrSidecar(
    @SerialName("v") val v: Int = 1,
    @SerialName("doc") val doc: String,
    @SerialName("dev") val dev: String,
    /** An [OcrEngineId.column]; older builds also wrote `mixed`, which ranks as unknown. */
    @SerialName("engine") val engine: String,
    @SerialName("lang") val lang: String,
    @SerialName("createdAt") val createdAt: Long,
    @SerialName("pages") val pages: List<String>,
) {
    /** Full document text: pages joined with a blank line, exactly as the FTS index stores it. */
    fun fullText(): String = pages.joinToString("\n\n")
}

/** Serialization codec for [OcrSidecar]. Keeps output compact (no pretty-printing). */
object OcrSidecarCodec {
    private val json = NpnJson.Persisted

    fun encode(sidecar: OcrSidecar): String = json.encodeToString(OcrSidecar.serializer(), sidecar)

    fun decode(text: String): OcrSidecar = json.decodeFromString(OcrSidecar.serializer(), text)
}
