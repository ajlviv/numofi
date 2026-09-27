package com.financetracker

import android.app.Application
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class FinanceTrackerApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // PDFBox keeps its glyph and font-mapping tables as assets. They have to be
        // copied out of the APK before any text can be read; without this, the first
        // statement import dies with ExceptionInInitializerError on a missing
        // glyphlist.txt.
        PDFBoxResourceLoader.init(this)
    }
}
