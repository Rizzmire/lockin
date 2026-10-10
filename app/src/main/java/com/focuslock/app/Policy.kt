package com.focuslock.app

import android.Manifest
import android.app.ActivityManager
import android.app.AlarmManager
import android.app.PendingIntent
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.UserManager
import android.telecom.TelecomManager

inline fun safe(block: () -> Unit) {
    try { block() } catch (e: Exception) { }
}

class AdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        Policy.apply(context)
    }
}

/** Device-owner policies. All calls are no-ops unless the app was made device owner via adb. */
object Policy {
    fun admin(c: Context) = ComponentName(c, AdminReceiver::class.java)
    fun dpm(c: Context): DevicePolicyManager = c.getSystemService(DevicePolicyManager::class.java)
    fun isOwner(c: Context): Boolean = dpm(c).isDeviceOwnerApp(c.packageName)

    fun apply(c: Context) {
        if (!isOwner(c)) return
        val d = dpm(c)
        val a = admin(c)
        safe { d.setUninstallBlocked(a, c.packageName, true) }
        safe { d.addUserRestriction(a, UserManager.DISALLOW_SAFE_BOOT) }
        grant(c, Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 33) grant(c, Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun grant(c: Context, perm: String) {
        safe {
            dpm(c).setPermissionGrantState(
                admin(c), c.packageName, perm, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
            )
        }
    }

    /** Packages that may run while the lock is on: LockIn, the allowed apps and the dialer. */
    fun whitelist(c: Context, allowed: List<String>) {
        if (!isOwner(c)) return
        val pkgs = LinkedHashSet<String>()
        pkgs.add(c.packageName)
        pkgs.addAll(allowed)
        safe { c.getSystemService(TelecomManager::class.java).defaultDialerPackage?.let { pkgs.add(it) } }
        safe {
            val d = dpm(c)
            d.setLockTaskPackages(admin(c), pkgs.toTypedArray())
            // No Home, no Recents, no notifications, no status bar, no power menu.
            d.setLockTaskFeatures(admin(c), DevicePolicyManager.LOCK_TASK_FEATURE_NONE)
        }
    }

    /** Removing the package from the whitelist also makes Android end lock-task mode for it. */
    fun clearWhitelist(c: Context) {
        if (!isOwner(c)) return
        safe { dpm(c).setLockTaskPackages(admin(c), arrayOf<String>()) }
    }

    @Suppress("DEPRECATION")
    fun release(c: Context) {
        val d = dpm(c)
        val a = admin(c)
        safe { d.clearUserRestriction(a, UserManager.DISALLOW_SAFE_BOOT) }
        safe { d.setUninstallBlocked(a, c.packageName, false) }
        safe { d.setLockTaskPackages(a, arrayOf<String>()) }
        d.clearDeviceOwnerApp(c.packageName)
    }
}

/** Lock-session helpers shared by the activity and the receivers. */
object Lock {
    private fun expiryPi(c: Context) = PendingIntent.getBroadcast(
        c, 77, Intent(c, LockEndReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    fun scheduleExpiry(c: Context, at: Long) {
        val am = c.getSystemService(AlarmManager::class.java)
        if (Alarms.canExact(c)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, expiryPi(c))
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, expiryPi(c))
    }

    fun cancelExpiry(c: Context) {
        c.getSystemService(AlarmManager::class.java).cancel(expiryPi(c))
    }

    fun active(c: Context): Boolean {
        val end = Store.lockEnd(c)
        val now = System.currentTimeMillis()
        if (end == 0L || now >= end) return false
        if (Policy.isOwner(c)) return true // the activity re-enters lock mode if needed
        val st = c.getSystemService(ActivityManager::class.java).lockTaskModeState
        return st != ActivityManager.LOCK_TASK_MODE_NONE || now - Store.lockStarted(c) < 5000
    }

    fun expire(c: Context) {
        Store.clearLock(c)
        cancelExpiry(c)
        Policy.clearWhitelist(c)
    }
}
