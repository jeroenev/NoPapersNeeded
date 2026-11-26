package dev.npn.core.journal

import dev.npn.core.app.LibrarySettingKeys

/**
 * The marker that keeps a tag the user created ([dev.npn.core.app.TagAdmin.createTag]) listed while
 * no document carries it: a per-tag LWW setting ([LibrarySettingKeys.KEPT_TAG_PREFIX]), set by the
 * create and cleared by a delete; a rename moves it to the new name unless documents already carry
 * that name, a merge drops it then too.
 *
 * Old builds ignore these keys. Mixed versions:
 * - 0.1.0 deleting an empty tag writes no event, so the tag stays kept until deleted on an upgraded
 *   device.
 * - 0.1.0 renaming or merging a created tag leaves the marker (and the style) under the old name.
 *   The fold carries the marker along that rename and drops it on that merge
 *   ([FoldedState.retainedTags]), so the old name does not come back as an empty tag; the style
 *   stays under the old name, so the renamed tag loses its color.
 * - 0.1.0 renaming a created tag onto a name documents already carry keeps that name for good, as
 *   the fold cannot tell what the name carried at the time.
 */
internal object TagRetention {
    fun key(tag: String): String = LibrarySettingKeys.KEPT_TAG_PREFIX + tag

    fun tag(key: String): String? =
        key
            .removePrefix(
                LibrarySettingKeys.KEPT_TAG_PREFIX,
            ).takeIf { key.startsWith(LibrarySettingKeys.KEPT_TAG_PREFIX) }
}
