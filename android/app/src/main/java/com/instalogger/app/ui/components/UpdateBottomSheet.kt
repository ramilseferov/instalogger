package com.instalogger.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.instalogger.app.R
import com.instalogger.app.ui.animation.bouncyClickable
import com.instalogger.app.ui.theme.AccentBlue
import com.instalogger.app.ui.theme.ShapeTokens
import com.instalogger.app.ui.theme.StatusAmber
import com.instalogger.app.ui.theme.StatusGreen
import com.instalogger.app.ui.theme.StatusRed
import com.instalogger.app.update.ApkInstallerEngine
import com.instalogger.app.update.AppUpdateChannel
import com.instalogger.app.update.AppUpdateManager
import com.instalogger.app.update.ReleaseInfo
import com.instalogger.app.update.UpdateUiState
import com.instalogger.app.util.LanguageManager
import java.io.File
import java.util.Locale

@Composable
fun UpdateAvailableBanner(
    updateManager: AppUpdateManager,
    modifier: Modifier = Modifier
) {
    val state by updateManager.state.collectAsState()
    val available by updateManager.availableRelease.collectAsState()
    val visible = available != null && updateManager.isBannerVisibleFor(available)

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
        modifier = modifier
    ) {
        val release = available ?: return@AnimatedVisibility
        val isDownloading = state is UpdateUiState.Downloading
        val isReady = state is UpdateUiState.ReadyToInstall || (state is UpdateUiState.Available && (state as UpdateUiState.Available).isCachedApkReady)

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .bouncyClickable(pressScale = 0.98f) { updateManager.openSheet() },
            shape = ShapeTokens.MediumShape,
            color = AccentBlue.copy(alpha = 0.12f),
            border = BorderStroke(1.dp, AccentBlue.copy(alpha = 0.35f))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(AccentBlue.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isReady) Icons.Default.InstallMobile else Icons.Default.SystemUpdate,
                        contentDescription = null,
                        tint = AccentBlue,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.update_banner_title, release.versionName),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val subtitle = when {
                        isDownloading -> {
                            val dl = state as UpdateUiState.Downloading
                            val pct = (dl.progressFraction * 100f).toInt()
                            stringResource(R.string.update_banner_downloading, pct)
                        }
                        isReady -> stringResource(R.string.update_banner_ready)
                        else -> stringResource(R.string.update_banner_subtitle, release.tag)
                    }
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Surface(
                    shape = ShapeTokens.PillShape,
                    color = AccentBlue
                ) {
                    Text(
                        text = stringResource(if (isReady) R.string.update_btn_install else R.string.update_btn_details),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateBottomSheet(
    updateManager: AppUpdateManager
) {
    val isVisible by updateManager.isSheetVisible.collectAsState()
    val state by updateManager.state.collectAsState()
    val available by updateManager.availableRelease.collectAsState()
    val currentLang by LanguageManager.currentLanguage.collectAsState()
    val context = LocalContext.current

    if (!isVisible) return

    val release: ReleaseInfo? = when (val s = state) {
        is UpdateUiState.Available -> s.release
        is UpdateUiState.Downloading -> s.release
        is UpdateUiState.Verifying -> s.release
        is UpdateUiState.ReadyToInstall -> s.release
        is UpdateUiState.PermissionRequired -> s.release
        is UpdateUiState.Installing -> s.release
        is UpdateUiState.Error -> s.release ?: available
        else -> available
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = { updateManager.dismissSheet() },
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (release == null) {
                when (val s = state) {
                    is UpdateUiState.Checking -> {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = stringResource(R.string.update_status_checking),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                    is UpdateUiState.UpToDate -> {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = StatusGreen, modifier = Modifier.size(28.dp))
                            Column {
                                Text(
                                    text = stringResource(R.string.update_status_up_to_date),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "v${s.currentVersionName} (Build ${s.currentVersionCode})",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    is UpdateUiState.Error -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = StatusRed, modifier = Modifier.size(26.dp))
                            Text(
                                text = s.message,
                                style = MaterialTheme.typography.bodyMedium,
                                color = StatusRed
                            )
                        }
                    }
                    else -> Unit
                }
                return@Column
            }

            // Header: Icon + Version Transition + Channel / Size badges
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = AccentBlue.copy(alpha = 0.15f),
                    modifier = Modifier.size(52.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.SystemUpdate,
                            contentDescription = null,
                            tint = AccentBlue,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.update_sheet_title),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        if (release.channel == AppUpdateChannel.BETA) {
                            Surface(
                                shape = ShapeTokens.PillShape,
                                color = StatusAmber.copy(alpha = 0.18f)
                            ) {
                                Text(
                                    text = "BETA",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = StatusAmber,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "v${updateManager.installedVersionName()} (b${updateManager.installedVersionCode()})  ->  v${release.versionName} (b${release.versionCode})",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = AccentBlue
                    )
                    val metaParts = buildList {
                        if (release.sizeBytes > 0L) add(formatFileSizeMb(release.sizeBytes))
                        if (release.publishedAt.length >= 10) add(release.publishedAt.substring(0, 10))
                    }
                    if (metaParts.isNotEmpty()) {
                        Text(
                            text = metaParts.joinToString(" • "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Changelog Card
            val notesText = release.changelogForLanguage(currentLang)
            if (notesText.isNotBlank()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = ShapeTokens.MediumShape,
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    border = BorderStroke(0.75.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.update_changelog_header),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        notesText.lines().filter { it.isNotBlank() }.forEach { line ->
                            Text(
                                text = line.trim(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // State-specific progress / verification / error / permission panel
            when (val s = state) {
                is UpdateUiState.Downloading -> {
                    val animatedProgress by animateFloatAsState(
                        targetValue = s.progressFraction,
                        label = "update_dl_progress"
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        LinearProgressIndicator(
                            progress = { animatedProgress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(999.dp)),
                            color = AccentBlue
                        )
                        val pct = (s.progressFraction * 100f).toInt()
                        val dlMb = formatFileSizeMb(s.downloadedBytes)
                        val totalMb = if (s.totalBytes > 0L) formatFileSizeMb(s.totalBytes) else "?"
                        val speedStr = if (s.speedBytesPerSec > 0L) "${formatFileSizeMb(s.speedBytesPerSec)}/s" else ""
                        val etaStr = if (s.etaSeconds > 0L) "~${s.etaSeconds}s" else ""
                        val metrics = listOf("$pct%", "$dlMb / $totalMb", speedStr, etaStr).filter { it.isNotBlank() }.joinToString(" · ")
                        Text(
                            text = if (s.paused) stringResource(R.string.update_status_paused, pct) else metrics,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                is UpdateUiState.Verifying -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = AccentBlue)
                        Text(
                            text = stringResource(R.string.update_status_verifying),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                is UpdateUiState.ReadyToInstall -> {
                    Surface(
                        shape = ShapeTokens.MediumShape,
                        color = StatusGreen.copy(alpha = 0.12f),
                        border = BorderStroke(0.75.dp, StatusGreen.copy(alpha = 0.32f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(Icons.Default.VerifiedUser, contentDescription = null, tint = StatusGreen, modifier = Modifier.size(20.dp))
                            Text(
                                text = stringResource(
                                    if (s.sha256Verified) R.string.update_verified_sha256 else R.string.update_verified_ready
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = StatusGreen
                            )
                        }
                    }
                }

                is UpdateUiState.PermissionRequired -> {
                    Surface(
                        shape = ShapeTokens.MediumShape,
                        color = StatusAmber.copy(alpha = 0.14f),
                        border = BorderStroke(0.75.dp, StatusAmber.copy(alpha = 0.35f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(Icons.Default.Security, contentDescription = null, tint = StatusAmber, modifier = Modifier.size(20.dp))
                            Text(
                                text = stringResource(R.string.update_permission_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }

                is UpdateUiState.Installing -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = AccentBlue)
                        Text(
                            text = stringResource(R.string.update_status_installing),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                is UpdateUiState.Error -> {
                    Surface(
                        shape = ShapeTokens.MediumShape,
                        color = StatusRed.copy(alpha = 0.12f),
                        border = BorderStroke(0.75.dp, StatusRed.copy(alpha = 0.32f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = StatusRed, modifier = Modifier.size(20.dp))
                            Text(
                                text = s.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = StatusRed
                            )
                        }
                    }
                }

                else -> Unit
            }

            // Action Buttons
            when (val s = state) {
                is UpdateUiState.Downloading -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = { updateManager.cancelDownload() },
                            shape = ShapeTokens.MediumShape,
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp)
                        ) {
                            Text(stringResource(R.string.dialog_cancel))
                        }
                        Button(
                            onClick = {
                                if (s.paused) {
                                    updateManager.startDownload(release, ignoreWifiOnly = true)
                                } else {
                                    updateManager.pauseDownload()
                                }
                            },
                            shape = ShapeTokens.MediumShape,
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp)
                        ) {
                            Icon(
                                imageVector = if (s.paused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(if (s.paused) R.string.update_btn_resume else R.string.update_btn_pause))
                        }
                    }
                }

                is UpdateUiState.PermissionRequired -> {
                    Button(
                        onClick = {
                            context.startActivity(ApkInstallerEngine.createUnknownSourcesSettingsIntent(context))
                        },
                        shape = ShapeTokens.MediumShape,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                    ) {
                        Icon(Icons.Default.Security, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.update_btn_grant_permission))
                    }
                }

                is UpdateUiState.ReadyToInstall -> {
                    Button(
                        onClick = { updateManager.triggerInstall(release, File(s.apkFilePath)) },
                        shape = ShapeTokens.MediumShape,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                    ) {
                        Icon(Icons.Default.InstallMobile, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.update_btn_install_now))
                    }
                }

                is UpdateUiState.Verifying, is UpdateUiState.Installing -> {
                    // Busy state
                }

                else -> {
                    val isCached = (s is UpdateUiState.Available && s.isCachedApkReady)
                    Button(
                        onClick = {
                            if (isCached) {
                                updateManager.startDownload(release, ignoreWifiOnly = true, autoInstall = true)
                            } else {
                                updateManager.startDownload(release, ignoreWifiOnly = s is UpdateUiState.Error)
                            }
                        },
                        shape = ShapeTokens.MediumShape,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                    ) {
                        Icon(
                            imageVector = if (isCached) Icons.Default.InstallMobile else Icons.Default.Download,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            stringResource(
                                when {
                                    isCached -> R.string.update_btn_install_now
                                    s is UpdateUiState.Error -> R.string.update_btn_retry
                                    else -> R.string.update_btn_download_install
                                }
                            )
                        )
                    }

                    if (!release.forceUpdate) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            TextButton(
                                onClick = { updateManager.skipVersion(release.versionCode) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(stringResource(R.string.update_btn_skip_version))
                            }
                            TextButton(
                                onClick = { updateManager.dismissSheet() },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(stringResource(R.string.update_btn_later))
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatFileSizeMb(bytes: Long): String {
    if (bytes <= 0L) return "0 MB"
    return if (bytes >= 1024L * 1024L) {
        String.format(Locale.US, "%.1f MB", bytes.toDouble() / (1024.0 * 1024.0))
    } else {
        String.format(Locale.US, "%.0f KB", bytes.toDouble() / 1024.0)
    }
}
