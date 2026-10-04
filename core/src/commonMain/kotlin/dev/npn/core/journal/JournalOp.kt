package dev.npn.core.journal

import dev.npn.core.model.FieldDef
import dev.npn.core.model.SystemProperty
import kotlinx.serialization.json.JsonObject

/** A dot uniquely identifies a single event by its author device and per-device sequence. */
data class Dot(
    val deviceId: String,
    val seq: Long,
)

/**
 * The operation carried by a journal event. Serialized on disk as an `op` string plus a
 * `p` payload object; see [JournalEventSerializer] for the exact mapping.
 *
 * Unknown op strings are represented by [Unknown] so that forward-compatible readers can
 * skip events they do not understand without crashing.
 */
sealed class JournalOp {
    /** The `op` wire string for this operation. */
    abstract val wire: String

    /** {"tag": String}; doc required. */
    data class TagAdd(
        val tag: String,
    ) : JournalOp() {
        override val wire get() = "tag-add"
    }

    /** {"tag": String, "dots": [[deviceId, seq], ...]}; observed-remove. doc required. */
    data class TagRemove(
        val tag: String,
        val dots: List<Dot>,
    ) : JournalOp() {
        override val wire get() = "tag-remove"
    }

    /**
     * {"key": SystemProperty.wire, "value": String?}; null clears. doc required. System state only
     * (reviewed marker, OCR engine pin); user metadata is [FieldSet]. Legacy `prop-set` events
     * carrying a well-known field key decode as [FieldSet], see [JournalEventSerializer].
     */
    data class PropSet(
        val key: SystemProperty,
        val value: String?,
    ) : JournalOp() {
        override val wire get() = "prop-set"
    }

    /**
     * {"key": String, "value": String?}; null clears. doc required. A document's value for one
     * registry field ([FieldDefSet]) — title, correspondent and every user-added field alike. The
     * key is a normalized free string; the value is in the field type's canonical form
     * ([dev.npn.core.model.FieldValues]). Last-writer-wins by HLC, same as [PropSet].
     */
    data class FieldSet(
        val key: String,
        val value: String?,
    ) : JournalOp() {
        override val wire get() = "field-set"
    }

    /**
     * {"key": String, "def": {label, type, showByDefault, aiEnabled, position, hint?} | null};
     * doc null, library-wide. Defines (or, with a null [def], removes) one entry of the field
     * registry. Last-writer-wins by HLC per key, so two devices adding two different fields both
     * survive. Removing a definition never touches stored values: they become orphans.
     */
    data class FieldDefSet(
        val key: String,
        val def: FieldDef?,
    ) : JournalOp() {
        init {
            require(def == null || def.key == key) { "FieldDefSet key must match its definition" }
        }

        override val wire get() = WIRE

        companion object {
            const val WIRE = "field-def"
        }
    }

    /** {"origPath": String}; doc required. */
    data class DocTrash(
        val origPath: String,
    ) : JournalOp() {
        override val wire get() = WIRE

        companion object {
            const val WIRE = "doc-trash"
        }
    }

    /** {}; doc required. */
    data object DocRestore : JournalOp() {
        override val wire get() = "doc-restore"
    }

    /** {}; permanent tombstone. doc required. */
    data object DocDelete : JournalOp() {
        override val wire get() = "doc-delete"
    }

    /** {"newDoc": sha256}; doc = old id. */
    data class Relink(
        val newDoc: String,
    ) : JournalOp() {
        override val wire get() = "relink"
    }

    /** {"from": String, "to": String}; doc null, library-wide. */
    data class TagRename(
        val from: String,
        val to: String,
    ) : JournalOp() {
        override val wire get() = "tag-rename"
    }

    /** {"from": String, "to": String}; doc null. Same folding as rename, kept distinct for UI/history. */
    data class TagMerge(
        val from: String,
        val to: String,
    ) : JournalOp() {
        override val wire get() = "tag-merge"
    }

    /** {"tag": String, "color": String?, "icon": String?}; doc null. */
    data class TagStyle(
        val tag: String,
        val color: String?,
        val icon: String?,
    ) : JournalOp() {
        override val wire get() = "tag-style"
    }

    /** {"key": String, "value": String?}; doc null, library-wide. LWW synced setting. */
    data class SettingSet(
        val key: String,
        val value: String?,
    ) : JournalOp() {
        override val wire get() = WIRE

        companion object {
            /**
             * Shared with `MetadataProjectionDao.eventsForOperation`, which selects setting events
             * by this value so the open path can fold settings without folding the whole journal.
             */
            const val WIRE = "setting-set"
        }
    }

    /** An op this build does not understand. Its payload is retained for migration and diagnostics. */
    data class Unknown(
        override val wire: String,
        val payload: JsonObject = JsonObject(emptyMap()),
    ) : JournalOp()
}
