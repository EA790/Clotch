package com.example

import android.app.Application
import com.example.recording.RecordingController

class ClotchApplication : Application() {
    lateinit var recordingController: RecordingController
        private set

    override fun onCreate() {
        super.onCreate()
        recordingController = RecordingController(this)
    }
}
