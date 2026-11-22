package com.instalogger.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.instalogger.app.InstaLoggerApp
import com.instalogger.app.R
import com.instalogger.app.data.local.HighlightEntity
import com.instalogger.app.data.local.HighlightMediaEntity
import com.instalogger.app.util.cleanDisplayText
import com.instalogger.app.ui.animation.MotionSpecs
import com.instalogger.app.ui.animation.bouncyClickable
import com.instalogger.app.ui.components.*
import com.instalogger.app.ui.theme.AccentBlue
import com.instalogger.app.ui.theme.ShapeTokens
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileDetailScreen(
    username: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as InstaLoggerApp
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val target by app.database.targetDao().getTargetFlow(username).collectAsStateWithLifecycle(initialValue = null)
    val logs by app.database.changeLogDao().getLogsForTarget(username).collectAsStateWithLifecycle(initialValue = emptyList())
    val avatars by app.database.avatarHistoryDao().getAvatarsForTarget(username).collectAsStateWithLifecycle(initialValue = emptyList())
    val highlights by app.database.highlightDao().getHighlightsForUser(username).collectAsStateWithLifecycle(initialValue = emptyList())
    val posts by app.database.postDao().getPostsForUser(username).collectAsStateWithLifecycle(initialValue = emptyList())
    val history by app.database.followerHistoryDao().getHistoryForTarget(username).collectAsStateWithLifecycle(initialValue = emptyList())
    val audits by app.database.followerAuditDao().getAuditsForTarget(username).collectAsStateWithLifecycle(initialValue = emptyList())

    var isRefreshing by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableIntStateOf(0) }
    var previewImagePath by remember { mutableStateOf<String?>(null) }
    var activeHighlight by remember { mutableStateOf<HighlightEntity?>(null) }
    var activeHighlightMedia by remember { mutableStateOf<List<HighlightMediaEntity>>(emptyList()) }
    var showAuditSheet by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var auditInitialFilter by remember { mutableStateOf("follower_lost") }
    val noneStr = stringResource(R.string.none_value)

    LaunchedEffect(username) {
        app.profileInspector.inspectHighlightsAndPosts(username)
    }

    fun refreshTargetData() {
        if (isRefreshing) return
        scope.launch {
            isRefreshing = true
            val result = app.profileInspector.inspectTarget(username)
            app.profileInspector.inspectHighlightsAndPosts(username)
            isRefreshing = false
            if (result.isSuccess) {
                snackbarHostState.showSnackbar(context.getString(R.string.msg_target_checked, username))
            } else {
                snackbarHostState.showSnackbar(context.getString(R.string.msg_check_failed, result.exceptionOrNull()?.message ?: ""))
            }
        }
    }

    Scaffold(
        topBar = {
            InstaLoggerTopBar(
                title = "@$username",
                subtitle = stringResource(R.string.target_detail_title),
                navigationIcon = Icons.AutoMirrored.Filled.ArrowBack,
                onNavigationClick = onBack,
                actions = {
                    val actionBtnShape = RoundedCornerShape(12.dp)
                    Box(
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .size(38.dp)
                            .clip(actionBtnShape)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .border(0.75.dp, MaterialTheme.colorScheme.outlineVariant, actionBtnShape),
                        contentAlignment = Alignment.Center
                    ) {
                        RotatingRefreshButton(
                            isRefreshing = isRefreshing,
                            onClick = { refreshTargetData() }
                        )
                    }

                    Box(
                        modifier = Modifier
                            .padding(end = 12.dp)
                            .size(38.dp)
                            .clip(actionBtnShape)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .border(0.75.dp, MaterialTheme.colorScheme.outlineVariant, actionBtnShape)
                            .bouncyClickable(pressScale = 0.90f, onClick = { showDeleteDialog = true }),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = stringResource(R.string.action_delete),
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { refreshTargetData() },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Profile Hero Card
                target?.let { t ->
                    val internalFile = com.instalogger.app.util.ArchiveDirectoryManager.getInternalAvatar(context, t.username)
                    val localFile = t.localAvatarPath?.let { File(it) }
                    val resolvedFile = when {
                        internalFile != null && internalFile.exists() && internalFile.length() > 0 -> internalFile
                        localFile != null && localFile.exists() && localFile.canRead() && localFile.length() > 0 -> localFile
                        else -> null
                    }

                    val heroShape = ShapeTokens.LargeShape
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .clip(heroShape)
                            .border(ShapeTokens.BorderSubtle, MaterialTheme.colorScheme.outlineVariant, heroShape),
                        shape = heroShape,
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        tonalElevation = 2.dp
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(18.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                val topImageSource: Any? = when {
                                    resolvedFile != null -> resolvedFile
                                    !t.avatarUrl.isNullOrBlank() -> t.avatarUrl
                                    !t.instagramId.isNullOrBlank() -> "http://127.0.0.1:3000/archives/${t.instagramId}/profile/avatar_current.jpg"
                                    else -> "http://127.0.0.1:3000/archives/${t.username}/profile/avatar_current.jpg"
                                }

                                if (topImageSource != null) {
                                    AsyncImage(
                                        model = topImageSource,
                                        contentDescription = t.username,
                                        modifier = Modifier
                                            .size(66.dp)
                                            .clip(CircleShape)
                                            .border(ShapeTokens.BorderActive, AccentBlue.copy(alpha = 0.8f), CircleShape)
                                            .bouncyClickable(pressScale = 0.92f) {
                                                previewImagePath = (topImageSource as? File)?.absolutePath ?: topImageSource.toString()
                                            },
                                        contentScale = ContentScale.Crop
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .size(66.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                            .border(ShapeTokens.BorderSubtle, MaterialTheme.colorScheme.outlineVariant, CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Person,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(32.dp)
                                        )
                                    }
                                }

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "@${t.username}",
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )

                                    if (t.fullName.isNotBlank()) {
                                        Text(
                                            text = t.fullName,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(6.dp))

                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        PrivacyBadge(isPrivate = t.isPrivate)
                                        AnimatedVisibility(visible = t.isTurbo) {
                                            TurboBadge(isTurbo = true)
                                        }
                                    }
                                }
                            }

                            if (t.biography.isNotBlank()) {
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = t.biography.cleanDisplayText(),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 6,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            // Grouped stats pod
                            val statsPodShape = ShapeTokens.MediumShape
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(statsPodShape)
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                    .border(ShapeTokens.BorderSubtle, MaterialTheme.colorScheme.outlineVariant, statsPodShape)
                                    .padding(vertical = 10.dp, horizontal = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(ShapeTokens.SmallShape)
                                        .bouncyClickable(pressScale = 0.94f) {
                                            auditInitialFilter = "follower_lost"
                                            showAuditSheet = true
                                        }
                                        .padding(vertical = 4.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(text = "${t.followersCount}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(text = stringResource(R.string.followers), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(ShapeTokens.SmallShape)
                                        .bouncyClickable(pressScale = 0.94f) {
                                            auditInitialFilter = "following_lost"
                                            showAuditSheet = true
                                        }
                                        .padding(vertical = 4.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(text = "${t.followingCount}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(text = stringResource(R.string.following), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(text = "${t.mediaCount}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(text = stringResource(R.string.posts), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }

                // Instagram Highlights Horizontal Row
                if (highlights.isNotEmpty()) {
                    InstagramHighlightRow(
                        highlights = highlights,
                        onHighlightClick = { hl ->
                            scope.launch {
                                activeHighlightMedia = app.database.highlightDao().getMediaListForHighlight(hl.id)
                                activeHighlight = hl
                            }
                        },
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }

                // Interactive Followers & Following Growth Chart
                FollowerGrowthChart(
                    history = history,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                )

                // Standardized Sliding Pill Tab Row
                SlidingPillTabRow(
                    tabs = listOf(
                        TabItem(
                            title = stringResource(R.string.tab_audit_logs, logs.size),
                            icon = Icons.Default.History
                        ),
                        TabItem(
                            title = stringResource(R.string.tab_posts, posts.size),
                            icon = Icons.Default.GridOn
                        ),
                        TabItem(
                            title = stringResource(R.string.tab_avatar_history, avatars.size),
                            icon = Icons.Default.Image
                        )
                    ),
                    selectedIndex = selectedTab,
                    onTabSelected = { selectedTab = it },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                )

                when (selectedTab) {
                    0 -> {
                        if (logs.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = stringResource(R.string.empty_target_logs),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                items(logs, key = { it.id }) { log ->
                                    if (log.eventType == "avatar_change") {
                                        AvatarComparisonCard(
                                            log = log,
                                            modifier = Modifier.animateItem()
                                        )
                                    } else {
                                        val timeFormat = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
                                        val formattedTime = timeFormat.format(Date(log.timestamp))

                                        val itemShape = RoundedCornerShape(20.dp)
                                        Surface(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(itemShape)
                                                .border(0.75.dp, MaterialTheme.colorScheme.outlineVariant, itemShape)
                                                .animateItem(),
                                            shape = itemShape,
                                            color = MaterialTheme.colorScheme.surfaceContainer,
                                            tonalElevation = 1.dp
                                        ) {
                                            Column(modifier = Modifier.padding(16.dp)) {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Text(
                                                        text = log.title,
                                                        style = MaterialTheme.typography.titleMedium,
                                                        fontWeight = FontWeight.Bold,
                                                        color = AccentBlue,
                                                        modifier = Modifier.weight(1f, fill = false),
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                    Text(
                                                        text = formattedTime,
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                                if (!log.oldValue.isNullOrBlank() || !log.newValue.isNullOrBlank()) {
                                                    Spacer(modifier = Modifier.height(8.dp))
                                                    Text(
                                                        text = "${log.oldValue?.cleanDisplayText() ?: noneStr}  ->  ${log.newValue?.cleanDisplayText() ?: noneStr}",
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        color = MaterialTheme.colorScheme.onSurface
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    1 -> {
                        InstagramPostGrid(
                            posts = posts,
                            onPostClick = { post ->
                                val localFile = post.localPath?.let { File(it) }
                                previewImagePath = if (localFile != null && localFile.exists()) localFile.absolutePath else post.displayUrl
                            }
                        )
                    }
                    2 -> {
                        if (avatars.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = stringResource(R.string.empty_target_avatars),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                items(avatars, key = { it.id }) { avatar ->
                                    val file = File(avatar.localPath)
                                    val internalCandidate = File(context.filesDir, "avatars/${avatar.username}/${file.name}")
                                    val imageModel: Any = when {
                                        internalCandidate.exists() && internalCandidate.length() > 0 -> internalCandidate
                                        file.exists() && file.canRead() && file.length() > 0 -> file
                                        else -> "http://127.0.0.1:3000/archives/${avatar.username}/profile/${file.name}"
                                    }
                                    val timeFormat = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault())
                                    val formattedDate = timeFormat.format(Date(avatar.archivedAt))

                                    val itemShape = RoundedCornerShape(20.dp)
                                    Surface(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(itemShape)
                                            .border(0.75.dp, MaterialTheme.colorScheme.outlineVariant, itemShape)
                                            .bouncyClickable(pressScale = 0.97f) {
                                                previewImagePath = (imageModel as? File)?.absolutePath ?: avatar.localPath
                                            }
                                            .animateItem(),
                                        shape = itemShape,
                                        color = MaterialTheme.colorScheme.surfaceContainer,
                                        tonalElevation = 1.dp
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(14.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                                        ) {
                                            AsyncImage(
                                                model = imageModel,
                                                contentDescription = null,
                                                modifier = Modifier
                                                    .size(64.dp)
                                                    .clip(RoundedCornerShape(16.dp))
                                                    .border(0.75.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp)),
                                                contentScale = ContentScale.Crop
                                            )
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = file.name,
                                                    style = MaterialTheme.typography.bodyLarge,
                                                    fontWeight = FontWeight.SemiBold,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Spacer(modifier = Modifier.height(4.dp))
                                                Text(
                                                    text = stringResource(R.string.avatar_archived_format, formattedDate),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                                Text(
                                                    text = stringResource(R.string.avatar_size_format, (avatar.fileSize / 1024)),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Standardized full screen image preview dialog
    previewImagePath?.let { path ->
        ImagePreviewDialog(
            imagePath = path,
            onDismiss = { previewImagePath = null }
        )
    }

    // Instagram Fullscreen Story Viewer
    activeHighlight?.let { hl ->
        InstagramStoryViewer(
            highlight = hl,
            mediaList = activeHighlightMedia,
            onDismiss = { activeHighlight = null }
        )
    }

    // Followers & Following Audit Bottom Sheet
    if (showAuditSheet) {
        FollowerAuditBottomSheet(
            targetUsername = username,
            initialFilter = auditInitialFilter,
            audits = audits,
            onDismiss = { showAuditSheet = false }
        )
    }

    // Delete Target Confirmation Dialog
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = {
                Text(
                    text = stringResource(R.string.dialog_delete_confirm_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.dialog_delete_confirm_desc, username),
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteDialog = false
                        scope.launch {
                            app.profileInspector.deleteTarget(username, target?.instagramId)
                            onBack()
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
                    onClick = { showDeleteDialog = false },
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
