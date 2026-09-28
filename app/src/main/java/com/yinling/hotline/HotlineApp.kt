package com.yinling.hotline

import android.app.Application
import android.content.Intent
import android.provider.Settings

class HotlineApp : Application() {
    lateinit var session: SessionController
        private set

    override fun onCreate() {
        super.onCreate()
        LoopLog.attach(this)
        session = SessionController(this)
        LoopLog.enabled = session.developerMode
    }

    fun openDisplaySettings() {
        startActivity(Intent(Settings.ACTION_DISPLAY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun launchers() = packageManager.queryIntentActivities(
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0,
    )

    fun launchableApps(): List<String> = launchers().map {
        it.loadLabel(packageManager).toString()
    }.distinct().sorted()

    fun openApp(name: String): Boolean {
        val matches = launchers().filter { it.loadLabel(packageManager).toString() == name }
        if (matches.size != 1) return false
        val intent = packageManager.getLaunchIntentForPackage(matches.single().activityInfo.packageName)
            ?: return false
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }
}
