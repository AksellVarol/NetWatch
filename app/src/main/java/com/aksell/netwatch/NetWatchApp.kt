package com.aksell.netwatch

import android.app.Application

// NetWatch entry point (Bridge Phase 1): installs the Bridge crash handler
// before anything else runs, so a crash is recorded from the very start of
// the app. Application.onCreate runs once per process start and covers every
// component (activities, MonitorService, widget receiver). The manifest has
// no android:process, so the app is single-process; if a separate process is
// ever added, Application.onCreate runs there too and the handler is
// installed automatically in that process as well.
class NetWatchApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Bridge.installCrashHandler(this)
        Bridge.loadPersistedState(this)
    }
}
