package com.instalogger.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.instalogger.app.InstaLoggerApp
import com.instalogger.app.ui.components.AddTargetDialog
import com.instalogger.app.ui.components.InstaLoggerTopBar
import com.instalogger.app.ui.components.RotatingRefreshButton
import com.instalogger.app.ui.components.TargetCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun DashboardScreen(
    onNavigateToDetail: (String) -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as InstaLoggerApp
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val targets by app.database.targetDao().getAllTargets().collectAsStateWithLifecycle(initialValue = emptyList())
    var checkingTargetUsername by remember { mutableStateOf<String?>(null) }
    var isGlobalRefreshing by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }

    fun refreshAllTargets() {
        if (isGlobalRefreshing) return
        scope.launch {
            isGlobalRefreshing = true
            val activeTargets = withContext(Dispatchers.IO) { app.database.targetDao().getActiveTargets() }
            for (t in activeTargets) {
                try {
                    app.profileInspector.inspectTarget(t.username)
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
                subtitle = "Müstəqil avtomatik monitorinq",
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
        Box(
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
                        text = "İzləmək istədiyiniz Instagram istifadəçi adını əlavə edin. Tətbiq birbaşa arxa fonda fasiləsiz yoxlayacaq.",
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
                                            snackbarHostState.showSnackbar("@${target.username} yoxlanıldı")
                                        } else {
                                            snackbarHostState.showSnackbar("Yoxlama xətası: ${result.exceptionOrNull()?.message}")
                                        }
                                    }
                                }
                            },
                            onToggleTurbo = { newTurbo ->
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        app.database.targetDao().updateTurbo(target.username, newTurbo)
                                    }
                                    snackbarHostState.showSnackbar(if (newTurbo) "Turbo aktiv edildi" else "Turbo deaktiv edildi")
                                }
                            },
                            onDelete = {
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
                    val progressSnackbar = snackbarHostState.showSnackbar(
                        message = "@$cleanUser əlavə edilir və ilk yoxlama aparılır...",
                        duration = SnackbarDuration.Short
                    )

                    val result = app.profileInspector.inspectTarget(cleanUser)
                    if (result.isSuccess) {
                        if (turbo) {
                            withContext(Dispatchers.IO) {
                                app.database.targetDao().updateTurbo(cleanUser, true)
                            }
                        }
                        snackbarHostState.showSnackbar("@$cleanUser uğurla əlavə edildi!")
                    } else {
                        // Insert placeholder target anyway
                        withContext(Dispatchers.IO) {
                            app.database.targetDao().insertTarget(
                                com.instalogger.app.data.local.TargetEntity(
                                    username = cleanUser,
                                    isTurbo = turbo
                                )
                            )
                        }
                        snackbarHostState.showSnackbar("@$cleanUser əlavə edildi (İlk yoxlama fon xidmətində təkrar ediləcək)")
                    }
                }
            }
        )
    }
}
