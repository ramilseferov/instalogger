package com.instalogger.app.ui.screens

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.instalogger.app.InstaLoggerApp
import com.instalogger.app.R
import com.instalogger.app.ui.components.InstaLoggerTopBar
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
            InstaLoggerTopBar(
                title = stringResource(R.string.session_title),
                subtitle = stringResource(R.string.session_subtitle),
                actions = {
                    IconButton(onClick = { hasSession = app.sessionManager.hasSession() }) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface
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
        ) {
            // Status Header Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
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
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (hasSession) stringResource(R.string.session_active_desc) else stringResource(R.string.session_inactive_desc),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (hasSession) {
                        StatusBadge(
                            text = stringResource(R.string.badge_active),
                            icon = Icons.Default.CheckCircle,
                            containerColor = StatusGreenLight,
                            contentColor = StatusGreen
                        )
                    } else {
                        StatusBadge(
                            text = stringResource(R.string.badge_inactive),
                            icon = Icons.Default.Lock,
                            containerColor = StatusRedLight,
                            contentColor = StatusRed
                        )
                    }
                }
            }

            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = MaterialTheme.colorScheme.background,
                contentColor = AccentBlue
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text(stringResource(R.string.tab_webview_login)) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text(stringResource(R.string.tab_manual_cookie)) }
                )
            }

            when (selectedTab) {
                0 -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                            .clip(RoundedCornerShape(22.dp))
                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(22.dp))
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
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
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
                            placeholder = { Text("sessionid=...; ds_user_id=...; csrftoken=...") },
                            minLines = 4,
                            maxLines = 8,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(18.dp)
                        )

                        Button(
                            onClick = {
                                if (manualCookieText.isNotBlank()) {
                                    app.sessionManager.saveCookieString(manualCookieText.trim())
                                    hasSession = true
                                    scope.launch {
                                        snackbarHostState.showSnackbar(savedMsg)
                                        manualCookieText = ""
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = manualCookieText.isNotBlank(),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(Icons.Default.VpnKey, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
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
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = StatusRed)
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
