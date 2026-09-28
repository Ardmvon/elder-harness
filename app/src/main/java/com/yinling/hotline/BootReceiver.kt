package com.yinling.hotline

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Brings the守护 back after a reboot or an app update.
 *
 * What this can and cannot do is worth stating plainly: Android only delivers these broadcasts to
 * apps that are not force-stopped, and nothing an app can do re-enables its own accessibility
 * service (that write needs a system-level permission). So the job here is to restart what we can
 * and to make a missing piece visible instead of silently absent.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in ACTIONS) return
        LoopLog.event("[boot] 收到 $action，尝试恢复守护")

        // The overlay service is what keeps the process (and with it the accessibility service)
        // alive; start it in the foreground so the system allows the start from the background.
        val service = Intent(context, OverlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(service)
        } else {
            context.startService(service)
        }

        // The accessibility service is re-bound by the system when it is still switched on. When it
        // is not, the person has to switch it back on: say so rather than pretend all is well.
        if (!ScreenAccessService.isEnabled(context)) {
            Notices.postAccessibilityOff(context)
        }
    }

    private companion object {
        val ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "com.oplus.intent.action.QUICKBOOT_POWERON",
        )
    }
}
