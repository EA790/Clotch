package com.example

import android.app.Application
import com.example.diagnostics.CrashLogger
import com.example.recording.RecordingController

class ClotchApplication : Application() {
    lateinit var recordingController: RecordingController
        private set

    override fun onCreate() {
        super.onCreate()
        CrashLogger.install(this)
        recordingController = RecordingController(this)
    }

    override fun onTerminate() {
        if (::recordingController.isInitialized) {
            recordingController.release()
        }
        super.onTerminate()
    }
}
