package com.instalogger.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.instalogger.app.InstaLoggerApp
import com.instalogger.app.R
import com.instalogger.app.data.local.AvatarHistoryEntity
import com.instalogger.app.data.local.ChangeLogEntity
import com.instalogger.app.data.local.HighlightEntity
import com.instalogger.app.data.local.HighlightMediaEntity
import com.instalogger.app.data.local.PostEntity
import com.instalogger.app.data.local.TargetEntity
import com.instalogger.app.engine.PostSyncStatus
import com.instalogger.app.engine.media.PostDownloadState
import com.instalogger.app.engine.pool.FollowerPoolEngine
import com.instalogger.app.engine.pool.FollowerPoolMode
import com.instalogger.app.ui.animation.bouncyClickable
import com.instalogger.app.ui.components.*
import com.instalogger.app.ui.theme.AccentBlue
import com.instalogger.app.ui.theme.ShapeTokens
import com.instalogger.app.util.ArchiveDirectoryManager
import com.instalogger.app.util.FormatUtils
import com.instalogger.app.util.cleanDisplayText
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileDetailScreen(
    username: String,
    onBack: () -> Unit,
    onNavigateToSession: (() -> Unit)? = null
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
    val syncStateMap by app.profileInspector.postSyncState.collectAsStateWithLifecycle()
    val downloadStates by app.mediaDownloadOrchestrator.downloadStates.collectAsStateWithLifecycle()
    val cleanUserLower = remember(username) { username.trim().trimStart('@').lowercase() }
    val targetSyncStatus = syncStateMap[cleanUserLower] ?: PostSyncStatus.IDLE
    val hasSession = remember { app.sessionManager.hasSession() }

    var isRefreshing by remember { mutableStateOf(false) }
    var isFetchingPosts by remember { mutableStateOf(posts.isEmpty()) }
    var selectedTab by remember { mutableIntStateOf(0) }
    var previewImagePath by remember { mutableStateOf<String?>(null) }
    var selectedPostForViewer by remember { mutableStateOf<PostEntity?>(null) }
    var activeHighlight by remember { mutableStateOf<HighlightEntity?>(null) }
    var activeHighlightMedia by remember { mutableStateOf<List<HighlightMediaEntity>>(emptyList()) }
    var isLoadingHighlightMedia by remember { mutableStateOf(false) }
    var showAuditSheet by remember { mutableStateOf(false) }
    var auditInitialTab by remember { mutableIntStateOf(0) }
    var showPoolSheet by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var avatarToDelete by remember { mutableStateOf<AvatarHistoryEntity?>(null) }
    val globalPoolMode by app.settingsManager.poolMode.collectAsStateWithLifecycle()

    // Auto-fetch posts & highlights immediately upon opening screen and clean duplicate avatars
    LaunchedEffect(username) {
        app.profileInspector.deduplicateAvatars(username)
        if (posts.isEmpty()) isFetchingPosts = true
        try {
            app.profileInspector.inspectHighlightsAndPosts(username)
        } finally {
            isFetchingPosts = false
        }
    }

    fun refreshTargetData() {
        if (isRefreshing) return
        scope.launch {
            isRefreshing = true
            if (posts.isEmpty()) isFetchingPosts = true
            val result = app.profileInspector.inspectTarget(username)
            app.profileInspector.inspectHighlightsAndPosts(username)
            isRefreshing = false
            isFetchingPosts = false
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
                            .padding(end = 8.dp)
                            .size(38.dp)
                            .clip(actionBtnShape)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .border(0.75.dp, MaterialTheme.colorScheme.outlineVariant, actionBtnShape)
                            .bouncyClickable(pressScale = 0.90f, onClick = { showPoolSheet = true }),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = stringResource(R.string.pool_global_title),
                            tint = AccentBlue,
                            modifier = Modifier.size(20.dp)
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
            // UNIFIED SEAMLESS SCROLLABLE CONTAINER
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 108.dp)
            ) {
                // 1. Profile Hero Card
                item(key = "hero_card") {
                    if (target != null) {
                        val currentTarget = target!!
                        val openActiveStory: () -> Unit = {
                            val virtualHl = HighlightEntity(
                                id = "active_story:${currentTarget.username.lowercase()}",
                                username = currentTarget.username.lowercase(),
                                title = context.getString(R.string.story_viewer_active_title, currentTarget.username),
                                coverUrl = currentTarget.avatarUrl.orEmpty(),
                                localCoverPath = currentTarget.localAvatarPath,
                                mediaCount = 0,
                                archivedAt = currentTarget.latestStoryAt
                            )
                            activeHighlight = virtualHl
                            activeHighlightMedia = emptyList()
                            isLoadingHighlightMedia = true
                            scope.launch {
                                activeHighlightMedia = app.profileInspector.fetchActiveStoriesOnDemand(currentTarget)
                                isLoadingHighlightMedia = false
                            }
                        }
                        val resolvedPoolPolicy = remember(currentTarget, globalPoolMode) {
                            app.settingsManager.resolvePoolPolicy(currentTarget, isFollowing = false)
                        }
                        val effectivePoolThreshold = remember(currentTarget, resolvedPoolPolicy) {
                            FollowerPoolEngine.resolveEffectiveThreshold(
                                mode = resolvedPoolPolicy.mode,
                                fixedThreshold = resolvedPoolPolicy.fixedThreshold,
                                percentThreshold = resolvedPoolPolicy.percentThreshold,
                                referenceCount = maxOf(currentTarget.followersCount, currentTarget.poolFollowerBaseline)
                            )
                        }
                        ProfileHeroSection(
                            target = currentTarget,
                            localPostsCount = posts.size,
                            effectivePoolMode = resolvedPoolPolicy.mode,
                            effectivePoolThreshold = effectivePoolThreshold,
                            onAvatarClick = { avatarPath ->
                                if (currentTarget.hasActiveStory) {
                                    openActiveStory()
                                } else {
                                    previewImagePath = avatarPath
                                }
                            },
                            onStoryBadgeClick = openActiveStory,
                            onPoolBadgeClick = { showPoolSheet = true },
                            onFollowersClick = {
                                auditInitialTab = 0
                                showAuditSheet = true
                            },
                            onFollowingClick = {
                                auditInitialTab = 1
                                showAuditSheet = true
                            }
                        )
                    } else {
                        SkeletonProfileHero()
                    }
                }

                // 2. Instagram Highlights Row (if available)
                if (highlights.isNotEmpty()) {
                    item(key = "highlights_row") {
                        InstagramHighlightRow(
                            highlights = highlights,
                            onHighlightClick = { hl ->
                                activeHighlight = hl
                                activeHighlightMedia = emptyList()
                                isLoadingHighlightMedia = true
                                scope.launch {
                                    activeHighlightMedia = app.profileInspector.fetchHighlightMediaOnDemand(hl)
                                    isLoadingHighlightMedia = false
                                }
                            },
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }
                }

                // 3. Follower Growth Trend Chart Card
                item(key = "growth_chart") {
                    FollowerGrowthChart(
                        history = history,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                    )
                }

                // 4. Sliding Pill Tab Selector
                val totalPostsCount = target?.let { maxOf(it.mediaCount, posts.size.toLong()).toInt() } ?: posts.size
                item(key = "tab_selector") {
                    SlidingPillTabRow(
                        tabs = listOf(
                            TabItem(
                                title = stringResource(R.string.tab_audit_short, logs.size),
                                icon = Icons.Default.History
                            ),
                            TabItem(
                                title = stringResource(R.string.tab_posts_short, totalPostsCount),
                                icon = Icons.Default.GridOn
                            ),
                            TabItem(
                                title = stringResource(R.string.tab_avatars_short, avatars.size),
                                icon = Icons.Default.Image
                            )
                        ),
                        selectedIndex = selectedTab,
                        onTabSelected = { selectedTab = it },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }

                // 5. Selected Tab Content Items
                when (selectedTab) {
                    0 -> {
                        // Audit Logs Tab
                        if (logs.isEmpty()) {
                            item(key = "empty_logs") {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(32.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = stringResource(R.string.empty_target_logs),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        } else {
                            items(logs, key = { "log_${it.id}" }) { log ->
                                Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                                    if (log.eventType == "avatar_change") {
                                        AvatarComparisonCard(
                                            log = log,
                                            modifier = Modifier.animateItem()
                                        )
                                    } else {
                                        AuditLogCard(log = log)
                                    }
                                }
                            }
                        }
                    }
                    1 -> {
                        // Posts Tab (Chunked into 3-column rows for smooth unified scrolling)
                        val isActivelySyncing = isFetchingPosts || isRefreshing || targetSyncStatus == PostSyncStatus.SYNCING
                        val isPrivateAccount = target?.isPrivate == true

                        if (posts.isEmpty()) {
                            if (isActivelySyncing && !(isPrivateAccount && !hasSession)) {
                                item(key = "skeleton_posts") {
                                    SkeletonPostGrid(
                                        count = if (totalPostsCount > 0) totalPostsCount else 3,
                                        showStatusCard = true,
                                        onRetry = { refreshTargetData() }
                                    )
                                }
                            } else if (isPrivateAccount && (!hasSession || targetSyncStatus == PostSyncStatus.REQUIRES_SESSION)) {
                                item(key = "private_account_card") {
                                    Surface(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 16.dp)
                                            .testTag("private_account_card"),
                                        shape = ShapeTokens.LargeShape,
                                        color = MaterialTheme.colorScheme.surfaceContainer,
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(24.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.spacedBy(14.dp)
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(56.dp)
                                                    .clip(CircleShape)
                                                    .background(AccentBlue.copy(alpha = 0.12f)),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Lock,
                                                    contentDescription = stringResource(R.string.posts_private_account_title),
                                                    tint = AccentBlue,
                                                    modifier = Modifier.size(28.dp)
                                                )
                                            }

                                            Text(
                                                text = stringResource(R.string.posts_private_account_title),
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                textAlign = TextAlign.Center
                                            )

                                            Text(
                                                text = stringResource(R.string.posts_private_account_desc),
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                textAlign = TextAlign.Center
                                            )

                                            Spacer(modifier = Modifier.height(4.dp))

                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(IntrinsicSize.Min),
                                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                if (onNavigateToSession != null) {
                                                    Button(
                                                        onClick = onNavigateToSession,
                                                        modifier = Modifier
                                                            .weight(1f)
                                                            .fillMaxHeight()
                                                            .minimumInteractiveComponentSize()
                                                            .testTag("private_login_button"),
                                                        shape = ShapeTokens.MediumShape,
                                                        colors = ButtonDefaults.buttonColors(containerColor = AccentBlue),
                                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Default.VpnKey,
                                                            contentDescription = null,
                                                            modifier = Modifier.size(16.dp)
                                                        )
                                                        Spacer(modifier = Modifier.width(6.dp))
                                                        Text(
                                                            text = stringResource(R.string.btn_login_session),
                                                            fontWeight = FontWeight.Bold,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis
                                                        )
                                                    }
                                                }

                                                OutlinedButton(
                                                    onClick = { refreshTargetData() },
                                                    modifier = Modifier
                                                        .weight(if (onNavigateToSession != null) 0.8f else 1f)
                                                        .fillMaxHeight()
                                                        .minimumInteractiveComponentSize()
                                                        .testTag("private_refresh_button"),
                                                    shape = ShapeTokens.MediumShape,
                                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.Refresh,
                                                        contentDescription = null,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Text(
                                                        text = stringResource(R.string.action_refresh_list),
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            } else if (targetSyncStatus == PostSyncStatus.RATE_LIMITED) {
                                item(key = "rate_limited_card") {
                                    Surface(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 16.dp),
                                        shape = ShapeTokens.LargeShape,
                                        color = MaterialTheme.colorScheme.surfaceContainer,
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(24.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.spacedBy(14.dp)
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(56.dp)
                                                    .clip(CircleShape)
                                                    .background(MaterialTheme.colorScheme.error.copy(alpha = 0.12f)),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Timer,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.error,
                                                    modifier = Modifier.size(28.dp)
                                                )
                                            }

                                            Text(
                                                text = stringResource(R.string.posts_rate_limited_title),
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                textAlign = TextAlign.Center
                                            )

                                            Text(
                                                text = stringResource(R.string.posts_rate_limited_desc),
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                textAlign = TextAlign.Center
                                            )

                                            Button(
                                                onClick = { refreshTargetData() },
                                                shape = ShapeTokens.MediumShape
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Refresh,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(stringResource(R.string.action_refresh_list))
                                            }
                                        }
                                    }
                                }
                            } else if (totalPostsCount == 0) {
                                item(key = "empty_posts") {
                                    UnifiedEmptyState(
                                        title = stringResource(R.string.empty_target_posts),
                                        description = "",
                                        icon = Icons.Default.GridOn,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 40.dp)
                                    )
                                }
                            } else {
                                // Sync failed or ended with 0 posts
                                item(key = "sync_failed_card") {
                                    Surface(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 16.dp),
                                        shape = ShapeTokens.LargeShape,
                                        color = MaterialTheme.colorScheme.surfaceContainer,
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(24.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.spacedBy(14.dp)
                                        ) {
                                            Text(
                                                text = stringResource(R.string.posts_sync_failed_title),
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                            Text(
                                                text = stringResource(R.string.posts_sync_failed_desc),
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                textAlign = TextAlign.Center
                                            )
                                            Button(
                                                onClick = { refreshTargetData() },
                                                shape = ShapeTokens.MediumShape
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Refresh,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(stringResource(R.string.action_refresh_list))
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            val postChunks = posts.chunked(3)
                            items(postChunks, key = { chunk -> "chunk_${chunk.first().id}" }) { chunk ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 2.dp),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    for (post in chunk) {
                                        Box(modifier = Modifier.weight(1f)) {
                                            PostGridItem(
                                                post = post,
                                                downloadState = downloadStates[post.id] ?: PostDownloadState.Idle,
                                                onClick = { selectedPostForViewer = post }
                                            )
                                        }
                                    }
                                    repeat(3 - chunk.size) {
                                        Spacer(modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }
                    2 -> {
                        // Avatar History Tab
                        if (avatars.isEmpty()) {
                            item(key = "empty_avatars") {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(32.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = stringResource(R.string.empty_target_avatars),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        } else {
                            items(avatars, key = { "avatar_${it.id}" }) { avatar ->
                                Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                                    AvatarHistoryCard(
                                        avatar = avatar,
                                        onAvatarClick = { previewImagePath = it },
                                        onDeleteClick = { avatarToDelete = it }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Full-screen post detail viewer dialog (always reads live post record from DB)
    selectedPostForViewer?.let { selected ->
        val livePost = posts.firstOrNull { it.id == selected.id } ?: selected
        InstagramPostViewerDialog(
            post = livePost,
            targetAvatarModel = target?.let { resolveAvatarSource(context, it) },
            onDismiss = { selectedPostForViewer = null }
        )
    }

    // Standardized full screen image preview dialog
    previewImagePath?.let { path ->
        ImagePreviewDialog(
            imagePath = path,
            onDismiss = { previewImagePath = null }
        )
    }

    // Instagram Fullscreen Story Viewer (with on-demand fetch + Vault video decryption)
    activeHighlight?.let { hl ->
        InstagramStoryViewer(
            highlight = hl,
            mediaList = activeHighlightMedia,
            isLoading = isLoadingHighlightMedia,
            hasSession = app.sessionManager.hasSession(),
            onRetry = {
                isLoadingHighlightMedia = true
                scope.launch {
                    val curTarget = target
                    activeHighlightMedia = if (hl.id.startsWith("active_story:") && curTarget != null) {
                        app.profileInspector.fetchActiveStoriesOnDemand(curTarget)
                    } else {
                        app.profileInspector.fetchHighlightMediaOnDemand(hl)
                    }
                    isLoadingHighlightMedia = false
                }
            },
            onNavigateToSession = onNavigateToSession,
            onDismiss = {
                activeHighlight = null
                isLoadingHighlightMedia = false
            }
        )
    }

    // Followers & Following Audit Bottom Sheet (3-Tab Viewer)
    if (showAuditSheet) {
        FollowerAuditBottomSheet(
            targetUsername = username,
            initialTab = auditInitialTab,
            audits = audits,
            onDismiss = { showAuditSheet = false }
        )
    }

    // Per-Target Follower Change Pool Settings Bottom Sheet
    if (showPoolSheet && target != null) {
        TargetPoolSettingsBottomSheet(
            target = target!!,
            onDismissRequest = { showPoolSheet = false },
            onFlushed = { flushedDelta ->
                scope.launch {
                    val msg = if (flushedDelta != 0L) {
                        val sign = if (flushedDelta > 0L) "+$flushedDelta" else "$flushedDelta"
                        context.getString(R.string.pool_msg_flushed, sign)
                    } else {
                        context.getString(R.string.pool_msg_reset)
                    }
                    snackbarHostState.showSnackbar(msg)
                }
            }
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
                        onBack()
                        scope.launch {
                            app.profileInspector.deleteTarget(username, target?.instagramId)
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

    // Delete Historical Avatar Confirmation Dialog
    avatarToDelete?.let { avatarItem ->
        AlertDialog(
            onDismissRequest = { avatarToDelete = null },
            title = {
                Text(
                    text = stringResource(R.string.dialog_delete_avatar_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.dialog_delete_avatar_desc),
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val toRemove = avatarItem
                        avatarToDelete = null
                        scope.launch(Dispatchers.IO) {
                            app.database.avatarHistoryDao().deleteAvatarById(toRemove.id)
                            try {
                                val f = File(toRemove.localPath)
                                if (f.exists() && f.name != "avatar_current.jpg") {
                                    f.delete()
                                }
                            } catch (_: Exception) {}
                            snackbarHostState.showSnackbar(context.getString(R.string.msg_avatar_deleted))
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
                    onClick = { avatarToDelete = null },
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

private fun resolveAvatarSource(context: android.content.Context, t: TargetEntity): Any? {
    val internalFile = ArchiveDirectoryManager.getInternalAvatar(context, t.username)
    val localFile = t.localAvatarPath?.let { File(it) }
    val archiveDirFile = File(ArchiveDirectoryManager.getArchiveDirectory(context), "${t.username}/profile/avatar_current.jpg")
    return when {
        internalFile != null && internalFile.exists() && internalFile.length() > 0 -> internalFile
        localFile != null && localFile.exists() && localFile.canRead() && localFile.length() > 0 -> localFile
        archiveDirFile.exists() && archiveDirFile.length() > 0 -> archiveDirFile
        !t.avatarUrl.isNullOrBlank() -> t.avatarUrl
        else -> null
    }
}

@Composable
private fun ProfileHeroSection(
    target: TargetEntity,
    localPostsCount: Int = 0,
    effectivePoolMode: FollowerPoolMode = FollowerPoolMode.OFF,
    effectivePoolThreshold: Long = 1L,
    onAvatarClick: (String) -> Unit,
    onStoryBadgeClick: () -> Unit = {},
    onPoolBadgeClick: () -> Unit = {},
    onFollowersClick: () -> Unit,
    onFollowingClick: () -> Unit
) {
    val context = LocalContext.current
    val topImageSource = resolveAvatarSource(context, target)
    val heroShape = ShapeTokens.LargeShape
    val storyRingGradient = Brush.linearGradient(
        colors = listOf(
            Color(0xFFF58529),
            Color(0xFFFEDA77),
            Color(0xFFDD2A7B),
            Color(0xFF8134AF),
            Color(0xFF515BD4)
        )
    )

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
                Box(
                    modifier = if (target.hasActiveStory) {
                        Modifier
                            .size(74.dp)
                            .clip(CircleShape)
                            .border(2.8.dp, storyRingGradient, CircleShape)
                            .padding(3.5.dp)
                    } else {
                        Modifier.size(66.dp)
                    },
                    contentAlignment = Alignment.Center
                ) {
                    if (topImageSource != null) {
                        AsyncImage(
                            model = topImageSource,
                            contentDescription = target.username,
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(CircleShape)
                                .then(
                                    if (!target.hasActiveStory) {
                                        Modifier.border(ShapeTokens.BorderActive, AccentBlue.copy(alpha = 0.8f), CircleShape)
                                    } else {
                                        Modifier
                                    }
                                )
                                .bouncyClickable(pressScale = 0.92f) {
                                    val path = (topImageSource as? File)?.absolutePath ?: topImageSource.toString()
                                    onAvatarClick(path)
                                },
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                .border(ShapeTokens.BorderSubtle, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                                .bouncyClickable(pressScale = 0.92f) {
                                    if (target.hasActiveStory) onStoryBadgeClick()
                                },
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
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "@${target.username}",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    if (target.fullName.isNotBlank()) {
                        Text(
                            text = target.fullName,
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
                        PrivacyBadge(isPrivate = target.isPrivate)
                        if (target.hasActiveStory) {
                            Surface(
                                shape = ShapeTokens.PillShape,
                                color = Color(0xFFDD2A7B).copy(alpha = 0.16f),
                                border = BorderStroke(0.75.dp, Color(0xFFDD2A7B).copy(alpha = 0.55f)),
                                modifier = Modifier.bouncyClickable(pressScale = 0.92f, onClick = onStoryBadgeClick)
                            ) {
                                Text(
                                    text = stringResource(R.string.badge_active_story),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFDD2A7B),
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        }
                        AnimatedVisibility(visible = target.isTurbo) {
                            TurboBadge(isTurbo = true)
                        }
                    }

                    // Live Pool Status / Quick Opt-In Chip
                    val showActivePoolPill = effectivePoolMode != FollowerPoolMode.OFF
                    val showLargeAccountHint = effectivePoolMode == FollowerPoolMode.OFF && target.followersCount >= 10_000L
                    if (showActivePoolPill || showLargeAccountHint) {
                        Spacer(modifier = Modifier.height(6.dp))
                        val baseline = if (target.poolFollowerBaseline > 0L) target.poolFollowerBaseline else target.followersCount
                        val netDelta = target.followersCount - baseline
                        val sign = if (netDelta > 0L) "+$netDelta" else "$netDelta"
                        Surface(
                            shape = ShapeTokens.PillShape,
                            color = AccentBlue.copy(alpha = if (showActivePoolPill) 0.14f else 0.08f),
                            border = BorderStroke(0.75.dp, AccentBlue.copy(alpha = if (showActivePoolPill) 0.45f else 0.25f)),
                            modifier = Modifier.bouncyClickable(pressScale = 0.94f, onClick = onPoolBadgeClick)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Tune,
                                    contentDescription = null,
                                    tint = AccentBlue,
                                    modifier = Modifier.size(12.dp)
                                )
                                Text(
                                    text = if (showActivePoolPill) {
                                        stringResource(R.string.pool_badge_active, sign, effectivePoolThreshold)
                                    } else {
                                        stringResource(R.string.pool_badge_suggest_large)
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = AccentBlue,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }

            if (target.biography.isNotBlank()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = target.biography.cleanDisplayText(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Interactive stats pod
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
                        .bouncyClickable(pressScale = 0.94f, onClick = onFollowersClick)
                        .padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(text = FormatUtils.formatCompactNumber(target.followersCount), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(text = stringResource(R.string.followers), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(ShapeTokens.SmallShape)
                        .bouncyClickable(pressScale = 0.94f, onClick = onFollowingClick)
                        .padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(text = FormatUtils.formatCompactNumber(target.followingCount), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(text = stringResource(R.string.following), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = FormatUtils.formatCompactNumber(if (target.mediaCount > 0) target.mediaCount else localPostsCount.toLong()),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(text = stringResource(R.string.posts), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun AuditLogCard(log: ChangeLogEntity) {
    val timeFormat = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
    val formattedTime = timeFormat.format(Date(log.timestamp))
    val itemShape = ShapeTokens.MediumShape

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(itemShape)
            .border(0.75.dp, MaterialTheme.colorScheme.outlineVariant, itemShape),
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
                    text = "${log.oldValue?.cleanDisplayText() ?: "-"}  ->  ${log.newValue?.cleanDisplayText() ?: "-"}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
private fun AvatarHistoryCard(
    avatar: AvatarHistoryEntity,
    onAvatarClick: (String) -> Unit,
    onDeleteClick: (AvatarHistoryEntity) -> Unit
) {
    val context = LocalContext.current
    val file = File(avatar.localPath)
    val vaultFile = File(context.filesDir, "vault/${avatar.username}/profile/${file.name}.enc")
    val internalCandidate = File(context.filesDir, "avatars/${avatar.username}/${file.name}")
    val externalArchive = File(ArchiveDirectoryManager.getArchiveDirectory(context), "${avatar.username}/profile/${file.name}")
    val imageModel: Any = when {
        file.exists() && file.canRead() && file.length() > 0 -> file
        vaultFile.exists() && vaultFile.length() > 0 -> vaultFile
        internalCandidate.exists() && internalCandidate.length() > 0 -> internalCandidate
        externalArchive.exists() && externalArchive.length() > 0 -> externalArchive
        else -> file
    }
    val timeFormat = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault())
    val formattedDate = timeFormat.format(Date(avatar.archivedAt))

    val itemShape = ShapeTokens.MediumShape
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(itemShape)
            .border(ShapeTokens.BorderSubtle, MaterialTheme.colorScheme.outlineVariant, itemShape)
            .bouncyClickable(pressScale = 0.97f) {
                val path = (imageModel as? File)?.absolutePath ?: avatar.localPath
                onAvatarClick(path)
            },
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
                    .clip(ShapeTokens.SmallShape)
                    .border(ShapeTokens.BorderSubtle, MaterialTheme.colorScheme.outlineVariant, ShapeTokens.SmallShape),
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
            IconButton(
                onClick = { onDeleteClick(avatar) },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = stringResource(R.string.action_delete),
                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
