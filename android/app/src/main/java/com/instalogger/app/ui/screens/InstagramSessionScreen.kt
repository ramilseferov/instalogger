package com.instalogger.app.ui.screens

import android.annotation.SuppressLint
import android.webkit.CookieManager
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
import com.instalogger.app.ui.components.StatusBadge
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

    val capturedMsg = stringResource(R.string.msg_session_captured)
    val savedMsg = stringResource(R.string.msg_cookie_saved)
    val clearedMsg = stringResource(R.string.msg_session_cleared)

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
                .padding(bottom = 72.dp)
        ) {
            // Status Header Card
            val statusCardShape = RoundedCornerShape(24.dp)
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .clip(statusCardShape)
                    .border(0.75.dp, MaterialTheme.colorScheme.outlineVariant, statusCardShape),
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

            // Sliding Pill Tab Selector
            val tabContainerShape = RoundedCornerShape(20.dp)
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .clip(tabContainerShape)
                    .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, tabContainerShape),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = tabContainerShape
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Tab 0: WebView Login
                    val isTab0 = selectedTab == 0
                    val pill0Shape = RoundedCornerShape(16.dp)
                    val color0 by animateColorAsState(
                        targetValue = if (isTab0) MaterialTheme.colorScheme.surface else Color.Transparent,
                        animationSpec = tween(180),
                        label = "session_tab0_bg"
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(pill0Shape)
                            .background(color0)
                            .then(
                                if (isTab0) Modifier.border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, pill0Shape)
                                else Modifier
                            )
                            .bouncyClickable(pressScale = 0.94f) { selectedTab = 0 }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.tab_webview_login),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (isTab0) FontWeight.Bold else FontWeight.Medium,
                            color = if (isTab0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Tab 1: Manual Cookie
                    val isTab1 = selectedTab == 1
                    val pill1Shape = RoundedCornerShape(16.dp)
                    val color1 by animateColorAsState(
                        targetValue = if (isTab1) MaterialTheme.colorScheme.surface else Color.Transparent,
                        animationSpec = tween(180),
                        label = "session_tab1_bg"
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(pill1Shape)
                            .background(color1)
                            .then(
                                if (isTab1) Modifier.border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, pill1Shape)
                                else Modifier
                            )
                            .bouncyClickable(pressScale = 0.94f) { selectedTab = 1 }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.tab_manual_cookie),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (isTab1) FontWeight.Bold else FontWeight.Medium,
                            color = if (isTab1) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            when (selectedTab) {
                0 -> {
                    val webViewShape = RoundedCornerShape(24.dp)
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                            .clip(webViewShape)
                            .border(0.75.dp, MaterialTheme.colorScheme.outlineVariant, webViewShape)
                    ) {
                        AndroidView(
                            factory = { ctx ->
                                WebView(ctx).apply {
                                    settings.javaScriptEnabled = true
                                    settings.domStorageEnabled = true
                                    settings.userAgentString =
                                        "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

                                    webViewClient = object : WebViewClient() {
                                        override fun onPageFinished(view: WebView?, url: String?) {
                                            super.onPageFinished(view, url)
                                            val cookies = CookieManager.getInstance().getCookie("https://www.instagram.com")
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
                        val cookieCardShape = RoundedCornerShape(24.dp)
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(cookieCardShape)
                                .border(0.75.dp, MaterialTheme.colorScheme.outlineVariant, cookieCardShape),
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
                                    shape = RoundedCornerShape(16.dp),
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
                                        shape = RoundedCornerShape(14.dp),
                                        modifier = Modifier
                                            .weight(1f)
                                            .bouncyClickable(pressScale = 0.94f) {
                                                if (manualCookieText.isNotBlank()) {
                                                    app.sessionManager.saveCookieString(manualCookieText.trim())
                                                    hasSession = true
                                                    manualCookieText = ""
                                                    scope.launch {
                                                        snackbarHostState.showSnackbar(savedMsg)
                                                    }
                                                }
                                            }
                                    ) {
                                        Icon(Icons.Default.VpnKey, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(stringResource(R.string.btn_save_cookie))
                                    }

                                    if (hasSession) {
                                        OutlinedButton(
                                            onClick = {
                                                app.sessionManager.clearSession()
                                                hasSession = false
                                                scope.launch {
                                                    snackbarHostState.showSnackbar(clearedMsg)
                                                }
                                            },
                                            shape = RoundedCornerShape(14.dp),
                                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                            modifier = Modifier.bouncyClickable(pressScale = 0.94f) {
                                                app.sessionManager.clearSession()
                                                hasSession = false
                                                scope.launch {
                                                    snackbarHostState.showSnackbar(clearedMsg)
                                                }
                                            }
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
}
