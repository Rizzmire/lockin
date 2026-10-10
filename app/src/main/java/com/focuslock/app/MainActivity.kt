package com.focuslock.app

import android.Manifest
import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewAssetLoader

class MainActivity : AppCompatActivity() {

    companion object {
        const val HOST = "appassets.androidplatform.net"
        const val URL = "https://$HOST/assets/index.html"

        // Makes "download" links (backup file, proof images) work inside a WebView.
        private const val SHIM = """
(function(){
 if(window.__flShim)return;window.__flShim=1;
 var oc=HTMLAnchorElement.prototype.click;
 HTMLAnchorElement.prototype.click=function(){
  var a=this,h=a.href||"";
  if(a.hasAttribute("download")&&(h.indexOf("blob:")===0||h.indexOf("data:")===0)&&window.Android&&Android.saveFile){
   fetch(h).then(function(r){return r.blob()}).then(function(b){
    var fr=new FileReader();
    fr.onload=function(){var s=String(fr.result);Android.saveFile(a.download||"file",b.type||"application/octet-stream",s.substring(s.indexOf(",")+1))};
    fr.readAsDataURL(b)});
   return;
  }
  return oc.apply(this,arguments);
 };
})();
"""
    }

    lateinit var web: WebView
    private var pageReady = false
    private var retries = 0
    private val h = Handler(Looper.getMainLooper())

    private var fileCb: ValueCallback<Array<Uri>>? = null
    private var camReq: PermissionRequest? = null

    private val loader by lazy {
        WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
    }

    private val fileLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            fileCb?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(r.resultCode, r.data))
            fileCb = null
        }

    private val camLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            camReq?.let { if (ok) it.grant(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) else it.deny() }
            camReq = null
        }

    private val startupLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    private val expiry = Runnable {
        val end = Store.lockEnd(this@MainActivity)
        if (end != 0L && System.currentTimeMillis() >= end) endLock(true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.getBooleanExtra("from_alarm", false) == true) wake()
        Policy.apply(this)

        web = WebView(this)
        setContentView(web)
        configureWebView()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (Lock.active(this@MainActivity)) return // no escape while locked
                moveTaskToBack(true)
            }
        })

        askStartupPermissions()
        web.loadUrl(URL)
    }

    private fun configureWebView() {
        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.mediaPlaybackRequiresUserGesture = false
        s.allowFileAccess = false
        s.allowContentAccess = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        s.textZoom = 100
        s.setSupportZoom(false)
        web.overScrollMode = View.OVER_SCROLL_NEVER
        web.setBackgroundColor(ContextCompat.getColor(this, R.color.bg))
        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        web.addJavascriptInterface(Bridge(this), "Android")

        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest): WebResourceResponse? =
                loader.shouldInterceptRequest(r.url)

            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                if (r.url.host == HOST) return false
                safe { startActivity(Intent(Intent.ACTION_VIEW, r.url)) }
                return true
            }

            override fun onPageFinished(v: WebView, url: String) {
                pageReady = true
                v.evaluateJavascript(SHIM, null)
                retries = 0
                dispatchPending()
            }

            override fun onRenderProcessGone(v: WebView, d: RenderProcessGoneDetail): Boolean {
                recreate()
                return true
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(r: PermissionRequest) {
                runOnUiThread {
                    val wantsCam = r.resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
                    if (r.origin.host != HOST || !wantsCam) {
                        r.deny()
                    } else if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA)
                        == PackageManager.PERMISSION_GRANTED
                    ) {
                        r.grant(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE))
                    } else {
                        camReq = r
                        camLauncher.launch(Manifest.permission.CAMERA)
                    }
                }
            }

            override fun onShowFileChooser(
                w: WebView, cb: ValueCallback<Array<Uri>>, p: WebChromeClient.FileChooserParams
            ): Boolean {
                fileCb?.onReceiveValue(null)
                fileCb = cb
                try {
                    fileLauncher.launch(p.createIntent())
                } catch (e: Exception) {
                    cb.onReceiveValue(null)
                    fileCb = null
                }
                return true
            }
        }
    }

    private fun askStartupPermissions() {
        if (Policy.isOwner(this) || Lock.active(this)) return
        val need = ArrayList<String>()
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) need.add(Manifest.permission.POST_NOTIFICATIONS)
        if (need.isNotEmpty()) startupLauncher.launch(need.toTypedArray())
    }

    // ---- alarm hand-off ---------------------------------------------------------------------

    private fun wake() {
        setShowWhenLocked(true)
        setTurnScreenOn(true)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("from_alarm", false)) wake()
        retries = 0
        dispatchPending()
    }

    /** Hands a started task to the page once it is ready (window.onNativeStart in the HTML). */
    private fun dispatchPending() {
        if (!pageReady) return
        val p = Store.pending(this) ?: return
        if (System.currentTimeMillis() - Store.pendingAt(this) > 15 * 60_000L) {
            Store.clearPending(this)
            return
        }
        web.evaluateJavascript(
            "(function(){if(typeof window.onNativeStart==='function'){window.onNativeStart($p);return 1}return 0})()"
        ) { r ->
            if (r == "1") Store.clearPending(this)
            else if (retries++ < 20) h.postDelayed({ dispatchPending() }, 500)
        }
    }

    // ---- lock -------------------------------------------------------------------------------

    fun beginLock(allowed: List<String>, minutes: Int) {
        val end = System.currentTimeMillis() + (minutes + 2) * 60_000L // 2 min safety margin
        Store.setLock(this, end, allowed)
        Policy.whitelist(this, allowed)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        safe { startLockTask() }
        Lock.scheduleExpiry(this, end)
        h.removeCallbacks(expiry)
        h.postDelayed(expiry, end - System.currentTimeMillis())
    }

    fun endLock(notifyPage: Boolean) {
        h.removeCallbacks(expiry)
        Lock.cancelExpiry(this)
        Store.clearLock(this)
        safe { stopLockTask() }
        Policy.clearWhitelist(this)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (notifyPage && pageReady) {
            web.evaluateJavascript("window.onNativeLockEnded&&window.onNativeLockEnded()", null)
        }
    }

    /** Re-enters the lock after a reboot or a crash, or ends it if the safety timeout passed. */
    private fun resumeLock() {
        val end = Store.lockEnd(this)
        if (end == 0L) return
        val now = System.currentTimeMillis()
        if (now >= end) {
            endLock(true)
            return
        }
        if (Policy.isOwner(this)) {
            val st = getSystemService(ActivityManager::class.java).lockTaskModeState
            if (st == ActivityManager.LOCK_TASK_MODE_NONE) {
                Policy.whitelist(this, Store.lockAllowed(this))
                safe { startLockTask() }
            }
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            h.removeCallbacks(expiry)
            h.postDelayed(expiry, end - now)
        }
    }

    // ---- lifecycle --------------------------------------------------------------------------

    override fun onResume() {
        super.onResume()
        getSystemService(NotificationManager::class.java).cancel(Notif.ID)
        resumeLock()
        dispatchPending()
        if (pageReady) {
            // refresh the setup checklist after returning from a system settings screen
            web.evaluateJavascript("if(typeof setupUI==='function')setupUI()", null)
        }
    }

    override fun onPause() {
        super.onPause()
        if (!Lock.active(this)) {
            setShowWhenLocked(false)
            setTurnScreenOn(false)
        }
    }

    override fun onDestroy() {
        h.removeCallbacksAndMessages(null)
        web.destroy()
        super.onDestroy()
    }
}
