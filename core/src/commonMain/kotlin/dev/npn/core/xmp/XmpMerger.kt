package dev.npn.core.xmp

import dev.npn.core.model.FieldKeys

/**
 * Merge policy for the metadata to bake.
 *
 * v1 policy (deliberately simple and deterministic): the packet is generated fresh from [data]
 * alone. The only value carried over from an existing packet is `dc:title`, and only when [data]
 * does not already provide one. Any other foreign XMP in the source packet is dropped.
 *
 * Rationale: bake output is primarily an interop *export* of npn's own metadata. Preserving
 * arbitrary foreign properties would require a full XMP model and merge semantics we do not
 * need in v1; dropping them keeps the output deterministic and testable. This limitation is
 * documented and acceptable per PLAN section 2.
 */
object XmpMerger {
    /** What to bake: [data], with the title of [existingXmp] when [data] has none. */
    fun merge(
        existingXmp: String?,
        data: NpnXmpData,
    ): NpnXmpData {
        if (data.title != null || existingXmp == null) return data
        val existingTitle = XmpParser.parse(existingXmp).title ?: return data
        return data.withField(FieldKeys.TITLE, existingTitle)
    }
}
