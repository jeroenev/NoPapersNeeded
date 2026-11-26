package dev.npn.core.app

import dev.npn.core.einvoice.EInvoiceFixtures
import dev.npn.core.model.FieldDef
import dev.npn.core.model.FieldKeys
import dev.npn.core.model.FieldType
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotContainAnyOf
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.first
import kotlin.test.Test

/**
 * The e-invoice fields are a library setting, off for a new library: turning it on adds the eight
 * definitions and lets an import read an embedded invoice; turning it off only stops the reading,
 * and a library that already has the fields counts as on until somebody decides.
 */
class EInvoiceFieldsSettingTest {
    @Test
    fun aNewLibraryHasNeitherTheFieldsNorTheReading() =
        appSessionTest { rig ->
            val registry = rig.session.fieldRegistry
            registry.eInvoiceFields.first() shouldBe false
            registry.current().map { it.key } shouldNotContainAnyOf FieldKeys.EINVOICE_FIELDS

            val imported = importInvoice(rig)

            rig.db
                .documentFieldDao()
                .valuesOf(imported.docId) shouldBe emptyMap()
        }

    @Test
    fun turningItOnAddsTheFieldsAfterTheLibrarysOwnAndReadsInvoices() =
        appSessionTest { rig ->
            val registry = rig.session.fieldRegistry
            registry.upsert(FieldDef("renewal", "Renewal", FieldType.DATE, false, false, FieldDef.PLACE_LAST))

            registry.setEInvoiceFields(true)

            registry.eInvoiceFields.first() shouldBe true
            val defs = registry.current()
            defs.map { it.key } shouldContainAll FieldKeys.EINVOICE_FIELDS
            val renewal = defs.single { it.key == "renewal" }.position
            defs.filter { it.key in FieldKeys.EINVOICE_FIELDS }.forEach { (it.position > renewal) shouldBe true }
            val imported = importInvoice(rig)
            rig.db.documentFieldDao().valuesOf(imported.docId)[FieldKeys.INVOICE_NUMBER] shouldBe "INV-2024-0042"
        }

    @Test
    fun turningItOffKeepsTheFieldsAndTheirValuesAndStopsTheReading() =
        appSessionTest { rig ->
            val registry = rig.session.fieldRegistry
            registry.setEInvoiceFields(true)
            val first = importInvoice(rig)

            registry.setEInvoiceFields(false)

            registry.eInvoiceFields.first() shouldBe false
            registry.current().map { it.key } shouldContainAll FieldKeys.EINVOICE_FIELDS
            rig.db.documentFieldDao().valuesOf(first.docId)[FieldKeys.INVOICE_NUMBER] shouldBe "INV-2024-0042"
            val second = importInvoice(rig) { it.replace("INV-2024-0042", "INV-2024-0043") }
            rig.db
                .documentFieldDao()
                .valuesOf(second.docId)[FieldKeys.INVOICE_NUMBER]
                .shouldBeNull()
        }

    /** A library an earlier build seeded at 3 holds the fields without the setting: it reads on. */
    @Test
    fun aLibraryThatAlreadyHasTheFieldsCountsAsOn() =
        appSessionTest { rig ->
            val registry = rig.session.fieldRegistry
            FieldKeys.eInvoiceDefinitions().forEach { registry.upsert(it) }

            registry.eInvoiceFields.first() shouldBe true
            val imported = importInvoice(rig)
            rig.db.documentFieldDao().valuesOf(imported.docId)[FieldKeys.INVOICE_NUMBER] shouldBe "INV-2024-0042"
        }

    @Test
    fun theSettingIsTheLibrarysOnEveryDevice() =
        appSessionTest { rig ->
            rig.session.fieldRegistry.setEInvoiceFields(true)
            rig.session.fieldRegistry.setEInvoiceFields(false)

            val (second, _) = rig.secondSession(backgroundScope)

            second.fieldRegistry.eInvoiceFields.first() shouldBe false
            second.fieldRegistry.current().map { it.key } shouldContainAll FieldKeys.EINVOICE_FIELDS
        }

    private suspend fun importInvoice(
        rig: AppSessionRig,
        edit: (String) -> String = { it },
    ): ImportOutcome.Imported =
        rig.session
            .importPdfBytes(EInvoiceFixtures.pdf("factur-x.xml", "cii.xml", edit), "invoice.pdf")
            .shouldBeInstanceOf<ImportOutcome.Imported>()
}
