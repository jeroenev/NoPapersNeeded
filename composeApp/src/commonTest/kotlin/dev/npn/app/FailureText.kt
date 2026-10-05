package dev.npn.app

import dev.npn.app.ui.common.UiText
import dev.npn.app.ui.common.uiText
import org.jetbrains.compose.resources.StringResource

/** The message a failed write sends: [sentence] with the thrown [reason] as its `%1$s`. */
fun failure(
    sentence: StringResource,
    reason: String,
): UiText = uiText(sentence, UiText.Raw(reason))
