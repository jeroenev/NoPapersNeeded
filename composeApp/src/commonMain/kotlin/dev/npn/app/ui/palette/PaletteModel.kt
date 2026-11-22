package dev.npn.app.ui.palette

import dev.npn.app.ui.settings.SettingsCategoryId
import dev.npn.core.app.SavedView

/** The groups the palette shows, in the order it shows them. */
internal enum class PaletteGroupKind { RECENT, DOCUMENTS, TAGS, VIEWS, SETTINGS, ACTIONS }

/** What running an [PaletteItem] does. */
internal sealed interface PaletteTarget {
    data class Doc(
        val docId: String,
    ) : PaletteTarget

    data class Tag(
        val name: String,
    ) : PaletteTarget

    data class View(
        val view: SavedView,
    ) : PaletteTarget

    /** A settings pane, its Advanced disclosure expanded when the match sat behind it. */
    data class Settings(
        val id: SettingsCategoryId,
        val revealAdvanced: Boolean = false,
    ) : PaletteTarget

    data class Action(
        val id: PaletteCommandId,
    ) : PaletteTarget
}

/**
 * One result of the palette. [key] is stable for recency: a document id, a tag name, a saved view's
 * id, a settings category, or an action.
 */
internal data class PaletteItem(
    val key: String,
    val title: String,
    val target: PaletteTarget,
    /** The matched settings label, for a match that was not the pane's title; null otherwise. */
    val subtitle: String? = null,
    /** How many documents carry a tag; the UI renders it as a count. */
    val count: Int? = null,
)

internal data class PaletteGroup(
    val kind: PaletteGroupKind,
    val items: List<PaletteItem>,
)

/** The palette as the UI renders it: open or closed, the query, and the groups that matched it. */
internal data class PaletteState(
    val open: Boolean = false,
    val query: String = "",
    val groups: List<PaletteGroup> = emptyList(),
    /** The selected result, as an index into [items]; 0 while nothing matched. */
    val selected: Int = 0,
    /** The "new tag" dialog is open over the palette. */
    val newTagOpen: Boolean = false,
) {
    /** The groups' results as one list, in the order Up and Down walk them. */
    val items: List<PaletteItem> get() = groups.flatMap { it.items }

    val selectedItem: PaletteItem? get() = items.getOrNull(selected)

    val hasResults: Boolean get() = items.isNotEmpty()
}

/** A palette command the UI labels and the viewmodel runs, such as "Import PDFs…". */
internal data class PaletteCommand(
    val id: PaletteCommandId,
    val title: String,
)

/** The common actions the palette offers; each one is wired to the existing facade in the viewmodel. */
internal enum class PaletteCommandId {
    IMPORT,
    SCAN,
    WATCH_FOLDER,
    SYNC_NOW,
    ASSISTANT,
    INBOX,
    TAGS,
    SETTINGS,
    NEW_TAG,
}

/**
 * A settings pane as the palette searches it: its resolved title and the labels of the settings it
 * holds, exactly as the settings screen's own search sees them.
 */
internal data class PaletteSettingsEntry(
    val id: SettingsCategoryId,
    val title: String,
    val keywords: List<String> = emptyList(),
    val advancedKeywords: List<String> = emptyList(),
)

/** What the palette asks of the platform, which only a composable can start. */
internal sealed interface PaletteRequest {
    data object PickFiles : PaletteRequest

    data object Scan : PaletteRequest
}

/** Where a palette result takes the user. Implemented by the shell; a fake in tests. */
internal interface PaletteNavigation {
    fun openDocument(docId: String)

    fun openLibrary(filter: dev.npn.core.app.LibraryFilter)

    fun openSavedView(view: SavedView)

    /** Apply the library's review preset (the Inbox, or untagged documents). */
    fun openReviewQueue()

    fun openTags()

    fun openSettings(
        category: SettingsCategoryId?,
        revealAdvanced: Boolean = false,
    )

    fun openAssistant()
}

internal fun documentPaletteKey(docId: String): String = "doc:$docId"

internal fun tagPaletteKey(name: String): String = "tag:$name"

internal fun viewPaletteKey(id: String): String = "view:$id"

internal fun settingsPaletteKey(id: SettingsCategoryId): String = "settings:${id.name}"

internal fun actionPaletteKey(id: PaletteCommandId): String = "action:${id.name}"
