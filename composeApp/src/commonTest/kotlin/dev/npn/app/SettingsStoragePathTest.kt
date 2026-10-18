package dev.npn.app

import dev.npn.app.ui.common.uiText
import dev.npn.app.ui.settings.text
import dev.npn.core.app.StoragePathSetting
import dev.npn.core.model.FieldDef
import dev.npn.core.model.FieldKeys
import dev.npn.core.model.FieldType
import dev.npn.core.pathrules.StoragePathContext
import dev.npn.core.pathrules.StoragePathError
import dev.npn.core.pathrules.StoragePathExpansion
import dev.npn.core.pathrules.StoragePathTemplate
import dev.npn.core.pathrules.pathOrNull
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_save_setting_failed
import no_papers_needed.composeapp.generated.resources.common_settings_storage_error_not_a_date
import no_papers_needed.composeapp.generated.resources.common_settings_storage_rule_stays_off
import kotlin.test.Test

/** The storage path rule on the Settings screen: its template, its preview and turning it on. */
class SettingsStoragePathTest : MainDispatcherTest() {
    @Test
    fun storagePathErrorsAreProjectedAsLocalizableData() {
        val result = StoragePathTemplate.expand("{unknown}/{title}.pdf", PREVIEW_CONTEXT)
        result shouldBe StoragePathExpansion.Invalid(StoragePathError.UnknownVariable("unknown"))
    }

    @Test
    fun aDatePatternOnANonDateFieldSaysSo() {
        val result = StoragePathTemplate.expand("{field:amount:yyyy}/{title}.pdf", PREVIEW_CONTEXT)

        val error = (result as StoragePathExpansion.Invalid).error
        error shouldBe StoragePathError.NotADateField("amount")
        error.text() shouldBe uiText(Res.string.common_settings_storage_error_not_a_date, "amount")
    }

    /**
     * A field the user defined as a date takes a date pattern like the seeded document date does;
     * judged against the seed types alone, Settings would refuse a template the import path expands.
     */
    @Test
    fun aDatePatternOnAUserDefinedDateFieldValidatesAndEnables() =
        runTest(dispatcher) {
            val invoiceDate =
                FieldDef(
                    "invoiceDate",
                    "Invoice date",
                    FieldType.DATE,
                    showByDefault = false,
                    aiEnabled = false,
                    position = 9,
                )
            val session =
                FakeLibrarySession().apply {
                    fieldRegistry.defs.value = fieldRegistry.defs.value + invoiceDate
                }
            val template = "{field:invoiceDate:yyyy}/{title}.pdf"
            session.storagePath.storagePathFlow.value = StoragePathSetting(template)
            val model = settingsVm(session = session)
            val types =
                session.fieldRegistry.defs.value
                    .associate { it.key to it.type }

            StoragePathTemplate.expand(template, PREVIEW_CONTEXT.copy(fieldTypes = types)).pathOrNull shouldNotBe null
            model.setStoragePathEnabled(true)
            advanceUntilIdle()

            session.storagePath.storagePathFlow.value.enabled shouldBe true
        }

    @Test
    fun storagePathPreviewUsesProvidedLocalizedSamples() {
        val result = StoragePathTemplate.expand("{docType}/{correspondent}/{tags}/{title}.pdf", PREVIEW_CONTEXT)
        result.pathOrNull shouldBe
            "Factuur/Voorbeeld Energie/nutsvoorzieningen, thuis/Elektriciteitsrekening.pdf"
    }

    @Test
    fun storagePathEditsWriteThroughTheLibrary() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            val model = settingsVm(session = session)
            model.setStoragePathTemplate("{docType}/{title}.pdf")
            model.setStoragePathEnabled(true)
            advanceUntilIdle()
            session.storagePath.storagePathFlow.value shouldBe
                StoragePathSetting("{docType}/{title}.pdf", enabled = true)
        }

    @Test
    fun aTemplateIsSavedOnceTypingPauses() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            val model = settingsVm(session = session)
            collecting(model.paneState)
            for (typed in listOf("{d", "{docType}", "{docType}/{title}.pdf")) {
                model.setStoragePathTemplate(typed)
                runCurrent()
                // The screen follows every keystroke before anything is saved.
                model.paneState.value.settings.storagePathTemplate shouldBe typed
            }
            session.storagePath.templateWrites shouldBe emptyList()

            advanceUntilIdle()

            session.storagePath.templateWrites shouldContainExactly listOf("{docType}/{title}.pdf")
            model.paneState.value.settings.storagePathTemplate shouldBe "{docType}/{title}.pdf"
        }

    /** A template whose save failed stays on screen as typed, and the failure is said. */
    @Test
    fun aTemplateWhoseSaveFailsStaysAsTyped() =
        runTest(dispatcher) {
            val session = FakeLibrarySession().apply { storagePath.failTemplate = { error("read-only file system") } }
            val model = settingsVm(session = session)
            collecting(model.paneState)
            val messages = collecting(model.messages.map { it.text })

            model.setStoragePathTemplate("{docType}/{title}.pdf")
            advanceUntilIdle()

            model.paneState.value.settings.storagePathTemplate shouldBe "{docType}/{title}.pdf"
            messages shouldContainExactly
                listOf(failure(Res.string.common_save_setting_failed, "read-only file system"))

            // Turning the rule on arms what the screen shows, so it is the way to try again.
            session.storagePath.failTemplate = null
            model.setStoragePathEnabled(true)
            advanceUntilIdle()
            session.storagePath.storagePathFlow.value shouldBe StoragePathSetting("{docType}/{title}.pdf", true)
            // Saved, it is no draft any more: the screen follows the library's template again.
            session.storagePath.storagePathFlow.value = StoragePathSetting("{title}.pdf", true)
            runCurrent()
            model.paneState.value.settings.storagePathTemplate shouldBe "{title}.pdf"
        }

    @Test
    fun enablingArmsTheTemplateTheScreenShows() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            val model = settingsVm(session = session)
            model.setStoragePathEnabled(true)
            advanceUntilIdle()
            session.storagePath.storagePathFlow.value shouldBe StoragePathSetting("{doc_date:yyyy}/{title}.pdf", true)
        }

    /**
     * Turning the rule on with a template that does not expand leaves it off and says why, in the
     * words the template field's own error uses.
     */
    @Test
    fun enablingAnInvalidTemplateSaysWhyTheRuleStaysOff() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            val model = settingsVm(session = session)
            val messages = collecting(model.messages.map { it.text })

            model.setStoragePathTemplate("{author}/{title}.pdf")
            model.setStoragePathEnabled(true)
            advanceUntilIdle()

            messages shouldContainExactly
                listOf(
                    uiText(
                        Res.string.common_settings_storage_rule_stays_off,
                        StoragePathError.UnknownVariable("author").text(),
                    ),
                )
            session.storagePath.storagePathFlow.value.enabled shouldBe false
        }

    private companion object {
        val PREVIEW_CONTEXT =
            StoragePathContext.sample(
                fieldTypes =
                    FieldKeys.seedDefinitions().associate { it.key to it.type } + ("amount" to FieldType.DECIMAL),
                title = "Elektriciteitsrekening",
                docType = "Factuur",
                correspondent = "Voorbeeld Energie",
                tags = listOf("thuis", "nutsvoorzieningen"),
            )
    }
}
