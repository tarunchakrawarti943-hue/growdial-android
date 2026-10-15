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
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
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
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.ViewCompat
import org.json.JSONObject
import org.json.JSONTokener
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
        /** Pages that need the whole screen: no bottom tabs there (the top bar stays for "back"). */
        val NO_TABS = listOf("live-room.php", "live-recording.php", "watch.php", "recording-watch.php")
        val BRAND: Int = 0xFF4F46E5.toInt()
        val MUTED: Int = 0xFF64748B.toInt()
        val INK: Int = 0xFF0F172A.toInt()
        val NO_PULL = listOf("live-room.php", "go-live.php", "live-recording.php", "messages.php", "watch.php", "recording-watch.php")
    }

    private lateinit var web: WebView
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var root: LinearLayout
    private lateinit var content: FrameLayout
    // the app's own (native) parts around the website
    private lateinit var topBar: LinearLayout
    private lateinit var backBtn: ImageButton
    private lateinit var titleView: TextView
    private lateinit var bellBox: FrameLayout
    private lateinit var bellBadge: TextView
    private lateinit var progress: ProgressBar
    private lateinit var bottomBar: LinearLayout
    private lateinit var skeleton: LinearLayout
    private var skeletonPulse: ObjectAnimator? = null
    private var role = "guest"
    private var unread = 0
    private var committed = false
    private var firstPage = true
    private val ui = Handler(Looper.getMainLooper())
    private val siteBase: String by lazy { Uri.parse(BuildConfig.SITE_URL).let { "${it.scheme}://${it.host}" } }

    data class Tab(val label: String, val icon: Int, val path: String)

    /** The bottom tabs for who is signed in (the page says it in <meta name="gd-role">). */
    private fun tabsFor(r: String): List<Tab> = when (r) {
        "student" -> listOf(Tab("Home", R.drawable.ic_home, "/"), Tab("Courses", R.drawable.ic_book, "/courses.php"), Tab("Live", R.drawable.ic_live, "/live.php"), Tab("Wallet", R.drawable.ic_wallet, "/student/wallet.php"), Tab("Me", R.drawable.ic_user, "/student/dashboard.php"))
        "teacher" -> listOf(Tab("Home", R.drawable.ic_grid, "/teacher/dashboard.php"), Tab("Go Live", R.drawable.ic_video, "/teacher/go-live.php"), Tab("Students", R.drawable.ic_users, "/teacher/students.php"), Tab("Earnings", R.drawable.ic_money, "/teacher/earnings.php"), Tab("Messages", R.drawable.ic_chat, "/messages.php"))
        "admin" -> listOf(Tab("Home", R.drawable.ic_grid, "/admin/dashboard.php"), Tab("Users", R.drawable.ic_users, "/admin/users.php"), Tab("Live", R.drawable.ic_live, "/admin/live.php"), Tab("Settings", R.drawable.ic_sliders, "/admin/settings.php"), Tab("Site", R.drawable.ic_home, "/"))
        "manager" -> listOf(Tab("Home", R.drawable.ic_grid, "/manager/dashboard.php"), Tab("Site", R.drawable.ic_home, "/"), Tab("Live", R.drawable.ic_live, "/live.php"))
        else -> listOf(Tab("Home", R.drawable.ic_home, "/"), Tab("Courses", R.drawable.ic_book, "/courses.php"), Tab("Live", R.drawable.ic_live, "/live.php"), Tab("Login", R.drawable.ic_login, "/login.php"))
    }
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

        // ---- screen: [top bar] [thin progress line] [website + loading skeleton] [bottom tabs]
        WindowCompat.setDecorFitsSystemWindows(window, false)   // we place the bars ourselves (Android 15 draws edge to edge)
        window.statusBarColor = Color.WHITE
        window.navigationBarColor = Color.WHITE
        WindowCompat.getInsetsController(window, window.decorView).apply { isAppearanceLightStatusBars = true; isAppearanceLightNavigationBars = true }
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE) }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            WindowInsetsCompat.CONSUMED
        }
        buildTopBar()
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; isIndeterminate = false; progressTintList = ColorStateList.valueOf(BRAND); progressBackgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT); visibility = View.INVISIBLE
        }
        root.addView(progress, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(3)).apply { topMargin = -dp(1); bottomMargin = -dp(2) })
        content = FrameLayout(this)
        swipe = SwipeRefreshLayout(this)
        web = WebView(this)
        swipe.addView(web, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        content.addView(swipe, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        buildSkeleton()
        root.addView(content, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setBackgroundColor(Color.WHITE); elevation = dp(8).toFloat(); gravity = Gravity.CENTER_VERTICAL
        }
        root.addView(bottomBar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(60)))
        buildTabs()
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
                committed = false
                progress.progress = 5; progress.visibility = View.VISIBLE
                ui.postDelayed({ if (!committed) showSkeleton(true) }, 280)   // only for slow pages: fast ones never flash it
                updateChrome(url)
            }

            override fun onPageCommitVisible(view: WebView, url: String) {
                committed = true
                showSkeleton(false)
                if (!firstPage) { web.alpha = 0.55f; web.animate().alpha(1f).setDuration(180).start() }   // a soft fade between pages
                firstPage = false
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) { updateChrome(url) }

            override fun onPageFinished(view: WebView, url: String) {
                pageReady = true
                committed = true; showSkeleton(false)
                progress.visibility = View.INVISIBLE
                readPageInfo()
                updateChrome(url)
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
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress.progress = newProgress
                if (newProgress >= 100) progress.visibility = View.INVISIBLE
            }

            override fun onReceivedTitle(view: WebView, title: String?) { setTitleFrom(title) }

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
                swipe.visibility = View.GONE; topBar.visibility = View.GONE; bottomBar.visibility = View.GONE; progress.visibility = View.GONE
                (customView?.parent as? ViewGroup)?.removeView(customView)
                content.addView(customView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
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
        content.removeView(v); customView = null
        swipe.visibility = View.VISIBLE; topBar.visibility = View.VISIBLE; progress.visibility = View.INVISIBLE
        updateChrome(web.url ?: "")
        customCallback?.onCustomViewHidden(); customCallback = null
        WindowCompat.getInsetsController(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
    }

    // ----------------------------------------------------------------- the native top bar, bottom tabs and loading skeleton
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun iconButton(icon: Int, desc: String): ImageButton = ImageButton(this).apply {
        setImageResource(icon); imageTintList = ColorStateList.valueOf(INK); contentDescription = desc
        background = null; scaleType = ImageView.ScaleType.CENTER
        val tv = android.util.TypedValue(); context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true); setBackgroundResource(tv.resourceId)
    }

    private fun buildTopBar() {
        topBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(Color.WHITE); elevation = dp(2).toFloat(); setPadding(dp(4), 0, dp(4), 0) }
        backBtn = iconButton(R.drawable.ic_back, "Back").apply { setOnClickListener { performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); if (web.canGoBack()) web.goBack() } }
        titleView = TextView(this).apply { textSize = 17f; setTextColor(INK); typeface = android.graphics.Typeface.DEFAULT_BOLD; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; text = getString(R.string.app_name); setPadding(dp(8), 0, dp(4), 0) }
        bellBox = FrameLayout(this)
        val bell = iconButton(R.drawable.ic_bell, "Notifications").apply { setOnClickListener { performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); web.loadUrl("$siteBase/notifications.php") } }
        bellBadge = TextView(this).apply {
            textSize = 10f; setTextColor(Color.WHITE); typeface = android.graphics.Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; minWidth = dp(17); setPadding(dp(4), 0, dp(4), 0)
            background = GradientDrawable().apply { setColor(0xFFDC2626.toInt()); cornerRadius = dp(9).toFloat() }; visibility = View.GONE
        }
        bellBox.addView(bell, FrameLayout.LayoutParams(dp(44), dp(44)))
        bellBox.addView(bellBadge, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, dp(17), Gravity.TOP or Gravity.END).apply { topMargin = dp(5); rightMargin = dp(3) })
        val share = iconButton(R.drawable.ic_share, "Share").apply {
            setOnClickListener {
                val u = web.url ?: return@setOnClickListener
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "${titleView.text}\n$u"), "Share"))
            }
        }
        topBar.addView(backBtn, LinearLayout.LayoutParams(dp(44), dp(44)))
        topBar.addView(titleView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        topBar.addView(bellBox, LinearLayout.LayoutParams(dp(44), dp(44)))
        topBar.addView(share, LinearLayout.LayoutParams(dp(44), dp(44)))
        root.addView(topBar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(54)))
    }

    private fun buildTabs() {
        bottomBar.removeAllViews()
        for (t in tabsFor(role)) {
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; tag = t.path; contentDescription = t.label
                val tv = android.util.TypedValue(); context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true); setBackgroundResource(tv.resourceId)
                setOnClickListener { performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); web.loadUrl(siteBase + t.path) }
            }
            cell.addView(ImageView(this).apply { setImageResource(t.icon); tag = "icon" }, LinearLayout.LayoutParams(dp(24), dp(24)))
            cell.addView(TextView(this).apply { text = t.label; textSize = 11f; tag = "label"; gravity = Gravity.CENTER; setPadding(0, dp(3), 0, 0) })
            bottomBar.addView(cell, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        }
        updateChrome(web.url ?: "")
    }

    private fun pathOf(url: String): String { val p = Uri.parse(url).path ?: "/"; return if (p == "" || p == "/index.php") "/" else p }

    /** Back arrow, the active tab and whether the tabs show — after every page change. */
    private fun updateChrome(url: String) {
        if (!::bottomBar.isInitialized || customView != null) return
        val path = pathOf(url)
        val tabs = tabsFor(role)
        val isRoot = tabs.any { it.path == path }
        backBtn.visibility = if (!isRoot && web.canGoBack()) View.VISIBLE else View.GONE
        titleView.setPadding(if (backBtn.visibility == View.VISIBLE) dp(4) else dp(12), 0, dp(4), 0)
        bottomBar.visibility = if (NO_TABS.any { url.contains(it) }) View.GONE else View.VISIBLE
        for (i in 0 until bottomBar.childCount) {
            val cell = bottomBar.getChildAt(i) as LinearLayout
            val on = cell.tag == path
            val color = if (on) BRAND else MUTED
            (cell.findViewWithTag<ImageView>("icon"))?.imageTintList = ColorStateList.valueOf(color)
            (cell.findViewWithTag<TextView>("label"))?.apply { setTextColor(color); typeface = if (on) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT }
        }
        bellBox.visibility = if (role == "guest") View.GONE else View.VISIBLE
    }

    private fun setTitleFrom(t: String?) {
        var x = (t ?: "").trim()
        for (sep in listOf(" — ", " | ", " · ", " - ")) { val i = x.indexOf(sep); if (i > 0) x = x.substring(0, i) }
        titleView.text = if (x.isBlank() || x.startsWith("http")) getString(R.string.app_name) else x
    }

    /** Who is signed in + unread notifications, from the page's <meta> tags (printed only inside the app). */
    private fun readPageInfo() {
        val js = "(function(){var r=document.querySelector('meta[name=gd-role]'),u=document.querySelector('meta[name=gd-unread]');return JSON.stringify({role:r?r.content:'',unread:u?+u.content:0});})()"
        web.evaluateJavascript(js) { raw ->
            try {
                val o = JSONObject(JSONTokener(raw).nextValue() as String)
                val r = o.optString("role", "")
                if (r.isNotEmpty() && r != role) { role = r; buildTabs() }
                unread = o.optInt("unread", 0)
                bellBadge.text = if (unread > 99) "99+" else unread.toString()
                bellBadge.visibility = if (unread > 0) View.VISIBLE else View.GONE
                updateChrome(web.url ?: "")
            } catch (e: Exception) { }
        }
    }

    private fun buildSkeleton() {
        skeleton = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE); setPadding(dp(16), dp(16), dp(16), dp(16)); visibility = View.GONE; isClickable = true }
        fun block(h: Int, widthPct: Float = 1f, top: Int = 12) {
            val v = View(this).apply { background = GradientDrawable().apply { setColor(0xFFEEF1F6.toInt()); cornerRadius = dp(12).toFloat() } }
            val lp = LinearLayout.LayoutParams((resources.displayMetrics.widthPixels * widthPct).toInt() - dp(32), dp(h)).apply { topMargin = dp(top) }
            skeleton.addView(v, lp)
        }
        block(22, 0.55f, 0); block(160); block(16, 0.9f, 16); block(16, 0.7f, 8); block(96, 1f, 16); block(16, 0.8f, 16); block(16, 0.6f, 8); block(96, 1f, 16)
        content.addView(skeleton, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    }

    private fun showSkeleton(on: Boolean) {
        if (!::skeleton.isInitialized) return
        if (on) {
            if (skeleton.visibility == View.VISIBLE) return
            skeleton.alpha = 1f; skeleton.visibility = View.VISIBLE
            skeletonPulse = ObjectAnimator.ofFloat(skeleton, "alpha", 1f, 0.55f).apply { duration = 700; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE; start() }
        } else if (skeleton.visibility == View.VISIBLE) {
            skeletonPulse?.cancel(); skeletonPulse = null
            skeleton.animate().alpha(0f).setDuration(150).withEndAction { skeleton.visibility = View.GONE; skeleton.alpha = 1f }.start()
        }
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
