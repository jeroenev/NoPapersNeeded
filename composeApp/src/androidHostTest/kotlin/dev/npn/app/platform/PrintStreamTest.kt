package dev.npn.app.platform

import kotlinx.coroutines.test.runTest
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/** The spooler gets the document chunk by chunk as the library reads it, never as a whole copy. */
class PrintStreamTest {
    private val chunks = List(3) { index -> ByteArray(CHUNK) { (index * CHUNK + it).toByte() } }

    @Test
    fun eachChunkReachesTheSpoolerAsItIsRead() =
        runTest {
            val out = ByteArrayOutputStream()
            val seen = mutableListOf<Int>()
            val source =
                PrintSource { sink ->
                    for (chunk in chunks) {
                        sink(chunk, chunk.size)
                        // Written through before the next chunk is read: nothing is held back.
                        seen += out.size()
                    }
                    true
                }

            assertEquals(PrintWrite.WRITTEN, writePrintStream(source, out) { false })

            assertEquals(listOf(CHUNK, 2 * CHUNK, 3 * CHUNK), seen)
            assertContentEquals(chunks.reduce(ByteArray::plus), out.toByteArray())
        }

    @Test
    fun aCancelledPrintStopsCopyingAtTheNextChunk() =
        runTest {
            val out = ByteArrayOutputStream()
            var read = 0
            val source =
                PrintSource { sink ->
                    for (chunk in chunks) {
                        read++
                        sink(chunk, chunk.size)
                    }
                    true
                }

            assertEquals(PrintWrite.CANCELLED, writePrintStream(source, out) { out.size() > 0 })

            assertEquals(CHUNK, out.size())
            assertEquals(2, read)
        }

    @Test
    fun bytesThatAreNotTheDocumentFailTheWrite() =
        runTest {
            val source =
                PrintSource { sink ->
                    sink(chunks.first(), CHUNK)
                    false
                }

            assertEquals(PrintWrite.NOT_THE_DOCUMENT, writePrintStream(source, ByteArrayOutputStream()) { false })
        }

    private companion object {
        const val CHUNK = 1024
    }
}
