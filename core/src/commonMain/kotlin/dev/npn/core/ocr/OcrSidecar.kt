package dev.npn.core.ocr

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * OCR sidecar payload (PLAN.md "OCR sidecars"). Serialized to
 * `.npn/ocr/<sha256>.<deviceId>.json`, single-writer and write-once. Compact by design
 * (text only). Field names are the exact wire keys.
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
    /** embedded | mlkit | tesseract | mixed */
    @SerialName("engine") val engine: String,
    @SerialName("lang") val lang: String,
    @SerialName("createdAt") val createdAt: Long,
    @SerialName("pages") val pages: List<String>,
) {
    /** Full document text: pages joined with a blank line, matching the index format. */
    fun fullText(): String = pages.joinToString("\n\n")
}

/** Serialization codec for [OcrSidecar]. Keeps output compact (no pretty-printing). */
object OcrSidecarCodec {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun encode(sidecar: OcrSidecar): String = json.encodeToString(OcrSidecar.serializer(), sidecar)

    fun decode(text: String): OcrSidecar = json.decodeFromString(OcrSidecar.serializer(), text)
}
