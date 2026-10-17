package dev.npn.app

import dev.npn.app.ui.settings.SettingsViewModel

/** A Settings viewmodel that counts the library folder and any folder under it as in the library. */
internal fun settingsVm(
    settings: FakeSettingsRepository = FakeSettingsRepository(),
    session: FakeLibrarySession = FakeLibrarySession(),
) = SettingsViewModel(
    settings,
    session,
    inLibrary = { folder, library -> folder == library || folder.startsWith("$library/") },
)
