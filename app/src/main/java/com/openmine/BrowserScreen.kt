package com.openmine

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.os.Message
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

private class BrowserSession(var restored: Bundle? = null) {
    var view: WebView? = null
}

private val BrowserSessionSaver = Saver<BrowserSession, Bundle>(
    save = { session ->
        Bundle().also { output ->
            session.view?.let { view ->
                runCatching { view.saveState(Bundle().also { output.putBundle("web", it) }) }
                output.putInt("scroll", view.scrollY)
            }
        }
    },
    restore = { BrowserSession(it) }
)

/** A real Android WebView. onExit returns to the caller when page history is exhausted. */
@Composable
fun BrowserScreen(modifier: Modifier = Modifier, onExit: () -> Unit = {}) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val prefs = remember(context) { context.getSharedPreferences("open_mine_browser", Context.MODE_PRIVATE) }
    val session = rememberSaveable(saver = BrowserSessionSaver) { BrowserSession() }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var draft by rememberSaveable { mutableStateOf(prefs.getString("last_url", "").orEmpty()) }
    var currentUrl by rememberSaveable { mutableStateOf(draft) }
    var javaScript by rememberSaveable { mutableStateOf(prefs.getBoolean("javascript", true)) }
    var pageTitle by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(0) }
    var canBack by remember { mutableStateOf(false) }
    var canForward by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var addressFocused by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var clearing by remember { mutableStateOf(false) }
    var rendererGone by remember { mutableStateOf(false) }
    var generation by remember { mutableIntStateOf(0) }
    var pendingScroll by remember(generation) { mutableIntStateOf(session.restored?.getInt("scroll") ?: prefs.getInt("scroll", 0)) }
    var userStopped by remember { mutableStateOf(false) }
    val popups = remember { mutableListOf<WebView>() }
    val destroyedViews = remember { java.util.Collections.newSetFromMap(java.util.WeakHashMap<WebView, Boolean>()) }
    var uploadCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    var multipleFiles by remember { mutableStateOf(false) }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val chosen = if (multipleFiles) uris else uris.take(1)
        uploadCallback?.onReceiveValue(chosen.takeIf { it.isNotEmpty() }?.toTypedArray())
        uploadCallback = null
    }
    val webViewResult = remember(context, generation) { runCatching { WebView(context) } }
    val webView = webViewResult.getOrNull()

    fun updateHistory(view: WebView) {
        if (session.view !== view || rendererGone) return
        val history = view.copyBackForwardList()
        canBack = history.currentIndex > 0
        canForward = history.currentIndex >= 0 && history.currentIndex < history.size - 1
    }

    fun scheduleHistoryUpdate(view: WebView) {
        updateHistory(view)
        // Providers may commit their native history after delivering a navigation callback.
        view.post { if (session.view === view && !rendererGone) updateHistory(view) }
    }

    fun traverseHistory(offset: Int): Boolean {
        val view = session.view ?: return false
        if (rendererGone || clearing) return false
        val history = view.copyBackForwardList()
        val target = history.currentIndex + offset
        if (target !in 0 until history.size) { updateHistory(view); return false }
        focus.clearFocus()
        keyboard?.hide()
        error = null
        notice = null
        userStopped = false
        pendingScroll = 0
        // Traverse the explicit entry shown by the toolbar, including script-created navigation.
        view.goBackOrForward(offset)
        scheduleHistoryUpdate(view)
        return true
    }

    fun destroy(view: WebView) {
        if (destroyedViews.add(view)) {
            (view.parent as? android.view.ViewGroup)?.removeView(view)
            view.destroy()
        }
    }

    fun persist(view: WebView) {
        // An invalidated or replaced renderer must never overwrite a cleared/new session.
        if (session.view !== view) return
        val address = view.url?.takeUnless { it == "about:blank" } ?: currentUrl
        if (address.isNotBlank() && runCatching { BrowserPolicy.navigation(address) }.isSuccess) {
            prefs.edit().putString("last_url", address).putInt("scroll", view.scrollY).apply()
        }
        CookieManager.getInstance().flush()
    }

    fun navigate(address: String) {
        val allowed = runCatching { BrowserPolicy.address(address) }
        if (allowed.isFailure) {
            notice = allowed.exceptionOrNull()?.message
            return
        }
        val url = allowed.getOrThrow()
        focus.clearFocus()
        keyboard?.hide()
        draft = url
        currentUrl = url
        pendingScroll = 0
        userStopped = false
        error = null
        notice = null
        prefs.edit().putString("last_url", url).putInt("scroll", 0).apply()
        if (rendererGone || webView == null) {
            session.restored = null
            rendererGone = false
            generation++
        } else {
            webView.loadUrl(url)
        }
    }

    fun retry() {
        if (currentUrl.isNotBlank()) {
            if (webView == null || rendererGone) navigate(currentUrl)
            else {
                error = null
                notice = null
                userStopped = false
                pendingScroll = webView.scrollY
                webView.reload()
            }
        }
        else if (webView == null || rendererGone) {
            rendererGone = false
            generation++
        }
    }

    BackHandler {
        when {
            addressFocused -> { focus.clearFocus(); keyboard?.hide() }
            traverseHistory(-1) -> Unit
            else -> onExit()
        }
    }

    DisposableEffect(webView, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (webView != null && !rendererGone) {
                when (event) {
                    Lifecycle.Event.ON_RESUME -> webView.onResume()
                    Lifecycle.Event.ON_PAUSE -> { persist(webView); webView.onPause() }
                    else -> Unit
                }
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            uploadCallback?.onReceiveValue(null)
            uploadCallback = null
            popups.forEach { it.destroy() }
            popups.clear()
            if (webView != null) {
                if (!rendererGone) runCatching { persist(webView); webView.stopLoading() }
                if (session.view === webView) session.view = null
                destroy(webView)
            }
        }
    }

    Column(modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = draft, onValueChange = { draft = it },
                label = { Text("Web address") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { navigate(draft) }),
                modifier = Modifier.weight(1f).padding(start = 8.dp).onFocusChanged { addressFocused = it.isFocused }
            )
            IconButton(onClick = { navigate(draft) }, enabled = !clearing, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.Search, "Go to web address")
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { traverseHistory(-1) }, enabled = canBack && !rendererGone && !clearing) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Previous page")
            }
            IconButton(onClick = { traverseHistory(1) }, enabled = canForward && !rendererGone && !clearing) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, "Next page")
            }
            IconButton(onClick = {
                if (loading) { userStopped = true; webView?.stopLoading(); loading = false; notice = "Page loading stopped." }
                else retry()
            }, enabled = currentUrl.isNotBlank() && !clearing) {
                Icon(if (loading) Icons.Default.Close else Icons.Default.Refresh, if (loading) "Stop loading" else "Reload page")
            }
            Text(pageTitle.ifBlank { if (currentUrl.isBlank()) "Browser" else currentUrl },
                modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelMedium)
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Browser options") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(if (javaScript) "Disable JavaScript" else "Enable JavaScript") }, onClick = {
                        menu = false
                        javaScript = !javaScript
                        prefs.edit().putBoolean("javascript", javaScript).apply()
                        if (!rendererGone) webView?.settings?.javaScriptEnabled = javaScript
                        if (currentUrl.isNotBlank()) retry()
                    })
                    DropdownMenuItem(text = { Text("Open in external browser") }, enabled = currentUrl.isNotBlank(), onClick = {
                        menu = false
                        runCatching {
                            val uri = Uri.parse(BrowserPolicy.navigation(currentUrl))
                            context.startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
                        }.onFailure { notice = "No external browser can open this page." }
                    })
                    DropdownMenuItem(text = { Text("Clear browsing data") }, onClick = { menu = false; confirmClear = true })
                    DropdownMenuItem(text = { Text("Return to workspace") }, onClick = { menu = false; onExit() })
                }
            }
        }
        if (loading) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
        notice?.let { message ->
            Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
                Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    IconButton(onClick = { notice = null }) { Icon(Icons.Default.Close, "Dismiss browser message") }
                }
            }
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            if (webView != null && !rendererGone) key(generation) {
                AndroidView(modifier = Modifier.fillMaxSize(), factory = {
                    webView.apply {
                        session.view = this
                        settings.javaScriptEnabled = javaScript
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        settings.safeBrowsingEnabled = true
                        settings.setGeolocationEnabled(false)
                        settings.javaScriptCanOpenWindowsAutomatically = false
                        settings.setSupportMultipleWindows(true)
                        settings.mediaPlaybackRequiresUserGesture = true
                        settings.builtInZoomControls = true
                        settings.displayZoomControls = false
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = true
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                val allowed = runCatching { BrowserPolicy.navigation(request.url.toString()) }
                                if (allowed.isFailure) {
                                    if (request.isForMainFrame) {
                                        notice = allowed.exceptionOrNull()?.message
                                        view.stopLoading()
                                        loading = false
                                    }
                                    return true
                                }
                                return false
                            }
                            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                                if (session.view !== view || url == "about:blank") return
                                loading = true
                                userStopped = false
                                progress = 0
                                error = null
                                currentUrl = url.orEmpty()
                                if (!addressFocused) draft = currentUrl
                                scheduleHistoryUpdate(view)
                            }
                            override fun onPageFinished(view: WebView, url: String?) {
                                if (session.view !== view) return
                                scheduleHistoryUpdate(view)
                                // A late finish from the previous page must not overwrite a newer URL/load.
                                if (url != null && url != view.url) return
                                loading = false
                                if (url != "about:blank") {
                                    currentUrl = url.orEmpty()
                                    if (!addressFocused) draft = currentUrl
                                    val scroll = pendingScroll
                                    pendingScroll = 0
                                    view.post {
                                        if (session.view === view && !rendererGone) {
                                            if (scroll > 0) view.scrollTo(0, scroll)
                                            persist(view)
                                        }
                                    }
                                }
                            }
                            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                                if (session.view !== view) return
                                scheduleHistoryUpdate(view)
                                if (url != null && url != "about:blank") {
                                    currentUrl = url
                                    if (!addressFocused) draft = url
                                }
                            }
                            override fun onPageCommitVisible(view: WebView, url: String?) {
                                if (session.view !== view) return
                                scheduleHistoryUpdate(view)
                                pageTitle = view.title.orEmpty()
                            }
                            override fun onReceivedError(view: WebView, request: WebResourceRequest, failure: WebResourceError) {
                                if (session.view === view && request.isForMainFrame && !userStopped) {
                                    loading = false
                                    error = "This page could not load: ${failure.description}. Check your connection or address, then retry."
                                }
                            }
                            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                                if (session.view === view && request.isForMainFrame) notice = "The website returned HTTP ${response.statusCode}. Its response is shown below."
                            }
                            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, sslError: SslError) {
                                handler.cancel()
                                if (session.view !== view) return
                                loading = false
                                error = "The site's certificate could not be verified. The connection was blocked. Check the address and device date, or contact the site owner."
                            }
                            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                                loading = false
                                session.view = null
                                rendererGone = true
                                error = "The browser renderer stopped. Retry to reopen this page."
                                destroy(view)
                                return true
                            }
                        }
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView, newProgress: Int) {
                                if (session.view === view) { progress = newProgress; scheduleHistoryUpdate(view) }
                            }
                            override fun onReceivedTitle(view: WebView, title: String?) {
                                if (session.view === view) { pageTitle = title.orEmpty(); scheduleHistoryUpdate(view) }
                            }
                            override fun onPermissionRequest(request: PermissionRequest) {
                                request.deny()
                                notice = "Website camera and microphone access is unavailable in this browser."
                            }
                            override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                                uploadCallback?.onReceiveValue(null)
                                uploadCallback = callback
                                multipleFiles = params.mode == FileChooserParams.MODE_OPEN_MULTIPLE
                                val types = params.acceptTypes.filter { it.contains('/') }.toTypedArray().ifEmpty { arrayOf("*/*") }
                                runCatching { files.launch(types) }.onFailure {
                                    uploadCallback?.onReceiveValue(null)
                                    uploadCallback = null
                                    notice = "No document picker is available on this device."
                                }
                                return true
                            }
                            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                                if (!isUserGesture) return false
                                val popup = WebView(context)
                                popup.settings.allowFileAccess = false
                                popup.settings.allowContentAccess = false
                                popups.add(popup)
                                popup.webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(child: WebView, request: WebResourceRequest): Boolean {
                                        navigate(request.url.toString())
                                        child.post { if (popups.remove(child)) child.destroy() }
                                        return true
                                    }
                                }
                                popup.webChromeClient = object : WebChromeClient() {
                                    override fun onCloseWindow(window: WebView) { if (popups.remove(window)) window.destroy() }
                                }
                                (resultMsg.obj as WebView.WebViewTransport).webView = popup
                                resultMsg.sendToTarget()
                                return true
                            }
                        }
                        setDownloadListener { _, _, _, _, _ ->
                            notice = "File downloads are handled by your external browser. Choose Open in external browser from Browser options."
                        }
                        val saved = session.restored
                        session.restored = null
                        val restored = saved?.getBundle("web")?.let { restoreState(it) } != null
                        if (restored) {
                            pageTitle = title.orEmpty()
                            scheduleHistoryUpdate(this)
                        }
                        if (!restored && currentUrl.isNotBlank()) {
                            runCatching { BrowserPolicy.navigation(currentUrl) }.onSuccess { loadUrl(it) }
                                .onFailure { error = it.message }
                        }
                    }
                })
            }
            val unavailable = webViewResult.exceptionOrNull()?.let { "Android WebView could not start. Install or enable a supported Android System WebView provider in device settings, then retry." }
            val displayedError = unavailable ?: error
            if (displayedError != null || currentUrl.isBlank() || clearing) Surface(Modifier.fillMaxSize()) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(when {
                        clearing -> "Clearing browsing data…"
                        displayedError != null -> displayedError
                        else -> "Enter a web address to start browsing. HTTPS websites and services on localhost are supported."
                    })
                    if (displayedError != null) Button(onClick = { retry() }, modifier = Modifier.padding(top = 16.dp)) { Text("Retry") }
                }
            }
        }
    }

    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false }, title = { Text("Clear browsing data?") },
        text = { Text("This removes browser history, cookies, site storage and cache, and signs you out of websites. Workspace objects and models are kept.") },
        confirmButton = { TextButton(onClick = {
            confirmClear = false
            clearing = true
            session.view = null
            if (!rendererGone) { webView?.stopLoading(); webView?.loadUrl("about:blank"); webView?.clearHistory(); webView?.clearCache(true) }
            WebStorage.getInstance().deleteAllData()
            CookieManager.getInstance().removeAllCookies {
                CookieManager.getInstance().flush()
                prefs.edit().remove("last_url").remove("scroll").apply()
                session.restored = null
                currentUrl = ""
                draft = ""
                pageTitle = ""
                canBack = false
                canForward = false
                error = null
                notice = "Browsing data cleared."
                loading = false
                clearing = false
                generation++
            }
        }) { Text("Clear data") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } }
    )
}
