package com.focuslock.app

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

object Alarms {
    const val ACTION = "com.focuslock.app.TASK_START"

    /** Turns a JSON id back into a JavaScript literal of the same type (string or number). */
    fun lit(v: Any?): String = when (v) {
        is String -> JSONObject.quote(v)
        is Double -> if (v == Math.floor(v) && !v.isInfinite()) v.toLong().toString() else v.toString()
        is Number, is Boolean -> v.toString()
        else -> JSONObject.quote(v.toString())
    }

    fun canExact(c: Context): Boolean =
        Build.VERSION.SDK_INT < 31 || c.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    private fun pending(c: Context, idLit: String, title: String): PendingIntent {
        val i = Intent(c, AlarmReceiver::class.java).setAction(ACTION)
            .putExtra("id", idLit).putExtra("title", title)
        return PendingIntent.getBroadcast(
            c, idLit.hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun setOne(c: Context, o: JSONObject) {
        val am = c.getSystemService(AlarmManager::class.java)
        val at = o.optLong("at")
        val pi = pending(c, lit(o.opt("id")), o.optString("title"))
        if (canExact(c)) {
            val show = PendingIntent.getActivity(
                c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
            )
            am.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    private fun nextWeek(at: Long, now: Long): Long {
        val cal = Calendar.getInstance().apply { timeInMillis = at }
        while (cal.timeInMillis <= now + 60_000L) cal.add(Calendar.DAY_OF_YEAR, 7)
        return cal.timeInMillis
    }

    fun cancelAll(c: Context) {
        val am = c.getSystemService(AlarmManager::class.java)
        val arr = Store.alarms(c)
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            am.cancel(pending(c, lit(o.opt("id")), o.optString("title")))
        }
    }

    /** (Re)schedules everything in the store. Past repeating alarms roll forward a week at a time. */
    fun scheduleAll(c: Context) {
        val now = System.currentTimeMillis()
        val arr = Store.alarms(c)
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            var at = o.optLong("at")
            if (at <= now) {
                if (!o.optBoolean("rep")) continue
                at = nextWeek(at, now)
                o.put("at", at)
            }
            safe { setOne(c, o) }
            out.put(o)
        }
        Store.setAlarms(c, out)
    }

    /** Called when an alarm fires: repeat it next week, or drop it. */
    fun afterFire(c: Context, idLit: String) {
        val now = System.currentTimeMillis()
        val arr = Store.alarms(c)
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (lit(o.opt("id")) == idLit) {
                if (o.optBoolean("rep")) {
                    o.put("at", nextWeek(o.optLong("at"), now))
                    safe { setOne(c, o) }
                    out.put(o)
                }
            } else out.put(o)
        }
        Store.setAlarms(c, out)
    }
}

object Notif {
    const val CH = "task_start"
    const val ID = 1001

    fun taskStart(c: Context, title: String, launch: Intent) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH, "Task start", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Opens LockIn when a scheduled task begins"
                enableVibration(true)
            }
        )
        if (!nm.areNotificationsEnabled()) return
        val pi = PendingIntent.getActivity(
            c, 1, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(c, CH)
            .setSmallIcon(R.drawable.ic_stat_lock)
            .setContentTitle("Time to focus")
            .setContentText(title)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(pi, true)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify(ID, n)
    }
}
