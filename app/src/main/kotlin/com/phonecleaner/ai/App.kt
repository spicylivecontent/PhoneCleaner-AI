package com.phonecleaner.ai

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        com.phonecleaner.ai.shizuku.ShizukuManager.init(this)
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
