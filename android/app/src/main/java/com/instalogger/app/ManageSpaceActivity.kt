package com.instalogger.app

import android.content.Context
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.instalogger.app.ui.components.DataManagementContent
import com.instalogger.app.ui.components.InstaLoggerTopBar
import com.instalogger.app.ui.screens.AppLockScreen
import com.instalogger.app.ui.theme.InstaLoggerTheme
import com.instalogger.app.ui.theme.StatusRed
import com.instalogger.app.util.LanguageManager
import com.instalogger.app.util.StorageCacheManager
import kotlinx.coroutines.launch

/**
 * Registered as `android:manageSpaceActivity` in AndroidManifest.xml.
 *
 * When the user opens Android System Settings -> Apps -> InstaLogger -> Storage & cache,
 * Android replaces the destructive "Clear storage / Clear data" button with "Manage space",
 * which launches this Activity directly into InstaLogger's Data & Storage Management hub.
 */
class ManageSpaceActivity : FragmentActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LanguageManager.wrapBaseContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        LanguageManager.init(this)
        val app = application as InstaLoggerApp

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.appLockManager.isSecureScreenEnabled.collect { isSecure ->
                    if (isSecure) {
                        window.setFlags(
                            WindowManager.LayoutParams.FLAG_SECURE,
                            WindowManager.LayoutParams.FLAG_SECURE
                        )
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    }
                }
            }
        }

        setContent {
            val currentLanguage by LanguageManager.currentLanguage.collectAsState()
            val themeMode by app.settingsManager.themeMode.collectAsState()
            val isAppLocked by app.appLockManager.isAppLocked.collectAsStateWithLifecycle()
            val isLockConfigured = remember(isAppLocked) { app.appLockManager.isLockConfigured() }

            val baseConfiguration = LocalConfiguration.current
            val localizedConfiguration = remember(currentLanguage, baseConfiguration) {
                LanguageManager.getLocalizedConfiguration(baseConfiguration, currentLanguage)
            }
            val localizedContext = remember(currentLanguage, baseConfiguration) {
                LanguageManager.getLocalizedContext(this@ManageSpaceActivity, currentLanguage, baseConfiguration)
            }

            CompositionLocalProvider(
                LocalContext provides localizedContext,
                LocalConfiguration provides localizedConfiguration
            ) {
                InstaLoggerTheme(themeMode = themeMode) {
                    if (isAppLocked && isLockConfigured) {
                        AppLockScreen(
                            activity = this@ManageSpaceActivity,
                            appLockManager = app.appLockManager
                        )
                    } else {
                        val scope = rememberCoroutineScope()
                        val snackbarHostState = remember { SnackbarHostState() }
                        var refreshTrigger by remember { mutableIntStateOf(0) }
                        var showFactoryResetDialog by remember { mutableStateOf(false) }
                        var isResettingAll by remember { mutableStateOf(false) }

                        Scaffold(
                            modifier = Modifier
                                .fillMaxSize()
                                .displayCutoutPadding(),
                            containerColor = MaterialTheme.colorScheme.background,
                            snackbarHost = { SnackbarHost(snackbarHostState) },
                            topBar = {
                                InstaLoggerTopBar(
                                    title = stringResource(R.string.storage_mgmt_title),
                                    subtitle = stringResource(R.string.storage_mgmt_header_sub),
                                    navigationIcon = Icons.AutoMirrored.Filled.ArrowBack,
                                    onNavigationClick = { finish() },
                                    actions = {
                                        IconButton(
                                            onClick = { refreshTrigger++ },
                                            enabled = !isResettingAll
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Refresh,
                                                contentDescription = stringResource(R.string.storage_action_refresh),
                                                tint = MaterialTheme.colorScheme.onSurface
                                            )
                                        }
                                    }
                                )
                            }
                        ) { innerPadding ->
                            DataManagementContent(
                                showHeaderBar = false,
                                onDismiss = { finish() },
                                refreshTrigger = refreshTrigger,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(innerPadding)
                                    .padding(horizontal = 16.dp),
                                extraFooterContent = {
                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(20.dp),
                                        colors = CardDefaults.cardColors(
                                            containerColor = StatusRed.copy(alpha = 0.08f)
                                        ),
                                        border = BorderStroke(1.dp, StatusRed.copy(alpha = 0.30f))
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(16.dp),
                                            verticalArrangement = Arrangement.spacedBy(10.dp)
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.WarningAmber,
                                                    contentDescription = null,
                                                    tint = StatusRed,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                                Text(
                                                    text = stringResource(R.string.storage_factory_reset_title),
                                                    fontSize = 14.sp,
                                                    fontWeight = FontWeight.ExtraBold,
                                                    color = StatusRed
                                                )
                                            }
                                            Text(
                                                text = stringResource(R.string.storage_factory_reset_desc),
                                                fontSize = 12.sp,
                                                lineHeight = 16.5.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            OutlinedButton(
                                                onClick = { showFactoryResetDialog = true },
                                                enabled = !isResettingAll,
                                                shape = RoundedCornerShape(14.dp),
                                                border = BorderStroke(1.dp, StatusRed.copy(alpha = 0.55f)),
                                                colors = ButtonDefaults.outlinedButtonColors(
                                                    contentColor = StatusRed
                                                ),
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                if (isResettingAll) {
                                                    CircularProgressIndicator(
                                                        modifier = Modifier.size(16.dp),
                                                        color = StatusRed,
                                                        strokeWidth = 2.dp
                                                    )
                                                    Spacer(modifier = Modifier.width(8.dp))
                                                } else {
                                                    Icon(
                                                        imageVector = Icons.Default.DeleteForever,
                                                        contentDescription = null,
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(8.dp))
                                                }
                                                Text(
                                                    text = stringResource(R.string.storage_factory_reset_btn),
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(8.dp))
                                }
                            )
                        }

                        if (showFactoryResetDialog) {
                            AlertDialog(
                                onDismissRequest = { showFactoryResetDialog = false },
                                title = {
                                    Text(
                                        text = stringResource(R.string.storage_factory_reset_confirm_title),
                                        fontWeight = FontWeight.Bold
                                    )
                                },
                                text = {
                                    Text(
                                        text = stringResource(R.string.storage_factory_reset_confirm_desc),
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                },
                                confirmButton = {
                                    Button(
                                        onClick = {
                                            showFactoryResetDialog = false
                                            scope.launch {
                                                isResettingAll = true
                                                val (freed, _) = StorageCacheManager.clearAllAppData(this@ManageSpaceActivity)
                                                isResettingAll = false
                                                refreshTrigger++
                                                snackbarHostState.showSnackbar(
                                                    localizedContext.getString(
                                                        R.string.storage_cleared_banner_format,
                                                        StorageCacheManager.formatBytes(freed)
                                                    )
                                                )
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = StatusRed,
                                            contentColor = Color.White
                                        ),
                                        shape = RoundedCornerShape(14.dp)
                                    ) {
                                        Text(stringResource(R.string.action_delete))
                                    }
                                },
                                dismissButton = {
                                    TextButton(onClick = { showFactoryResetDialog = false }) {
                                        Text(stringResource(R.string.dialog_cancel))
                                    }
                                },
                                shape = RoundedCornerShape(24.dp),
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        (application as InstaLoggerApp).appLockManager.onAppForegrounded()
    }

    override fun onStop() {
        super.onStop()
        (application as InstaLoggerApp).appLockManager.onAppBackgrounded()
    }
}
