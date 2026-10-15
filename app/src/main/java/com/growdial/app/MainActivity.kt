package com.growdial.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Dialog
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import java.io.File

/**
 * Growdial for Android: the website inside a fast, secure app.
 *  - Screenshots and screen recording are blocked (FLAG_SECURE) — the screen shows black in a recording.
 *  - Camera + microphone work for live calls and "Go live from this phone".
 *  - Photos / PDFs can be picked or taken with the camera for uploads.
 *  - UPI apps (GPay, PhonePe, Paytm…), WhatsApp, YouTube and other outside links open in their own apps.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        /** true = no screenshots / screen recording of the app (black screen). */
        const val SECURE = true
        /** Hosts that always open OUTSIDE the app (their own apps). */
        val EXTERNAL_HOSTS = listOf(
            "wa.me", "whatsapp.com", "play.google.com", "youtube.com", "youtu.be", "t.me", "telegram.me",
            "instagram.com", "facebook.com", "fb.me", "twitter.com", "x.com", "linkedin.com", "maps.google.com", "maps.app.goo.gl"
        )
        /** Pages where pull-to-refresh is off (they scroll inside, and a refresh would cut a live or a call). */
        val NO_PULL = listOf("live-room.php", "go-live.php", "live-recording.php", "messages.php", "watch.php", "recording-watch.php")
    }

    private lateinit var web: WebView
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var root: FrameLayout
    private val siteHost: String by lazy { (Uri.parse(BuildConfig.SITE_URL).host ?: "growdial.com").removePrefix("www.") }

    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var cameraUri: Uri? = null
    private var pendingPermission: PermissionRequest? = null
    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null
    private var failedUrl: String? = null
    /** The start screen stays until the first page is drawn (at most 3 seconds), so there is no white flash. */
    @Volatile private var pageReady = false

    // ---- runtime permissions asked when a page wants the camera / microphone (live, calls)
    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val req = pendingPermission ?: return@registerForActivityResult
        pendingPermission = null
        grantWhatWeCan(req)
    }

    // ---- the file picker (or the camera) for an <input type=file>
    private val fileLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val cb = fileCallback ?: return@registerForActivityResult
        fileCallback = null
        var uris: Array<Uri>? = null
        if (result.resultCode == RESULT_OK) {
            val data = result.data
            val clip = data?.clipData
            uris = when {
                clip != null -> Array(clip.itemCount) { i -> clip.getItemAt(i).uri }
                data?.data != null -> arrayOf(data.data!!)
                cameraUri != null && File(cacheDir, "camera/photo.jpg").length() > 0 -> arrayOf(cameraUri!!)
                else -> null
            }
        }
        cb.onReceiveValue(uris)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        splash.setKeepOnScreenCondition { !pageReady }
        Handler(Looper.getMainLooper()).postDelayed({ pageReady = true }, 3000)
        splash.setOnExitAnimationListener { provider ->   // leave with a soft zoom + fade instead of a cut
            provider.view.animate().alpha(0f).scaleX(1.12f).scaleY(1.12f).setDuration(320)
                .withEndAction { provider.remove() }.start()
        }
        if (SECURE) window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)

        root = FrameLayout(this)
        swipe = SwipeRefreshLayout(this)
        web = WebView(this)
        swipe.addView(web, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(swipe, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        setContentView(root)

        swipe.setColorSchemeColors(Color.parseColor("#4F46E5"))
        swipe.setOnRefreshListener { web.reload() }
        swipe.setOnChildScrollUpCallback { _, _ -> web.scrollY > 0 }   // only when the page is at its very top

        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)
            allowFileAccess = false
            allowContentAccess = true
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString = "$userAgentString GrowdialApp/1.0"   // the website knows it runs inside the app
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        web.addJavascriptInterface(Bridge(), "GrowdialApp")

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                if (!request.isForMainFrame) false else handleUrl(request.url)

            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                swipe.isEnabled = NO_PULL.none { url.contains(it) }
            }

            override fun onPageFinished(view: WebView, url: String) {
                pageReady = true
                swipe.isRefreshing = false
                CookieManager.getInstance().flush()
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (!request.isForMainFrame) return
                failedUrl = request.url.toString()
                swipe.isRefreshing = false
                pageReady = true
                val html = resources.openRawResource(R.raw.offline).bufferedReader().use { it.readText() }
                view.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread {
                    val host = request.origin.host ?: ""
                    if (!isOurHost(host)) { request.deny(); return@runOnUiThread }   // only our own site may use the camera / microphone
                    val need = mutableListOf<String>()
                    if (request.resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE) && !has(Manifest.permission.CAMERA)) need += Manifest.permission.CAMERA
                    if (request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE) && !has(Manifest.permission.RECORD_AUDIO)) need += Manifest.permission.RECORD_AUDIO
                    if (need.isEmpty()) grantWhatWeCan(request) else { pendingPermission = request; permLauncher.launch(need.toTypedArray()) }
                }
            }

            override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                val pick = params.createIntent().apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    if (params.mode == FileChooserParams.MODE_OPEN_MULTIPLE) putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }
                val extra = mutableListOf<Intent>()
                val wantsImage = params.acceptTypes.isEmpty() || params.acceptTypes.any { it.isBlank() || it.startsWith("image") }
                if (wantsImage && has(Manifest.permission.CAMERA)) {   // "take a photo now" next to "choose a file"
                    val dir = File(cacheDir, "camera").apply { mkdirs() }
                    val f = File(dir, "photo.jpg").apply { delete() }
                    cameraUri = FileProvider.getUriForFile(this@MainActivity, "$packageName.files", f)
                    extra += Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, cameraUri)
                        .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                } else cameraUri = null
                val chooser = Intent.createChooser(pick, "Choose a file").apply {
                    if (extra.isNotEmpty()) putExtra(Intent.EXTRA_INITIAL_INTENTS, extra.toTypedArray())
                }
                return try { fileLauncher.launch(chooser); true } catch (e: ActivityNotFoundException) { fileCallback = null; false }
            }

            // full screen video (the live player's ⛶ button)
            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (customView != null) { callback.onCustomViewHidden(); return }
                customView = view; customCallback = callback
                root.addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                swipe.visibility = View.GONE
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    hide(WindowInsetsCompat.Type.systemBars())
                    systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            }

            override fun onHideCustomView() = exitFullscreen()

            // a link that opens a new window (target=_blank): shown in a sheet over the page, so a live or a call is never left
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                val popup = WebView(this@MainActivity)
                popup.settings.javaScriptEnabled = true
                popup.settings.domStorageEnabled = true
                CookieManager.getInstance().setAcceptThirdPartyCookies(popup, true)
                val dialog = Dialog(this@MainActivity, android.R.style.Theme_Material_Light_NoActionBar_Fullscreen)
                if (SECURE) dialog.window?.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
                val box = FrameLayout(this@MainActivity)
                box.addView(popup, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                val close = android.widget.Button(this@MainActivity).apply { text = "✕  Close"; setOnClickListener { dialog.dismiss() } }
                box.addView(close, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, android.view.Gravity.TOP or android.view.Gravity.END))
                popup.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                        if (!r.isForMainFrame) return false
                        val outside = handleUrl(r.url)
                        if (outside) dialog.dismiss()
                        return outside
                    }
                }
                popup.webChromeClient = object : WebChromeClient() { override fun onCloseWindow(window: WebView) { dialog.dismiss() } }
                dialog.setContentView(box)
                dialog.setOnDismissListener { popup.destroy() }
                dialog.show()
                (resultMsg.obj as WebView.WebViewTransport).webView = popup
                resultMsg.sendToTarget()
                return true
            }
        }

        // files the site offers for download (receipts, backups, PDFs)
        web.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            if (!url.startsWith("http")) return@setDownloadListener
            try {
                val name = URLUtil.guessFileName(url, contentDisposition, mimeType)
                val req = DownloadManager.Request(Uri.parse(url))
                    .addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url) ?: "")
                    .addRequestHeader("User-Agent", userAgent)
                    .setMimeType(mimeType)
                    .setTitle(name)
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
                (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
                Toast.makeText(this, "Downloading $name…", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) { openOutside(Uri.parse(url)) }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    customView != null -> exitFullscreen()
                    web.canGoBack() -> web.goBack()
                    else -> { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
                }
            }
        })

        if (savedInstanceState != null) web.restoreState(savedInstanceState)
        else web.loadUrl(startUrl(intent))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.data?.let { if (isOurHost(it.host ?: "")) web.loadUrl(it.toString()) }
    }

    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); web.saveState(outState) }
    override fun onPause() { super.onPause(); CookieManager.getInstance().flush() }

    // ----------------------------------------------------------------- helpers
    private fun startUrl(i: Intent?): String {
        val d = i?.data
        return if (d != null && isOurHost(d.host ?: "")) d.toString() else BuildConfig.SITE_URL
    }

    private fun isOurHost(h: String): Boolean { val x = h.removePrefix("www."); return x == siteHost || x.endsWith(".$siteHost") }

    /** true = handled outside the app; false = load it in the app. */
    private fun handleUrl(uri: Uri): Boolean {
        val scheme = (uri.scheme ?: "").lowercase()
        if (scheme == "http" || scheme == "https") {
            val h = (uri.host ?: "").lowercase().removePrefix("www.")
            if (isOurHost(h)) return false
            if (EXTERNAL_HOSTS.any { h == it || h.endsWith(".$it") }) { openOutside(uri); return true }
            return false   // payment pages, bank OTP pages, Google Meet links… stay in the app and come back to the site
        }
        if (scheme == "intent") {   // UPI and app links from payment pages: intent://…#Intent;scheme=upi;package=…;end
            return try {
                val target = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)
                try { startActivity(target) } catch (e: ActivityNotFoundException) {
                    val fallback = target.getStringExtra("browser_fallback_url")
                    if (fallback != null) web.loadUrl(fallback)
                    else target.`package`?.let { p -> openOutside(Uri.parse("market://details?id=$p")) }
                }
                true
            } catch (e: Exception) { true }
        }
        openOutside(uri)   // upi:, tel:, mailto:, whatsapp:, phonepe:, paytmmp:, tez:, market: …
        return true
    }

    private fun openOutside(uri: Uri) {
        try { startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (e: ActivityNotFoundException) {
            val msg = if ((uri.scheme ?: "").lowercase() == "upi") "No UPI app found on this phone." else "No app found to open this."
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
    }

    private fun has(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun grantWhatWeCan(req: PermissionRequest) {
        val ok = req.resources.filter {
            (it == PermissionRequest.RESOURCE_VIDEO_CAPTURE && has(Manifest.permission.CAMERA)) ||
                (it == PermissionRequest.RESOURCE_AUDIO_CAPTURE && has(Manifest.permission.RECORD_AUDIO))
        }
        if (ok.isEmpty()) req.deny() else req.grant(ok.toTypedArray())
    }

    private fun exitFullscreen() {
        val v = customView ?: return
        root.removeView(v); customView = null
        swipe.visibility = View.VISIBLE
        customCallback?.onCustomViewHidden(); customCallback = null
        WindowCompat.getInsetsController(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
    }

    /** window.GrowdialApp in the page. */
    inner class Bridge {
        @JavascriptInterface fun isApp(): Boolean = true
        /** Keep the screen on while the teacher is live (true) / back to normal (false). */
        @JavascriptInterface fun keepAwake(on: Boolean) = runOnUiThread {
            if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        /** "Try again" on the no-internet page. */
        @JavascriptInterface fun reload() = runOnUiThread { web.loadUrl(failedUrl ?: BuildConfig.SITE_URL) }
    }
}
