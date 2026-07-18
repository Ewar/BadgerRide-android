package com.badgerride

import android.app.Application
import android.content.Context

class BadgerApp : Application() {
    lateinit var engine: RideEngine
        private set

    override fun onCreate() {
        super.onCreate()
        engine = RideEngine(this)
    }
}

internal val Context.engine: RideEngine
    get() = (applicationContext as BadgerApp).engine
