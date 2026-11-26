package com.dsha.zipforge

import android.app.Application
import com.dsha.zipforge.task.TaskManager

class UnzipApp : Application() {
    override fun onCreate() {
        super.onCreate()
        TaskManager.ensureChannel(this)
    }
}
