package dev.npn.core.diagnostics

/**
 * One user-initiated diagnostics bundle: what this device runs, what the open library holds as
 * counts, how cloud sync last fared, and the tail of the app log.
 *
 * Deliberately absent: document content, document names, document paths, library paths, email
 * addresses, API keys and tokens. A field added here must be safe to paste into a public issue;
 * anything free-form travels only in [logTail], which [Diagnostics.render] scrubs.
 */
data class DiagnosticsReport(
    val appVersion: String,
    /** Human-readable OS and device, e.g. "Linux 6.8.0 (amd64)" or "Android 14 (SDK 34) on Pixel 8". */
    val platform: String,
    /** The app's language tag, e.g. "en-US". */
    val locale: String,
    val library: LibrarySummary? = null,
    val cloudSync: CloudSyncSummary? = null,
    val ai: AiSummary? = null,
    /** Raw recent log text; [Diagnostics.render] scrubs it before it reaches the bundle. */
    val logTail: String = "",
)

/** Counts only: never a title, a path or a byte of document text. */
data class LibrarySummary(
    val liveDocuments: Int = 0,
    val trashedDocuments: Int = 0,
    val missingDocuments: Int = 0,
    val tags: Int = 0,
    val fields: Int = 0,
    val journalObjects: Int = 0,
    val ocrQueue: Int = 0,
    val enrichmentQueue: Int = 0,
    val editRuns: Int = 0,
    /** Documents the active review workflow still lists. */
    val reviewQueue: Int = 0,
)

/** Cloud sync health, without the remote's name or any credential. */
data class CloudSyncSummary(
    val configured: Boolean = false,
    val automatic: Boolean = false,
    val intervalMinutes: Int = 0,
    val running: Boolean = false,
    /** Null until a pass has run on this device. */
    val lastRunSucceeded: Boolean? = null,
    /** The failure enum's name, or null when the last pass succeeded or none ran. */
    val lastRunFailure: String? = null,
    val lastRunConflicts: Int = 0,
    val lastRunErrors: Int = 0,
)

/** Which provider and model are configured. Never the endpoint's key. */
data class AiSummary(
    val enabled: Boolean = false,
    val provider: String = "",
    val model: String = "",
)

/**
 * Renders and redacts a diagnostics bundle, and builds the prefilled GitHub issue for one.
 *
 * Redaction is defence in depth, not the only guarantee: [DiagnosticsReport] carries no document
 * text, names or library path in the first place. It exists because the log is free-form and a
 * failure message may quote a path, a file name or an endpoint's token.
 */
object Diagnostics {
    /** How much of the log a bundle carries, and its ceiling, so an issue attachment stays small. */
    const val MAX_LOG_BYTES: Long = 64 * 1024

    const val ISSUE_BASE = "https://github.com/jeroenev/NoPapersNeeded/issues/new"

    /** A stable file name for the bundle; the version is made safe for a file system. */
    fun bundleFileName(version: String): String {
        val safe = version.map { if (it.isLetterOrDigit() || it in ".-_") it else '-' }.joinToString("")
        return "npn-diagnostics-${safe.ifBlank { "unknown" }}.txt"
    }

    /** The complete, scrubbed text of the bundle. */
    fun render(
        report: DiagnosticsReport,
        secrets: List<String> = emptyList(),
    ): String =
        buildString {
            appendLine("# No Papers Needed diagnostics")
            appendLine()
            appendLine("Version: ${report.appVersion.trim().ifBlank { "unknown" }}")
            appendLine("Platform: ${report.platform.trim().ifBlank { "unknown" }}")
            appendLine("Locale: ${report.locale.trim().ifBlank { "unknown" }}")
            report.library?.let { library ->
                appendLine()
                appendLine("## Library")
                appendLine("Documents: ${library.liveDocuments}")
                appendLine("In trash: ${library.trashedDocuments}")
                appendLine("Missing: ${library.missingDocuments}")
                appendLine("Tags: ${library.tags}")
                appendLine("Fields: ${library.fields}")
                appendLine("Journal objects: ${library.journalObjects}")
                appendLine("OCR queue: ${library.ocrQueue}")
                appendLine("AI enrichment queue: ${library.enrichmentQueue}")
                appendLine("Queued AI edit runs: ${library.editRuns}")
                appendLine("Review queue: ${library.reviewQueue}")
            }
            report.cloudSync?.let { sync ->
                appendLine()
                appendLine("## Cloud sync")
                appendLine("Configured: ${if (sync.configured) "yes" else "no"}")
                appendLine("Automatic: ${if (sync.automatic) "yes" else "no"}")
                appendLine("Interval: ${sync.intervalMinutes} minutes")
                appendLine("Running now: ${if (sync.running) "yes" else "no"}")
                when (sync.lastRunSucceeded) {
                    null -> {
                        appendLine("Last pass: none yet")
                    }

                    true -> {
                        appendLine(
                            "Last pass: succeeded (${sync.lastRunConflicts} conflicts, ${sync.lastRunErrors} errors)",
                        )
                    }

                    false -> {
                        appendLine("Last pass: failed (${sync.lastRunFailure ?: "UNKNOWN"})")
                    }
                }
            }
            report.ai?.let { ai ->
                appendLine()
                appendLine("## AI assistant")
                appendLine("Enabled: ${if (ai.enabled) "yes" else "no"}")
                appendLine("Provider: ${ai.provider.trim().ifBlank { "not configured" }}")
                appendLine("Model: ${ai.model.trim().ifBlank { "not configured" }}")
            }
            appendLine()
            appendLine("## Recent log")
            appendLine()
            appendLine("```")
            append(sanitize(report.logTail, secrets).trimEnd())
            appendLine()
            appendLine("```")
        }

    /** The issue title, with nothing that identifies the user or their documents. */
    fun issueTitle(report: DiagnosticsReport): String =
        "Problem report: version ${report.appVersion.trim().ifBlank { "unknown" }} on " +
            report.platform.trim().ifBlank { "unknown" }

    /**
     * The issue body. It names the summary only; the user attaches the diagnostics file, so a
     * public issue never carries the log unless they choose to paste it.
     */
    fun issueBody(report: DiagnosticsReport): String =
        buildString {
            appendLine("<!-- Please describe what happened below. -->")
            appendLine()
            appendLine("### What happened")
            appendLine()
            appendLine()
            appendLine("### Steps to reproduce")
            appendLine()
            appendLine("1. ")
            appendLine()
            appendLine("### Environment")
            appendLine()
            appendLine("- App version: ${report.appVersion.trim().ifBlank { "unknown" }}")
            appendLine("- Platform: ${report.platform.trim().ifBlank { "unknown" }}")
            appendLine("- Locale: ${report.locale.trim().ifBlank { "unknown" }}")
            report.library?.let {
                appendLine(
                    "- Library: ${it.liveDocuments} documents, ${it.tags} tags, ${it.fields} fields, " +
                        "${it.journalObjects} journal objects",
                )
                appendLine(
                    "- Queues: ${it.ocrQueue} OCR, ${it.enrichmentQueue} AI enrichment, " +
                        "${it.editRuns} AI edit runs, ${it.reviewQueue} to review",
                )
            }
            report.cloudSync?.let {
                appendLine(
                    "- Cloud sync: ${if (it.configured) "configured" else "not configured"}, " +
                        "${if (it.automatic) "automatic" else "manual"}",
                )
                appendLine(
                    "- Last cloud pass: " +
                        when (it.lastRunSucceeded) {
                            null -> "none yet"
                            true -> "succeeded"
                            false -> "failed (${it.lastRunFailure ?: "UNKNOWN"})"
                        },
                )
            }
            report.ai?.let {
                appendLine(
                    "- AI assistant: ${if (it.enabled) "on" else "off"}, provider " +
                        "${it.provider.trim().ifBlank { "not configured" }}, model " +
                        "${it.model.trim().ifBlank { "not configured" }}",
                )
            }
            appendLine()
            appendLine("### Diagnostics")
            appendLine()
            appendLine(
                "Attach `${bundleFileName(report.appVersion)}`, which the app saved or shared for you. " +
                    "It holds counts and the recent log, with paths, document names, addresses and tokens removed.",
            )
        }

    fun issueUrl(report: DiagnosticsReport): String =
        "$ISSUE_BASE?title=${encodeQuery(issueTitle(report))}&body=${encodeQuery(issueBody(report))}"

    /**
     * Scrub [text] before it reaches the bundle: exact [secrets] first (the library path, the
     * installation id, the API key, the remote), then the shapes a log line may carry by itself.
     * Best effort by design; a log implementation must never throw into the failure path, and
     * neither does this.
     */
    fun sanitize(
        text: String,
        secrets: List<String> = emptyList(),
    ): String {
        // The bundle carries log entries, never arbitrary prose. A line that is neither ours nor a
        // stack trace could be document text that reached the log by accident, so drop it.
        var scrubbed = onlyLogLines(text)
        secrets
            .map(String::trim)
            .filter { it.length >= 4 }
            .sortedByDescending(String::length)
            .forEach { secret -> scrubbed = scrubbed.replace(secret, REDACTED) }
        scrubbed = replace(scrubbed, EMAIL, "<email>")
        // Tokens that carry their own name, with or without a separator.
        scrubbed =
            replace(scrubbed, NAMED_SECRET) { match ->
                "${match.groupValues[1]}=$REDACTED"
            }
        scrubbed = replace(scrubbed, BEARER, "Bearer $REDACTED")
        scrubbed = replace(scrubbed, JWT, REDACTED)
        scrubbed = replace(scrubbed, API_KEY_SHAPE, REDACTED)
        // URIs and paths: a log line names the library folder, watched folder or an imported file.
        scrubbed = replace(scrubbed, FILE_URI, "<path>")
        scrubbed = replace(scrubbed, URL_SHAPE, "<url>")
        scrubbed = replace(scrubbed, POSIX_PATH, "<path>")
        scrubbed = replace(scrubbed, WINDOWS_PATH, "<path>")
        // Document names and content hashes. A relative path goes before its file name, so folder
        // names (often correspondents) do not survive beside `<document>`.
        scrubbed = replace(scrubbed, RELATIVE_DOCUMENT_PATH, "<document>")
        scrubbed = replace(scrubbed, DOCUMENT_NAME, "<document>")
        scrubbed = replace(scrubbed, SHA256, "<hash>")
        return scrubbed
    }

    private fun onlyLogLines(text: String): String =
        text
            .lineSequence()
            .filter { line ->
                line.isBlank() ||
                    LOG_ENTRY.containsMatchIn(line) ||
                    EXCEPTION_LINE.containsMatchIn(line) ||
                    line.startsWith("Caused by:") ||
                    line.startsWith("Suppressed:") ||
                    DEBUG_MORE.matches(line.trim()) ||
                    // Only a stack frame is an indented line we keep; indented prose is document text.
                    line.trimStart().startsWith("at ")
            }.joinToString("\n")

    private fun replace(
        text: String,
        regex: Regex,
        replacement: String,
    ): String = replace(text, regex) { replacement }

    private fun replace(
        text: String,
        regex: Regex,
        replacement: (MatchResult) -> String,
    ): String =
        try {
            regex.replace(text, replacement)
        } catch (_: RuntimeException) {
            // Fail closed: a redaction that cannot run must not let the raw text through.
            ""
        }

    /** Percent-encode a query value; GitHub accepts `+` and `%xx`, and needs no locale. */
    private fun encodeQuery(value: String): String =
        buildString {
            value.encodeToByteArray().forEach { byte ->
                val unsigned = byte.toInt() and 0xFF
                val char = unsigned.toChar()
                when {
                    char.isLetterOrDigit() && unsigned < 128 -> {
                        append(char)
                    }

                    char in "-_.~" -> {
                        append(char)
                    }

                    char == ' ' -> {
                        append('+')
                    }

                    else -> {
                        append('%')
                        append(HEX[unsigned shr 4])
                        append(HEX[unsigned and 0x0F])
                    }
                }
            }
        }

    private const val REDACTED = "<redacted>"
    private const val HEX = "0123456789ABCDEF"

    /** `2026-11-22T10:00:00Z WARN message`, the shape [dev.npn.core.util.formatLogEntry] writes. */
    private val LOG_ENTRY = Regex("^\\d{4}-\\d{2}-\\d{2}T\\S+ (?:INFO|WARN|ERROR) ")
    private val DEBUG_MORE = Regex("^\\.\\.\\. \\d+ more$")
    private val EXCEPTION_LINE = Regex("^[A-Za-z_$][\\w.$]*(?:Exception|Error)(?::|$)")

    private const val EMAIL_LOCAL = "[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+"
    private val EMAIL = Regex("\\b$EMAIL_LOCAL@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)+\\b")
    private val NAMED_SECRET =
        Regex(
            "(?i)\\b(api[_-]?key|access[_-]?token|refresh[_-]?token|token|secret|password|passwd|authorization)\\b" +
                "\\s*[:=]\\s*(?:Bearer\\s+)?[\"']?[^\\s\"',;]+",
        )
    private val BEARER = Regex("(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]+")
    private val JWT = Regex("\\beyJ[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}\\b")
    private val API_KEY_SHAPE = Regex("\\b(?:sk|pk|rk|ghp|gho|ghu|ghs|ghr|xox[abprs])[-_][A-Za-z0-9_-]{10,}\\b")
    private val FILE_URI = Regex("(?i)\\b(?:file|content)://[^\\s\"'<>)]+")
    private val URL_SHAPE = Regex("(?i)\\b(?:https?|ftp|s3|webdav)://[^\\s\"'<>)]+")

    // An absolute path with at least one nested segment; `and/or` and a bare `/` stay readable.
    // Spaces inside a segment are allowed, so "My Documents/Steuerbescheid 2024.pdf" goes whole.
    private val POSIX_PATH = Regex("(?<![\\w<])/(?:[^/\\n\"'<>]+/)+[^/\\n\"'<>]+")
    private val WINDOWS_PATH = Regex("(?i)\\b[A-Z]:\\\\[^\\n\"'<>|]+")

    /** Extensions a scanned or imported source can carry; the whole name is a document name. */
    private const val DOCUMENT_EXTENSIONS = "pdf|jpe?g|png|heic|tiff?|webp|bmp"

    /** `Inbox/Acme/Invoice.pdf`, a library-relative path: the folders often name a correspondent. */
    private val RELATIVE_DOCUMENT_PATH =
        Regex("(?i)(?:[^\\s/\\\\][^/\\\\\\n]*?/)+[^\\s/\\\\][^/\\\\\\n]*?\\.(?:$DOCUMENT_EXTENSIONS)\\b")

    /** `Müller Bescheid.pdf` and `IMG_20241122_1234.jpg`, ASCII or not, spaces included. */
    private val DOCUMENT_NAME = Regex("(?i)(?<![\\w/\\\\])[^\\s/\\\\][^/\\\\\\n]*?\\.(?:$DOCUMENT_EXTENSIONS)\\b")

    private val SHA256 = Regex("\\b[0-9a-f]{64}\\b")
}
