package com.instalogger.app.ui.screens

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.instalogger.app.InstaLoggerApp
import com.instalogger.app.R
import com.instalogger.app.ui.animation.bouncyClickable
import com.instalogger.app.ui.components.HeaderActionButton
import com.instalogger.app.ui.components.ReachabilityHeader
import com.instalogger.app.ui.components.SlidingPillTabRow
import com.instalogger.app.ui.components.StatusBadge
import com.instalogger.app.ui.components.TabItem
import com.instalogger.app.ui.theme.*
import kotlinx.coroutines.launch

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun InstagramSessionScreen(
    isActiveTab: Boolean = true
) {
    val context = LocalContext.current
    val app = context.applicationContext as InstaLoggerApp
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val autoPreviewEnabled by app.settingsManager.sessionWebViewAutoPreview.collectAsStateWithLifecycle()
    var isWebViewOpen by remember(autoPreviewEnabled) { mutableStateOf(autoPreviewEnabled) }
    var webViewAttachAllowed by remember { mutableStateOf(false) }

    var selectedTab by remember { mutableIntStateOf(0) }
    var hasSession by remember { mutableStateOf(app.sessionManager.hasSession()) }
    var showClearSessionDialog by remember { mutableStateOf(false) }
    var manualCookieText by remember { mutableStateOf(app.sessionManager.getCookieString().orEmpty()) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var isLoadingWeb by remember { mutableStateOf(true) }
    var webProgress by remember { mutableFloatStateOf(0.1f) }

    // Defer WebView inflation slightly past the 160ms tab glide so switching into Session never stutters
    LaunchedEffect(isActiveTab, isWebViewOpen) {
        if (isActiveTab && isWebViewOpen && !webViewAttachAllowed) {
            kotlinx.coroutines.delay(175L)
            webViewAttachAllowed = true
        }
        val wv = webViewRef ?: return@LaunchedEffect
        if (isActiveTab && isWebViewOpen) {
            wv.visibility = android.view.View.VISIBLE
            runCatching { wv.onResume() }
        } else {
            runCatching { wv.onPause() }
            wv.visibility = android.view.View.GONE
        }
    }

    fun closeWebView() {
        runCatching {
            webViewRef?.stopLoading()
            webViewRef?.destroy()
        }
        webViewRef = null
        isLoadingWeb = false
        isWebViewOpen = false
    }

    DisposableEffect(Unit) {
        onDispose {
            runCatching {
                webViewRef?.stopLoading()
                webViewRef?.destroy()
            }
            webViewRef = null
        }
    }

    val capturedMsg = stringResource(R.string.msg_session_captured)
    val savedMsg = stringResource(R.string.msg_cookie_saved)
    val clearedMsg = stringResource(R.string.msg_session_cleared)
    val copiedMsg = stringResource(R.string.msg_session_copied)

    // Keep manual field synced with active session when switching tabs or refreshing
    LaunchedEffect(selectedTab, hasSession) {
        if (selectedTab == 1 && hasSession && manualCookieText.isBlank()) {
            manualCookieText = app.sessionManager.getCookieString().orEmpty()
        }
    }

    // Periodic automatic cookie detection ONLY while tab is active and login WebView is open
    LaunchedEffect(isActiveTab, selectedTab, hasSession, isWebViewOpen) {
        if (isActiveTab && selectedTab == 0 && isWebViewOpen && !hasSession) {
            while (isActiveTab && !hasSession && isWebViewOpen) {
                kotlinx.coroutines.delay(1500)
                try {
                    val cookies = captureMergedInstagramCookies()
                    if (cookies != null && cookies.contains("sessionid=")) {
                        app.sessionManager.saveCookieString(cookies)
                        manualCookieText = cookies
                        hasSession = true
                        snackbarHostState.showSnackbar(capturedMsg)
                        break
                    }
                } catch (e: Exception) {
                }
            }
        }
    }

    Scaffold(
        topBar = {
            ReachabilityHeader(
                title = stringResource(R.string.session_title),
                subtitle = stringResource(R.string.session_subtitle),
                logoResId = R.drawable.ic_header_session,
                actions = {
                    HeaderActionButton(onClick = { hasSession = app.sessionManager.hasSession() }) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(bottom = 12.dp)
        ) {
            val statusCardShape = ShapeTokens.LargeShape
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .clip(statusCardShape)
                    .border(ShapeTokens.BorderSubtle, MaterialTheme.colorScheme.outlineVariant, statusCardShape),
                shape = statusCardShape,
                color = MaterialTheme.colorScheme.surfaceContainer,
                tonalElevation = 2.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.session_status_card_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = if (hasSession) stringResource(R.string.session_active_desc) else stringResource(R.string.session_inactive_desc),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    val isDark = isSystemInDarkTheme()
                    if (hasSession) {
                        StatusBadge(
                            text = stringResource(R.string.badge_active),
                            icon = Icons.Default.CheckCircle,
                            containerColor = if (isDark) StatusGreenSubtleDark else StatusGreenLight,
                            contentColor = StatusGreen,
                            borderColor = StatusGreen.copy(alpha = 0.3f)
                        )
                    } else {
                        StatusBadge(
                            text = stringResource(R.string.badge_inactive),
                            icon = Icons.Default.Lock,
                            containerColor = if (isDark) StatusRedSubtleDark else StatusRedLight,
                            contentColor = StatusRed,
                            borderColor = StatusRed.copy(alpha = 0.3f)
                        )
                    }
                }
            }

            SlidingPillTabRow(
                tabs = listOf(
                    TabItem(title = stringResource(R.string.tab_webview_login)),
                    TabItem(title = stringResource(R.string.tab_manual_cookie))
                ),
                selectedIndex = selectedTab,
                onTabSelected = { selectedTab = it },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            when (selectedTab) {
                0 -> {
                    val webViewShape = ShapeTokens.LargeShape
                    AnimatedContent(
                        targetState = isWebViewOpen,
                        transitionSpec = {
                            fadeIn(tween(220)) togetherWith fadeOut(tween(180))
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        label = "session_webview_state"
                    ) { open ->
                        if (!open) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .widthIn(max = 460.dp)
                                        .clip(webViewShape)
                                        .border(
                                            ShapeTokens.BorderSubtle,
                                            MaterialTheme.colorScheme.outlineVariant,
                                            webViewShape
                                        ),
                                    shape = webViewShape,
                                    color = MaterialTheme.colorScheme.surfaceContainer,
                                    tonalElevation = 2.dp
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 24.dp, vertical = 28.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(14.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(64.dp)
                                                .clip(CircleShape)
                                                .background(AccentBlue.copy(alpha = 0.14f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Public,
                                                contentDescription = null,
                                                tint = AccentBlue,
                                                modifier = Modifier.size(30.dp)
                                            )
                                        }

                                        Text(
                                            text = stringResource(R.string.session_webview_closed_title),
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            textAlign = TextAlign.Center
                                        )

                                        Text(
                                            text = stringResource(R.string.session_webview_closed_desc),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            textAlign = TextAlign.Center
                                        )

                                        Spacer(modifier = Modifier.height(4.dp))

                                        Button(
                                            onClick = {
                                                isLoadingWeb = true
                                                webProgress = 0.1f
                                                isWebViewOpen = true
                                            },
                                            shape = ShapeTokens.MediumShape,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .heightIn(min = 48.dp)
                                                .testTag("session_open_webview_btn")
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.OpenInBrowser,
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = stringResource(R.string.session_webview_btn_open),
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }
                                    }
                                }
                            }
                        } else {
                            Column(modifier = Modifier.fillMaxSize()) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 6.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = stringResource(R.string.webview_login_title),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )

                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        TextButton(
                                            onClick = {
                                                val cookies = captureMergedInstagramCookies()
                                                if (cookies != null && cookies.contains("sessionid=")) {
                                                    app.sessionManager.saveCookieString(cookies)
                                                    manualCookieText = cookies
                                                    hasSession = true
                                                    scope.launch { snackbarHostState.showSnackbar(capturedMsg) }
                                                } else {
                                                    scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.session_not_detected_yet)) }
                                                }
                                            },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                        ) {
                                            Icon(Icons.Default.VpnKey, contentDescription = null, modifier = Modifier.size(14.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(stringResource(R.string.btn_check), style = MaterialTheme.typography.labelSmall)
                                        }

                                        TextButton(
                                            onClick = { webViewRef?.reload() },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                        ) {
                                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(stringResource(R.string.btn_refresh), style = MaterialTheme.typography.labelSmall)
                                        }

                                        TextButton(
                                            onClick = { closeWebView() },
                                            colors = ButtonDefaults.textButtonColors(
                                                contentColor = MaterialTheme.colorScheme.error
                                            ),
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                            modifier = Modifier.testTag("session_close_webview_btn")
                                        ) {
                                            Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(14.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(stringResource(R.string.session_webview_btn_close), style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                }

                                Surface(
                                    modifier = Modifier.fillMaxSize(),
                                    shape = webViewShape,
                                    border = androidx.compose.foundation.BorderStroke(ShapeTokens.BorderSubtle, MaterialTheme.colorScheme.outlineVariant),
                                    color = MaterialTheme.colorScheme.surface
                                ) {
                                    Box(modifier = Modifier.fillMaxSize()) {
                                        val isDark = isSystemInDarkTheme()
                                        if (webViewAttachAllowed) {
                                        AndroidView(
                                            factory = { ctx ->
                                                WebView(ctx).apply {
                                                    webViewRef = this
                                                    setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
                                                    layoutParams = ViewGroup.LayoutParams(
                                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                                        ViewGroup.LayoutParams.MATCH_PARENT
                                                    )
                                                    setBackgroundColor(if (isDark) 0xFF0D0E12.toInt() else 0xFFFFFFFF.toInt())
                                                    val cm = CookieManager.getInstance()
                                                    cm.setAcceptCookie(true)
                                                    cm.setAcceptThirdPartyCookies(this, true)

                                                    settings.apply {
                                                        javaScriptEnabled = true
                                                        domStorageEnabled = true
                                                        databaseEnabled = true
                                                        useWideViewPort = true
                                                        loadWithOverviewMode = true
                                                        javaScriptCanOpenWindowsAutomatically = true
                                                        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                                                        cacheMode = WebSettings.LOAD_DEFAULT
                                                        userAgentString = "Mozilla/5.0 (Linux; Android 14; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.6778.200 Mobile Safari/537.36"
                                                    }

                                                    webChromeClient = object : WebChromeClient() {
                                                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                                            webProgress = newProgress / 100f
                                                            isLoadingWeb = newProgress < 100
                                                        }
                                                    }

                                                    webViewClient = object : WebViewClient() {
                                                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                                            val uri = request?.url ?: return false
                                                            val scheme = uri.scheme?.lowercase()
                                                            if (scheme == "http" || scheme == "https") {
                                                                return false
                                                            }
                                                            // Prevent intent:// or instagram:// redirects from failing
                                                            return true
                                                        }

                                                        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: android.webkit.WebResourceError?) {
                                                            super.onReceivedError(view, request, error)
                                                            if (request?.isForMainFrame == true) {
                                                                isLoadingWeb = false
                                                            }
                                                        }

                                                        override fun onPageFinished(view: WebView?, url: String?) {
                                                            super.onPageFinished(view, url)
                                                            isLoadingWeb = false
                                                            val cookies = captureMergedInstagramCookies()
                                                            if (cookies != null && cookies.contains("sessionid=")) {
                                                                app.sessionManager.saveCookieString(cookies)
                                                                manualCookieText = cookies
                                                                hasSession = true
                                                                scope.launch {
                                                                    snackbarHostState.showSnackbar(capturedMsg)
                                                                }
                                                            }
                                                        }
                                                    }
                                                    loadUrl("https://www.instagram.com/accounts/login/")
                                                }
                                            },
                                            modifier = Modifier.fillMaxSize()
                                        )
                                        }

                                        if (isLoadingWeb) {
                                            LinearProgressIndicator(
                                                progress = { webProgress },
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .align(Alignment.TopCenter)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            1 -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        val cookieCardShape = ShapeTokens.LargeShape
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(cookieCardShape)
                                .border(ShapeTokens.BorderSubtle, MaterialTheme.colorScheme.outlineVariant, cookieCardShape),
                            shape = cookieCardShape,
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            tonalElevation = 2.dp
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(20.dp),
                                verticalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.cookie_input_desc),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                OutlinedTextField(
                                    value = manualCookieText,
                                    onValueChange = { manualCookieText = it },
                                    label = { Text(stringResource(R.string.cookie_input_label)) },
                                    placeholder = { Text("sessionid=...") },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(130.dp),
                                    maxLines = 6,
                                    shape = ShapeTokens.MediumShape,
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                                    )
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Button(
                                        onClick = {
                                            val trimmed = manualCookieText.trim()
                                            if (trimmed.isNotBlank()) {
                                                app.sessionManager.saveCookieString(trimmed)
                                                manualCookieText = trimmed
                                                hasSession = true
                                                scope.launch {
                                                    snackbarHostState.showSnackbar(savedMsg)
                                                }
                                            }
                                        },
                                        enabled = manualCookieText.isNotBlank(),
                                        shape = ShapeTokens.MediumShape,
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(46.dp)
                                    ) {
                                        Icon(Icons.Default.VpnKey, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = stringResource(R.string.btn_save_cookie),
                                            maxLines = 1,
                                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                        )
                                    }

                                    OutlinedButton(
                                        onClick = { showClearSessionDialog = true },
                                        enabled = hasSession,
                                        shape = ShapeTokens.MediumShape,
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(46.dp)
                                    ) {
                                        Icon(Icons.Default.DeleteOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = stringResource(R.string.btn_clear_session),
                                            maxLines = 1,
                                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                        )
                                    }
                                }

                                if (hasSession) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        FilledTonalButton(
                                            onClick = {
                                                val activeCookie = manualCookieText.trim()
                                                    .ifBlank { app.sessionManager.getCookieString().orEmpty() }
                                                if (activeCookie.isNotBlank()) {
                                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                                    clipboard.setPrimaryClip(ClipData.newPlainText("instagram_session", activeCookie))
                                                    scope.launch { snackbarHostState.showSnackbar(copiedMsg) }
                                                }
                                            },
                                            shape = ShapeTokens.MediumShape,
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(46.dp)
                                        ) {
                                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = stringResource(R.string.btn_copy_session),
                                                maxLines = 1,
                                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                            )
                                        }

                                        FilledTonalButton(
                                            onClick = {
                                                val activeCookie = manualCookieText.trim()
                                                    .ifBlank { app.sessionManager.getCookieString().orEmpty() }
                                                if (activeCookie.isNotBlank()) {
                                                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                                        type = "text/plain"
                                                        putExtra(Intent.EXTRA_SUBJECT, "InstaLogger Session")
                                                        putExtra(Intent.EXTRA_TEXT, activeCookie)
                                                    }
                                                    context.startActivity(Intent.createChooser(shareIntent, context.getString(R.string.btn_export_session)))
                                                }
                                            },
                                            shape = ShapeTokens.MediumShape,
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(46.dp)
                                        ) {
                                            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = stringResource(R.string.btn_export_session),
                                                maxLines = 1,
                                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showClearSessionDialog) {
        AlertDialog(
            onDismissRequest = { showClearSessionDialog = false },
            title = {
                Text(
                    text = stringResource(R.string.session_clear_confirm_title),
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.session_clear_confirm_desc),
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showClearSessionDialog = false
                        app.sessionManager.clearSession()
                        manualCookieText = ""
                        hasSession = false
                        scope.launch { snackbarHostState.showSnackbar(clearedMsg) }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(stringResource(R.string.btn_clear_session))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearSessionDialog = false }) {
                    Text(stringResource(R.string.dialog_cancel))
                }
            },
            shape = RoundedCornerShape(24.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    }
}

private fun captureMergedInstagramCookies(): String? {
    val cm = CookieManager.getInstance()
    cm.flush()
    val merged = linkedMapOf<String, String>()
    val domains = listOf(
        "https://instagram.com",
        "https://www.instagram.com",
        "https://i.instagram.com"
    )
    for (url in domains) {
        val raw = cm.getCookie(url) ?: continue
        for (part in raw.split(";")) {
            val trimmed = part.trim()
            val eqIdx = trimmed.indexOf('=')
            if (eqIdx > 0) {
                val key = trimmed.substring(0, eqIdx).trim()
                val value = trimmed.substring(eqIdx + 1).trim()
                if (key.isNotBlank() && value.isNotBlank() && value != "\"\"") {
                    merged[key] = value
                }
            }
        }
    }
    if (!merged.containsKey("sessionid")) return null
    return merged.entries.joinToString("; ") { "${it.key}=${it.value}" }
}
