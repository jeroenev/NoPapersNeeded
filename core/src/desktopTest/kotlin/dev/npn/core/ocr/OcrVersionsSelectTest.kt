package dev.npn.core.ocr

import dev.npn.core.fs.JvmLibraryFileSystem
import dev.npn.core.fs.OcrSidecarFiles
import dev.npn.core.fs.OcrSidecarName
import dev.npn.core.fs.PathExternalIo
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * Which stored version every device indexes, and the key a new one is published under. Both are
 * pure, so every device that sees the same sidecars must agree.
 */
class OcrVersionsSelectTest {
    private val root: Path = Files.createTempDirectory("npn-ocr-versions")
    private val versions = OcrVersions(OcrSidecarFiles(JvmLibraryFileSystem(root, PathExternalIo)), 1)

    @AfterTest
    fun teardown() {
        root.toFile().deleteRecursively()
    }

    private fun first(
        device: String,
        engine: String,
    ) = OcrVersions.Version(OcrSidecarName.writerKey(device, engine), device, engine)

    private fun rerun(
        device: String,
        engine: String,
        run: Long,
    ) = OcrVersions.Version(OcrSidecarName.rerunWriterKey(device, run, engine), device, engine, run)

    private data class SelectCase(
        val name: String,
        val stored: List<OcrVersions.Version>,
        val override: String?,
        val expected: String?,
    )

    @Test
    fun selectPicksTheSameVersionOnEveryDevice() {
        val cases =
            listOf(
                SelectCase(
                    "rank beats run",
                    listOf(first("devA", "mlkit"), rerun("devB", "tesseract", 9_000)),
                    null,
                    "devA.mlkit",
                ),
                SelectCase(
                    "a newer run beats a lower writer key",
                    listOf(first("devA", "mlkit"), rerun("devB", "mlkit", 5)),
                    null,
                    "devB.r5.mlkit",
                ),
                SelectCase(
                    "a full tie picks the lowest writer key",
                    listOf(first("devB", "mlkit"), first("devA", "mlkit")),
                    null,
                    "devA.mlkit",
                ),
                SelectCase(
                    "an override matching nothing selects from all",
                    listOf(first("devA", "tesseract"), first("devB", "mlkit")),
                    "embedded",
                    "devB.mlkit",
                ),
                SelectCase(
                    "an override beats a higher-ranked engine",
                    listOf(first("devA", "tesseract"), first("devB", "mlkit")),
                    "tesseract",
                    "devA.tesseract",
                ),
                SelectCase("nothing stored selects nothing", emptyList(), null, null),
            )
        cases.forEach { case ->
            val picked = versions.select(case.stored, case.override)?.writerKey
            if (case.expected == null) {
                picked.shouldBeNull()
            } else {
                (case.name to picked) shouldBe (case.name to case.expected)
            }
            // The order the versions were found in must not matter.
            versions.select(case.stored.reversed(), case.override)?.writerKey shouldBe picked
        }
    }

    private data class KeyCase(
        val name: String,
        val stored: List<OcrVersions.Version>,
        val runMillis: Long,
        val expected: String,
    )

    @Test
    fun newWriterKeyIsMonotonicPerDeviceAndEngine() {
        val cases =
            listOf(
                KeyCase("a first run takes the plain key", emptyList(), 500, "d.mlkit"),
                KeyCase("another device's run is no first run of ours", listOf(first("e", "mlkit")), 500, "d.mlkit"),
                KeyCase(
                    "another engine's run is no first run of ours",
                    listOf(first("d", "tesseract")),
                    500,
                    "d.mlkit",
                ),
                KeyCase("a re-run takes the clock", listOf(first("d", "mlkit")), 500, "d.r500.mlkit"),
                KeyCase(
                    "a re-run under a clock gone back stays newest",
                    listOf(first("d", "mlkit"), rerun("d", "mlkit", 1_000)),
                    500,
                    "d.r1001.mlkit",
                ),
                KeyCase(
                    "a re-run in the same millisecond gets a key of its own",
                    listOf(first("d", "mlkit"), rerun("d", "mlkit", 1_000)),
                    1_000,
                    "d.r1001.mlkit",
                ),
                KeyCase(
                    "another device's later run does not move ours",
                    listOf(first("d", "mlkit"), rerun("e", "mlkit", 9_000)),
                    500,
                    "d.r500.mlkit",
                ),
            )
        cases.forEach { case ->
            (case.name to versions.newWriterKey(case.stored, "d", "mlkit", case.runMillis)) shouldBe
                (case.name to case.expected)
        }
    }
}
