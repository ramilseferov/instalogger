package com.instalogger.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.instalogger.app.R
import com.instalogger.app.ui.animation.bouncyClickable
import com.instalogger.app.ui.components.InstaLoggerTopBar
import com.instalogger.app.ui.theme.ShapeTokens
import com.instalogger.app.util.AppLogger
import com.instalogger.app.util.DevLogEntry
import com.instalogger.app.util.LogLevel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val TerminalBg = Color(0xFF0A0D12)
private val TerminalSurface = Color(0xFF121720)
private val TerminalBorder = Color(0xFF232B3A)
private val TermTextPrimary = Color(0xFFE2E8F0)
private val TermTextMuted = Color(0xFF7B879B)
private val TermDebug = Color(0xFF64B5F6)
private val TermInfo = Color(0xFF4ADE80)
private val TermWarn = Color(0xFFFBBF24)
private val TermError = Color(0xFFF87171)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeveloperLogsScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val allLogs by AppLogger.logs.collectAsStateWithLifecycle()

    var selectedLevel by remember { mutableStateOf<LogLevel?>(null) } // null = ALL
    var searchQuery by remember { mutableStateOf("") }
    var autoScroll by remember { mutableStateOf(true) }
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
            listState.scrollToItem(filteredLogs.lastIndex)
        }
    }

    fun copyToClipboard(label: String, text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        scope.launch {
            snackbarHostState.showSnackbar(context.getString(R.string.dev_logs_copied))
        }
    }

    Scaffold(
        containerColor = TerminalBg,
        topBar = {
            InstaLoggerTopBar(
                title = stringResource(R.string.dev_logs_title),
                subtitle = stringResource(R.string.dev_logs_subtitle_stats, filteredLogs.size, errorCount, warnCount),
                navigationIcon = Icons.AutoMirrored.Filled.ArrowBack,
                onNavigationClick = onBack,
                actions = {
                    val btnShape = RoundedCornerShape(10.dp)
                    Box(
                        modifier = Modifier
                            .padding(end = 6.dp)
                            .size(36.dp)
                            .clip(btnShape)
                            .background(if (autoScroll) TermInfo.copy(alpha = 0.16f) else TerminalSurface)
                            .border(0.75.dp, if (autoScroll) TermInfo.copy(alpha = 0.5f) else TerminalBorder, btnShape)
                            .bouncyClickable(pressScale = 0.90f) {
                                autoScroll = !autoScroll
                                if (autoScroll && filteredLogs.isNotEmpty()) {
                                    scope.launch { listState.scrollToItem(filteredLogs.lastIndex) }
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.VerticalAlignBottom,
                            contentDescription = "Auto-scroll",
                            tint = if (autoScroll) TermInfo else TermTextMuted,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    Box(
                        modifier = Modifier
                            .padding(end = 6.dp)
                            .size(36.dp)
                            .clip(btnShape)
                            .background(TerminalSurface)
                            .border(0.75.dp, TerminalBorder, btnShape)
                            .bouncyClickable(pressScale = 0.90f) {
                                if (filteredLogs.isNotEmpty()) {
                                    copyToClipboard("InstaLogger Logs", AppLogger.formatDump(filteredLogs))
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = stringResource(R.string.dev_logs_copy_all),
                            tint = TermDebug,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    Box(
                        modifier = Modifier
                            .padding(end = 10.dp)
                            .size(36.dp)
                            .clip(btnShape)
                            .background(TerminalSurface)
                            .border(0.75.dp, TerminalBorder, btnShape)
                            .bouncyClickable(pressScale = 0.90f) {
                                AppLogger.clear()
                                scope.launch {
                                    snackbarHostState.showSnackbar(context.getString(R.string.dev_logs_cleared))
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteSweep,
                            contentDescription = stringResource(R.string.dev_logs_clear),
                            tint = TermError,
                            modifier = Modifier.size(18.dp)
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
                .background(TerminalBg)
                .padding(padding)
        ) {
            // Encrypted Vault Banner + Filter Controls
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(TerminalSurface)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        tint = TermInfo,
                        modifier = Modifier.size(13.dp)
                    )
                    Text(
                        text = stringResource(R.string.dev_logs_encrypted_badge),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = TermInfo
                    )
                }

                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = {
                        Text(
                            text = stringResource(R.string.dev_logs_search_hint),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = TermTextMuted
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                            tint = TermTextMuted,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = TermTextPrimary
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = TermDebug,
                        unfocusedBorderColor = TerminalBorder,
                        focusedContainerColor = TerminalBg,
                        unfocusedContainerColor = TerminalBg,
                        cursorColor = TermInfo
                    ),
                    shape = ShapeTokens.SmallShape,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val chips = listOf(
                        null to "ALL (${allLogs.size})",
                        LogLevel.ERROR to "ERR ($errorCount)",
                        LogLevel.WARN to "WARN ($warnCount)",
                        LogLevel.INFO to "INFO",
                        LogLevel.DEBUG to "DBG"
                    )
                    chips.forEach { (lvl, label) ->
                        val active = selectedLevel == lvl
                        val accent = when (lvl) {
                            LogLevel.ERROR -> TermError
                            LogLevel.WARN -> TermWarn
                            LogLevel.INFO -> TermInfo
                            LogLevel.DEBUG -> TermDebug
                            null -> TermTextPrimary
                        }
                        Surface(
                            onClick = { selectedLevel = lvl },
                            shape = RoundedCornerShape(8.dp),
                            color = if (active) accent.copy(alpha = 0.18f) else TerminalBg,
                            border = BorderStroke(0.75.dp, if (active) accent else TerminalBorder)
                        ) {
                            Text(
                                text = label,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                                color = if (active) accent else TermTextMuted,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                            )
                        }
                    }
                }
            }

            HorizontalDivider(color = TerminalBorder, thickness = 1.dp)

            if (filteredLogs.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Terminal,
                            contentDescription = null,
                            tint = TermTextMuted,
                            modifier = Modifier.size(34.dp)
                        )
                        Text(
                            text = stringResource(R.string.dev_logs_empty),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = TermTextMuted
                        )
                    }
                }
            } else {
                SelectionContainer(modifier = Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        items(filteredLogs, key = { it.id }) { entry ->
                            TerminalLogRow(
                                entry = entry,
                                onTapCopy = {
                                    copyToClipboard("Log Line", entry.formatTerminalLine())
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TerminalLogRow(
    entry: DevLogEntry,
    onTapCopy: () -> Unit
) {
    val timeStr = remember(entry.timestampMs) {
        SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(entry.timestampMs))
    }
    val levelColor = when (entry.level) {
        LogLevel.DEBUG -> TermDebug
        LogLevel.INFO -> TermInfo
        LogLevel.WARN -> TermWarn
        LogLevel.ERROR -> TermError
    }
    val lineText = remember(entry.id) {
        buildAnnotatedString {
            withStyle(SpanStyle(color = TermTextMuted)) {
                append(timeStr)
                append(" ")
            }
            withStyle(SpanStyle(color = levelColor, fontWeight = FontWeight.Bold)) {
                append(entry.level.code)
                append("/")
                append(entry.tag)
                append(": ")
            }
            withStyle(SpanStyle(color = if (entry.level == LogLevel.ERROR) TermError.copy(alpha = 0.95f) else TermTextPrimary)) {
                append(entry.message)
            }
        }
    }

    Text(
        text = lineText,
        fontFamily = FontFamily.Monospace,
        fontSize = 11.5.sp,
        lineHeight = 16.sp,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .clickable(onClick = onTapCopy)
            .padding(horizontal = 4.dp, vertical = 2.dp)
    )
}
