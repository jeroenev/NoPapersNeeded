package dev.npn.app.ui.common

import androidx.compose.ui.Modifier

/**
 * `Modifier.testTag` ids of the elements UI automation looks for. The baseline-profile generator
 * (`:baselineprofile`) finds them by id, so it works whatever language the device runs; it cannot
 * depend on this module, so it repeats these strings.
 */
object TestTags {
    const val STORAGE_ACCESS_GRANT = "storage_access_grant"
    const val USE_RECOMMENDED_FOLDER = "use_recommended_folder"

    /** One per metadata panel tab: `panel_tab_preview`, `panel_tab_details`, ... */
    fun panelTab(name: String): String = "panel_tab_${name.lowercase()}"
}

/**
 * Exposes the test tags of this node and its descendants as Android resource ids, which is where
 * UI Automator looks for them. A no-op on desktop.
 */
expect fun Modifier.testTagsAsResourceIds(): Modifier
