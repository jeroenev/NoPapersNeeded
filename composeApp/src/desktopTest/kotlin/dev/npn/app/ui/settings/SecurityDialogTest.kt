@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.npn.app.ui.settings

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.AnnotatedString
import dev.npn.app.EnglishLocaleTest
import dev.npn.app.ui.click
import dev.npn.app.ui.closing
import dev.npn.app.ui.editableField
import dev.npn.app.ui.editableText
import dev.npn.app.ui.has
import dev.npn.app.ui.isEditable
import dev.npn.app.ui.nodes
import dev.npn.app.ui.onUiThread
import dev.npn.app.ui.texts
import dev.npn.app.ui.themedScene
import dev.npn.app.ui.type
import dev.npn.core.security.UnlockResult
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

    @Test
    fun theSetupOffersTheNumberPadFirstAndLettersOnATap() =
        onUiThread {
            themedScene(800, 600) {
                SecurityDialogContent(SecurityDialog.SetPin, onDismiss = {}, onSave = {}, offerKeyboardChoice = true)
            }.closing {
                render()
                has { "Use letters as well" in it.texts() } shouldBe true

                click { "Use letters as well" in it.texts() }
                render()

                has { "Use the number pad" in it.texts() } shouldBe true
            }
        }

    @Test
    fun aPhysicalKeyboardIsOfferedNoChoice() =
        onUiThread {
            themedScene(800, 600) {
                SecurityDialogContent(SecurityDialog.SetPin, onDismiss = {}, onSave = {}, offerKeyboardChoice = false)
            }.closing {
                render()
                has { "Use letters as well" in it.texts() } shouldBe false
            }
        }

    @Test
    fun theCurrentPinCheckStartsEachTryFromAnEmptyField() =
        onUiThread {
            val tried = mutableListOf<String>()
            themedScene(800, 600) {
                CurrentSecretDialog(
                    numeric = true,
                    check = SecurityCheck(outcome = UnlockResult.WrongSecret),
                    deviceAuthAvailable = false,
                    onConfirm = { tried += it },
                    onDevice = {},
                    onDismiss = {},
                )
            }.closing {
                render()
                type("9999")
                click { "Confirm" in it.texts() }
                render()

                tried shouldBe listOf("9999")
                editableField()?.editableText().orEmpty() shouldBe ""
            }
        }
}
