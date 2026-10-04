package dev.npn.core.ocr

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Desktop [OcrEngine] that shells out to the `tesseract` CLI. Detects the binary once, on the
 * first availability check. If tesseract is not on PATH, [available] is false and the pipeline
 * skips OCR for text-less pages. No JNI/native bindings, so it degrades gracefully.
 *
 * [tessdataDir], when set, is passed to the process as `TESSDATA_PREFIX`: a bundled binary
 * (Windows builds ship one, see `desktopApp/build.gradle.kts`) must read its own language data,
 * not whatever a separate install on the machine points that variable at.
 */
class TesseractCliEngine(
    private val binary: String = "tesseract",
    private val timeoutSeconds: Long = 60,
    private val tessdataDir: String? = null,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : OcrEngine {
    override val name: String = OcrEngines.TESSERACT

    /** Probed once; the binary does not appear or disappear over a session's lifetime. */
    private val detected: Boolean by lazy { query("--version") != null }

    /** The last [probeLanguages] result; null until the first one. */
    @Volatile
    private var installedLanguages: Set<String>? = null

    override suspend fun available(): Boolean = detected

    override suspend fun languagesFor(lang: String): String = withContext(io) { usableLanguages(lang) }

    /**
     * [lang] without the codes that have no installed model: Tesseract refuses the whole run when
     * one is missing, and the automatic setting asks for the app language's model, which a Linux
     * install with only English would lack. Unchanged when nothing requested is installed, or the
     * list is unknown, so Tesseract's own error still applies then.
     */
    private fun usableLanguages(lang: String): String {
        val requested = lang.split('+').filter(String::isNotBlank)
        var installed = installedLanguages ?: probeLanguages()
        // A model can be installed while the app runs, so look again before skipping one. Only a
        // request with a missing model pays for that, a short process next to a page of OCR.
        if (requested.any { it !in installed }) installed = probeLanguages()
        if (installed.isEmpty()) return lang
        val kept = requested.filter { it in installed }
        return if (kept.isEmpty()) lang else kept.joinToString("+")
    }

    /**
     * Models this Tesseract has, from `--list-langs` (a header line, then one code per line);
     * empty when it cannot say.
     */
    private fun probeLanguages(): Set<String> =
        query("--list-langs")
            ?.lines()
            ?.drop(1)
            ?.map(String::trim)
            ?.filter { it.isNotEmpty() && ' ' !in it }
            ?.toSet()
            .orEmpty()
            .also { installedLanguages = it }

    /**
     * Runs `tesseract <args>` briefly; its combined output on success, else null. Older releases
     * print the probe answers on stderr, hence combined.
     */
    private fun query(vararg args: String): String? =
        try {
            exec(args.toList(), QUERY_TIMEOUT_SECONDS, mergeStderr = true)
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }

    /**
     * Run `tesseract <args>` to completion and return its stdout, or throw: on a start failure, a
     * non-zero exit (with the tail of stderr, which says why) or when it outlives
     * [timeoutSeconds].
     *
     * Output goes to temp files rather than pipes, so a process that writes more than a pipe holds
     * cannot stall waiting for a reader that is itself waiting for the exit. Blocking, and
     * interruptible while it waits: an interrupt, like a timeout, kills the process before this
     * returns, so no run outlives its caller.
     */
    private fun exec(
        args: List<String>,
        timeoutSeconds: Long,
        mergeStderr: Boolean,
    ): String {
        val out = File.createTempFile("npn-tess-", ".out")
        val err = File.createTempFile("npn-tess-", ".err")
        var proc: Process? = null
        try {
            val builder = process(listOf(binary) + args).redirectOutput(out)
            if (mergeStderr) builder.redirectErrorStream(true) else builder.redirectError(err)
            val started = builder.start().also { proc = it }
            if (!started.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                throw IOException("tesseract timed out after $timeoutSeconds s")
            }
            val exit = started.exitValue()
            if (exit != 0) {
                val why = (if (mergeStderr) out else err).readText().trim().takeLast(MAX_ERROR_CHARS)
                throw IOException("tesseract exited with $exit: $why")
            }
            return out.readText()
        } finally {
            proc?.takeIf { it.isAlive }?.let(::kill)
            out.delete()
            err.delete()
        }
    }

    /** Kill [proc] and anything it started (a wrapper script's real binary), and wait for it. */
    private fun kill(proc: Process) {
        proc.descendants().forEach { it.destroyForcibly() }
        proc.destroyForcibly()
        try {
            proc.waitFor(KILL_WAIT_SECONDS, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun process(command: List<String>): ProcessBuilder =
        ProcessBuilder(command).apply {
            tessdataDir?.let { environment()["TESSDATA_PREFIX"] = it }
        }

    /**
     * Throws rather than answering `""` on any failure, including no binary at all: an empty
     * string is a page with no text on it, and the pipeline would publish it as one.
     */
    override suspend fun recognizePng(
        png: ByteArray,
        lang: String,
    ): String =
        withContext(io) {
            if (!detected) throw IOException("tesseract is not available")
            val temp = File.createTempFile("npn-ocr-", ".png")
            try {
                temp.writeBytes(png)
                val langs = usableLanguages(lang)
                // `tesseract <input> stdout -l <lang>` prints recognized text to stdout.
                runInterruptible {
                    exec(listOf(temp.absolutePath, "stdout", "-l", langs), timeoutSeconds, mergeStderr = false)
                }.trim()
            } finally {
                temp.delete()
            }
        }

    private companion object {
        const val QUERY_TIMEOUT_SECONDS = 10L
        const val KILL_WAIT_SECONDS = 5L

        /** Enough of stderr to name the problem without storing a whole log as the error. */
        const val MAX_ERROR_CHARS = 300
    }
}
