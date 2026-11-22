package com.instalogger.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.instalogger.app.ui.components.HeaderActionButton
import com.instalogger.app.ui.components.ReachabilityHeader
import com.instalogger.app.ui.components.RotatingRefreshButton
import com.instalogger.app.ui.components.StatusBadge
import com.instalogger.app.ui.components.TargetCard
import com.instalogger.app.ui.components.UnifiedEmptyState
import com.instalogger.app.ui.theme.AccentBlue
import com.instalogger.app.ui.theme.ShapeTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
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
    var checkingTargetUsername by remember { mutableStateOf<String?>(null) }
    var isGlobalRefreshing by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }
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
                    // Ignore background sync errors
                }
            }
        }
    }

    fun refreshAllTargets() {
        if (isGlobalRefreshing) return
        scope.launch {
            isGlobalRefreshing = true
            withContext(Dispatchers.IO) {
                app.profileInspector.syncAllFromDaemon()
                val activeTargets = app.database.targetDao().getActiveTargets().filter { !app.profileInspector.isTargetDeleted(it.username) }
                for (t in activeTargets) {
                    try {
                        val result = app.profileInspector.inspectTarget(t.username)
                        app.profileInspector.inspectHighlightsAndPosts(t.username)
                        result.onSuccess { updated ->
                            val preservedTurbo = targets.find { it.username.equals(t.username, ignoreCase = true) }?.isTurbo ?: updated.isTurbo
                            targets = targets.map { if (it.username.equals(t.username, ignoreCase = true)) updated.copy(isTurbo = preservedTurbo) else it }
                        }
                    } catch (e: Exception) {
                        // Continue to next target
                    }
                }
            }
            isGlobalRefreshing = false
            snackbarHostState.showSnackbar(allCheckedMsg)
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
                    HeaderActionButton(onClick = { refreshAllTargets() }) {
                        RotatingRefreshButton(
                            isRefreshing = isGlobalRefreshing,
                            onClick = { refreshAllTargets() }
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
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.bouncyClickable(pressScale = 0.92f, onClick = { showAddDialog = true })
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
            if (targets.isEmpty()) {
                UnifiedEmptyState(
                    icon = Icons.Default.Security,
                    title = stringResource(R.string.dashboard_empty_title),
                    description = stringResource(R.string.dashboard_empty_desc),
                    actionLabel = stringResource(R.string.btn_add_target),
                    actionIcon = Icons.Default.Add,
                    onActionClick = { showAddDialog = true }
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    items(targets, key = { it.username }) { target ->
                        val isChecking = checkingTargetUsername == target.username

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
                            },
                            modifier = Modifier.animateItem()
                        )
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
                val cleanUser = username.trim().trimStart('@')
                scope.launch {
                    app.profileInspector.unmarkDeleted(cleanUser)
                    app.profileInspector.notifyDaemonAddTarget(cleanUser, turbo)
                    val result = app.profileInspector.inspectTarget(cleanUser)
                    app.profileInspector.inspectHighlightsAndPosts(cleanUser)
                    if (result.isSuccess) {
                        val newTarget = result.getOrThrow().copy(isTurbo = turbo)
                        if (turbo) {
                            withContext(Dispatchers.IO) {
                                app.database.targetDao().updateTurbo(cleanUser, true)
                            }
                        }
                        targets = listOf(newTarget) + targets.filter { it.username != cleanUser }
                        snackbarHostState.showSnackbar(context.getString(R.string.msg_target_added, cleanUser))
                    } else {
                        val placeholder = TargetEntity(username = cleanUser, isTurbo = turbo)
                        withContext(Dispatchers.IO) {
                            app.database.targetDao().insertTarget(placeholder)
                        }
                        targets = listOf(placeholder) + targets.filter { it.username != cleanUser }
                        snackbarHostState.showSnackbar(context.getString(R.string.msg_target_added, cleanUser))
                    }
                }
            }
        )
    }

    // Delete Confirmation Dialog
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
                        targets = targets.filter { !it.username.equals(t.username, ignoreCase = true) }
                        scope.launch {
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
}
