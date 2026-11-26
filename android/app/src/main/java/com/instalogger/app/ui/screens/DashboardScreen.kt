package com.instalogger.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.instalogger.app.InstaLoggerApp
import com.instalogger.app.R
import com.instalogger.app.data.local.TargetEntity
import com.instalogger.app.ui.animation.bouncyClickable
import com.instalogger.app.ui.components.AddTargetDialog
import com.instalogger.app.ui.components.DownloadQueueBottomSheet
import com.instalogger.app.ui.components.DownloadQueueHeaderButton
import com.instalogger.app.ui.components.HeaderActionButton
import com.instalogger.app.ui.components.ReachabilityHeader
import com.instalogger.app.ui.components.RotatingRefreshIcon
import com.instalogger.app.ui.components.SkeletonTargetCard
import com.instalogger.app.ui.components.StatusBadge
import com.instalogger.app.ui.components.TargetCard
import com.instalogger.app.ui.components.UnifiedEmptyState
import com.instalogger.app.ui.theme.AccentBlue
import com.instalogger.app.ui.theme.ShapeTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onNavigateToDetail: (String) -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as InstaLoggerApp
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var targets by remember { mutableStateOf<List<TargetEntity>>(emptyList()) }
    val pendingTargets = remember { mutableStateListOf<String>() }
    val deletingTargets = remember { mutableStateListOf<String>() }
    val visiblePendingTargets = remember(pendingTargets.toList(), targets) {
        pendingTargets.filter { pending ->
            targets.none { it.username.equals(pending, ignoreCase = true) }
        }
    }
    var checkingTargetUsername by remember { mutableStateOf<String?>(null) }
    var isGlobalRefreshing by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }
    var showDownloadQueueSheet by remember { mutableStateOf(false) }
    var targetToDelete by remember { mutableStateOf<TargetEntity?>(null) }
    var turboSnackbarJob by remember { mutableStateOf<Job?>(null) }

    val allCheckedMsg = stringResource(R.string.msg_all_targets_checked)
    val turboEnabledMsg = stringResource(R.string.msg_turbo_enabled)
    val turboDisabledMsg = stringResource(R.string.msg_turbo_disabled)

    // Instant real-time Flow observation from Room Database
    LaunchedEffect(Unit) {
        app.database.targetDao().getAllTargets().collect { list ->
            targets = list.filter { !app.profileInspector.isTargetDeleted(it.username) }
        }
    }

    // Auto-inspect and sync daemon on initial launch
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            app.profileInspector.syncAllFromDaemon()
            val current = app.database.targetDao().getActiveTargets().filter { !app.profileInspector.isTargetDeleted(it.username) }
            for (t in current) {
                try {
                    app.profileInspector.inspectTarget(t.username)
                } catch (e: Exception) {
                }
            }
        }
    }

    fun refreshAllTargets() {
        if (isGlobalRefreshing) return
        scope.launch {
            isGlobalRefreshing = true
            var okCount = 0
            var failCount = 0
            withContext(Dispatchers.IO) {
                app.profileInspector.syncAllFromDaemon()
                val activeTargets = app.database.targetDao().getActiveTargets().filter { !app.profileInspector.isTargetDeleted(it.username) }
                val sem = Semaphore(app.settingsManager.archiverSpeedPreset.value.targetWorkers.coerceIn(2, 4))
                val results = coroutineScope {
                    activeTargets.map { t ->
                        async {
                            sem.withPermit {
                                try {
                                    val result = app.profileInspector.inspectTarget(t.username)
                                    if (result.isSuccess) {
                                        app.profileInspector.inspectHighlightsAndPosts(t.username)
                                        true
                                    } else {
                                        false
                                    }
                                } catch (_: Exception) {
                                    false
                                }
                            }
                        }
                    }.awaitAll()
                }
                okCount = results.count { it }
                failCount = results.count { !it }
            }
            isGlobalRefreshing = false
            val summaryMsg = when {
                failCount == 0 && okCount > 0 -> allCheckedMsg
                okCount > 0 && failCount > 0 -> context.getString(R.string.msg_refresh_partial, okCount, failCount)
                failCount > 0 -> context.getString(R.string.msg_refresh_all_failed, failCount)
                else -> allCheckedMsg
            }
            snackbarHostState.showSnackbar(summaryMsg)
        }
    }

    Scaffold(
        topBar = {
            ReachabilityHeader(
                title = stringResource(R.string.dashboard_title),
                subtitle = stringResource(R.string.dashboard_subtitle),
                badgeContent = {
                    if (targets.isNotEmpty()) {
                        val turboCount = targets.count { it.isTurbo }
                        StatusBadge(
                            text = stringResource(R.string.badge_active_count_format, targets.size) + if (turboCount > 0) " • $turboCount ${stringResource(R.string.badge_turbo)}" else "",
                            containerColor = AccentBlue.copy(alpha = 0.14f),
                            contentColor = AccentBlue,
                            borderColor = AccentBlue.copy(alpha = 0.28f)
                        )
                    }
                },
                actions = {
                    DownloadQueueHeaderButton(
                        onClick = { showDownloadQueueSheet = true }
                    )
                    HeaderActionButton(
                        enabled = !isGlobalRefreshing,
                        onClick = { refreshAllTargets() }
                    ) {
                        RotatingRefreshIcon(
                            isRefreshing = isGlobalRefreshing,
                            iconSize = 20.dp
                        )
                    }
                    HeaderActionButton(onClick = { showAddDialog = true }) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = stringResource(R.string.btn_add_target),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddDialog = true },
                shape = ShapeTokens.MediumShape,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = stringResource(R.string.btn_add_target),
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = isGlobalRefreshing,
            onRefresh = { refreshAllTargets() },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (targets.isEmpty() && pendingTargets.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                    contentAlignment = Alignment.Center
                ) {
                    UnifiedEmptyState(
                        icon = Icons.Default.Security,
                        title = stringResource(R.string.dashboard_empty_title),
                        description = stringResource(R.string.dashboard_empty_desc),
                        actionLabel = stringResource(R.string.btn_add_target),
                        actionIcon = Icons.Default.Add,
                        onActionClick = { showAddDialog = true }
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    items(visiblePendingTargets, key = { "pending_$it" }) { pendingUser ->
                        SkeletonTargetCard(
                            username = pendingUser,
                            modifier = Modifier.animateItem()
                        )
                    }

                    // 2. Active Targets with smooth AnimatedVisibility collapse upon deletion
                    items(targets, key = { it.username }) { target ->
                        val isChecking = checkingTargetUsername == target.username
                        val isDeleting = deletingTargets.contains(target.username.lowercase())

                        AnimatedVisibility(
                            visible = !isDeleting,
                            enter = fadeIn(tween(250)),
                            exit = fadeOut(tween(250)) + shrinkVertically(tween(250), shrinkTowards = Alignment.Top),
                            modifier = Modifier.animateItem()
                        ) {
                            TargetCard(
                                target = target,
                                isChecking = isChecking,
                                onClick = { onNavigateToDetail(target.username) },
                                onCheckNow = {
                                    if (checkingTargetUsername == null) {
                                        scope.launch {
                                            checkingTargetUsername = target.username
                                            val result = app.profileInspector.inspectTarget(target.username)
                                            checkingTargetUsername = null

                                            if (result.isSuccess) {
                                                val updated = result.getOrThrow()
                                                val preservedTurbo = targets.find { it.username == target.username }?.isTurbo ?: updated.isTurbo
                                                targets = targets.map { if (it.username == target.username) updated.copy(isTurbo = preservedTurbo) else it }
                                                snackbarHostState.showSnackbar(context.getString(R.string.msg_target_checked, target.username))
                                            } else {
                                                snackbarHostState.showSnackbar(context.getString(R.string.msg_check_failed, result.exceptionOrNull()?.message ?: ""))
                                            }
                                        }
                                    }
                                },
                                onToggleTurbo = { newTurbo ->
                                    // 1. Instant optimistic UI state update for zero lag
                                    targets = targets.map { if (it.username == target.username) it.copy(isTurbo = newTurbo) else it }

                                    // 2. Asynchronously persist to Room DB & notify daemon
                                    scope.launch(Dispatchers.IO) {
                                        app.database.targetDao().updateTurbo(target.username, newTurbo)
                                        val daemonId = target.instagramId ?: target.username
                                        app.profileInspector.updateDaemonTurbo(daemonId, newTurbo)
                                    }

                                    // 3. Instant snackbar without blocking coroutine queue
                                    turboSnackbarJob?.cancel()
                                    turboSnackbarJob = scope.launch {
                                        snackbarHostState.showSnackbar(
                                            message = if (newTurbo) turboEnabledMsg else turboDisabledMsg,
                                            duration = SnackbarDuration.Short
                                        )
                                    }
                                },
                                onDelete = {
                                    targetToDelete = target
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddTargetDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { username, turbo ->
                showAddDialog = false
                val cleanUser = username.trim().trimStart('@').lowercase()
                if (!pendingTargets.contains(cleanUser) && targets.none { it.username.equals(cleanUser, ignoreCase = true) }) {
                    pendingTargets.add(0, cleanUser)
                }
                scope.launch {
                    try {
                        app.profileInspector.unmarkDeleted(cleanUser)
                        app.profileInspector.notifyDaemonAddTarget(cleanUser, turbo)
                        val result = app.profileInspector.inspectTarget(cleanUser)
                        if (result.isSuccess) {
                            val newTarget = result.getOrThrow().copy(isTurbo = turbo)
                            if (turbo) {
                                withContext(Dispatchers.IO) {
                                    app.database.targetDao().updateTurbo(cleanUser, true)
                                }
                            }
                            targets = listOf(newTarget) + targets.filter { !it.username.equals(cleanUser, ignoreCase = true) }
                            snackbarHostState.showSnackbar(context.getString(R.string.msg_target_added, cleanUser))
                        } else {
                            val placeholder = TargetEntity(username = cleanUser, isTurbo = turbo)
                            withContext(Dispatchers.IO) {
                                app.database.targetDao().insertTarget(placeholder)
                            }
                            targets = listOf(placeholder) + targets.filter { !it.username.equals(cleanUser, ignoreCase = true) }
                            snackbarHostState.showSnackbar(context.getString(R.string.msg_target_added_pending, cleanUser))
                        }
                    } finally {
                        pendingTargets.remove(cleanUser)
                    }

                    scope.launch(Dispatchers.IO) {
                        try {
                            app.profileInspector.inspectHighlightsAndPosts(cleanUser)
                        } catch (e: Exception) {
                        }
                    }
                }
            }
        )
    }

    targetToDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { targetToDelete = null },
            title = {
                Text(
                    text = stringResource(R.string.dialog_delete_confirm_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.dialog_delete_confirm_desc, target.username),
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val t = targetToDelete ?: return@Button
                        targetToDelete = null
                        val uLower = t.username.lowercase()
                        deletingTargets.add(uLower)
                        scope.launch {
                            kotlinx.coroutines.delay(240) // Give exit animation window to finish smoothly
                            targets = targets.filter { !it.username.equals(t.username, ignoreCase = true) }
                            deletingTargets.remove(uLower)
                            app.profileInspector.deleteTarget(t.username, t.instagramId)
                            snackbarHostState.showSnackbar(context.getString(R.string.msg_target_deleted, t.username))
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    shape = ShapeTokens.SmallShape
                ) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { targetToDelete = null },
                    shape = ShapeTokens.SmallShape
                ) {
                    Text(stringResource(R.string.dialog_cancel))
                }
            },
            shape = ShapeTokens.LargeShape,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    }

    if (showDownloadQueueSheet) {
        DownloadQueueBottomSheet(
            onDismiss = { showDownloadQueueSheet = false },
            onOpenTarget = { username -> onNavigateToDetail(username) }
        )
    }
}
