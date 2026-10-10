package com.focuslock.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings

/** A task's start time arrived. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        val id = intent.getStringExtra("id") ?: return
        val title = intent.getStringExtra("title") ?: "Task"
        Store.setPending(c, id)
        Alarms.afterFire(c, id)

        val launch = Intent(c, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra("from_alarm", true)

        // Works with the screen off or the phone locked.
        safe { Notif.taskStart(c, title, launch) }
        // Opens the app immediately when Android allows it (overlay permission or device owner).
        if (Settings.canDrawOverlays(c) || Policy.isOwner(c)) safe { c.startActivity(launch) }
    }
}

/** Safety timeout reached while the app was not running. */
class LockEndReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        Lock.expire(c)
    }
}

/** Reboot, app update, clock or timezone change: restore alarms and a running lock. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        Alarms.scheduleAll(c)
        val end = Store.lockEnd(c)
        val now = System.currentTimeMillis()
        if (end > now) {
            if (intent.action == Intent.ACTION_BOOT_COMPLETED && Policy.isOwner(c)) {
                Policy.apply(c)
                Lock.scheduleExpiry(c, end)
                safe {
                    c.startActivity(
                        Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
        } else if (end != 0L) {
            Lock.expire(c)
        }
    }
}
