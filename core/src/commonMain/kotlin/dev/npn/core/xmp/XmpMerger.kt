package dev.npn.core.xmp

import dev.npn.core.model.FieldKeys

/**
 * Merge policy for the metadata to bake.
 *
 * Deliberately simple and deterministic: the packet is generated fresh from [data] alone. The only
 * value carried over from an existing packet is `dc:title`, and only when [data] does not already
 * provide one. Any other foreign XMP in the source packet is dropped: bake output is an interop
 * *export* of npn's own metadata, and keeping foreign properties would need a full XMP model and
 * merge semantics.
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
