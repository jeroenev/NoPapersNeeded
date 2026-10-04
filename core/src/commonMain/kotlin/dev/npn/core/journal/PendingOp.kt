package dev.npn.core.journal

import dev.npn.core.model.FieldDef
import dev.npn.core.model.SystemProperty

/**
 * A pending op the caller wants to commit. The writer assigns seq and HLC and fills in the
 * envelope. `doc` follows the op's requirements (see [JournalOp]).
 */
data class PendingOp(
    val doc: String?,
    val op: JournalOp,
) {
    companion object {
        fun tagAdd(
            doc: String,
            tag: String,
        ) = PendingOp(doc, JournalOp.TagAdd(tag))

        /**
         * `internal` on purpose: a correct OR-set `tag-remove` must carry the dots CURRENTLY live
         * for `(doc, tag)`. The only sanctioned way to obtain those is [FoldedState.tagRemovalOps],
         * which reads a live [FoldedState] under the coordinator mutex. Keeping this factory out of
         * the public surface makes a removal against stale/fabricated dots unexpressible.
         */
        internal fun tagRemove(
            doc: String,
            tag: String,
            dots: List<Dot>,
        ) = PendingOp(doc, JournalOp.TagRemove(tag, dots))

        fun propSet(
            doc: String,
            key: SystemProperty,
            value: String?,
        ) = PendingOp(doc, JournalOp.PropSet(key, value))

        fun fieldSet(
            doc: String,
            key: String,
            value: String?,
        ) = PendingOp(doc, JournalOp.FieldSet(key, value))

        fun docTrash(
            doc: String,
            origPath: String,
        ) = PendingOp(doc, JournalOp.DocTrash(origPath))

        fun docRestore(doc: String) = PendingOp(doc, JournalOp.DocRestore)

        fun docDelete(doc: String) = PendingOp(doc, JournalOp.DocDelete)

        fun relink(
            oldDoc: String,
            newDoc: String,
        ) = PendingOp(oldDoc, JournalOp.Relink(newDoc))

        fun tagRename(
            from: String,
            to: String,
        ) = PendingOp(null, JournalOp.TagRename(from, to))

        fun tagMerge(
            from: String,
            to: String,
        ) = PendingOp(null, JournalOp.TagMerge(from, to))

        fun tagStyle(
            tag: String,
            color: String?,
            icon: String?,
        ) = PendingOp(null, JournalOp.TagStyle(tag, color, icon))

        fun settingSet(
            key: String,
            value: String?,
        ) = PendingOp(null, JournalOp.SettingSet(key, value))

        /** Define or update a registry field. */
        fun fieldDefSet(def: FieldDef) = PendingOp(null, JournalOp.FieldDefSet(def.key, def))

        /** Remove a registry field's definition; stored values are untouched. */
        fun fieldDefRemove(key: String) = PendingOp(null, JournalOp.FieldDefSet(key, null))
    }
}
