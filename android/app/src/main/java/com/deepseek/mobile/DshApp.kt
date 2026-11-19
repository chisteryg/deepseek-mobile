package com.deepseek.mobile

import android.app.Application
import com.deepseek.mobile.data.AppContainer

class DshApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
