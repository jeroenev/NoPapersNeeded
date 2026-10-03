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
    private val detected: Boolean by lazy { detect() }

    override suspend fun available(): Boolean = detected

    private fun process(vararg command: String): ProcessBuilder =
        ProcessBuilder(*command).apply {
            tessdataDir?.let { environment()["TESSDATA_PREFIX"] = it }
        }

    private fun detect(): Boolean =
        try {
            val proc =
                process(binary, "--version")
                    .redirectErrorStream(true)
                    .start()
            proc.inputStream.readBytes() // drain
            val finished = proc.waitFor(10, TimeUnit.SECONDS)
            if (!finished) {
                proc.destroyForcibly()
                false
            } else {
                proc.exitValue() == 0
            }
        } catch (_: Throwable) {
            false
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
                    process(binary, temp.absolutePath, "stdout", "-l", lang)
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
