package com.instalogger.app.ui.components

import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.instalogger.app.InstaLoggerApp
import com.instalogger.app.R
import com.instalogger.app.engine.media.DownloadQueueItem
import com.instalogger.app.engine.media.DownloadTelemetryFormatter
import com.instalogger.app.engine.media.PostDownloadState
import com.instalogger.app.ui.animation.bouncyClickable
import com.instalogger.app.ui.theme.AccentBlue
import com.instalogger.app.ui.theme.ShapeTokens
import com.instalogger.app.ui.theme.StatusAmber
import com.instalogger.app.ui.theme.StatusGreen
import com.instalogger.app.ui.theme.StatusRed
import java.io.File
import kotlin.math.roundToInt

/**
 * Main-menu header button showing live download queue state with:
 * - Animated circular progress ring around the button when downloads are active
 * - Subtle bobbing download arrow during active transfers
 * - Spring-animated count badge for active/queued or paused items
 * - Fixed 44dp accessibility slot so neighboring top-bar buttons never shift
 */
@Composable
fun DownloadQueueHeaderButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val app = context.applicationContext as InstaLoggerApp
    val queueItems by app.mediaDownloadOrchestrator.queueItems.collectAsStateWithLifecycle()
    val isQueuePaused by app.mediaDownloadOrchestrator.isQueuePaused.collectAsStateWithLifecycle()

    val activeItems = remember(queueItems) { queueItems.filter { it.state.isActive } }
    val pausedItems = remember(queueItems) { queueItems.filter { it.state.isPaused } }
    val completedCount = remember(queueItems) { queueItems.count { it.state is PostDownloadState.Completed } }

    val badgeCount = if (activeItems.isNotEmpty()) activeItems.size else pausedItems.size
    val isDownloadingNow = activeItems.isNotEmpty()
    val isPausedState = !isDownloadingNow && (pausedItems.isNotEmpty() || isQueuePaused)

    val aggregateProgress = remember(activeItems) {
        if (activeItems.isEmpty()) 0f
        else (activeItems.sumOf { it.progress.toDouble() } / (activeItems.size * 100.0)).toFloat().coerceIn(0f, 1f)
    }
    val animatedRingProgress by animateFloatAsState(
        targetValue = if (isDownloadingNow) aggregateProgress.coerceAtLeast(0.08f) else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "dl_header_ring"
    )

    val infinite = rememberInfiniteTransition(label = "dl_header_motion")
    val arrowOffsetY by infinite.animateFloat(
        initialValue = -1.5f,
        targetValue = 2.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 750, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dl_arrow_bob"
    )
    val indeterminateSweepAngle by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "dl_ring_spin"
    )

    val ringColor by animateColorAsState(
        targetValue = when {
            isDownloadingNow -> AccentBlue
            isPausedState -> StatusAmber
            completedCount > 0 -> StatusGreen
            else -> MaterialTheme.colorScheme.outlineVariant
        },
        animationSpec = tween(250),
        label = "dl_ring_color"
    )

    val shape = ShapeTokens.SmallShape
    Box(
        modifier = modifier
            .sizeIn(minWidth = 44.dp, minHeight = 44.dp)
            .bouncyClickable(pressScale = 0.92f, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(shape)
                .background(
                    when {
                        isDownloadingNow -> AccentBlue.copy(alpha = 0.14f)
                        isPausedState -> StatusAmber.copy(alpha = 0.14f)
                        else -> MaterialTheme.colorScheme.surfaceContainerHigh
                    }
                )
                .border(
                    width = if (isDownloadingNow || isPausedState) 1.dp else ShapeTokens.BorderSubtle,
                    color = if (isDownloadingNow || isPausedState) ringColor.copy(alpha = 0.45f) else MaterialTheme.colorScheme.outlineVariant,
                    shape = shape
                ),
            contentAlignment = Alignment.Center
        ) {
            if (isDownloadingNow) {
                Canvas(modifier = Modifier.size(34.dp)) {
                    val stroke = Stroke(width = 2.4.dp.toPx(), cap = StrokeCap.Round)
                    val inset = 1.5.dp.toPx()
                    val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
                    drawArc(
                        color = ringColor.copy(alpha = 0.18f),
                        startAngle = 0f,
                        sweepAngle = 360f,
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = arcSize,
                        style = stroke
                    )
                    val startAngle = if (aggregateProgress <= 0.02f) indeterminateSweepAngle - 90f else -90f
                    val sweep = if (aggregateProgress <= 0.02f) 95f else (animatedRingProgress * 360f).coerceAtLeast(24f)
                    drawArc(
                        color = ringColor,
                        startAngle = startAngle,
                        sweepAngle = sweep,
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = arcSize,
                        style = stroke
                    )
                }
            }

            AnimatedContent(
                targetState = when {
                    isDownloadingNow -> "active"
                    isPausedState -> "paused"
                    else -> "idle"
                },
                transitionSpec = {
                    (fadeIn(tween(180)) + scaleIn(initialScale = 0.8f)) togetherWith
                        (fadeOut(tween(150)) + scaleOut(targetScale = 0.8f))
                },
                label = "dl_header_icon"
            ) { mode ->
                when (mode) {
                    "active" -> Icon(
                        imageVector = Icons.Default.ArrowDownward,
                        contentDescription = stringResource(R.string.dl_queue_title),
                        tint = AccentBlue,
                        modifier = Modifier
                            .size(18.dp)
                            .offset(y = arrowOffsetY.dp)
                    )
                    "paused" -> Icon(
                        imageVector = Icons.Default.Pause,
                        contentDescription = stringResource(R.string.dl_queue_title),
                        tint = StatusAmber,
                        modifier = Modifier.size(19.dp)
                    )
                    else -> Icon(
                        imageVector = Icons.Default.Download,
                        contentDescription = stringResource(R.string.dl_queue_title),
                        tint = if (completedCount > 0) StatusGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = badgeCount > 0,
            enter = scaleIn(spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium)) + fadeIn(tween(150)),
            exit = scaleOut(tween(180)) + fadeOut(tween(150)),
            modifier = Modifier.align(Alignment.TopEnd)
        ) {
            Box(
                modifier = Modifier
                    .defaultMinSize(minWidth = 17.dp, minHeight = 17.dp)
                    .clip(CircleShape)
                    .background(if (isDownloadingNow) AccentBlue else StatusAmber)
                    .border(1.5.dp, MaterialTheme.colorScheme.background, CircleShape)
                    .padding(horizontal = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                AnimatedContent(
                    targetState = badgeCount,
                    transitionSpec = {
                        (fadeIn(tween(150)) + scaleIn(initialScale = 0.75f)) togetherWith
                            (fadeOut(tween(120)) + scaleOut(targetScale = 0.75f))
                    },
                    label = "dl_badge_num"
                ) { count ->
                    Text(
                        text = if (count > 99) "99+" else "$count",
                        color = Color.White,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.ExtraBold,
                        lineHeight = 10.sp
                    )
                }
            }
        }
    }
}

private enum class DownloadQueueTab {
    ACTIVE, PAUSED, COMPLETED, ALL
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadQueueBottomSheet(
    onDismiss: () -> Unit,
    onOpenTarget: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val app = context.applicationContext as InstaLoggerApp
    val orchestrator = app.mediaDownloadOrchestrator

    val queueItems by orchestrator.queueItems.collectAsStateWithLifecycle()
    val isQueuePaused by orchestrator.isQueuePaused.collectAsStateWithLifecycle()
    val isAutoSuppressed by orchestrator.isAutoEnqueueSuppressed.collectAsStateWithLifecycle()
    val totalSpeed by orchestrator.totalSpeedBytesPerSec.collectAsStateWithLifecycle()
    val activeWorkers by orchestrator.activeDownloadCount.collectAsStateWithLifecycle()
    val speedPreset by app.settingsManager.archiverSpeedPreset.collectAsStateWithLifecycle()

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedTab by remember { mutableStateOf(DownloadQueueTab.ACTIVE) }

    // Confirmation dialog states
    var showCancelAllDialog by remember { mutableStateOf(false) }
    var showClearFinishedDialog by remember { mutableStateOf(false) }
    var itemToCancel by remember { mutableStateOf<DownloadQueueItem?>(null) }
    var itemToDelete by remember { mutableStateOf<DownloadQueueItem?>(null) }

    val activeList = remember(queueItems) { queueItems.filter { it.state.isActive } }
    val pausedList = remember(queueItems) { queueItems.filter { it.state.isPaused } }
    val completedList = remember(queueItems) { queueItems.filter { it.state.isTerminal } }

    LaunchedEffect(Unit) {
        if (activeList.isEmpty()) {
            selectedTab = when {
                pausedList.isNotEmpty() -> DownloadQueueTab.PAUSED
                completedList.isNotEmpty() -> DownloadQueueTab.ALL
                else -> DownloadQueueTab.ACTIVE
            }
        }
    }

    val visibleItems = remember(selectedTab, queueItems, activeList, pausedList, completedList) {
        when (selectedTab) {
            DownloadQueueTab.ACTIVE -> activeList
            DownloadQueueTab.PAUSED -> pausedList
            DownloadQueueTab.COMPLETED -> completedList
            DownloadQueueTab.ALL -> queueItems
        }
    }

    val hasActiveOrPaused = activeList.isNotEmpty() || pausedList.isNotEmpty()
    val hasTerminal = completedList.isNotEmpty()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.88f)
                .navigationBarsPadding()
        ) {
            // 1. Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.dl_queue_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = stringResource(
                            R.string.dl_queue_subtitle_format,
                            activeWorkers,
                            speedPreset.downloadWorkers,
                            speedPreset.key
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier
                        .size(36.dp)
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

            Spacer(modifier = Modifier.height(8.dp))

            // 2. Live Speed & Telemetry Hero Card (Zero-overflow layout)
            LiveTelemetryBanner(
                totalSpeedBytesPerSec = totalSpeed,
                activeCount = activeList.size,
                pausedCount = pausedList.size,
                completedCount = completedList.count { it.state is PostDownloadState.Completed },
                isQueuePaused = isQueuePaused || (activeList.isEmpty() && pausedList.isNotEmpty()),
                isAutoSuppressed = isAutoSuppressed,
                hasActiveOrPaused = hasActiveOrPaused,
                hasTerminal = hasTerminal,
                onPauseAll = {
                    orchestrator.pauseAll()
                    selectedTab = DownloadQueueTab.PAUSED
                    Toast.makeText(context, context.getString(R.string.dl_toast_paused_all), Toast.LENGTH_SHORT).show()
                },
                onResumeAll = {
                    orchestrator.resumeAll()
                    selectedTab = DownloadQueueTab.ACTIVE
                    Toast.makeText(context, context.getString(R.string.dl_toast_resumed_all), Toast.LENGTH_SHORT).show()
                },
                onReenableAuto = {
                    orchestrator.resumeAutoDownloads()
                    Toast.makeText(context, context.getString(R.string.dl_toast_auto_reenabled), Toast.LENGTH_SHORT).show()
                },
                onCancelAll = { showCancelAllDialog = true },
                onClearFinished = { showClearFinishedDialog = true }
            )

            Spacer(modifier = Modifier.height(10.dp))

            // 3. Filter Chips Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                QueueFilterChip(
                    label = stringResource(R.string.dl_tab_active, activeList.size),
                    selected = selectedTab == DownloadQueueTab.ACTIVE,
                    accentColor = AccentBlue,
                    onClick = { selectedTab = DownloadQueueTab.ACTIVE }
                )
                QueueFilterChip(
                    label = stringResource(R.string.dl_tab_paused, pausedList.size),
                    selected = selectedTab == DownloadQueueTab.PAUSED,
                    accentColor = StatusAmber,
                    onClick = { selectedTab = DownloadQueueTab.PAUSED }
                )
                QueueFilterChip(
                    label = stringResource(R.string.dl_tab_finished, completedList.size),
                    selected = selectedTab == DownloadQueueTab.COMPLETED,
                    accentColor = StatusGreen,
                    onClick = { selectedTab = DownloadQueueTab.COMPLETED }
                )
                QueueFilterChip(
                    label = stringResource(R.string.dl_tab_all, queueItems.size),
                    selected = selectedTab == DownloadQueueTab.ALL,
                    accentColor = MaterialTheme.colorScheme.primary,
                    onClick = { selectedTab = DownloadQueueTab.ALL }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))

            // 4. Animated Queue Items List
            if (visibleItems.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(32.dp)
                    ) {
                        Icon(
                            imageVector = when (selectedTab) {
                                DownloadQueueTab.PAUSED -> Icons.Default.PauseCircleOutline
                                DownloadQueueTab.COMPLETED -> Icons.Default.TaskAlt
                                else -> Icons.Default.CloudDone
                            },
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            modifier = Modifier.size(46.dp)
                        )
                        Text(
                            text = stringResource(R.string.dl_queue_empty_title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = stringResource(R.string.dl_queue_empty_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(visibleItems, key = { it.postId }) { item ->
                        DownloadQueueCard(
                            item = item,
                            onPause = {
                                orchestrator.pauseDownload(item.postId)
                                Toast.makeText(context, context.getString(R.string.dl_toast_paused_single), Toast.LENGTH_SHORT).show()
                            },
                            onResume = { orchestrator.resumeDownload(item.postId) },
                            onCancel = { itemToCancel = item },
                            onRetry = { orchestrator.retryDownload(item.postId) },
                            onDeleteRequest = { itemToDelete = item },
                            onOpenProfile = {
                                if (item.username.isNotBlank()) {
                                    onDismiss()
                                    onOpenTarget(item.username)
                                }
                            },
                            modifier = Modifier.animateItem(
                                fadeInSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing),
                                placementSpec = spring(
                                    dampingRatio = Spring.DampingRatioLowBouncy,
                                    stiffness = Spring.StiffnessMediumLow
                                ),
                                fadeOutSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing)
                            )
                        )
                    }
                }
            }
        }
    }

    // Confirmation 1: Cancel ALL downloads (always asks confirmation!)
    if (showCancelAllDialog) {
        AlertDialog(
            onDismissRequest = { showCancelAllDialog = false },
            title = { Text(stringResource(R.string.dl_cancel_all_title), fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(R.string.dl_cancel_all_desc, activeList.size + pausedList.size)) },
            confirmButton = {
                Button(
                    onClick = {
                        showCancelAllDialog = false
                        orchestrator.cancelAll()
                        Toast.makeText(context, context.getString(R.string.dl_toast_cancelled_all), Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = StatusRed)
                ) {
                    Text(stringResource(R.string.dl_action_cancel_short), maxLines = 1, softWrap = false)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCancelAllDialog = false }) {
                    Text(stringResource(R.string.dialog_cancel))
                }
            }
        )
    }

    // Confirmation 2: Cancel SINGLE download
    itemToCancel?.let { target ->
        AlertDialog(
            onDismissRequest = { itemToCancel = null },
            title = { Text(stringResource(R.string.dl_cancel_single_title), fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    stringResource(
                        R.string.dl_cancel_single_desc,
                        if (target.username.isNotBlank()) "@${target.username}" else target.postId
                    )
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val id = target.postId
                        itemToCancel = null
                        orchestrator.cancelDownload(id)
                        Toast.makeText(context, context.getString(R.string.dl_toast_cancelled_single), Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = StatusRed)
                ) {
                    Text(stringResource(R.string.dl_action_cancel_short), maxLines = 1, softWrap = false)
                }
            },
            dismissButton = {
                TextButton(onClick = { itemToCancel = null }) {
                    Text(stringResource(R.string.dialog_cancel))
                }
            }
        )
    }

    // Confirmation 3: Delete downloaded / finished item (offers List-Only OR Delete Vault File from device!)
    itemToDelete?.let { target ->
        val isCompletedFile = target.state is PostDownloadState.Completed
        AlertDialog(
            onDismissRequest = { itemToDelete = null },
            title = {
                Text(
                    text = stringResource(
                        if (isCompletedFile) R.string.dl_delete_item_title else R.string.dl_remove_history_title
                    ),
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = stringResource(
                        if (isCompletedFile) R.string.dl_delete_item_desc else R.string.dl_remove_history_desc,
                        if (target.username.isNotBlank()) "@${target.username}" else target.postId
                    )
                )
            },
            confirmButton = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    horizontalAlignment = Alignment.End
                ) {
                    if (isCompletedFile) {
                        Button(
                            onClick = {
                                val id = target.postId
                                itemToDelete = null
                                orchestrator.deleteDownloadedMedia(id, deleteVaultFiles = true)
                                Toast.makeText(context, context.getString(R.string.dl_toast_deleted_file), Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = StatusRed)
                        ) {
                            Text(stringResource(R.string.dl_action_delete_with_file), maxLines = 1, softWrap = false)
                        }
                    }
                    OutlinedButton(
                        onClick = {
                            val id = target.postId
                            itemToDelete = null
                            orchestrator.deleteDownloadedMedia(id, deleteVaultFiles = false)
                            Toast.makeText(context, context.getString(R.string.dl_toast_removed_list), Toast.LENGTH_SHORT).show()
                        }
                    ) {
                        Text(stringResource(R.string.dl_action_delete_list_only), maxLines = 1, softWrap = false)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { itemToDelete = null }) {
                    Text(stringResource(R.string.dialog_cancel))
                }
            }
        )
    }

    // Confirmation 4: Clear all finished items
    if (showClearFinishedDialog) {
        AlertDialog(
            onDismissRequest = { showClearFinishedDialog = false },
            title = { Text(stringResource(R.string.dl_clear_done_title), fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(R.string.dl_clear_done_desc, completedList.size)) },
            confirmButton = {
                Button(
                    onClick = {
                        showClearFinishedDialog = false
                        orchestrator.clearFinished()
                        Toast.makeText(context, context.getString(R.string.dl_toast_cleared_done), Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Text(stringResource(R.string.dl_action_clear_short), maxLines = 1, softWrap = false)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearFinishedDialog = false }) {
                    Text(stringResource(R.string.dialog_cancel))
                }
            }
        )
    }
}

@Composable
private fun LiveTelemetryBanner(
    totalSpeedBytesPerSec: Long,
    activeCount: Int,
    pausedCount: Int,
    completedCount: Int,
    isQueuePaused: Boolean,
    isAutoSuppressed: Boolean,
    hasActiveOrPaused: Boolean,
    hasTerminal: Boolean,
    onPauseAll: () -> Unit,
    onResumeAll: () -> Unit,
    onReenableAuto: () -> Unit,
    onCancelAll: () -> Unit,
    onClearFinished: () -> Unit
) {
    val isLive = activeCount > 0
    val infinite = rememberInfiniteTransition(label = "telemetry_pulse")
    val pulseAlpha by infinite.animateFloat(
        initialValue = 0.35f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_dot"
    )

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .border(
                1.dp,
                when {
                    isLive -> AccentBlue.copy(alpha = 0.3f)
                    isQueuePaused || isAutoSuppressed -> StatusAmber.copy(alpha = 0.35f)
                    else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                },
                RoundedCornerShape(16.dp)
            )
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(9.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    isLive -> AccentBlue.copy(alpha = pulseAlpha)
                                    isQueuePaused || isAutoSuppressed -> StatusAmber
                                    else -> StatusGreen
                                }
                            )
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = when {
                                isLive -> DownloadTelemetryFormatter.formatSpeed(totalSpeedBytesPerSec)
                                isQueuePaused -> stringResource(R.string.dl_status_queue_paused)
                                isAutoSuppressed -> stringResource(R.string.dl_status_auto_stopped)
                                else -> stringResource(R.string.dl_status_idle)
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = when {
                                isLive -> AccentBlue
                                isQueuePaused || isAutoSuppressed -> StatusAmber
                                else -> MaterialTheme.colorScheme.onSurface
                            }
                        )
                        Text(
                            text = stringResource(
                                R.string.dl_telemetry_counts,
                                activeCount,
                                pausedCount,
                                completedCount
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // Compact Clear Finished button (zero overflow)
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier
                        .alpha(if (hasTerminal) 1f else 0.35f)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = hasTerminal, onClick = onClearFinished)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            Icons.Default.CleaningServices,
                            contentDescription = stringResource(R.string.dl_action_clear_done),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = stringResource(R.string.dl_action_clear_short),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }

            // Global Pause / Resume & Cancel All controls (equal-width, single-line, zero overflow)
            AnimatedVisibility(
                visible = hasActiveOrPaused,
                enter = expandVertically(spring(stiffness = Spring.StiffnessMediumLow)) + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val showResume = isQueuePaused && activeCount == 0
                    val primaryColor by animateColorAsState(
                        targetValue = if (showResume) AccentBlue else StatusAmber,
                        animationSpec = tween(220),
                        label = "pause_resume_color"
                    )
                    Button(
                        onClick = { if (showResume) onResumeAll() else onPauseAll() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = primaryColor.copy(alpha = 0.16f),
                            contentColor = primaryColor
                        ),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        AnimatedContent(
                            targetState = showResume,
                            transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(150)) },
                            label = "pause_resume_label"
                        ) { resuming ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Icon(
                                    imageVector = if (resuming) Icons.Default.PlayArrow else Icons.Default.Pause,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = stringResource(
                                        if (resuming) R.string.dl_action_resume_short else R.string.dl_action_pause_short
                                    ),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    OutlinedButton(
                        onClick = onCancelAll,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, StatusRed.copy(alpha = 0.45f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = StatusRed),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Cancel, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = stringResource(R.string.dl_action_cancel_short),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // Re-enable Auto-Download banner when user previously cancelled all
            AnimatedVisibility(
                visible = isAutoSuppressed && !hasActiveOrPaused,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Button(
                    onClick = onReenableAuto,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AccentBlue.copy(alpha = 0.14f),
                        contentColor = AccentBlue
                    ),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.dl_action_reenable_auto),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun DownloadQueueCard(
    item: DownloadQueueItem,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onDeleteRequest: () -> Unit,
    onOpenProfile: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state = item.state
    val isDownloading = state is PostDownloadState.Downloading
    val isIndeterminateStage = state is PostDownloadState.Extracting ||
        state is PostDownloadState.Merging ||
        state is PostDownloadState.Encrypting ||
        state is PostDownloadState.Queued

    val accentColor by animateColorAsState(
        targetValue = when (state) {
            is PostDownloadState.Completed -> StatusGreen
            is PostDownloadState.Failed -> StatusRed
            is PostDownloadState.Paused -> StatusAmber
            is PostDownloadState.Cancelled -> MaterialTheme.colorScheme.onSurfaceVariant
            else -> AccentBlue
        },
        animationSpec = tween(250),
        label = "card_accent"
    )

    val animatedProgress by animateFloatAsState(
        targetValue = (item.progress / 100f).coerceIn(0f, 1f),
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "item_progress"
    )

    val shimmerTransition = rememberInfiniteTransition(label = "bar_shimmer")
    val shimmerX by shimmerTransition.animateFloat(
        initialValue = -0.4f,
        targetValue = 1.4f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "bar_shimmer_x"
    )

    val stageLabel = when (state) {
        is PostDownloadState.Queued -> stringResource(R.string.download_stage_queued)
        is PostDownloadState.Extracting -> stringResource(R.string.download_stage_extracting)
        is PostDownloadState.Downloading -> stringResource(R.string.download_stage_downloading, item.progress.roundToInt())
        is PostDownloadState.Merging -> stringResource(R.string.download_stage_merging)
        is PostDownloadState.Encrypting -> stringResource(R.string.download_stage_encrypting)
        is PostDownloadState.Paused -> stringResource(R.string.download_stage_paused)
        is PostDownloadState.Completed -> stringResource(R.string.download_stage_completed, state.providerId)
        is PostDownloadState.Cancelled -> stringResource(R.string.download_stage_cancelled)
        is PostDownloadState.Failed -> stringResource(R.string.download_stage_failed, state.message)
        is PostDownloadState.Idle -> ""
    }

    val thumbModel: Any = remember(item.thumbnailUrl) {
        val f = File(item.thumbnailUrl)
        if (item.thumbnailUrl.isNotBlank() && f.exists()) f else item.thumbnailUrl
    }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 1.dp,
        modifier = modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = if (state.isActive) accentColor.copy(alpha = 0.32f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f),
                shape = RoundedCornerShape(14.dp)
            )
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onOpenProfile)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    contentAlignment = Alignment.Center
                ) {
                    if (item.thumbnailUrl.isNotBlank()) {
                        AsyncImage(
                            model = thumbModel,
                            contentDescription = item.postId,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Icon(
                            imageVector = when (item.mediaType) {
                                "video" -> Icons.Default.Videocam
                                "carousel" -> Icons.Default.Collections
                                else -> Icons.Default.Image
                            },
                            contentDescription = null,
                            tint = accentColor,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(3.dp)
                            .size(16.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.65f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = when (item.mediaType) {
                                "video" -> Icons.Default.PlayArrow
                                "carousel" -> Icons.Default.Collections
                                else -> Icons.Default.Photo
                            },
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(10.dp)
                        )
                    }
                }

                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = if (item.username.isNotBlank()) "@${item.username}" else item.postId,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (!item.providerName.isNullOrBlank()) {
                            Text(
                                text = item.providerName,
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = accentColor,
                                maxLines = 1,
                                softWrap = false,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(accentColor.copy(alpha = 0.13f))
                                    .padding(horizontal = 5.dp, vertical = 1.dp)
                            )
                        }
                    }

                    Text(
                        text = stageLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = accentColor,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    val telemetryParts = buildList {
                        if (item.downloadedBytes > 0L) {
                            if (item.totalBytes > 0L) {
                                add("${DownloadTelemetryFormatter.formatBytes(item.downloadedBytes)} / ${DownloadTelemetryFormatter.formatBytes(item.totalBytes)}")
                            } else {
                                add(DownloadTelemetryFormatter.formatBytes(item.downloadedBytes))
                            }
                        }
                        if (isDownloading && item.speedBytesPerSec > 0L) {
                            add(DownloadTelemetryFormatter.formatSpeed(item.speedBytesPerSec))
                        } else if (isDownloading && !item.detail.isNullOrBlank()) {
                            add(item.detail)
                        }
                        if (isDownloading && item.etaSeconds > 0L) {
                            add("ETA ${DownloadTelemetryFormatter.formatEta(item.etaSeconds)}")
                        }
                    }
                    Text(
                        text = if (telemetryParts.isNotEmpty()) telemetryParts.joinToString(" · ") else item.postId,
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (telemetryParts.isNotEmpty()) 1f else 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Fixed-width per-item action controls (2 clean 32dp circular buttons, never overflows)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    when {
                        state.isActive -> {
                            QueueActionIcon(
                                icon = Icons.Default.Pause,
                                contentDescription = stringResource(R.string.dl_action_pause),
                                tint = StatusAmber,
                                onClick = onPause
                            )
                            QueueActionIcon(
                                icon = Icons.Default.Close,
                                contentDescription = stringResource(R.string.action_cancel_download),
                                tint = StatusRed,
                                onClick = onCancel
                            )
                        }
                        state.isPaused -> {
                            QueueActionIcon(
                                icon = Icons.Default.PlayArrow,
                                contentDescription = stringResource(R.string.dl_action_resume),
                                tint = AccentBlue,
                                onClick = onResume
                            )
                            QueueActionIcon(
                                icon = Icons.Default.Close,
                                contentDescription = stringResource(R.string.action_cancel_download),
                                tint = StatusRed,
                                onClick = onCancel
                            )
                        }
                        state is PostDownloadState.Failed || state is PostDownloadState.Cancelled -> {
                            QueueActionIcon(
                                icon = Icons.Default.Refresh,
                                contentDescription = stringResource(R.string.dl_action_retry),
                                tint = AccentBlue,
                                onClick = onRetry
                            )
                            QueueActionIcon(
                                icon = Icons.Default.DeleteOutline,
                                contentDescription = stringResource(R.string.dl_action_remove),
                                tint = StatusRed,
                                onClick = onDeleteRequest
                            )
                        }
                        else -> {
                            QueueActionIcon(
                                icon = Icons.Default.DeleteOutline,
                                contentDescription = stringResource(R.string.dl_action_remove),
                                tint = StatusRed,
                                onClick = onDeleteRequest
                            )
                        }
                    }
                }
            }

            if (state.isActive || state.isPaused || state is PostDownloadState.Completed) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(accentColor.copy(alpha = 0.14f))
                ) {
                    if (isIndeterminateStage && item.progress <= 1f) {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxSize(),
                            color = accentColor,
                            trackColor = Color.Transparent
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(animatedProgress.coerceAtLeast(if (state.isActive) 0.04f else 0f))
                                .clip(RoundedCornerShape(3.dp))
                                .background(
                                    if (isDownloading) {
                                        Brush.horizontalGradient(
                                            colorStops = arrayOf(
                                                (shimmerX - 0.35f).coerceIn(0f, 1f) to accentColor,
                                                shimmerX.coerceIn(0f, 1f) to accentColor.copy(alpha = 0.55f),
                                                (shimmerX + 0.35f).coerceIn(0f, 1f) to accentColor
                                            )
                                        )
                                    } else {
                                        Brush.horizontalGradient(listOf(accentColor, accentColor))
                                    }
                                )
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QueueActionIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String?,
    tint: Color,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Surface(
        shape = CircleShape,
        color = tint.copy(alpha = 0.13f),
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .then(if (enabled) Modifier.bouncyClickable(pressScale = 0.88f, onClick = onClick) else Modifier)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = tint,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
private fun QueueFilterChip(
    label: String,
    selected: Boolean,
    accentColor: Color,
    onClick: () -> Unit
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
                softWrap = false
            )
        },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = accentColor.copy(alpha = 0.16f),
            selectedLabelColor = accentColor
        )
    )
}
