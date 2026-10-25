package dev.npn.app.ui.nav

/** The two ways out of a write block the read-only banner offers; see [WithWriteBlockBanner]. */
internal interface WriteBlockActions {
    /** Read the journal again. */
    fun retry()

    /** Build this device's index again from the folder alone. */
    fun rebuildIndex()
}
