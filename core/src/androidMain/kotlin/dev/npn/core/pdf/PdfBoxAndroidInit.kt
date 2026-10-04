package dev.npn.core.pdf

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

/**
 * pdfbox-android's one-time resource setup, shared by everything in the app that uses the library
 * (the PDF engine and the XMP reader/writer), so it runs once, under one lock.
 */
internal object PdfBoxAndroidInit {
    @Volatile
    private var initialized = false

    /** Load pdfbox-android's resources if no call has yet. Throws when that fails; the next call tries again. */
    fun ensure(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (!initialized) {
                PDFBoxResourceLoader.init(context.applicationContext)
                initialized = true
            }
        }
    }
}
