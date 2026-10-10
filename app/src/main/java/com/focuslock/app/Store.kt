package com.focuslock.app

import android.content.Context
import org.json.JSONArray

/** Small persistent state, readable from receivers even when the UI is not running. */
object Store {
    private fun p(c: Context) = c.getSharedPreferences("fl", Context.MODE_PRIVATE)

    // Scheduled task alarms: JSON array of {id,title,at,dur,rep} exactly as sent by the page.
    fun alarms(c: Context): JSONArray =
        try { JSONArray(p(c).getString("alarms", "[]")) } catch (e: Exception) { JSONArray() }

    fun setAlarms(c: Context, a: JSONArray) {
        p(c).edit().putString("alarms", a.toString()).apply()
    }

    // Active lock session
    fun lockEnd(c: Context): Long = p(c).getLong("lock_end", 0L)
    fun lockStarted(c: Context): Long = p(c).getLong("lock_started", 0L)

    fun lockAllowed(c: Context): List<String> {
        return try {
            val a = JSONArray(p(c).getString("lock_allowed", "[]"))
            (0 until a.length()).map { a.getString(it) }
        } catch (e: Exception) { emptyList() }
    }

    fun setLock(c: Context, end: Long, allowed: List<String>) {
        p(c).edit()
            .putLong("lock_end", end)
            .putLong("lock_started", System.currentTimeMillis())
            .putString("lock_allowed", JSONArray(allowed).toString())
            .apply()
    }

    fun clearLock(c: Context) {
        p(c).edit().remove("lock_end").remove("lock_started").remove("lock_allowed").apply()
    }

    // Task id (as a JS literal) waiting to be handed to the page
    fun pending(c: Context): String? = p(c).getString("pending", null)
    fun pendingAt(c: Context): Long = p(c).getLong("pending_at", 0L)
    fun setPending(c: Context, jsLiteral: String) {
        p(c).edit().putString("pending", jsLiteral).putLong("pending_at", System.currentTimeMillis()).apply()
    }
    fun clearPending(c: Context) {
        p(c).edit().remove("pending").remove("pending_at").apply()
    }

    // Device-owner release request (24h wait)
    fun relReq(c: Context): Long = p(c).getLong("rel_req", 0L)
    fun setRelReq(c: Context, t: Long) {
        if (t == 0L) p(c).edit().remove("rel_req").apply() else p(c).edit().putLong("rel_req", t).apply()
    }
}
