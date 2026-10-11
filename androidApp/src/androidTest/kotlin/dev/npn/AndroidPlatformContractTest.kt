package dev.npn

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import android.security.NetworkSecurityPolicy
import androidx.test.platform.app.InstrumentationRegistry
import dev.npn.app.platform.sharePdfBytes
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Contracts that depend on the installed app's manifest, URI provider and network policy. */
class AndroidPlatformContractTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test
    fun sharingTheSameFileNameTwiceKeepsBothUrisReadableWithTheirOwnBytes() =
        runBlocking {
            val capture = ShareCapture()
            instrumentation.addMonitor(capture)
            try {
                val first = "first PDF".encodeToByteArray()
                val second = "second PDF".encodeToByteArray()
                sharePdfBytes("npn-regression.pdf", first)
                sharePdfBytes("npn-regression.pdf", second)

                assertEquals(2, capture.uris.size)
                val original = capture.uris.first()
                val later = capture.uris.last()
                assertNotEquals(original, later)
                assertEquals("${context.packageName}.fileprovider", original.authority)
                assertArrayEquals(first, context.contentResolver.openInputStream(original)?.use { it.readBytes() })
                assertArrayEquals(second, context.contentResolver.openInputStream(later)?.use { it.readBytes() })
            } finally {
                instrumentation.removeMonitor(capture)
                capture.uris.forEach { context.contentResolver.delete(it, null, null) }
            }
        }

    @Test
    fun aUserConfiguredHttpEndpointCanBeReachedUnderTheInstalledAppsNetworkPolicy() {
        assertTrue(NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted("127.0.0.1"))
        val executor = Executors.newSingleThreadExecutor()
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        server.soTimeout = TIMEOUT_MILLIS
        try {
            val request =
                executor.submit<String> {
                    server.accept().use { socket ->
                        socket.soTimeout = TIMEOUT_MILLIS
                        val reader = socket.getInputStream().bufferedReader()
                        val firstLine = checkNotNull(reader.readLine())
                        while (!reader.readLine().isNullOrEmpty()) { /* consume HTTP headers */ }
                        socket.getOutputStream().write(
                            "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok".encodeToByteArray(),
                        )
                        firstLine
                    }
                }
            val connection = URL("http://127.0.0.1:${server.localPort}/models").openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = TIMEOUT_MILLIS
                connection.readTimeout = TIMEOUT_MILLIS
                assertEquals("ok", connection.inputStream.bufferedReader().use { it.readText() })
                assertEquals("GET /models HTTP/1.1", request.get(TIMEOUT_MILLIS.toLong(), TimeUnit.MILLISECONDS))
            } finally {
                connection.disconnect()
            }
        } finally {
            server.close()
            executor.shutdownNow()
        }
    }

    /** Capture the actual share intent and prevent a system chooser from interrupting the test. */
    private class ShareCapture : Instrumentation.ActivityMonitor() {
        val uris = CopyOnWriteArrayList<Uri>()

        @Suppress("DEPRECATION") // The typed Parcelable overload needs API 33; the app supports API 30.
        override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
            if (intent.action != Intent.ACTION_CHOOSER) return null
            val share = checkNotNull(intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT))
            assertEquals(Intent.ACTION_SEND, share.action)
            assertTrue(share.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            uris += checkNotNull(share.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
            return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
        }
    }

    private companion object {
        const val TIMEOUT_MILLIS = 5_000
    }
}
