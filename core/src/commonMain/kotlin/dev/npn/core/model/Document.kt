package dev.npn.core.model

import kotlinx.serialization.Serializable

/** Document identity: SHA-256 of file bytes, lowercase hex. Stable forever because documents are immutable. */
@Serializable
@JvmInline
value class DocId(val sha256: String) {
    init {
        require(sha256.length == 64) { "DocId must be a sha256 hex string" }
    }
    override fun toString(): String = sha256
}

/** Document lifecycle status, drives review queue and default kanban lanes. */
enum class DocStatus(val wire: String) {
    INBOX("inbox"),
    REVIEWED("reviewed"),
    FILED("filed");

    companion object {
        fun fromWire(value: String): DocStatus = entries.firstOrNull { it.wire == value } ?: INBOX
    }
}

/** Single-value document properties settable via prop-set journal events. */
enum class DocProperty(val wire: String) {
    STATUS("status"),
    CORRESPONDENT("correspondent"),
    DOC_TYPE("docType"),
    DOC_DATE("docDate"), // ISO-8601 local date, e.g. 2026-07-15
    TITLE("title");

    companion object {
        fun fromWire(value: String): DocProperty? = entries.firstOrNull { it.wire == value }
    }
}
