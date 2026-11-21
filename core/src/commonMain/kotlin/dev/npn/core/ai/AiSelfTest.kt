package dev.npn.core.ai

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.params.LLMParams
import dev.npn.core.util.reason
import dev.npn.core.util.runCatchingCancellable
import kotlinx.coroutines.CancellationException
import kotlin.io.encoding.Base64

/**
 * The three things the settings self-test proves about a configured endpoint, in the order they
 * are attempted. Each one is a strictly larger claim than the one before it, which is why a
 * failure stops the run: a model that cannot answer "pong" will not read a page either, and the
 * first failure is the only actionable one.
 */
enum class AiCheck {
    /** The server is reachable and accepts the credentials. Costs nothing (model list only). */
    API,

    /** The configured model answers a prompt. One tiny paid request. */
    MESSAGES,

    /** The configured model reads text off an image — what AI page reading needs. */
    VISION,
}

enum class AiCheckStatus { PASSED, FAILED, SKIPPED }

/**
 * One check's outcome. [detail] is populated only for [AiCheckStatus.FAILED] and holds the reason
 * in the endpoint's own words (an error message, or the answer that was wrong) — it is diagnostic
 * text, deliberately not localized, because it is quoted from the server.
 */
data class AiCheckOutcome(
    val check: AiCheck,
    val status: AiCheckStatus,
    val detail: String? = null,
)

/** Result of one [AiSelfTest] run: exactly one outcome per [AiCheck], in [AiCheck] order. */
data class AiSelfTestReport(
    val outcomes: List<AiCheckOutcome>,
) {
    fun statusOf(check: AiCheck): AiCheckStatus = outcomes.first { it.check == check }.status

    /**
     * Whether this run is grounds for switching [AiConfig.ocrAuto] off.
     *
     * Only a vision check that actually RAN and failed counts. A skipped one proves nothing — the
     * endpoint was unreachable, which is a temporary condition, and silently turning off a setting
     * the user paid attention to because their wifi was down would be worse than leaving it on.
     * The inverse is deliberately not offered: a passing check never switches the setting back on,
     * because "this model can read images" is not a reason to spend a request per page.
     */
    val provesAiOcrCannotWork: Boolean
        get() = statusOf(AiCheck.VISION) == AiCheckStatus.FAILED
}

/**
 * Diagnoses a configured AI endpoint by actually using it, so "why doesn't the assistant work?"
 * has an answer that does not require reading a log: the server, the model and the model's vision
 * are three separate things that fail separately, and the settings screen can only ever show the
 * user what was tried.
 *
 * Runs against a short-lived runtime of its own (like [AiModelCatalog]) rather than the facade's
 * cached one, so a diagnosis never disturbs in-flight work.
 */
object AiSelfTest {
    /** Word the [AiCheck.MESSAGES] probe asks for; matching it proves an answer, not just a 200. */
    private const val MESSAGE_TOKEN = "pong"

    /** Digits drawn in [PROBE_PAGE_PNG_BASE64]. Unguessable enough that a blind model cannot pass. */
    private const val PROBE_PAGE_CODE = "7482"

    /** How much of a wrong answer or error message is worth showing in settings. */
    private const val MAX_DETAIL_CHARS = 300

    suspend fun run(config: AiConfig): AiSelfTestReport =
        runCatchingCancellable {
            AiRuntime.build(config).use { run(EndpointProbes(it, config)) }
        }.getOrElse { e ->
            // The runtime could not even be built (an unusable base URL, an unmappable model):
            // nothing was attempted, so every check reports as the same single failure.
            AiSelfTestReport(
                listOf(
                    AiCheckOutcome(AiCheck.API, AiCheckStatus.FAILED, e.detail()),
                    AiCheckOutcome(AiCheck.MESSAGES, AiCheckStatus.SKIPPED),
                    AiCheckOutcome(AiCheck.VISION, AiCheckStatus.SKIPPED),
                ),
            )
        }

    /**
     * The run order and the conclusions drawn from it, over injectable [probes] so both are
     * testable without an endpoint. A check that fails stops the run: everything after it would
     * report the same failure, and each later check costs a paid request.
     */
    internal suspend fun run(probes: AiProbes): AiSelfTestReport {
        val api =
            attempt(AiCheck.API) {
                probes.listModels()
                null
            }
        if (api.status == AiCheckStatus.FAILED) return report(api, skipped(AiCheck.MESSAGES), skipped(AiCheck.VISION))

        val messages =
            attempt(AiCheck.MESSAGES) {
                val answer = probes.answerMessage()
                answer.takeUnless { it.contains(MESSAGE_TOKEN, ignoreCase = true) }
            }
        if (messages.status == AiCheckStatus.FAILED) return report(api, messages, skipped(AiCheck.VISION))

        val vision =
            attempt(AiCheck.VISION) {
                val answer = probes.readProbePage()
                answer.takeUnless { it.filter(Char::isDigit).contains(PROBE_PAGE_CODE) }
            }
        return report(api, messages, vision)
    }

    /**
     * Run one probe. It passes when it returns null; any other return value is the wrong answer
     * that made it fail, and a throw is the endpoint failing. [CancellationException] is never a
     * result — the caller navigated away, so the run leaves rather than reporting a verdict.
     */
    private suspend fun attempt(
        check: AiCheck,
        probe: suspend () -> String?,
    ): AiCheckOutcome =
        runCatchingCancellable {
            probe()?.let { AiCheckOutcome(check, AiCheckStatus.FAILED, it.trim().take(MAX_DETAIL_CHARS)) }
                ?: AiCheckOutcome(check, AiCheckStatus.PASSED)
        }.getOrElse { AiCheckOutcome(check, AiCheckStatus.FAILED, it.detail()) }

    private fun skipped(check: AiCheck) = AiCheckOutcome(check, AiCheckStatus.SKIPPED)

    private fun report(vararg outcomes: AiCheckOutcome) = AiSelfTestReport(outcomes.toList())

    private fun Throwable.detail(): String = reason().trim().take(MAX_DETAIL_CHARS)

    /**
     * A 420x140 one-bit PNG of the digits [PROBE_PAGE_CODE], embedded rather than rendered so the
     * vision check sends the exact same bytes on every platform and the expected answer is known.
     * 650 bytes on the wire — small enough to be a rounding error on the request it rides in.
     */
    private const val PROBE_PAGE_PNG_BASE64 =
        "iVBORw0KGgoAAAANSUhEUgAAAaQAAACMAQAAAAAbv+B3AAACUUlEQVR4Xu3WTY7bMAwFYAZZeOkb" +
            "1DcZX6uLotbRdBQdwUsvXKt8lNJQMp0oXRWFHgaZjKMvtn5IDMW/yEr1lZZ0pdOVTlc6Xel0pdOV" +
            "Tlc6Xen8OyoM/LKdh5zUThwMpikeREuMnt9VuVQHj90oUXxJkUu1MwhEI+itGvNaebxb+e9qzLXa" +
            "WDmiOyhmV+RSrawID8eU5mrQO0XyMlWDLlWg6aAhkNxrqgbZapRd4sVfafE3TLHMSSHuoeaNljAe" +
            "+JYipsJ3O5p2Wvgn8PyaFT3UurQpHKan2tITF7HVHI/bQ/1qVDur/Y+KzWqJ+13W8APFyx23pFJF" +
            "Niq+PuRdxoVmFQacwzHIx00rv3IVhhHq7qUgm5WfHvWV968cYajAyn2jXMvNim/gvij3jbR/ZSzl" +
            "WdFPVmuqrK1NDfxQUFvqGNi/MpZyUJjMkXra2qZGPoZQe7pX2jQdS6F1isrzSpumc6XuUHkNHTat" +
            "iKn4LgNU3i95KWIouYsoJy3+3GxsNUc0JpxDd0sdv4qhcBQ8yoRv4il1/CqmWqKD2qWL8pC5HmIo" +
            "HAW0afqBWp6N7bpSQPQd3WY2tstSuJSVrMx5u1rUebsshaJMCm+wkvWIBmVsl6lQlE91rklTSSk/" +
            "1bm6bDU8V0NUPcBUTtbswLNhDTHNOq/VwspT6oo6hkorLQonyn2odvT5BTN8r1BY6Zf8E/GxOgBk" +
            "Od+r3J+lltEBPlYO4z9WHj2qTeWuDiVdtE3lcwclHbtN6eBIWnmtrtKVTlc6Xel0pdOVTlc6Xen8" +
            "t+o3RBOOz2kVKKMAAAAASUVORK5CYII="

    private val PROBE_PAGE_PNG: ByteArray by lazy { Base64.decode(PROBE_PAGE_PNG_BASE64) }

    /**
     * The three endpoint calls a run makes. An interface rather than three lambdas because the
     * order they are called in — and what is skipped after a failure — is the part worth testing,
     * and it is only testable if the calls can be faked.
     */
    internal interface AiProbes {
        /** Reachability and credentials. Must throw when the endpoint refuses. */
        suspend fun listModels(): List<String>

        /** The configured model's answer to a one-word prompt. */
        suspend fun answerMessage(): String

        /** The configured model's reading of an image with known text on it. */
        suspend fun readProbePage(): String
    }

    /**
     * The real probes. Each one deliberately mirrors the calls it is standing in for: the message
     * probe asks with the assistant's params ([AiRuntime.chatParams]), and again with the AI edits'
     * ([AiRuntime.suggestionParams]) when their reasoning levels differ, and the page probe uses the
     * OCR ones ([AiRuntime.ocrParams]) and its image attachment ([AiPrompts.pageImage]), so a
     * reasoning level or a vision capability that breaks a real feature breaks the check too.
     */
    private class EndpointProbes(
        private val runtime: AiRuntime,
        private val config: AiConfig,
    ) : AiProbes {
        override suspend fun listModels(): List<String> = runtime.listModelIds()

        override suspend fun answerMessage(): String {
            val chat = ask(AiRuntime.chatParams(config) ?: LLMParams())
            if (config.reasoningEdit == config.reasoningAssistant || !chat.contains(MESSAGE_TOKEN, ignoreCase = true)) {
                return chat
            }
            return ask(AiRuntime.suggestionParams(config))
        }

        private suspend fun ask(params: LLMParams): String {
            val thePrompt =
                prompt("npn-selftest-message", params) {
                    system("You are a connectivity check. Answer with a single word and nothing else.")
                    user("Reply with the word: $MESSAGE_TOKEN")
                }
            return runtime.executor.execute(thePrompt, runtime.model).textContent()
        }

        override suspend fun readProbePage(): String {
            val thePrompt =
                prompt("npn-selftest-page", AiRuntime.ocrParams(config)) {
                    system("You read text off images. Answer with the characters you see and nothing else.")
                    user {
                        text("Which digits are shown in this image?")
                        image(AiPrompts.pageImage(PROBE_PAGE_PNG))
                    }
                }
            return runtime.executor.execute(thePrompt, runtime.model).textContent()
        }
    }
}
