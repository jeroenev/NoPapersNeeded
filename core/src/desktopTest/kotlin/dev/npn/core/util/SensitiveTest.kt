package dev.npn.core.util

import io.kotest.matchers.shouldBe
import kotlin.test.Test

class SensitiveTest {
    @Test
    fun aMarkedValueRendersAsItsPlaceholder() {
        val name = "Müller Bescheid.pdf"

        "Importing ${sensitive(name, Sensitive.Kind.DOCUMENT)} failed" shouldBe "Importing <document> failed"
        "Tagging with ${sensitive("Health", Sensitive.Kind.TAG)}" shouldBe "Tagging with <tag>"
        "${sensitive("Inbox/Acme", Sensitive.Kind.FOLDER)} was deleted" shouldBe "<folder> was deleted"
        "Reading ${sensitive("/home/alice/settings.json", Sensitive.Kind.PATH)}" shouldBe "Reading <path>"
        "Titled ${sensitive("Jane Doe")}" shouldBe "Titled <name>"
    }

    @Test
    fun aLoggedMessageCarriesThePlaceholder() {
        val log = RecordingLog()

        log.warn("Importing ${sensitive("Arztbrief.pdf", Sensitive.Kind.DOCUMENT)} failed")

        log.entries.single().message shouldBe "Importing <document> failed"
    }
}
