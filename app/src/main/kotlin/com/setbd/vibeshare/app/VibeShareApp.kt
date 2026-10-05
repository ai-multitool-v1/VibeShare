package com.setbd.vibeshare.app

import android.app.Application
import com.setbd.vibeshare.core.coroutines.DefaultDispatcherProvider
import com.setbd.vibeshare.core.log.VibeLog
import com.setbd.vibeshare.app.di.initKoin
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

/** VibeShare (SETBD) application entry point. */
class VibeShareApp : Application() {

    override fun onCreate() {
        super.onCreate()
        VibeLog.minLevel = VibeLog.Level.INFO
        startKoin {
            androidContext(this@VibeShareApp)
            modules(initKoin())
        }
        VibeLog.i(TAG, "VibeShare started — your files stay on your devices.")
    }

    companion object {
        private const val TAG = "VibeShareApp"
    }
}
