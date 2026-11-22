package com.instalogger.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.instalogger.app.data.remote.dto.TargetDto
import com.instalogger.app.data.repository.TargetRepository
import com.instalogger.app.ui.components.AddTargetDialog
import com.instalogger.app.ui.components.InstaLoggerTopBar
import com.instalogger.app.ui.components.TargetCard
import kotlinx.coroutines.launch

@Composable
fun DashboardScreen(
    targetRepository: TargetRepository,
    onNavigateToDetail: (Long) -> Unit
) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var targets by remember { mutableStateOf<List<TargetDto>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var showAddDialog by remember { mutableStateOf(false) }

    fun refreshTargets() {
        scope.launch {
            isLoading = true
            targetRepository.getTargets().onSuccess {
                targets = it
            }.onFailure {
                snackbarHostState.showSnackbar("Hədəfləri yükləmək mümkün olmadı: ${it.localizedMessage}")
            }
            isLoading = false
        }
    }

    LaunchedEffect(Unit) {
        refreshTargets()
    }

    Scaffold(
        topBar = {
            InstaLoggerTopBar(
                title = "İzləmə Paneli",
                subtitle = "Aktiv Instagram hədəfləri və audit vəziyyəti",
                actions = {
                    IconButton(onClick = { refreshTargets() }) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Yenilə",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
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
            if (isLoading && targets.isEmpty()) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center)
                )
            } else if (targets.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterVertically,
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
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "İzləmək istədiyiniz Instagram istifadəçi adını əlavə edərək dərhal başlayın.",
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
                    items(targets, key = { it.id }) { target ->
                        TargetCard(
                            target = target,
                            onClick = { onNavigateToDetail(target.id) },
                            onCheckNow = {
                                scope.launch {
                                    targetRepository.checkTargetNow(target.id).onSuccess {
                                        snackbarHostState.showSnackbar("@${target.username} üçün yoxlanış başladıldı")
                                        refreshTargets()
                                    }.onFailure {
                                        snackbarHostState.showSnackbar("Yoxlama xətası: ${it.localizedMessage}")
                                    }
                                }
                            },
                            onToggleTurbo = { newTurbo ->
                                scope.launch {
                                    targetRepository.toggleTurbo(target.id, newTurbo).onSuccess {
                                        snackbarHostState.showSnackbar("Turbo vəziyyəti dəyişdirildi")
                                        refreshTargets()
                                    }
                                }
                            },
                            onDelete = {
                                scope.launch {
                                    targetRepository.deleteTarget(target.id).onSuccess {
                                        snackbarHostState.showSnackbar("@${target.username} silindi")
                                        refreshTargets()
                                    }
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
                scope.launch {
                    targetRepository.addTarget(username, turbo).onSuccess {
                        snackbarHostState.showSnackbar("@$username əlavə edildi")
                        refreshTargets()
                    }.onFailure {
                        snackbarHostState.showSnackbar("Əlavə edilmədi: ${it.localizedMessage}")
                    }
                }
            }
        )
    }
}
