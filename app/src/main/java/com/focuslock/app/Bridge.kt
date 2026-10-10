package com.focuslock.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.PowerManager
import android.provider.MediaStore
import android.provider.Settings
import android.util.Base64
import android.webkit.JavascriptInterface
import android.widget.Toast
import androidx.core.graphics.drawable.toBitmap
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/** The `Android` object the page talks to. Every method the HTML calls is here. */
class Bridge(private val a: MainActivity) {

    private val appsJson: String by lazy { buildApps() }

    private fun toast(msg: String) = a.runOnUiThread { Toast.makeText(a, msg, Toast.LENGTH_LONG).show() }

    @JavascriptInterface
    fun isNative(): Boolean = true

    // ---- apps -------------------------------------------------------------------------------

    private fun buildApps(): String {
        val pm = a.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val pkgs = pm.queryIntentActivities(intent, 0)
            .map { it.activityInfo.packageName }
            .distinct()
            .filter { it != a.packageName }
        val items = pkgs.mapNotNull { p ->
            try {
                val label = pm.getApplicationLabel(pm.getApplicationInfo(p, 0)).toString()
                Pair(p, label)
            } catch (e: Exception) { null }
        }.sortedBy { it.second.lowercase() }

        val out = JSONArray()
        for ((p, label) in items) {
            val o = JSONObject().put("p", p).put("n", label)
            try {
                val bmp = pm.getApplicationIcon(p).toBitmap(96, 96)
                val bos = ByteArrayOutputStream()
                bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, bos)
                o.put("i", Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP))
            } catch (e: Exception) { }
            out.put(o)
        }
        return out.toString()
    }

    @JavascriptInterface
    fun getApps(): String = appsJson

    @JavascriptInterface
    fun openApp(pkg: String) {
        a.runOnUiThread {
            try {
                val i = a.packageManager.getLaunchIntentForPackage(pkg)
                if (i != null) a.startActivity(i)
            } catch (e: Exception) { }
        }
    }

    @JavascriptInterface
    fun openDialer() {
        a.runOnUiThread { safe { a.startActivity(Intent(Intent.ACTION_DIAL)) } }
    }

    // ---- lock -------------------------------------------------------------------------------

    @JavascriptInterface
    fun startLock(json: String) {
        try {
            val o = JSONObject(json)
            val arr = o.optJSONArray("allowed") ?: JSONArray()
            val list = (0 until arr.length()).map { arr.getString(it) }
            val minutes = o.optInt("minutes", 1).coerceAtLeast(1)
            a.runOnUiThread { a.beginLock(list, minutes) }
        } catch (e: Exception) { }
    }

    @JavascriptInterface
    fun stopLock() {
        a.runOnUiThread { a.endLock(false) }
    }

    @JavascriptInterface
    fun isLocked(): Boolean = Lock.active(a)

    // ---- alarms -----------------------------------------------------------------------------

    @JavascriptInterface
    fun setAlarms(json: String) {
        try {
            Alarms.cancelAll(a)
            val now = System.currentTimeMillis()
            val src = JSONArray(json)
            val keep = JSONArray()
            for (i in 0 until src.length()) {
                val o = src.getJSONObject(i)
                if (o.optLong("at") > now) keep.put(o)
            }
            Store.setAlarms(a, keep)
            Alarms.scheduleAll(a)
        } catch (e: Exception) { }
    }

    // ---- status / settings ------------------------------------------------------------------

    @JavascriptInterface
    fun getStatus(): String {
        val o = JSONObject()
        o.put("owner", Policy.isOwner(a))
        o.put("exact", Alarms.canExact(a))
        o.put("notif", androidx.core.app.NotificationManagerCompat.from(a).areNotificationsEnabled())
        o.put("battery", a.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(a.packageName))
        o.put("overlay", Settings.canDrawOverlays(a))
        o.put("adb", "adb shell dpm set-device-owner ${a.packageName}/.AdminReceiver")
        return o.toString()
    }

    @JavascriptInterface
    fun openSetting(name: String) {
        val uri = Uri.parse("package:${a.packageName}")
        val i: Intent = when (name) {
            "exact_alarm" ->
                if (android.os.Build.VERSION.SDK_INT >= 31) Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, uri)
                else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri)
            "notifications" ->
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, a.packageName)
            "battery" -> Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, uri)
            "overlay" -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, uri)
            else -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri)
        }
        a.runOnUiThread {
            try { a.startActivity(i) }
            catch (e: Exception) {
                safe { a.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri)) }
            }
        }
    }

    @JavascriptInterface
    fun copy(text: String) {
        a.runOnUiThread {
            val cm = a.getSystemService(ClipboardManager::class.java)
            cm.setPrimaryClip(ClipData.newPlainText("LockIn", text))
        }
    }

    // ---- release from device-owner mode (24 h wait) -----------------------------------------

    private val waitMs = 24L * 60 * 60 * 1000

    @JavascriptInterface
    fun releaseState(): String {
        val req = Store.relReq(a)
        return JSONObject()
            .put("owner", Policy.isOwner(a))
            .put("requestedAt", req)
            .put("readyAt", if (req == 0L) 0L else req + waitMs)
            .put("now", System.currentTimeMillis())
            .toString()
    }

    @JavascriptInterface
    fun requestRelease() {
        if (!Policy.isOwner(a) || Lock.active(a)) return
        if (Store.relReq(a) == 0L) Store.setRelReq(a, System.currentTimeMillis())
    }

    @JavascriptInterface
    fun cancelRelease() {
        Store.setRelReq(a, 0L)
    }

    @JavascriptInterface
    fun confirmRelease(): String {
        if (!Policy.isOwner(a)) return "Nothing to release."
        if (Lock.active(a)) return "Blocked while a session is running."
        val req = Store.relReq(a)
        if (req == 0L || System.currentTimeMillis() < req + waitMs) return "Not ready yet."
        return try {
            Policy.release(a)
            Store.setRelReq(a, 0L)
            "Released. You can uninstall LockIn now."
        } catch (e: Exception) {
            "Could not release: ${e.message}"
        }
    }

    // ---- file saving (used by the injected download shim) -----------------------------------

    @JavascriptInterface
    fun saveFile(name: String, mime: String, base64: String) {
        try {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            val safeName = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, safeName)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = a.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!
            a.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
            toast("Saved to Downloads: $safeName")
        } catch (e: Exception) {
            toast("Couldn't save the file")
        }
    }
}
