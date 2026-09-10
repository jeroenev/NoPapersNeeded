package dev.npn.app.ui.nav

/**
 * The state of the one-shot "is the AI assistant configured?" probe, as the navigation shell needs
 * to see it.
 *
 * A plain boolean cannot say "not answered yet" apart from "answered no", and the shell has to tell
 * them apart: while the probe runs it *reserves* the AI slots rather than leaving them out, so the
 * items do not shift sideways the instant the answer lands. Once resolved, [CONFIGURED] shows the
 * AI entries and [UNAVAILABLE] hides them.
 */
enum class AiProbe {
    /** The probe has not resolved. Reserve the AI slots but do not let them act yet. */
    PROBING,

    /** The endpoint is configured; show the AI entries. */
    CONFIGURED,

    /** The endpoint is missing or unreachable; hide the AI entries. */
    UNAVAILABLE,
    ;

    /** True once the endpoint is known to be usable — the AI entries may act. */
    val isConfigured: Boolean get() = this == CONFIGURED

    /**
     * True while the shell should hold space for the AI entries: either they are known to belong,
     * or the probe has not resolved and evicting them now would shift the bar under the user.
     */
    val reservesSlot: Boolean get() = this != UNAVAILABLE
}
