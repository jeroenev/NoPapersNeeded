package dev.npn.app.ui.common

/**
 * Documents the platform asks the open library to show, from outside its own navigation: a tapped
 * reminder notification or tray message. A hand-off, so a request that arrives before the library
 * (or after a screen recreated it) is consumed when the library is there to open it.
 */
data class OpenDocumentRequest(
    val docId: String,
    val libraryLocation: String,
) {
    fun belongsTo(location: String?): Boolean = libraryLocation == location
}

object OpenDocumentRequests {
    val documents = Handoff<OpenDocumentRequest>()

    /** The Upcoming list of the library at this location: a tapped summary of several reminders. */
    val upcoming = Handoff<String>()
}
