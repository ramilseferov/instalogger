package com.instalogger.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.instalogger.app.InstaLoggerApp
import com.instalogger.app.data.local.TargetEntity
import com.instalogger.app.ui.components.AddTargetDialog
import com.instalogger.app.ui.components.InstaLoggerTopBar
import com.instalogger.app.ui.components.RotatingRefreshButton
import com.instalogger.app.ui.components.TargetCard
import kotlinx.coroutines.Dispatchers
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

    // Instant real-time Flow observation from Room Database
    LaunchedEffect(Unit) {
        app.database.targetDao().getAllTargets().collect { list ->
            targets = list
        }
    }

    fun refreshAllTargets() {
        if (isGlobalRefreshing) return
        scope.launch {
            isGlobalRefreshing = true
            val activeTargets = withContext(Dispatchers.IO) { app.database.targetDao().getActiveTargets() }
            for (t in activeTargets) {
                try {
                    val result = app.profileInspector.inspectTarget(t.username)
                    result.onSuccess { updated ->
                        targets = targets.map { if (it.username == t.username) updated else it }
                    }
                } catch (e: Exception) {
                    // Continue to next target
                }
            }
            isGlobalRefreshing = false
            snackbarHostState.showSnackbar("Bütün hədəflər yoxlanıldı")
        }
    }

    Scaffold(
        topBar = {
            InstaLoggerTopBar(
                title = "İzləmə Paneli",
                subtitle = "Aktiv Instagram hədəfləri və audit vəziyyəti",
                actions = {
                    RotatingRefreshButton(
                        isRefreshing = isGlobalRefreshing,
                        onClick = { refreshAllTargets() }
                    )
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Hədəf Əlavə Et",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddDialog = true },
                shape = RoundedCornerShape(18.dp),
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Hədəf Əlavə Et",
                    tint = MaterialTheme.colorScheme.onPrimary
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
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Security,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Heç bir hədəf tapılmadı",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "İzləmək istədiyiniz Instagram istifadəçi adını əlavə edin. Yeniləmək üçün aşağı sürüşdürün.",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(
                        onClick = { showAddDialog = true },
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text("Yeni Hədəf Əlavə Et")
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
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
                                            targets = targets.map { if (it.username == target.username) updated else it }
                                            snackbarHostState.showSnackbar("@${target.username} yoxlanıldı")
                                        } else {
                                            snackbarHostState.showSnackbar("Yoxlama xətası: ${result.exceptionOrNull()?.message}")
                                        }
                                    }
                                }
                            },
                            onToggleTurbo = { newTurbo ->
                                // Instant optimistic update
                                targets = targets.map { if (it.username == target.username) it.copy(isTurbo = newTurbo) else it }
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        app.database.targetDao().updateTurbo(target.username, newTurbo)
                                    }
                                    snackbarHostState.showSnackbar(if (newTurbo) "Turbo aktiv edildi" else "Turbo deaktiv edildi")
                                }
                            },
                            onDelete = {
                                // Instant optimistic deletion from UI
                                targets = targets.filter { it.username != target.username }
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        app.database.targetDao().deleteTarget(target.username)
                                        app.database.changeLogDao().deleteLogsForTarget(target.username)
                                        app.database.avatarHistoryDao().deleteAvatarsForTarget(target.username)
                                    }
                                    snackbarHostState.showSnackbar("@${target.username} silindi")
                                }
                            }
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
                    val result = app.profileInspector.inspectTarget(cleanUser)
                    if (result.isSuccess) {
                        val newTarget = result.getOrThrow().copy(isTurbo = turbo)
                        if (turbo) {
                            withContext(Dispatchers.IO) {
                                app.database.targetDao().updateTurbo(cleanUser, true)
                            }
                        }
                        targets = listOf(newTarget) + targets.filter { it.username != cleanUser }
                        snackbarHostState.showSnackbar("@$cleanUser uğurla əlavə edildi!")
                    } else {
                        val placeholder = TargetEntity(username = cleanUser, isTurbo = turbo)
                        withContext(Dispatchers.IO) {
                            app.database.targetDao().insertTarget(placeholder)
                        }
                        targets = listOf(placeholder) + targets.filter { it.username != cleanUser }
                        snackbarHostState.showSnackbar("@$cleanUser əlavə edildi")
                    }
                }
            }
        )
    }
}
