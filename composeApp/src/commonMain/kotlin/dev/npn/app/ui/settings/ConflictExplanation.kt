package dev.npn.app.ui.settings

import dev.npn.core.sync.ConflictKind
import dev.npn.core.sync.SyncConflict

/**
 * A conflict as the screen explains it: which document or path it is about ([label] is the title
 * when the library still shows the document, else the remote path), and, for the copy kinds, where
 * the document went instead ([otherPath]).
 */
internal sealed interface ConflictExplanation {
    val label: String

    data class TargetOccupied(
        override val label: String,
    ) : ConflictExplanation

    data class UploadedAsCopy(
        override val label: String,
        val otherPath: String,
    ) : ConflictExplanation

    data class InstalledAsCopy(
        override val label: String,
        val otherPath: String,
    ) : ConflictExplanation

    data class Unidentified(
        override val label: String,
    ) : ConflictExplanation

    data class RemoteChanged(
        override val label: String,
    ) : ConflictExplanation

    data class LocalChanged(
        override val label: String,
    ) : ConflictExplanation

    data class KeptOnThisDevice(
        override val label: String,
    ) : ConflictExplanation
}

/**
 * Explain [conflict] in plain language, naming [document] when the conflict's document still exists:
 * a title is what the user searches for, while the remote path is what the pass saw.
 */
internal fun explainConflict(
    conflict: SyncConflict,
    document: ConflictDocument?,
): ConflictExplanation {
    // A title is what the user searches for; the remote path is where the pass saw it and is what
    // makes an occupied target or a copy actionable, so a resolved document keeps both.
    val label =
        document?.title?.takeIf { it.isNotBlank() }?.let { "$it — ${conflict.remotePath}" }
            ?: conflict.remotePath
    val other = conflict.otherPath.orEmpty()
    return when (conflict.kind) {
        ConflictKind.TARGET_OCCUPIED -> ConflictExplanation.TargetOccupied(label)
        ConflictKind.UPLOADED_AS_COPY -> ConflictExplanation.UploadedAsCopy(label, other)
        ConflictKind.INSTALLED_AS_COPY -> ConflictExplanation.InstalledAsCopy(label, other)
        ConflictKind.UNIDENTIFIED -> ConflictExplanation.Unidentified(label)
        ConflictKind.REMOTE_CHANGED -> ConflictExplanation.RemoteChanged(label)
        ConflictKind.LOCAL_CHANGED -> ConflictExplanation.LocalChanged(label)
        ConflictKind.KEPT_ON_THIS_DEVICE -> ConflictExplanation.KeptOnThisDevice(label)
    }
}
