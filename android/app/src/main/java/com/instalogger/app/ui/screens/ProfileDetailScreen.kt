package com.instalogger.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Storage
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
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
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

    val detailListState = rememberLazyListState()
    val targetFlow = remember(app, username) { app.database.targetDao().getTargetFlow(username).distinctUntilChanged() }
    val logsFlow = remember(app, username) { app.database.changeLogDao().getLogsForTarget(username).distinctUntilChanged() }
    val avatarsFlow = remember(app, username) { app.database.avatarHistoryDao().getAvatarsForTarget(username).distinctUntilChanged() }
    val highlightsFlow = remember(app, username) { app.database.highlightDao().getHighlightsForUser(username).distinctUntilChanged() }
    val archivedStoriesFlow = remember(app, username) { app.database.highlightDao().getArchivedStoriesForUser(username).distinctUntilChanged() }
    val allHighlightMediaFlow = remember(app, username) { app.database.highlightDao().getAllHighlightMediaForUser(username).distinctUntilChanged() }
    val postsFlow = remember(app, username) { app.database.postDao().getPostsForUser(username).distinctUntilChanged() }
    val historyFlow = remember(app, username) { app.database.followerHistoryDao().getHistoryForTarget(username).distinctUntilChanged() }
    val auditsFlow = remember(app, username) { app.database.followerAuditDao().getAuditsForTarget(username).distinctUntilChanged() }

    val target by targetFlow.collectAsStateWithLifecycle(initialValue = null)
    val logs by logsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val avatars by avatarsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val highlights by highlightsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val archivedStories by archivedStoriesFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val allHighlightMedia by allHighlightMediaFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val posts by postsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val history by historyFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val audits by auditsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val syncStateMap by app.profileInspector.postSyncState.collectAsStateWithLifecycle()
    val backfillProgressMap by app.profileInspector.postBackfillProgress.collectAsStateWithLifecycle()
    val downloadStates by app.mediaDownloadOrchestrator.downloadStates.collectAsStateWithLifecycle()
    val cleanUserLower = remember(username) { username.trim().trimStart('@').lowercase() }
    val targetSyncStatus = syncStateMap[cleanUserLower] ?: PostSyncStatus.IDLE
    val backfillProgress = backfillProgressMap[cleanUserLower]
    val hasSession = remember { app.sessionManager.hasSession() }

    var isRefreshing by remember { mutableStateOf(false) }
    var isFetchingPosts by remember { mutableStateOf(posts.isEmpty()) }
    var selectedTab by remember { mutableIntStateOf(0) }
    var previewImagePath by remember { mutableStateOf<String?>(null) }
    var selectedPostForViewer by remember { mutableStateOf<PostEntity?>(null) }
    var activeHighlight by remember { mutableStateOf<HighlightEntity?>(null) }
    var activeHighlightMedia by remember { mutableStateOf<List<HighlightMediaEntity>>(emptyList()) }
    var activeHighlightStartIndex by remember { mutableIntStateOf(0) }
    var isLoadingHighlightMedia by remember { mutableStateOf(false) }
    var showAuditSheet by remember { mutableStateOf(false) }
    var auditInitialTab by remember { mutableIntStateOf(0) }
    var showPoolSheet by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var avatarToDelete by remember { mutableStateOf<AvatarHistoryEntity?>(null) }
    var storyItemToDelete by remember { mutableStateOf<HighlightMediaEntity?>(null) }
    var highlightToDelete by remember { mutableStateOf<HighlightEntity?>(null) }
    var showClearStoriesDialog by remember { mutableStateOf(false) }
    var isSyncingStories by remember { mutableStateOf(false) }
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
            val syncStatus = if (result.isSuccess) {
                app.profileInspector.inspectHighlightsAndPosts(username)
            } else {
                PostSyncStatus.FAILED
            }
            isRefreshing = false
            isFetchingPosts = false
            if (result.isSuccess) {
                val feedback = when (syncStatus) {
                    PostSyncStatus.COMPLETED -> context.getString(R.string.msg_target_checked, username)
                    PostSyncStatus.RATE_LIMITED -> context.getString(R.string.msg_target_checked_rate_limited, username)
                    PostSyncStatus.REQUIRES_SESSION -> context.getString(R.string.msg_target_checked_session_needed, username)
                    else -> context.getString(R.string.msg_target_checked_media_partial, username)
                }
                snackbarHostState.showSnackbar(feedback)
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
                state = detailListState,
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
                val totalStoryArchiveCount = archivedStories.size + allHighlightMedia.size
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
                                title = stringResource(R.string.tab_stories_short, totalStoryArchiveCount),
                                icon = Icons.Default.AutoAwesome
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
                            if (totalPostsCount > posts.size || backfillProgress != null) {
                                item(key = "posts_backfill_banner") {
                                    val loadedCount = maxOf(posts.size, backfillProgress?.first ?: 0)
                                    val expectedCount = maxOf(totalPostsCount, backfillProgress?.second ?: 0)
                                    val isLoadingAll = isActivelySyncing || backfillProgress != null
                                    Surface(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 6.dp),
                                        shape = ShapeTokens.MediumShape,
                                        color = MaterialTheme.colorScheme.surfaceContainer,
                                        border = BorderStroke(0.75.dp, MaterialTheme.colorScheme.outlineVariant)
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 14.dp, vertical = 10.dp),
                                            verticalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        text = stringResource(R.string.posts_archived_progress, loadedCount, expectedCount),
                                                        style = MaterialTheme.typography.labelLarge,
                                                        fontWeight = FontWeight.SemiBold,
                                                        color = MaterialTheme.colorScheme.onSurface
                                                    )
                                                    if (isLoadingAll) {
                                                        Text(
                                                            text = stringResource(R.string.posts_syncing_all_desc),
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = AccentBlue
                                                        )
                                                    }
                                                }
                                                if (!isLoadingAll && totalPostsCount > posts.size) {
                                                    FilledTonalButton(
                                                        onClick = {
                                                            scope.launch {
                                                                isFetchingPosts = true
                                                                try {
                                                                    app.profileInspector.syncAllPostsNow(username)
                                                                } finally {
                                                                    isFetchingPosts = false
                                                                }
                                                            }
                                                        },
                                                        shape = ShapeTokens.MediumShape,
                                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Default.CloudDownload,
                                                            contentDescription = null,
                                                            modifier = Modifier.size(16.dp)
                                                        )
                                                        Spacer(modifier = Modifier.width(6.dp))
                                                        Text(
                                                            text = stringResource(R.string.btn_load_all_posts),
                                                            style = MaterialTheme.typography.labelMedium,
                                                            fontWeight = FontWeight.SemiBold
                                                        )
                                                    }
                                                }
                                            }
                                            if (isLoadingAll) {
                                                val fraction = if (expectedCount > 0) {
                                                    (loadedCount.toFloat() / expectedCount.toFloat()).coerceIn(0f, 1f)
                                                } else {
                                                    0f
                                                }
                                                if (fraction > 0f && fraction < 1f) {
                                                    LinearProgressIndicator(
                                                        progress = { fraction },
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .height(4.dp)
                                                            .clip(CircleShape),
                                                        color = AccentBlue
                                                    )
                                                } else {
                                                    LinearProgressIndicator(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .height(4.dp)
                                                            .clip(CircleShape),
                                                        color = AccentBlue
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
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
                        // Story & Highlight Archive Tab (History + Deleted Badges + Exact Storage + Delete)
                        val nowMs = System.currentTimeMillis()
                        val activeStoryCutoff = nowMs - (24L * 60L * 60L * 1000L)
                        val activeStoriesCount = archivedStories.count { !it.isVanished && it.takenAt >= activeStoryCutoff }
                        val vanishedStoriesCount = archivedStories.count { it.isVanished }

                        item(key = "story_archive_summary") {
                            val totalStoryBytes = remember(archivedStories, allHighlightMedia, highlights) {
                                var sum = 0L
                                for (it in archivedStories) {
                                    val f = File(it.localPath)
                                    sum += if (!it.localPath.startsWith("http") && f.exists()) f.length() else it.fileSize
                                }
                                for (it in allHighlightMedia) {
                                    val f = File(it.localPath)
                                    sum += if (!it.localPath.startsWith("http") && f.exists()) f.length() else it.fileSize
                                }
                                for (hl in highlights) {
                                    val cf = hl.localCoverPath?.let { File(it) }
                                    if (cf != null && cf.exists()) sum += cf.length()
                                }
                                sum
                            }
                            val formattedTotalStorage = remember(totalStoryBytes) {
                                when {
                                    totalStoryBytes >= 1024L * 1024L * 1024L -> String.format(Locale.US, "%.2f GB", totalStoryBytes / (1024.0 * 1024.0 * 1024.0))
                                    totalStoryBytes >= 1024L * 1024L -> String.format(Locale.US, "%.2f MB", totalStoryBytes / (1024.0 * 1024.0))
                                    totalStoryBytes >= 1024L -> String.format(Locale.US, "%.1f KB", totalStoryBytes / 1024.0)
                                    else -> "$totalStoryBytes B"
                                }
                            }
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 6.dp),
                                shape = ShapeTokens.LargeShape,
                                color = MaterialTheme.colorScheme.surfaceContainer,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = stringResource(R.string.story_archive_title),
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Surface(
                                            color = AccentBlue.copy(alpha = 0.12f),
                                            shape = RoundedCornerShape(8.dp)
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Storage,
                                                    contentDescription = null,
                                                    tint = AccentBlue,
                                                    modifier = Modifier.size(13.dp)
                                                )
                                                Text(
                                                    text = stringResource(R.string.story_archive_storage_used, formattedTotalStorage),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = AccentBlue
                                                )
                                            }
                                        }
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Surface(
                                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                            shape = RoundedCornerShape(8.dp)
                                        ) {
                                            Text(
                                                text = stringResource(R.string.story_archive_summary_stories, archivedStories.size),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                            )
                                        }
                                        Surface(
                                            color = Color(0xFF10B981).copy(alpha = 0.14f),
                                            shape = RoundedCornerShape(8.dp)
                                        ) {
                                            Text(
                                                text = stringResource(R.string.story_archive_summary_active, activeStoriesCount),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = Color(0xFF10B981),
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                            )
                                        }
                                        if (vanishedStoriesCount > 0) {
                                            Surface(
                                                color = Color(0xFFE11D48).copy(alpha = 0.14f),
                                                shape = RoundedCornerShape(8.dp)
                                            ) {
                                                Text(
                                                    text = stringResource(R.string.story_archive_summary_deleted, vanishedStoriesCount),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = Color(0xFFE11D48),
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                                )
                                            }
                                        }
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        OutlinedButton(
                                            onClick = {
                                                if (!isSyncingStories) {
                                                    isSyncingStories = true
                                                    scope.launch {
                                                        try {
                                                            app.profileInspector.syncStoriesAndHighlightsNow(username)
                                                        } finally {
                                                            isSyncingStories = false
                                                        }
                                                    }
                                                }
                                            },
                                            modifier = Modifier.weight(1f),
                                            shape = ShapeTokens.SmallShape,
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                                        ) {
                                            if (isSyncingStories) {
                                                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                            } else {
                                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(15.dp))
                                            }
                                            Spacer(Modifier.width(6.dp))
                                            Text(
                                                text = stringResource(R.string.story_archive_sync_now),
                                                style = MaterialTheme.typography.labelMedium,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        if (archivedStories.isNotEmpty()) {
                                            OutlinedButton(
                                                onClick = { showClearStoriesDialog = true },
                                                shape = ShapeTokens.SmallShape,
                                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.4f)),
                                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                                            ) {
                                                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(15.dp))
                                                Spacer(Modifier.width(4.dp))
                                                Text(
                                                    text = stringResource(R.string.story_archive_clear_all),
                                                    style = MaterialTheme.typography.labelMedium,
                                                    maxLines = 1
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        if (highlights.isNotEmpty()) {
                            item(key = "story_archive_hl_header") {
                                Text(
                                    text = stringResource(R.string.story_archive_highlights_section, highlights.size),
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp)
                                )
                            }
                            items(highlights, key = { "hl_archive_${it.id}" }) { hl ->
                                val hlMedia = allHighlightMedia.filter { it.highlightId == hl.id }
                                val hlBytes = remember(hlMedia, hl.localCoverPath) {
                                    var b = hl.localCoverPath?.let { File(it) }?.takeIf { it.exists() }?.length() ?: 0L
                                    for (m in hlMedia) {
                                        val f = File(m.localPath)
                                        b += if (!m.localPath.startsWith("http") && f.exists()) f.length() else m.fileSize
                                    }
                                    b
                                }
                                val hlSizeLabel = remember(hlBytes) {
                                    when {
                                        hlBytes >= 1024L * 1024L -> String.format(Locale.US, "%.2f MB", hlBytes / (1024.0 * 1024.0))
                                        hlBytes >= 1024L -> String.format(Locale.US, "%.1f KB", hlBytes / 1024.0)
                                        else -> "$hlBytes B"
                                    }
                                }
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 4.dp)
                                        .bouncyClickable(pressScale = 0.98f) {
                                            activeHighlight = hl
                                            activeHighlightStartIndex = 0
                                            activeHighlightMedia = hlMedia
                                            isLoadingHighlightMedia = hlMedia.isEmpty()
                                            scope.launch {
                                                activeHighlightMedia = app.profileInspector.fetchHighlightMediaOnDemand(hl)
                                                isLoadingHighlightMedia = false
                                            }
                                        },
                                    shape = ShapeTokens.MediumShape,
                                    color = MaterialTheme.colorScheme.surfaceContainer,
                                    border = BorderStroke(
                                        1.dp,
                                        if (hl.isVanished) Color(0xFFE11D48).copy(alpha = 0.45f)
                                        else MaterialTheme.colorScheme.outlineVariant
                                    )
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        val coverModel: Any? = hl.localCoverPath?.let { File(it) }?.takeIf { it.exists() && it.length() > 0L }
                                            ?: hl.coverUrl.takeIf { it.isNotBlank() }
                                        Box(
                                            modifier = Modifier
                                                .size(48.dp)
                                                .clip(CircleShape)
                                                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            if (coverModel != null) {
                                                AsyncImage(
                                                    model = coverModel,
                                                    contentDescription = hl.title,
                                                    contentScale = ContentScale.Crop,
                                                    modifier = Modifier.fillMaxSize()
                                                )
                                            } else {
                                                Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = AccentBlue)
                                            }
                                        }
                                        Column(modifier = Modifier.weight(1f)) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                Text(
                                                    text = hl.title,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = MaterialTheme.colorScheme.onSurface,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.weight(1f, fill = false)
                                                )
                                                if (hl.isVanished) {
                                                    Surface(
                                                        color = Color(0xFFE11D48).copy(alpha = 0.14f),
                                                        shape = RoundedCornerShape(6.dp)
                                                    ) {
                                                        Text(
                                                            text = stringResource(R.string.story_status_deleted),
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = Color(0xFFE11D48),
                                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                                                        )
                                                    }
                                                }
                                            }
                                            Text(
                                                text = "${maxOf(hl.mediaCount, hlMedia.size)} · $hlSizeLabel",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        IconButton(onClick = { highlightToDelete = hl }) {
                                            Icon(
                                                imageVector = Icons.Default.Delete,
                                                contentDescription = stringResource(R.string.action_delete),
                                                tint = MaterialTheme.colorScheme.error,
                                                modifier = Modifier.size(19.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        if (archivedStories.isNotEmpty()) {
                            item(key = "story_archive_history_header") {
                                Text(
                                    text = stringResource(R.string.story_archive_history_section, archivedStories.size),
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp)
                                )
                            }
                            items(archivedStories.size, key = { "story_hist_${archivedStories[it].id}" }) { idx ->
                                val story = archivedStories[idx]
                                val isVideo = story.mediaType == "video" || story.localPath.contains(".mp4", ignoreCase = true)
                                val isActiveNow = !story.isVanished && story.takenAt >= activeStoryCutoff
                                val itemBytes = remember(story.localPath, story.fileSize) {
                                    val f = File(story.localPath)
                                    if (!story.localPath.startsWith("http") && f.exists()) f.length() else story.fileSize
                                }
                                val sizeStr = remember(itemBytes) {
                                    when {
                                        itemBytes >= 1024L * 1024L -> String.format(Locale.US, "%.2f MB", itemBytes / (1024.0 * 1024.0))
                                        itemBytes >= 1024L -> String.format(Locale.US, "%.1f KB", itemBytes / 1024.0)
                                        else -> "$itemBytes B"
                                    }
                                }
                                val dateFmt = remember { java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()) }
                                val thumbFile = remember(story.localPath) { File(story.localPath) }
                                val thumbModel: Any = if (!story.localPath.startsWith("http") && thumbFile.exists() && thumbFile.length() > 0L) thumbFile else story.mediaUrl

                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 4.dp)
                                        .bouncyClickable(pressScale = 0.98f) {
                                            val curTarget = target
                                            activeHighlight = HighlightEntity(
                                                id = "active_story:$cleanUserLower",
                                                username = cleanUserLower,
                                                title = context.getString(R.string.story_archive_title),
                                                coverUrl = curTarget?.avatarUrl.orEmpty(),
                                                localCoverPath = curTarget?.localAvatarPath,
                                                mediaCount = archivedStories.size,
                                                archivedAt = story.takenAt
                                            )
                                            activeHighlightMedia = archivedStories
                                            activeHighlightStartIndex = idx
                                            isLoadingHighlightMedia = false
                                        },
                                    shape = ShapeTokens.MediumShape,
                                    color = MaterialTheme.colorScheme.surfaceContainer,
                                    border = BorderStroke(
                                        1.dp,
                                        if (story.isVanished) Color(0xFFE11D48).copy(alpha = 0.45f)
                                        else MaterialTheme.colorScheme.outlineVariant
                                    )
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(54.dp)
                                                .clip(RoundedCornerShape(12.dp))
                                                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            if (!isVideo) {
                                                AsyncImage(
                                                    model = thumbModel,
                                                    contentDescription = null,
                                                    contentScale = ContentScale.Crop,
                                                    modifier = Modifier.fillMaxSize()
                                                )
                                            } else {
                                                Icon(
                                                    imageVector = Icons.Default.PlayArrow,
                                                    contentDescription = null,
                                                    tint = AccentBlue,
                                                    modifier = Modifier.size(28.dp)
                                                )
                                            }
                                        }
                                        Column(
                                            modifier = Modifier.weight(1f),
                                            verticalArrangement = Arrangement.spacedBy(3.dp)
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                val (statusText, statusColor) = when {
                                                    story.isVanished -> stringResource(R.string.story_status_deleted) to Color(0xFFE11D48)
                                                    isActiveNow -> stringResource(R.string.story_status_active) to Color(0xFF10B981)
                                                    else -> stringResource(R.string.story_status_archived) to MaterialTheme.colorScheme.onSurfaceVariant
                                                }
                                                Surface(
                                                    color = statusColor.copy(alpha = 0.14f),
                                                    shape = RoundedCornerShape(6.dp)
                                                ) {
                                                    Text(
                                                        text = statusText,
                                                        style = MaterialTheme.typography.labelSmall,
                                                        fontWeight = FontWeight.SemiBold,
                                                        color = statusColor,
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                    )
                                                }
                                                Text(
                                                    text = if (isVideo) "MP4" else "JPG",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            Text(
                                                text = dateFmt.format(java.util.Date(story.takenAt)),
                                                style = MaterialTheme.typography.bodySmall,
                                                fontWeight = FontWeight.Medium,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                            Text(
                                                text = sizeStr,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        IconButton(onClick = { storyItemToDelete = story }) {
                                            Icon(
                                                imageVector = Icons.Default.Delete,
                                                contentDescription = stringResource(R.string.action_delete),
                                                tint = MaterialTheme.colorScheme.error,
                                                modifier = Modifier.size(19.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        } else if (highlights.isEmpty()) {
                            item(key = "empty_story_archive") {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(32.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = stringResource(R.string.story_archive_empty),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }
                    }
                    3 -> {
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

    previewImagePath?.let { path ->
        ImagePreviewDialog(
            imagePath = path,
            onDismiss = { previewImagePath = null }
        )
    }

    // Instagram Fullscreen Story Viewer (with on-demand fetch + Vault video decryption + delete)
    activeHighlight?.let { hl ->
        InstagramStoryViewer(
            highlight = hl,
            mediaList = activeHighlightMedia,
            initialIndex = activeHighlightStartIndex,
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
            onDeleteItem = { itemToDelete ->
                storyItemToDelete = itemToDelete
            },
            onDismiss = {
                activeHighlight = null
                activeHighlightStartIndex = 0
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

    storyItemToDelete?.let { item ->
        val itemSizeStr = remember(item.localPath, item.fileSize) {
            val f = File(item.localPath)
            val b = if (!item.localPath.startsWith("http") && f.exists()) f.length() else item.fileSize
            when {
                b >= 1024L * 1024L -> String.format(Locale.US, "%.2f MB", b / (1024.0 * 1024.0))
                b >= 1024L -> String.format(Locale.US, "%.1f KB", b / 1024.0)
                else -> "$b B"
            }
        }
        AlertDialog(
            onDismissRequest = { storyItemToDelete = null },
            title = { Text(stringResource(R.string.story_archive_delete_item_title), fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(R.string.story_archive_delete_item_desc, itemSizeStr)) },
            confirmButton = {
                Button(
                    onClick = {
                        val toDel = item
                        storyItemToDelete = null
                        scope.launch {
                            app.profileInspector.deleteArchivedStoryOrHighlightItem(toDel)
                            activeHighlightMedia = activeHighlightMedia.filterNot { it.id == toDel.id }
                            if (activeHighlightMedia.isEmpty()) {
                                activeHighlight = null
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    shape = ShapeTokens.SmallShape
                ) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { storyItemToDelete = null }) {
                    Text(stringResource(R.string.dialog_cancel))
                }
            }
        )
    }

    highlightToDelete?.let { hl ->
        val hlMedia = allHighlightMedia.filter { it.highlightId == hl.id }
        val hlSizeStr = remember(hlMedia, hl.localCoverPath) {
            var b = hl.localCoverPath?.let { File(it) }?.takeIf { it.exists() }?.length() ?: 0L
            for (m in hlMedia) {
                val f = File(m.localPath)
                b += if (!m.localPath.startsWith("http") && f.exists()) f.length() else m.fileSize
            }
            when {
                b >= 1024L * 1024L -> String.format(Locale.US, "%.2f MB", b / (1024.0 * 1024.0))
                b >= 1024L -> String.format(Locale.US, "%.1f KB", b / 1024.0)
                else -> "$b B"
            }
        }
        AlertDialog(
            onDismissRequest = { highlightToDelete = null },
            title = { Text(stringResource(R.string.story_archive_delete_hl_title), fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(R.string.story_archive_delete_hl_desc, hl.title, hlSizeStr)) },
            confirmButton = {
                Button(
                    onClick = {
                        val toDel = hl
                        highlightToDelete = null
                        scope.launch {
                            app.profileInspector.deleteArchivedHighlightTray(toDel)
                            if (activeHighlight?.id == toDel.id) {
                                activeHighlight = null
                                activeHighlightMedia = emptyList()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    shape = ShapeTokens.SmallShape
                ) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { highlightToDelete = null }) {
                    Text(stringResource(R.string.dialog_cancel))
                }
            }
        )
    }

    if (showClearStoriesDialog) {
        val storiesBytesStr = remember(archivedStories) {
            var b = 0L
            for (m in archivedStories) {
                val f = File(m.localPath)
                b += if (!m.localPath.startsWith("http") && f.exists()) f.length() else m.fileSize
            }
            when {
                b >= 1024L * 1024L -> String.format(Locale.US, "%.2f MB", b / (1024.0 * 1024.0))
                b >= 1024L -> String.format(Locale.US, "%.1f KB", b / 1024.0)
                else -> "$b B"
            }
        }
        AlertDialog(
            onDismissRequest = { showClearStoriesDialog = false },
            title = { Text(stringResource(R.string.story_archive_clear_all), fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(R.string.story_archive_clear_all_desc, storiesBytesStr)) },
            confirmButton = {
                Button(
                    onClick = {
                        showClearStoriesDialog = false
                        scope.launch {
                            app.profileInspector.deleteAllArchivedStoriesForUser(username)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    shape = ShapeTokens.SmallShape
                ) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearStoriesDialog = false }) {
                    Text(stringResource(R.string.dialog_cancel))
                }
            }
        )
    }

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
                            model = ImageRequest.Builder(context)
                                .data(topImageSource)
                                .memoryCacheKey("hero_avatar_${target.username.lowercase()}_${target.avatarHash ?: target.lastCheckedAt ?: 0L}")
                                .crossfade(true)
                                .build(),
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
    val context = LocalContext.current
    val currentLang by com.instalogger.app.util.LanguageManager.currentLanguage.collectAsState()
    val (localizedTitle, localizedOld, localizedNew) = remember(log.id, log.title, log.oldValue, log.newValue, log.eventType, currentLang) {
        com.instalogger.app.util.LanguageManager.localizeChangeLog(context, log)
    }
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
                    text = localizedTitle,
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
            if (!localizedOld.isNullOrBlank() || !localizedNew.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "${localizedOld?.cleanDisplayText() ?: "-"}  ->  ${localizedNew?.cleanDisplayText() ?: "-"}",
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
