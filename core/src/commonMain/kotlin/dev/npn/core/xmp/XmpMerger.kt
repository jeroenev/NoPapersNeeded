package dev.npn.core.xmp

/**
 * Merge policy for producing the XMP packet to bake.
 *
 * v1 policy (deliberately simple and deterministic): generate a fresh packet from [data] alone.
 * The only value carried over from an existing packet is `dc:title`, and only when [data] does
 * not already provide one. Any other foreign XMP in the source packet is dropped.
 *
 * Rationale: bake output is primarily an interop *export* of npn's own metadata. Preserving
 * arbitrary foreign properties would require a full XMP model and merge semantics we do not
 * need in v1; dropping them keeps the output deterministic and testable. This limitation is
 * documented and acceptable per PLAN section 2.
 */
object XmpMerger {

    fun merge(existingXmp: String?, data: NpnXmpData): String {
        val effective = if (data.title == null && existingXmp != null) {
            val existingTitle = XmpParser.parse(existingXmp).title
            if (existingTitle != null) data.copy(title = existingTitle) else data
        } else {
            data
        }
        return XmpSerializer.serialize(effective)
    }
}
