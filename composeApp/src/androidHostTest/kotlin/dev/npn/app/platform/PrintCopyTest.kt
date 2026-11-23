package dev.npn.app.platform

import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class PrintCopyTest {
    @Test
    fun copiesBytesAndStopsBetweenChunksWhenCancelled() {
        val bytes = ByteArray(200_000) { it.toByte() }
        val complete = ByteArrayOutputStream()
        copyPrintBytes(bytes, complete) { false }
        assertContentEquals(bytes, complete.toByteArray())

        val partial = ByteArrayOutputStream()
        copyPrintBytes(bytes, partial) { partial.size() > 0 }
        assertEquals(65_536, partial.size())
    }
}
