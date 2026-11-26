package com.instalogger.app.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.instalogger.app.InstaLoggerApp
import com.instalogger.app.R
import com.instalogger.app.data.local.PostCommentEntity
import com.instalogger.app.data.local.PostEntity
import com.instalogger.app.engine.comments.CommentExportFormat
import com.instalogger.app.engine.comments.CommentExporter
import com.instalogger.app.engine.comments.CommentFilterMode
import com.instalogger.app.engine.comments.CommentQueryOptions
import com.instalogger.app.engine.comments.CommentSortOrder
import com.instalogger.app.engine.comments.CommentThreadNode
import com.instalogger.app.engine.comments.CommentTreeEngine
import com.instalogger.app.ui.theme.AccentBlue
import com.instalogger.app.ui.theme.ShapeTokens
import com.instalogger.app.ui.theme.StatusAmber
import com.instalogger.app.ui.theme.StatusGreen
import com.instalogger.app.ui.theme.StatusRed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PostCommentsBottomSheet(
    post: PostEntity,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as InstaLoggerApp
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val allComments by app.database.postCommentDao()
        .getCommentsForPostFlow(post.id)
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val syncProgressMap by app.profileInspector.commentSyncProgress.collectAsStateWithLifecycle()
    val activeSyncCount = syncProgressMap[post.id]
    val isSyncing = activeSyncCount != null

    var searchQuery by remember { mutableStateOf("") }
    var sortOrder by remember { mutableStateOf(CommentSortOrder.MOST_LIKED) }
    var filterMode by remember { mutableStateOf(CommentFilterMode.ALL) }
    var minLikes by remember { mutableIntStateOf(0) }
    var authorFilter by remember { mutableStateOf<String?>(null) }
    var expandAllThreads by remember { mutableStateOf(false) }
    var expandedMap by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    var showExportDialog by remember { mutableStateOf(false) }
    var lastSyncNotice by remember { mutableStateOf<String?>(null) }
    var autoTriggered by remember(post.id) { mutableStateOf(false) }

    val stats = remember(allComments, post.commentCount) {
        CommentTreeEngine.computeStats(allComments, post.commentCount)
    }

    val queryOptions = remember(searchQuery, sortOrder, filterMode, minLikes, authorFilter) {
        CommentQueryOptions(
            searchQuery = searchQuery,
            sortOrder = sortOrder,
            filterMode = filterMode,
            minLikes = minLikes,
            authorFilter = authorFilter
        )
    }

    val fullTree = remember(allComments) {
        CommentTreeEngine.buildCommentTree(allComments)
    }

    val displayedNodes = remember(allComments, queryOptions) {
        CommentTreeEngine.queryTree(allComments, queryOptions)
    }

    val filteredCount = remember(displayedNodes) {
        displayedNodes.sumOf { 1 + it.replies.size }
    }

    val hasActiveFilter = searchQuery.isNotBlank() ||
        filterMode != CommentFilterMode.ALL ||
        minLikes > 0 ||
        !authorFilter.isNullOrBlank()

    fun triggerSync() {
        if (isSyncing) return
        lastSyncNotice = null
        scope.launch {
            val res = app.profileInspector.syncPostComments(post, maxComments = 600)
            lastSyncNotice = when {
                res.rateLimited -> context.getString(R.string.comments_notice_rate_limited, res.comments.size)
                res.comments.isEmpty() && !res.errorMessage.isNullOrBlank() -> res.errorMessage
                res.comments.isEmpty() -> context.getString(R.string.comments_notice_none_found)
                else -> null
            }
        }
    }

    LaunchedEffect(post.id) {
        if (!autoTriggered) {
            autoTriggered = true
            val existingCount = withContext(Dispatchers.IO) {
                app.database.postCommentDao().getCommentsCountForPost(post.id)
            }
            if (existingCount == 0) {
                triggerSync()
            }
        }
    }

    val maxSheetHeight = (LocalConfiguration.current.screenHeightDp * 0.86f).dp

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        modifier = Modifier
            .statusBarsPadding()
            .displayCutoutPadding()
            .padding(top = 12.dp),
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(maxSheetHeight)
                .navigationBarsPadding()
                .imePadding()
        ) {
            // 1. Header with fixed-width action buttons (never shifts layout)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.comments_sheet_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "@${post.username} · ${post.id}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Refresh / Sync button
                    Surface(
                        shape = CircleShape,
                        color = AccentBlue.copy(alpha = 0.12f),
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .clickable(enabled = !isSyncing) { triggerSync() }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            RotatingRefreshIcon(
                                isRefreshing = isSyncing,
                                tint = AccentBlue,
                                iconSize = 20.dp
                            )
                        }
                    }

                    // Export button
                    Surface(
                        shape = CircleShape,
                        color = if (allComments.isNotEmpty()) StatusGreen.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .clickable(enabled = allComments.isNotEmpty()) { showExportDialog = true }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.FileDownload,
                                contentDescription = stringResource(R.string.comments_action_export),
                                tint = if (allComments.isNotEmpty()) StatusGreen else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    // Close button
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .clickable(onClick = onDismiss)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = stringResource(R.string.dialog_close),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }

            // 2. Stats & Collection Completeness Strip
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CommentStatPill(
                    label = stringResource(R.string.comments_stat_total, stats.totalCount),
                    color = AccentBlue
                )
                CommentStatPill(
                    label = stringResource(R.string.comments_stat_top_level, stats.topLevelCount),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                CommentStatPill(
                    label = stringResource(R.string.comments_stat_replies, stats.replyCount),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                CommentStatPill(
                    label = stringResource(R.string.comments_stat_authors, stats.uniqueAuthorsCount),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (stats.collectionStatus != "empty") {
                    val isComplete = stats.collectionStatus == "complete"
                    CommentStatPill(
                        label = stringResource(
                            if (isComplete) R.string.comments_status_complete else R.string.comments_status_partial
                        ),
                        color = if (isComplete) StatusGreen else StatusAmber
                    )
                }
            }

            // Live Syncing Banner
            AnimatedVisibility(visible = isSyncing) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(AccentBlue.copy(alpha = 0.12f))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = AccentBlue
                    )
                    Text(
                        text = stringResource(R.string.comments_syncing_progress, activeSyncCount ?: 0),
                        style = MaterialTheme.typography.labelMedium,
                        color = AccentBlue,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            // Optional Status/Warning Banner
            if (!lastSyncNotice.isNullOrBlank() && !isSyncing) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(StatusAmber.copy(alpha = 0.12f))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.Info, contentDescription = null, tint = StatusAmber, modifier = Modifier.size(16.dp))
                    Text(
                        text = lastSyncNotice!!,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // 3. Search Bar + Active Author Filter Pill
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(modifier = Modifier.weight(1f)) {
                        if (searchQuery.isEmpty()) {
                            Text(
                                text = stringResource(R.string.comments_search_placeholder),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        BasicTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            singleLine = true,
                            textStyle = TextStyle(
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 13.sp
                            ),
                            cursorBrush = SolidColor(AccentBlue),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    if (searchQuery.isNotEmpty()) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Clear",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .size(18.dp)
                                .clickable { searchQuery = "" }
                        )
                    }
                }

                if (!authorFilter.isNullOrBlank()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        InputChip(
                            selected = true,
                            onClick = { authorFilter = null },
                            label = {
                                Text(
                                    text = "@$authorFilter",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                            },
                            trailingIcon = {
                                Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(14.dp))
                            }
                        )
                    }
                }
            }

            // 4. Sort & Filter Chips Strip
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SortChipItem(
                    label = stringResource(R.string.comments_sort_most_liked),
                    selected = sortOrder == CommentSortOrder.MOST_LIKED,
                    onClick = { sortOrder = CommentSortOrder.MOST_LIKED }
                )
                SortChipItem(
                    label = stringResource(R.string.comments_sort_newest),
                    selected = sortOrder == CommentSortOrder.NEWEST,
                    onClick = { sortOrder = CommentSortOrder.NEWEST }
                )
                SortChipItem(
                    label = stringResource(R.string.comments_sort_oldest),
                    selected = sortOrder == CommentSortOrder.OLDEST,
                    onClick = { sortOrder = CommentSortOrder.OLDEST }
                )
                SortChipItem(
                    label = stringResource(R.string.comments_sort_most_replies),
                    selected = sortOrder == CommentSortOrder.MOST_REPLIES,
                    onClick = { sortOrder = CommentSortOrder.MOST_REPLIES }
                )
                SortChipItem(
                    label = stringResource(R.string.comments_sort_author_az),
                    selected = sortOrder == CommentSortOrder.AUTHOR_AZ,
                    onClick = { sortOrder = CommentSortOrder.AUTHOR_AZ }
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterChipItem(
                    label = stringResource(R.string.comments_filter_all),
                    selected = filterMode == CommentFilterMode.ALL && minLikes == 0,
                    onClick = {
                        filterMode = CommentFilterMode.ALL
                        minLikes = 0
                    }
                )
                FilterChipItem(
                    label = stringResource(R.string.comments_filter_with_replies),
                    selected = filterMode == CommentFilterMode.WITH_REPLIES,
                    onClick = {
                        filterMode = if (filterMode == CommentFilterMode.WITH_REPLIES) CommentFilterMode.ALL else CommentFilterMode.WITH_REPLIES
                    }
                )
                FilterChipItem(
                    label = stringResource(R.string.comments_filter_top_only),
                    selected = filterMode == CommentFilterMode.TOP_LEVEL_ONLY,
                    onClick = {
                        filterMode = if (filterMode == CommentFilterMode.TOP_LEVEL_ONLY) CommentFilterMode.ALL else CommentFilterMode.TOP_LEVEL_ONLY
                    }
                )
                FilterChipItem(
                    label = stringResource(R.string.comments_filter_min_likes, 10),
                    selected = minLikes >= 10,
                    onClick = { minLikes = if (minLikes >= 10) 0 else 10 }
                )
                FilterChipItem(
                    label = stringResource(R.string.comments_filter_verified),
                    selected = filterMode == CommentFilterMode.VERIFIED_ONLY,
                    onClick = {
                        filterMode = if (filterMode == CommentFilterMode.VERIFIED_ONLY) CommentFilterMode.ALL else CommentFilterMode.VERIFIED_ONLY
                    }
                )

                // Expand / Collapse all replies tree toggle
                if (stats.replyCount > 0) {
                    FilterChip(
                        selected = expandAllThreads,
                        onClick = {
                            expandAllThreads = !expandAllThreads
                            expandedMap = emptyMap()
                        },
                        label = {
                            Text(
                                text = stringResource(
                                    if (expandAllThreads) R.string.comments_collapse_all else R.string.comments_expand_all
                                ),
                                style = MaterialTheme.typography.labelSmall
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = if (expandAllThreads) Icons.Default.UnfoldLess else Icons.Default.UnfoldMore,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    )
                }
            }

            if (hasActiveFilter && allComments.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.comments_filtered_summary, filteredCount, stats.totalCount),
                    style = MaterialTheme.typography.labelSmall,
                    color = AccentBlue,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 2.dp)
                )
            }

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f),
                modifier = Modifier.padding(top = 4.dp)
            )

            // 5. Tree-Structured Comment Thread List
            if (displayedNodes.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.padding(32.dp)
                    ) {
                        Icon(
                            imageVector = if (hasActiveFilter) Icons.Default.SearchOff else Icons.Default.Forum,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                            modifier = Modifier.size(44.dp)
                        )
                        Text(
                            text = stringResource(
                                if (hasActiveFilter) R.string.comments_empty_search_title
                                else R.string.comments_empty_title
                            ),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = stringResource(
                                if (hasActiveFilter) R.string.comments_empty_search_desc
                                else R.string.comments_empty_desc
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (!hasActiveFilter && !isSyncing) {
                            Button(
                                onClick = { triggerSync() },
                                shape = ShapeTokens.PillShape
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(text = stringResource(R.string.comments_action_sync_now))
                            }
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(displayedNodes, key = { it.comment.id }) { node ->
                        val isExpanded = expandedMap[node.comment.id]
                            ?: (expandAllThreads || node.matchedReplyCount > 0)
                        CommentThreadCard(
                            node = node,
                            searchQuery = searchQuery,
                            isExpanded = isExpanded,
                            onToggleExpand = {
                                expandedMap = expandedMap + (node.comment.id to !isExpanded)
                            },
                            onAuthorClick = { uname ->
                                authorFilter = if (authorFilter.equals(uname, ignoreCase = true)) null else uname
                            },
                            onCopyText = { c ->
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("Comment", "@${c.authorUsername}: ${c.text}"))
                                Toast.makeText(context, context.getString(R.string.comments_copied_toast), Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                }
            }
        }
    }

    if (showExportDialog) {
        CommentExportDialog(
            totalCount = stats.totalCount,
            filteredCount = filteredCount,
            hasActiveFilter = hasActiveFilter,
            onDismiss = { showExportDialog = false },
            onExportFile = { format, exportFilteredOnly ->
                showExportDialog = false
                scope.launch {
                    try {
                        val targetNodes = if (exportFilteredOnly && hasActiveFilter) displayedNodes else fullTree
                        val targetFlat = targetNodes.flatMap { listOf(it.comment) + it.replies }
                        val exportStats = CommentTreeEngine.computeStats(targetFlat, if (exportFilteredOnly) 0 else post.commentCount)
                        val res = withContext(Dispatchers.IO) {
                            CommentExporter.exportToShareableFile(
                                context = context,
                                shortcode = post.id,
                                mediaId = allComments.firstOrNull()?.mediaId,
                                targetUsername = post.username,
                                nodes = targetNodes,
                                stats = exportStats,
                                format = format
                            )
                        }
                        Toast.makeText(
                            context,
                            context.getString(R.string.comments_export_saved_toast, res.exportedCount, format.extension.uppercase(Locale.US)),
                            Toast.LENGTH_SHORT
                        ).show()
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = res.mimeType
                            putExtra(Intent.EXTRA_STREAM, res.shareUri)
                            putExtra(Intent.EXTRA_SUBJECT, "@${post.username} (${post.id}) comments.${format.extension}")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(shareIntent, context.getString(R.string.comments_action_export)))
                    } catch (e: Exception) {
                        Toast.makeText(context, "Export error: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onCopyClipboard = { format, exportFilteredOnly ->
                showExportDialog = false
                scope.launch {
                    val targetNodes = if (exportFilteredOnly && hasActiveFilter) displayedNodes else fullTree
                    val targetFlat = targetNodes.flatMap { listOf(it.comment) + it.replies }
                    val exportStats = CommentTreeEngine.computeStats(targetFlat, if (exportFilteredOnly) 0 else post.commentCount)
                    val rendered = withContext(Dispatchers.Default) {
                        CommentExporter.render(
                            shortcode = post.id,
                            mediaId = allComments.firstOrNull()?.mediaId,
                            targetUsername = post.username,
                            nodes = targetNodes,
                            stats = exportStats,
                            format = format
                        )
                    }
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Instagram Comments", rendered))
                    Toast.makeText(context, context.getString(R.string.comments_export_copied_toast), Toast.LENGTH_SHORT).show()
                }
            }
        )
    }
}

@Composable
private fun CommentThreadCard(
    node: CommentThreadNode,
    searchQuery: String,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onAuthorClick: (String) -> Unit,
    onCopyText: (PostCommentEntity) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 1.dp,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f), RoundedCornerShape(14.dp))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            CommentRowContent(
                comment = node.comment,
                searchQuery = searchQuery,
                isReply = false,
                onAuthorClick = onAuthorClick,
                onCopyText = onCopyText
            )

            // Expand/Collapse Replies Tree Control
            if (node.replies.isNotEmpty() || node.totalReplyCount > 0) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .clip(ShapeTokens.PillShape)
                        .background(AccentBlue.copy(alpha = 0.10f))
                        .clickable(enabled = node.replies.isNotEmpty(), onClick = onToggleExpand)
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Reply,
                        contentDescription = null,
                        tint = AccentBlue,
                        modifier = Modifier.size(14.dp)
                    )
                    val label = when {
                        node.replies.isEmpty() -> stringResource(R.string.comments_replies_unloaded, node.totalReplyCount)
                        isExpanded -> stringResource(R.string.comments_hide_replies, node.replies.size)
                        else -> stringResource(R.string.comments_show_replies, node.replies.size)
                    }
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        color = AccentBlue,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (node.replies.isNotEmpty()) {
                        Icon(
                            imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null,
                            tint = AccentBlue,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }
            }

            // Nested Tree Replies with Left Vertical Thread Guide Line (drawBehind avoids IntrinsicSize Subcompose issues)
            AnimatedVisibility(
                visible = isExpanded && node.replies.isNotEmpty(),
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                val guideColor = AccentBlue.copy(alpha = 0.38f)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp, start = 8.dp)
                        .drawBehind {
                            val strokePx = 2.dp.toPx()
                            drawLine(
                                color = guideColor,
                                start = Offset(strokePx / 2f, 2.dp.toPx()),
                                end = Offset(strokePx / 2f, size.height - 2.dp.toPx()),
                                strokeWidth = strokePx,
                                cap = StrokeCap.Round
                            )
                        }
                        .padding(start = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    node.replies.forEach { reply ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.55f))
                                .padding(10.dp)
                        ) {
                            CommentRowContent(
                                comment = reply,
                                searchQuery = searchQuery,
                                isReply = true,
                                onAuthorClick = onAuthorClick,
                                onCopyText = onCopyText
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CommentRowContent(
    comment: PostCommentEntity,
    searchQuery: String,
    isReply: Boolean,
    onAuthorClick: (String) -> Unit,
    onCopyText: (PostCommentEntity) -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()) }
    val timeText = remember(comment.createdAt) { dateFormat.format(Date(comment.createdAt)) }
    val avatarSize = if (isReply) 26.dp else 32.dp

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Avatar or Initials Monogram
        Box(
            modifier = Modifier
                .size(avatarSize)
                .clip(CircleShape)
                .background(AccentBlue.copy(alpha = 0.16f))
                .clickable { onAuthorClick(comment.authorUsername) },
            contentAlignment = Alignment.Center
        ) {
            if (!comment.authorAvatarUrl.isNullOrBlank()) {
                AsyncImage(
                    model = comment.authorAvatarUrl,
                    contentDescription = comment.authorUsername,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Text(
                    text = comment.authorUsername.take(1).uppercase(Locale.US),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = AccentBlue
                )
            }
        }

        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { onAuthorClick(comment.authorUsername) }
                        .padding(end = 4.dp)
                ) {
                    Text(
                        text = "@${comment.authorUsername}",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (comment.isAuthorVerified) {
                        Icon(
                            imageVector = Icons.Default.Verified,
                            contentDescription = "Verified",
                            tint = AccentBlue,
                            modifier = Modifier.size(13.dp)
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (comment.likeCount > 0) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Favorite,
                                contentDescription = null,
                                tint = StatusRed.copy(alpha = 0.85f),
                                modifier = Modifier.size(12.dp)
                            )
                            Text(
                                text = "${comment.likeCount}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "Copy",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                        modifier = Modifier
                            .size(14.dp)
                            .clickable { onCopyText(comment) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(3.dp))

            val highlightedText = remember(comment.text, searchQuery) {
                buildHighlightedCommentText(comment.text, searchQuery, AccentBlue)
            }
            Text(
                text = highlightedText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                lineHeight = 18.sp
            )

            Spacer(modifier = Modifier.height(4.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = timeText,
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                )
                if (comment.hasGifOrMedia) {
                    Text(
                        text = "GIF",
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = AccentBlue,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(AccentBlue.copy(alpha = 0.14f))
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    )
                }
            }
        }
    }
}

private fun buildHighlightedCommentText(text: String, query: String, highlightColor: Color) = buildAnnotatedString {
    val q = query.trim()
    if (q.isBlank()) {
        append(text)
        return@buildAnnotatedString
    }
    val lowerText = text.lowercase(Locale.ROOT)
    val lowerQuery = q.lowercase(Locale.ROOT)
    var start = 0
    while (start < text.length) {
        val idx = lowerText.indexOf(lowerQuery, startIndex = start)
        if (idx < 0) {
            append(text.substring(start))
            break
        }
        if (idx > start) {
            append(text.substring(start, idx))
        }
        withStyle(
            SpanStyle(
                background = highlightColor.copy(alpha = 0.25f),
                color = highlightColor,
                fontWeight = FontWeight.Bold
            )
        ) {
            append(text.substring(idx, idx + q.length))
        }
        start = idx + q.length
    }
}

@Composable
private fun CommentStatPill(label: String, color: Color) {
    Surface(
        shape = ShapeTokens.PillShape,
        color = color.copy(alpha = 0.11f)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun SortChipItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
            )
        },
        leadingIcon = if (selected) {
            { Icon(Icons.Default.SwapVert, contentDescription = null, modifier = Modifier.size(14.dp)) }
        } else null
    )
}

@Composable
private fun FilterChipItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
            )
        }
    )
}

@Composable
private fun CommentExportDialog(
    totalCount: Int,
    filteredCount: Int,
    hasActiveFilter: Boolean,
    onDismiss: () -> Unit,
    onExportFile: (CommentExportFormat, Boolean) -> Unit,
    onCopyClipboard: (CommentExportFormat, Boolean) -> Unit
) {
    var selectedFormat by remember { mutableStateOf(CommentExportFormat.JSON) }
    var exportFilteredOnly by remember { mutableStateOf(hasActiveFilter) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.comments_export_dialog_title),
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (hasActiveFilter) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = !exportFilteredOnly,
                            onClick = { exportFilteredOnly = false },
                            label = { Text(stringResource(R.string.comments_export_scope_all, totalCount), fontSize = 11.sp) }
                        )
                        FilterChip(
                            selected = exportFilteredOnly,
                            onClick = { exportFilteredOnly = true },
                            label = { Text(stringResource(R.string.comments_export_scope_filtered, filteredCount), fontSize = 11.sp) }
                        )
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    CommentExportFormat.entries.forEach { fmt ->
                        val isSelected = selectedFormat == fmt
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (isSelected) AccentBlue.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .border(
                                    1.dp,
                                    if (isSelected) AccentBlue else Color.Transparent,
                                    RoundedCornerShape(10.dp)
                                )
                                .clickable { selectedFormat = fmt }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = fmt.label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSelected) AccentBlue else MaterialTheme.colorScheme.onSurface
                                )
                                if (isSelected) {
                                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = AccentBlue, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onExportFile(selectedFormat, exportFilteredOnly) }) {
                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.comments_export_save_share))
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { onCopyClipboard(selectedFormat, exportFilteredOnly) }) {
                    Text(stringResource(R.string.comments_export_copy))
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.dialog_cancel))
                }
            }
        }
    )
}
