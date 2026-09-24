package com.anbu.research

import android.app.Application
import com.anbu.research.core.NativeLib

class AnbuApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Load the .so once. Fails fast here if the ABI/ndkVersion is wrong
        // (e.g. emulator x86_64, which this app does not ship).
        NativeLib.load()
    }
}
