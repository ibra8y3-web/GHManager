package com.ghmanager.app

import android.app.Application
import com.ghmanager.app.data.Github
import com.ghmanager.app.data.Notifier
import com.ghmanager.app.data.Store
import com.ghmanager.app.ui.Lang

class GhApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        Github.token = Store.token
        Lang.init()
        Notifier.init(this)
        if (Store.bgNotify) Notifier.schedule(this)
    }
}
