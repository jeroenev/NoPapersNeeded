package dev.npn.core.diagnostics

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlin.test.Test

class DiagnosticsTest {
    private val report =
        DiagnosticsReport(
            appVersion = "0.1.0",
            platform = "Linux 6.8.0 (amd64)",
            locale = "en-US",
            library =
                LibrarySummary(
                    liveDocuments = 12,
                    trashedDocuments = 2,
                    missingDocuments = 1,
                    tags = 7,
                    fields = 5,
                    journalObjects = 42,
                    ocrQueue = 3,
                    enrichmentQueue = 2,
                    editRuns = 1,
                    reviewQueue = 4,
                ),
            cloudSync =
                CloudSyncSummary(
                    configured = true,
                    automatic = true,
                    intervalMinutes = 15,
                    lastRunSucceeded = false,
                    lastRunFailure = "REMOTE_UNREACHABLE",
                ),
            ai = AiSummary(enabled = true, provider = "OPENAI", model = "gpt-4o-mini"),
            logTail =
                """
                2026-11-22T10:00:00Z INFO Importing Tax return 2024.pdf from /home/alice/Documents/My Library
                2026-11-22T10:01:00Z WARN Cloud sync: alice@example.com rejected
                2026-11-22T10:02:00Z WARN Importing IMG_20241122_1234.jpg failed
                2026-11-22T10:03:00Z WARN Hashing Inbox/Acme/Invoice.pdf failed
                2026-11-22T10:04:00Z WARN Reading C:\\Users\\bob\\Documents\\Tax.pdf failed
                2026-11-22T10:05:00Z WARN Importing Résumé.pdf failed
                2026-11-22T10:06:00Z WARN Importing Müller Bescheid.pdf failed
                    at dev.npn.core.sync.SyncEngine.run(SyncEngine.kt:42)
                    indented document text
                Authorization: Bearer sk-live-abcdefghijklmnop
                Authorization: Bearer generic-token-123456
                apiKey=sk-abcdefghijklmnop
                JWT ${listOf(
                    "eyJhbGciOiJIUzI1NiJ9",
                    "eyJzdWIiOiIxMjM0NTY3ODkwIn0",
                    "dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U",
                ).joinToString(".")}
                The quick brown fox jumps over the lazy dog.
                """.trimIndent(),
        )

    @Test
    fun bundleCarriesTheSummaryItIsFor() {
        val text = Diagnostics.render(report)

        text shouldContain "Version: 0.1.0"
        text shouldContain "Platform: Linux 6.8.0 (amd64)"
        text shouldContain "Locale: en-US"
        text shouldContain "Documents: 12"
        text shouldContain "Journal objects: 42"
        text shouldContain "OCR queue: 3"
        text shouldContain "Review queue: 4"
        text shouldContain "Last pass: failed (REMOTE_UNREACHABLE)"
        text shouldContain "Provider: OPENAI"
        text shouldContain "Model: gpt-4o-mini"
    }

    @Test
    fun documentNamesPathsAddressesAndTokensNeverReachTheBundle() {
        val text = Diagnostics.render(report, secrets = listOf("/home/alice/Documents/My Library"))

        text shouldNotContain "Tax return 2024"
        text shouldNotContain "/home/alice"
        text shouldNotContain "alice@example.com"
        text shouldNotContain "sk-live"
        text shouldNotContain "sk-abcdefghijklmnop"
        text shouldNotContain "generic-token-123456"
        text shouldNotContain "eyJhbGciOiJIUzI1NiJ9"
        text shouldNotContain "IMG_20241122_1234"
        text shouldNotContain "Inbox/Acme"
        text shouldNotContain "C:\\Users\\bob"
        text shouldNotContain "Résumé"
        text shouldNotContain "Müller"
        // The offending lines are still recognisably ours, with the sensitive parts gone.
        text shouldContain "<document>"
        text shouldContain "<email>"
        text shouldContain "<redacted>"
    }

    @Test
    fun proseThatIsNotALogEntryIsDropped() {
        val text = Diagnostics.render(report)

        // A document's own text, if it ever leaked into the tail, is not written as a log entry.
        text shouldNotContain "The quick brown fox"
        text shouldNotContain "indented document text"
        // But the stack trace that belongs to the warning survives.
        text shouldContain "at dev.npn.core.sync.SyncEngine.run"
    }

    @Test
    fun retainedEntriesRedactCredentialsAndNamesWithSpacesAndUnicode() {
        val text =
            Diagnostics.sanitize(
                """
                2026-11-22T10:00:00Z WARN Authorization: Bearer opaqueCredential123456
                2026-11-22T10:00:01Z WARN /home/alice/Private Records/invoice.pdf
                2026-11-22T10:00:02Z WARN Private Records/Invoice.pdf
                2026-11-22T10:00:03Z WARN 税务.pdf
                2026-11-22T10:00:04Z WARN Impôts.pdf
                    Confidential document text
                    at confidential document text
                    at dev.npn.Example.run(Example.kt:42)
                """.trimIndent(),
            )

        listOf(
            "opaqueCredential123456",
            "alice",
            "Private Records",
            "invoice",
            "Invoice",
            "税务",
            "Impôts",
            "Confidential document",
            "at confidential",
        ).forEach {
            text shouldNotContain it
        }
        text shouldContain "at dev.npn.Example.run(Example.kt:42)"
    }

    @Test
    fun aDocumentNameIsScrubbedWithoutTheTimestampLevelOrAction() {
        val text =
            Diagnostics.sanitize(
                """
                2026-11-22T10:06:00Z WARN Importing Müller Bescheid.pdf failed
                2026-11-22T10:00:00Z INFO Importing Tax return 2024.pdf from /home/alice/Documents/My Library
                2026-11-22T10:03:00Z WARN Hashing Inbox/Acme/Invoice.pdf failed
                2026-11-22T10:03:30Z WARN Hashing Private Records/Scan (2).pdf failed; it is left as it was
                2026-11-22T10:04:00Z WARN Cloud sync: Steuer 2024/Bescheid.pdf
                2026-11-22T10:05:00Z WARN Impôts.pdf
                java.nio.file.NoSuchFileException: Kontoauszug März.pdf
                Caused by: java.io.IOException: Cannot read Arztbrief.pdf
                """.trimIndent(),
            )

        text shouldBe
            """
            2026-11-22T10:06:00Z WARN Importing <document> failed
            2026-11-22T10:00:00Z INFO Importing <document> from <path>
            2026-11-22T10:03:00Z WARN Hashing <document> failed
            2026-11-22T10:03:30Z WARN Hashing <document> failed; it is left as it was
            2026-11-22T10:04:00Z WARN Cloud sync: <document>
            2026-11-22T10:05:00Z WARN <document>
            java.nio.file.NoSuchFileException: <document>
            Caused by: java.io.IOException: <document>
            """.trimIndent()
    }

    /**
     * Text no call site marked (a library's or the JDK's exception message, or an entry that is one)
     * loses the whole path, folders included: a folder is user data. An entry keeps its timestamp,
     * level and action.
     */
    @Test
    fun unmarkedPathsGoWholeFoldersIncluded() {
        val text =
            Diagnostics.sanitize(
                """
                2026-11-22T10:00:00Z WARN Private Records/Invoice.pdf
                java.io.IOException: Private Records/Invoice.pdf (No such file or directory)
                2026-11-22T10:01:00Z WARN Hashing Steuer Unterlagen/Bank Auszüge 2024/März Abrechnung.pdf failed
                2026-11-22T10:02:00Z WARN Reading C:\Users\bob\My Documents\Tax Return.pdf failed
                java.nio.file.AccessDeniedException: C:\Users\bob\Private Records
                Caused by: java.io.IOException: Private Records\Arzt Briefe\Befund.pdf
                2026-11-22T10:03:00Z WARN Reading /home/alice/My Docs/Tax.pdf failed
                2026-11-22T10:04:00Z WARN Restoring failed: Steuer 2024/Alte Briefe was deleted meanwhile
                java.nio.file.NoSuchFileException: Private Records/Arzt
                2026-11-22T10:05:00Z WARN Private Records/Arzt
                2026-11-22T10:06:00Z WARN Rendering page 3/10 failed
                java.io.IOException: unexpected status HTTP/1.1 500 for application/json
                """.trimIndent(),
            )

        text shouldBe
            """
            2026-11-22T10:00:00Z WARN <document>
            java.io.IOException: <document> (No such file or directory)
            2026-11-22T10:01:00Z WARN Hashing <document> failed
            2026-11-22T10:02:00Z WARN Reading <path> failed
            java.nio.file.AccessDeniedException: <path>
            Caused by: java.io.IOException: <document>
            2026-11-22T10:03:00Z WARN Reading <path> failed
            2026-11-22T10:04:00Z WARN Restoring failed: <path>
            java.nio.file.NoSuchFileException: <path>
            2026-11-22T10:05:00Z WARN <path>
            2026-11-22T10:06:00Z WARN Rendering page 3/10 failed
            java.io.IOException: unexpected status HTTP/1.1 500 for application/json
            """.trimIndent()
    }

    @Test
    fun jdkAndConstructorFramesStayInTheTrace() {
        val trace =
            """
            2026-11-22T10:00:00Z ERROR Uncaught exception on main
            java.lang.IllegalStateException: boom
                at dev.npn.core.app.Foo.<init>(Foo.kt:12)
                at java.base/java.lang.Thread.run(Thread.java:1583)
                at app//dev.npn.desktop.MainKt.main(Main.kt:40)
                at java.base/jdk.internal.reflect.DirectMethodHandleAccessor.invoke(Unknown Source)
                ... 3 more
            """.trimIndent()

        Diagnostics.sanitize(trace) shouldBe trace
    }

    @Test
    fun issueUrlPrefillsTitleAndEnvironmentButNotTheLog() {
        val url = Diagnostics.issueUrl(report)

        url shouldContain Diagnostics.ISSUE_BASE
        url shouldContain "title=Problem+report"
        url shouldContain "App+version"
        url shouldContain "Library"
        url shouldNotContain "Tax+return"
        url shouldNotContain "alice"
        url shouldNotContain "sk-live"
    }

    @Test
    fun bundleFileNameIsSafeForEveryVersion() {
        Diagnostics.bundleFileName("0.1.0") shouldContain "npn-diagnostics-0.1.0.txt"
        Diagnostics.bundleFileName("1.0 beta/2") shouldContain "npn-diagnostics-1.0-beta-2.txt"
    }
}
