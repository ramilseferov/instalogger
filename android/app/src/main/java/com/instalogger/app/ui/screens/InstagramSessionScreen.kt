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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.instalogger.app.data.repository.TargetRepository
import com.instalogger.app.ui.components.InstaLoggerTopBar
import com.instalogger.app.ui.components.StatusBadge
import com.instalogger.app.ui.theme.AccentBlue
import com.instalogger.app.ui.theme.StatusGreen
import com.instalogger.app.ui.theme.StatusGreenLight
import com.instalogger.app.ui.theme.StatusRed
import com.instalogger.app.ui.theme.StatusRedLight
import kotlinx.coroutines.launch

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun InstagramSessionScreen(
    targetRepository: TargetRepository
) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var selectedTab by remember { mutableIntStateOf(0) }
    var hasSessionCookie by remember { mutableStateOf(false) }
    var manualCookieText by remember { mutableStateOf("") }
    var isCheckingSession by remember { mutableStateOf(true) }

    fun checkSessionStatus() {
        scope.launch {
            isCheckingSession = true
            targetRepository.getSettings().onSuccess { settings ->
                hasSessionCookie = settings.hasSessionCookie
            }
            isCheckingSession = false
        }
    }

    LaunchedEffect(Unit) {
        checkSessionStatus()
    }

    Scaffold(
        topBar = {
            InstaLoggerTopBar(
                title = "Instagram Sessiyası",
                subtitle = "Məxfi profillər və anlıq izləmə üçün hesab əlaqəsi",
                actions = {
                    IconButton(onClick = { checkSessionStatus() }) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Yoxla",
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
                    Column {
                        Text(
                            text = "Sessiya Vəziyyəti",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (hasSessionCookie) "Instagram hesabı aktivdir" else "Sessiya bağlı deyil",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (hasSessionCookie) {
                        StatusBadge(
                            text = "Aktivdir",
                            icon = Icons.Default.CheckCircle,
                            containerColor = StatusGreenLight,
                            contentColor = StatusGreen
                        )
                    } else {
                        StatusBadge(
                            text = "Deaktiv",
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
                    text = { Text("Daxili Giriş (WebView)") }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Əl ilə Cookie") }
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
                            factory = { context ->
                                WebView(context).apply {
                                    settings.javaScriptEnabled = true
                                    settings.domStorageEnabled = true
                                    settings.userAgentString =
                                        "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

                                    webViewClient = object : WebViewClient() {
                                        override fun onPageFinished(view: WebView?, url: String?) {
                                            super.onPageFinished(view, url)
                                            val cookies = CookieManager.getInstance().getCookie("https://www.instagram.com")
                                            if (cookies != null && cookies.contains("sessionid=")) {
                                                scope.launch {
                                                    targetRepository.saveSessionCookie(cookies).onSuccess {
                                                        hasSessionCookie = true
                                                        snackbarHostState.showSnackbar("Instagram sessiyası avtomatik aşkarlandı və saxlanıldı!")
                                                    }
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
                            text = "Brauzerdən çıxarılmış sessionid və ya tam cookie stringini bura yapışdırın.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        OutlinedTextField(
                            value = manualCookieText,
                            onValueChange = { manualCookieText = it },
                            label = { Text("Cookie String") },
                            placeholder = { Text("sessionid=...; ds_user_id=...; csrftoken=...") },
                            minLines = 4,
                            maxLines = 8,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(18.dp)
                        )

                        Button(
                            onClick = {
                                if (manualCookieText.isNotBlank()) {
                                    scope.launch {
                                        targetRepository.saveSessionCookie(manualCookieText.trim()).onSuccess {
                                            hasSessionCookie = true
                                            snackbarHostState.showSnackbar("Cookie uğurla yadda saxlanıldı")
                                            manualCookieText = ""
                                        }.onFailure {
                                            snackbarHostState.showSnackbar("Xəta: ${it.localizedMessage}")
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = manualCookieText.isNotBlank(),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(Icons.Default.VpnKey, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Cookie Yadda Saxla")
                        }

                        if (hasSessionCookie) {
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        targetRepository.saveSessionCookie("").onSuccess {
                                            hasSessionCookie = false
                                            snackbarHostState.showSnackbar("Sessiya təmizləndi")
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = StatusRed)
                            ) {
                                Text("Sessiyanı Sıfırla")
                            }
                        }
                    }
                }
            }
        }
    }
}
