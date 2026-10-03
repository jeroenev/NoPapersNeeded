package dev.npn.core.ocr

import kotlinx.coroutines.Dispatchers
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
) : OcrEngine {
    override val name: String = OcrEngines.TESSERACT

    /** Probed once; the binary does not appear or disappear over a session's lifetime. */
    private val detected: Boolean by lazy { query("--version") != null }

    /** The last [probeLanguages] result; null until the first one. */
    @Volatile
    private var installedLanguages: Set<String>? = null

    override suspend fun available(): Boolean = detected

    override suspend fun languagesFor(lang: String): String = withContext(Dispatchers.IO) { usableLanguages(lang) }

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

    private fun process(vararg command: String): ProcessBuilder =
        ProcessBuilder(*command).apply {
            tessdataDir?.let { environment()["TESSDATA_PREFIX"] = it }
        }

    /** Runs `tesseract <args>` briefly; its combined output on success, else null. */
    private fun query(vararg args: String): String? =
        try {
            val proc =
                process(binary, *args)
                    .redirectErrorStream(true)
                    .start()
            val output = proc.inputStream.readBytes().decodeToString()
            val finished = proc.waitFor(10, TimeUnit.SECONDS)
            if (!finished) {
                proc.destroyForcibly()
                null
            } else {
                output.takeIf { proc.exitValue() == 0 }
            }
        } catch (_: Throwable) {
            null
        }

    override suspend fun recognizePng(
        png: ByteArray,
        lang: String,
    ): String =
        withContext(Dispatchers.IO) {
            if (!detected) return@withContext ""
            val temp = File.createTempFile("npn-ocr-", ".png")
            try {
                temp.writeBytes(png)
                // `tesseract <input> stdout -l <lang>` prints recognized text to stdout.
                val proc =
                    process(binary, temp.absolutePath, "stdout", "-l", usableLanguages(lang))
                        .redirectErrorStream(false)
                        .start()

                // Drain stderr on a separate thread so it cannot block the pipe.
                val stderrDrain =
                    Thread {
                        try {
                            proc.errorStream.readBytes()
                        } catch (_: IOException) {
                        }
                    }.apply {
                        isDaemon = true
                        start()
                    }

                val stdout = proc.inputStream.readBytes().decodeToString()
                val finished = proc.waitFor(timeoutSeconds, TimeUnit.SECONDS)
                if (!finished) {
                    proc.destroyForcibly()
                    stderrDrain.join(1000)
                    return@withContext ""
                }
                stderrDrain.join(1000)
                stdout.trim()
            } finally {
                temp.delete()
            }
        }
}
