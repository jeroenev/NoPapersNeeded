@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.npn.app.ui.settings

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.AnnotatedString
import dev.npn.app.EnglishLocaleTest
import dev.npn.app.ui.closing
import dev.npn.app.ui.isEditable
import dev.npn.app.ui.nodes
import dev.npn.app.ui.onUiThread
import dev.npn.app.ui.themedScene
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class SecurityDialogTest : EnglishLocaleTest() {
    @Test
    fun pinFieldsAreArrangedVertically() =
        onUiThread {
            themedScene(800, 600) {
                SecurityDialogContent(SecurityDialog.SetPin, onDismiss = {}, onSave = {})
            }.closing {
                render()
                val fields = nodes().filter { it.isEditable() }.sortedBy { it.boundsInRoot.top }
                fields.size shouldBe 2
                (fields[0].boundsInRoot.bottom <= fields[1].boundsInRoot.top) shouldBe true
            }
        }

    @Test
    fun pinBuffersAreNotSaved() =
        onUiThread {
            val registry = SaveableStateRegistry(null) { true }
            themedScene(800, 600) {
                CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                    SecurityDialogContent(SecurityDialog.SetPin, onDismiss = {}, onSave = {})
                }
            }.closing {
                render()
                val fields = nodes().filter { it.isEditable() }.sortedBy { it.boundsInRoot.top }
                fields.size shouldBe 2
                fields.forEach { it.config[SemanticsActions.SetText].action?.invoke(AnnotatedString("secret123")) }
                render()
                registry.performSave().toString().contains("secret123") shouldBe false
            }
        }
}
