package com.instalogger.app.ui.screens

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
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
fun InstagramSessionScreen() {
    val context = LocalContext.current
    val app = context.applicationContext as InstaLoggerApp
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var selectedTab by remember { mutableIntStateOf(0) }
    var hasSession by remember { mutableStateOf(app.sessionManager.hasSession()) }
    var manualCookieText by remember { mutableStateOf("") }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var isLoadingWeb by remember { mutableStateOf(true) }
    var webProgress by remember { mutableFloatStateOf(0.1f) }

    val capturedMsg = stringResource(R.string.msg_session_captured)
    val savedMsg = stringResource(R.string.msg_cookie_saved)
    val clearedMsg = stringResource(R.string.msg_session_cleared)

    // Periodic automatic cookie detection while in login WebView
    LaunchedEffect(selectedTab, hasSession) {
        if (selectedTab == 0 && !hasSession) {
            while (!hasSession) {
                kotlinx.coroutines.delay(1500)
                try {
                    val cookies = captureMergedInstagramCookies()
                    if (cookies != null && cookies.contains("sessionid=")) {
                        app.sessionManager.saveCookieString(cookies)
                        hasSession = true
                        snackbarHostState.showSnackbar(capturedMsg)
                        break
                    }
                } catch (e: Exception) {
                    // Ignore background polling error
                }
            }
        }
    }

    Scaffold(
        topBar = {
            ReachabilityHeader(
                title = stringResource(R.string.session_title),
                subtitle = stringResource(R.string.session_subtitle),
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
            // Status Header Card
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

            // Standardized Sliding Pill Tab Selector
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
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                    ) {
                        // Mini action bar for WebView
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

                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(
                                    onClick = {
                                        val cookies = captureMergedInstagramCookies()
                                        if (cookies != null && cookies.contains("sessionid=")) {
                                            app.sessionManager.saveCookieString(cookies)
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
                                            if (manualCookieText.isNotBlank()) {
                                                app.sessionManager.saveCookieString(manualCookieText.trim())
                                                hasSession = true
                                                manualCookieText = ""
                                                scope.launch {
                                                    snackbarHostState.showSnackbar(savedMsg)
                                                }
                                            }
                                        },
                                        shape = ShapeTokens.SmallShape,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(Icons.Default.VpnKey, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(stringResource(R.string.btn_save_cookie))
                                    }

                                    OutlinedButton(
                                        onClick = {
                                            app.sessionManager.clearSession()
                                            hasSession = false
                                            scope.launch {
                                                snackbarHostState.showSnackbar(clearedMsg)
                                            }
                                        },
                                        enabled = hasSession,
                                        shape = ShapeTokens.SmallShape,
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                                    ) {
                                        Text(stringResource(R.string.btn_clear_session))
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
