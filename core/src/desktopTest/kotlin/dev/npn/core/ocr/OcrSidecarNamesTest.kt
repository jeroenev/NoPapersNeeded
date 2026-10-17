package dev.npn.core.ocr

import dev.npn.core.fs.JvmLibraryFileSystem
import dev.npn.core.fs.NpnPaths
import dev.npn.core.fs.PathExternalIo
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * The sidecar file names every reader must keep understanding, legacy ones included: discovery per
 * document, discovery across the library, a writer key's device, engine and run, and the
 * watcher's test for this device's own writes.
 */
class OcrSidecarNamesTest {
    private lateinit var root: Path
    private lateinit var fs: JvmLibraryFileSystem
    private lateinit var versions: OcrVersions

    private val hash = "ab" + "0".repeat(62)
    private val otherHash = "cd" + "0".repeat(62)

    @BeforeTest
    fun setup() {
        root = Files.createTempDirectory("npn-sidecar-names")
        fs = JvmLibraryFileSystem(root, PathExternalIo)
        versions = OcrVersions(OcrSidecarFiles(fs), minEmbeddedCharsPerPage = 1)
    }

    @AfterTest
    fun teardown() {
        root.toFile().deleteRecursively()
    }

    private suspend fun sidecar(
        name: String,
        engine: String = "tesseract",
    ) {
        val json =
            OcrSidecarCodec.encode(
                OcrSidecar(doc = hash, dev = "x", engine = engine, lang = "eng", createdAt = 1, pages = listOf("t")),
            )
        fs.writeBytesOnce("${NpnPaths.OCR_DIR}/$name", json.encodeToByteArray())
    }

    @Test
    fun everyWriterKeyFormIsFoundAndReadBack() =
        runTest {
            sidecar("$hash.devA.json", engine = "tesseract")
            sidecar("$hash.devB.mlkit.json")
            sidecar("$hash.devB.r5.mlkit.json")
            // A legacy device id may hold dots; its last part is no engine.
            sidecar("$hash.my.device.json", engine = "embedded")
            sidecar("$otherHash.devA.mlkit.json")

            versions.of(hash) shouldContainExactlyInAnyOrder
                listOf(
                    OcrVersions.Version("devA", "devA", "tesseract"),
                    OcrVersions.Version("devB.mlkit", "devB", "mlkit"),
                    OcrVersions.Version("devB.r5.mlkit", "devB", "mlkit", run = 5),
                    OcrVersions.Version("my.device", "my.device", "embedded"),
                )
        }

    @Test
    fun aRunStampIsOnlyReadFromDigitsBeforeAKnownEngine() =
        runTest {
            sidecar("$hash.dev.r12.r34.mlkit.json")
            sidecar("$hash.dev.rabc.mlkit.json")
            sidecar("$hash.dev.r99999999999999999999.mlkit.json")

            versions.of(hash) shouldContainExactlyInAnyOrder
                listOf(
                    OcrVersions.Version("dev.r12.r34.mlkit", "dev.r12", "mlkit", run = 34),
                    OcrVersions.Version("dev.rabc.mlkit", "dev.rabc", "mlkit"),
                    OcrVersions.Version("dev.r99999999999999999999.mlkit", "dev.r99999999999999999999", "mlkit"),
                )
        }

    @Test
    fun namesThatAreNoSidecarAreSkipped() =
        runTest {
            sidecar("$hash.devA.mlkit.json")
            sidecar("$hash.devA.mlkit.json.part")
            sidecar("$hash..json")
            sidecar(".devA.json")
            sidecar("$hash.json")

            OcrSidecarFiles(fs).listForHash(hash) shouldBe listOf("devA.mlkit")
        }

    @Test
    fun libraryWideDiscoveryKeysOnlyByAFullLengthHash() =
        runTest {
            sidecar("$hash.devA.mlkit.json")
            sidecar("$hash.my.device.json")
            sidecar("$otherHash.devB.json")
            sidecar("abc123.devA.mlkit.json")

            versions.writerKeysByHash().mapValues { it.value.toSet() } shouldBe
                mapOf(hash to setOf("devA.mlkit", "my.device"), otherHash to setOf("devB"))
        }

    @Test
    fun ownWritesAreThoseUnderThisDevicesKeys() {
        fun own(name: String) = OcrSidecarName.isOwnWrite("${NpnPaths.OCR_DIR}/$name", "dev1")

        own("$hash.dev1.json") shouldBe true
        own("$hash.dev1.mlkit.json") shouldBe true
        own("$hash.dev1.r5.mlkit.json") shouldBe true
        own("$hash.dev2.json") shouldBe false
        own("$hash.dev12.mlkit.json") shouldBe false
        own("$hash.other.dev1.json") shouldBe false
    }
}
