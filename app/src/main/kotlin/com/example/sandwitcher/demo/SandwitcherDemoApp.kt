package com.example.sandwitcher.demo

import android.app.Application
import io.sandwitcher.Sandwitcher
import io.sandwitcher.SandwitcherConfig

class SandwitcherDemoApp : Application() {

    override fun onCreate() {
        super.onCreate()
        Sandwitcher.init(this, SandwitcherConfig(debugLogging = true))
    }
}
