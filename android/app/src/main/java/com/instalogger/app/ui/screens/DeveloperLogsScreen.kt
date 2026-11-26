package com.instalogger.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.instalogger.app.R
import com.instalogger.app.ui.animation.bouncyClickable
import com.instalogger.app.ui.components.InstaLoggerTopBar
import com.instalogger.app.ui.theme.AccentBlue
import com.instalogger.app.ui.theme.ShapeTokens
import com.instalogger.app.ui.theme.StatusAmber
import com.instalogger.app.ui.theme.StatusGreen
import com.instalogger.app.ui.theme.StatusRed
import com.instalogger.app.util.AppLogger
import com.instalogger.app.util.DevLogEntry
import com.instalogger.app.util.LogLevel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun DeveloperLogsScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val allLogs by AppLogger.logs.collectAsStateWithLifecycle()
    val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f

    var selectedLevel by remember { mutableStateOf<LogLevel?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var autoScroll by remember { mutableStateOf(true) }
    var showClearConfirmDialog by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    val filteredLogs = remember(allLogs, selectedLevel, searchQuery) {
        val q = searchQuery.trim()
        allLogs.filter { entry ->
            val levelOk = selectedLevel == null || entry.level == selectedLevel
            val queryOk = q.isEmpty() ||
                entry.tag.contains(q, ignoreCase = true) ||
                entry.message.contains(q, ignoreCase = true)
            levelOk && queryOk
        }
    }

    val errorCount = remember(allLogs) { allLogs.count { it.level == LogLevel.ERROR } }
    val warnCount = remember(allLogs) { allLogs.count { it.level == LogLevel.WARN } }

    LaunchedEffect(filteredLogs.size, autoScroll) {
        if (autoScroll && filteredLogs.isNotEmpty()) {
            listState.animateScrollToItem(filteredLogs.lastIndex)
        }
    }

    fun copyToClipboard(label: String, text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        scope.launch {
            snackbarHostState.showSnackbar(context.getString(R.string.dev_logs_copied))
        }
    }

    // Muted, refined semantic colors (no harsh neon AI slop)
    val infoTint = if (isDark) Color(0xFF34D399) else Color(0xFF059669)
    val debugTint = if (isDark) Color(0xFF60A5FA) else AccentBlue
    val warnTint = if (isDark) Color(0xFFFBBF24) else Color(0xFFD97706)
    val errorTint = if (isDark) Color(0xFFF87171) else StatusRed

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            InstaLoggerTopBar(
                title = stringResource(R.string.dev_logs_title),
                subtitle = stringResource(R.string.dev_logs_subtitle_stats, filteredLogs.size, errorCount, warnCount),
                navigationIcon = Icons.AutoMirrored.Filled.ArrowBack,
                onNavigationClick = onBack,
                actions = {
                    val btnShape = ShapeTokens.SmallShape
                    val autoScrollBg by animateColorAsState(
                        targetValue = if (autoScroll) AccentBlue.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceContainerHigh,
                        animationSpec = tween(200),
                        label = "autoscroll_bg"
                    )
                    val autoScrollTint by animateColorAsState(
                        targetValue = if (autoScroll) AccentBlue else MaterialTheme.colorScheme.onSurfaceVariant,
                        animationSpec = tween(200),
                        label = "autoscroll_tint"
                    )

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(end = 10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(btnShape)
                                .background(autoScrollBg)
                                .border(ShapeTokens.BorderSubtle, MaterialTheme.colorScheme.outlineVariant, btnShape)
                                .bouncyClickable(pressScale = 0.90f) {
                                    autoScroll = !autoScroll
                                    if (autoScroll && filteredLogs.isNotEmpty()) {
                                        scope.launch { listState.animateScrollToItem(filteredLogs.lastIndex) }
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.VerticalAlignBottom,
                                contentDescription = null,
                                tint = autoScrollTint,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(btnShape)
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                .border(ShapeTokens.BorderSubtle, MaterialTheme.colorScheme.outlineVariant, btnShape)
                                .bouncyClickable(pressScale = 0.90f, enabled = filteredLogs.isNotEmpty()) {
                                    copyToClipboard("InstaLogger Logs", AppLogger.formatDump(filteredLogs))
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = stringResource(R.string.dev_logs_copy_all),
                                tint = if (filteredLogs.isNotEmpty()) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(btnShape)
                                .background(
                                    if (allLogs.isNotEmpty()) errorTint.copy(alpha = 0.12f)
                                    else MaterialTheme.colorScheme.surfaceContainerHigh
                                )
                                .border(
                                    ShapeTokens.BorderSubtle,
                                    if (allLogs.isNotEmpty()) errorTint.copy(alpha = 0.28f) else MaterialTheme.colorScheme.outlineVariant,
                                    btnShape
                                )
                                .bouncyClickable(pressScale = 0.90f, enabled = allLogs.isNotEmpty()) {
                                    showClearConfirmDialog = true
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.DeleteOutline,
                                contentDescription = stringResource(R.string.dev_logs_clear),
                                tint = if (allLogs.isNotEmpty()) errorTint else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                modifier = Modifier.size(18.dp)
                            )
                        }
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
            // Clean Controls Card
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                shape = ShapeTokens.LargeShape,
                color = MaterialTheme.colorScheme.surfaceContainer,
                border = BorderStroke(ShapeTokens.BorderSubtle, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = null,
                            tint = StatusGreen,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = stringResource(R.string.dev_logs_encrypted_badge),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = {
                            Text(
                                text = stringResource(R.string.dev_logs_search_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(
                                    onClick = { searchQuery = "" },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall.copy(
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentBlue,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                            focusedContainerColor = MaterialTheme.colorScheme.surface,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surface
                        ),
                        shape = ShapeTokens.MediumShape,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val chips = listOf(
                            null to "${stringResource(R.string.dev_logs_filter_all)} (${allLogs.size})",
                            LogLevel.ERROR to "${stringResource(R.string.dev_logs_filter_error)} ($errorCount)",
                            LogLevel.WARN to "${stringResource(R.string.dev_logs_filter_warn)} ($warnCount)",
                            LogLevel.INFO to stringResource(R.string.dev_logs_filter_info),
                            LogLevel.DEBUG to stringResource(R.string.dev_logs_filter_debug)
                        )
                        chips.forEach { (lvl, label) ->
                            val active = selectedLevel == lvl
                            val accent = when (lvl) {
                                LogLevel.ERROR -> errorTint
                                LogLevel.WARN -> warnTint
                                LogLevel.INFO -> infoTint
                                LogLevel.DEBUG -> debugTint
                                null -> AccentBlue
                            }
                            val chipBg by animateColorAsState(
                                targetValue = if (active) accent.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surface,
                                animationSpec = tween(180, easing = FastOutSlowInEasing),
                                label = "log_chip_bg_${lvl?.code ?: "ALL"}"
                            )
                            val chipBorder by animateColorAsState(
                                targetValue = if (active) accent.copy(alpha = 0.55f) else MaterialTheme.colorScheme.outlineVariant,
                                animationSpec = tween(180, easing = FastOutSlowInEasing),
                                label = "log_chip_bdr_${lvl?.code ?: "ALL"}"
                            )
                            val chipTxt by animateColorAsState(
                                targetValue = if (active) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                                animationSpec = tween(180, easing = FastOutSlowInEasing),
                                label = "log_chip_txt_${lvl?.code ?: "ALL"}"
                            )

                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = chipBg,
                                border = BorderStroke(if (active) 1.dp else 0.75.dp, chipBorder),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .bouncyClickable(pressScale = 0.94f) { selectedLevel = lvl }
                            ) {
                                Text(
                                    text = label,
                                    fontSize = 11.5.sp,
                                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                                    color = chipTxt,
                                    maxLines = 1,
                                    modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Animated Body (Smooth transition between list & empty state)
            AnimatedContent(
                targetState = filteredLogs.isEmpty(),
                transitionSpec = {
                    (fadeIn(tween(220)) + scaleIn(initialScale = 0.96f)) togetherWith
                        (fadeOut(tween(160)) + scaleOut(targetScale = 0.96f))
                },
                modifier = Modifier.weight(1f),
                label = "dev_logs_body_state"
            ) { isEmpty ->
                if (isEmpty) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.padding(32.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Terminal,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(26.dp)
                                )
                            }
                            Text(
                                text = stringResource(R.string.dev_logs_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(filteredLogs, key = { it.id }) { entry ->
                            CleanLogEntryCard(
                                entry = entry,
                                levelColor = when (entry.level) {
                                    LogLevel.ERROR -> errorTint
                                    LogLevel.WARN -> warnTint
                                    LogLevel.INFO -> infoTint
                                    LogLevel.DEBUG -> debugTint
                                },
                                onTapCopy = {
                                    copyToClipboard("Log Line", entry.formatTerminalLine())
                                },
                                modifier = Modifier.animateItem(
                                    fadeInSpec = tween(180),
                                    fadeOutSpec = tween(160),
                                    placementSpec = spring(
                                        dampingRatio = 0.85f,
                                        stiffness = Spring.StiffnessMediumLow
                                    )
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    if (showClearConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showClearConfirmDialog = false },
            title = {
                Text(
                    text = stringResource(R.string.dev_logs_clear_confirm_title),
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.dev_logs_clear_confirm_desc),
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showClearConfirmDialog = false
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        AppLogger.clear()
                        scope.launch {
                            snackbarHostState.showSnackbar(context.getString(R.string.dev_logs_cleared))
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = StatusRed),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirmDialog = false }) {
                    Text(stringResource(R.string.dialog_cancel))
                }
            },
            shape = RoundedCornerShape(24.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    }
}

@Composable
private fun CleanLogEntryCard(
    entry: DevLogEntry,
    levelColor: Color,
    onTapCopy: () -> Unit,
    modifier: Modifier = Modifier
) {
    val timeStr = remember(entry.timestampMs) {
        SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(entry.timestampMs))
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onTapCopy),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(
            0.75.dp,
            if (entry.level == LogLevel.ERROR) levelColor.copy(alpha = 0.30f)
            else MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = levelColor.copy(alpha = 0.14f)
                    ) {
                        Text(
                            text = entry.level.code,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = levelColor,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.5.dp)
                        )
                    }
                    Text(
                        text = entry.tag,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = timeStr,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Text(
                text = entry.message,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.5.sp,
                lineHeight = 16.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.88f)
            )
        }
    }
}
