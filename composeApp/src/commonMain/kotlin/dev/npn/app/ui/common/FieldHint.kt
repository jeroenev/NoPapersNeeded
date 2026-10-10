package dev.npn.app.ui.common

import dev.npn.core.model.FieldDef
import dev.npn.core.model.FieldKeys
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.detail_field_seed_hint_correspondent
import no_papers_needed.composeapp.generated.resources.detail_field_seed_hint_doc_date
import no_papers_needed.composeapp.generated.resources.detail_field_seed_hint_title
import org.jetbrains.compose.resources.StringResource

/** The app's own words for each seeded hint ([FieldKeys.SEED_HINTS]), in every language it speaks. */
private val seedHintText: Map<String, StringResource> =
    mapOf(
        FieldKeys.TITLE to Res.string.detail_field_seed_hint_title,
        FieldKeys.CORRESPONDENT to Res.string.detail_field_seed_hint_correspondent,
        FieldKeys.DOC_DATE to Res.string.detail_field_seed_hint_doc_date,
    )

/**
 * The guidance shown under this field's editor, or null when it has none. A seeded field whose hint
 * is still the seed's shows the app's translation of it, so it follows the app's language; a hint
 * the library wrote is shown as written. A model is told the stored hint either way.
 */
fun FieldDef.hintText(): UiText? {
    val stored = hint ?: return null
    val seeded = seedHintText[key]?.takeIf { stored == FieldKeys.SEED_HINTS[key] }
    return if (seeded != null) UiText.Res(seeded) else UiText.Raw(stored)
}
