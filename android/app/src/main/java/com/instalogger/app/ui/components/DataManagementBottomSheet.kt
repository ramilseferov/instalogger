package com.instalogger.app.ui.components

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cookie
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.instalogger.app.R
import com.instalogger.app.ui.animation.bouncyClickable
import com.instalogger.app.ui.theme.AccentBlue
import com.instalogger.app.ui.theme.StatusGreen
import com.instalogger.app.ui.theme.StatusRed
import com.instalogger.app.util.DeviceStorageSnapshot
import com.instalogger.app.util.StorageCacheManager
import com.instalogger.app.util.StorageCategoryMetric
import com.instalogger.app.util.StorageCategoryType
import com.instalogger.app.util.TargetStorageMetric
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

private data class ProtectedWarningToastData(
    val categoryType: StorageCategoryType,
    val title: String,
    val message: String,
    val accentColor: Color,
    val triggerId: Int,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataManagementBottomSheet(
    onDismissRequest: () -> Unit,
    onStorageChanged: (DeviceStorageSnapshot) -> Unit = {},
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val maxSheetHeight = (LocalConfiguration.current.screenHeightDp * 0.86f).dp

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = if (isDark) Color(0xFF10151C) else MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .statusBarsPadding()
            .displayCutoutPadding()
            .padding(top = 12.dp),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 12.dp, bottom = 6.dp)
                    .size(width = 44.dp, height = 4.5.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.outlineVariant)
            )
        }
    ) {
        com.instalogger.app.util.LanguageManager.ProvideLocalizedContext {
            DataManagementContent(
                showHeaderBar = true,
                onDismiss = onDismissRequest,
                onStorageChanged = onStorageChanged,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxSheetHeight)
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(horizontal = 16.dp)
                    .testTag("data_management_bottom_sheet"),
            )
        }
    }
}

@Composable
fun DataManagementContent(
    showHeaderBar: Boolean,
    onDismiss: () -> Unit = {},
    onStorageChanged: (DeviceStorageSnapshot) -> Unit = {},
    refreshTrigger: Int = 0,
    modifier: Modifier = Modifier,
    extraFooterContent: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()
    val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f

    var snapshot by remember { mutableStateOf<DeviceStorageSnapshot?>(null) }
    var isAnalyzing by remember { mutableStateOf(true) }
    var isClearingCache by remember { mutableStateOf(false) }
    var isClearingCookies by remember { mutableStateOf(false) }
    var clearingTargetUsername by remember { mutableStateOf<String?>(null) }
    var clearedBannerText by remember { mutableStateOf<String?>(null) }

    // Confirmation dialog states
    var showConfirmClearSelected by remember { mutableStateOf(false) }
    var showConfirmClearCookies by remember { mutableStateOf(false) }
    var targetPendingClear by remember { mutableStateOf<TargetStorageMetric?>(null) }

    // Selection map
    val selectedMap = remember {
        mutableStateMapOf<StorageCategoryType, Boolean>().apply {
            StorageCategoryType.entries.forEach { type ->
                put(type, type.selectedByDefault)
            }
        }
    }

    val expandedDetailsMap = remember {
        mutableStateMapOf<StorageCategoryType, Boolean>()
    }

    var activeWarningToast by remember { mutableStateOf<ProtectedWarningToastData?>(null) }
    var warningTriggerCounter by remember { mutableIntStateOf(0) }

    LaunchedEffect(activeWarningToast?.triggerId) {
        val current = activeWarningToast ?: return@LaunchedEffect
        delay(6500L)
        if (activeWarningToast?.triggerId == current.triggerId) {
            activeWarningToast = null
        }
    }

    LaunchedEffect(clearedBannerText) {
        if (clearedBannerText != null) {
            delay(4500L)
            clearedBannerText = null
        }
    }

    suspend fun refreshSnapshot() {
        isAnalyzing = true
        val fresh = StorageCacheManager.analyzeStorage(context)
        snapshot = fresh
        onStorageChanged(fresh)
        isAnalyzing = false
    }

    LaunchedEffect(refreshTrigger) {
        refreshSnapshot()
    }

    val categories = snapshot?.categories.orEmpty()
    val totalAllBytes = snapshot?.totalAppCacheBytes ?: 0L
    val selectedBytes = categories
        .filter { selectedMap[it.type] == true }
        .sumOf { it.sizeBytes }
    val selectedCount = StorageCategoryType.entries.count { selectedMap[it] == true }
    val selectedProtectedTypes = StorageCategoryType.entries.filter {
        selectedMap[it] == true && it.isProtectedCategory
    }

    fun triggerProtectedWarning(type: StorageCategoryType) {
        warningTriggerCounter++
        val (titleRes, msgRes) = when (type) {
            StorageCategoryType.VAULT_POSTS ->
                R.string.storage_warn_vault_posts_title to R.string.storage_warn_vault_posts_desc
            StorageCategoryType.VAULT_STORIES_HIGHLIGHTS ->
                R.string.storage_warn_vault_stories_title to R.string.storage_warn_vault_stories_desc
            StorageCategoryType.VAULT_AVATARS ->
                R.string.storage_warn_vault_avatars_title to R.string.storage_warn_vault_avatars_desc
            StorageCategoryType.COMMENTS_AND_ARCHIVES ->
                R.string.storage_warn_comments_archives_title to R.string.storage_warn_comments_archives_desc
            else -> return
        }
        activeWarningToast = ProtectedWarningToastData(
            categoryType = type,
            title = context.getString(titleRes),
            message = context.getString(msgRes),
            accentColor = Color(type.colorHex),
            triggerId = warningTriggerCounter,
        )
    }

    Column(modifier = modifier) {
        if (showHeaderBar) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF32ADE6).copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Storage,
                            contentDescription = null,
                            tint = Color(0xFF32ADE6),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Column {
                        Text(
                            text = stringResource(R.string.storage_mgmt_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = stringResource(R.string.storage_mgmt_header_sub),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(
                        onClick = {
                            if (!isAnalyzing && !isClearingCache) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                coroutineScope.launch { refreshSnapshot() }
                            }
                        },
                        enabled = !isAnalyzing && !isClearingCache,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.storage_action_refresh),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.dialog_cancel),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(modifier = Modifier.height(6.dp))

                StorageSegmentedDonutChart(
                    categories = categories,
                    selectedMap = selectedMap,
                    centerBytes = if (selectedCount > 0) selectedBytes else totalAllBytes,
                    isAnalyzing = isAnalyzing || isClearingCache,
                    isDark = isDark,
                )

                Spacer(modifier = Modifier.height(14.dp))

                // 2. DEVICE STORAGE SHARE & HORIZONTAL BAR (StatFs)
                Text(
                    text = stringResource(R.string.storage_usage_heading),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )

                Spacer(modifier = Modifier.height(5.dp))

                val devicePercentStr = remember(snapshot) {
                    val pct = snapshot?.appShareOfDevicePercent ?: 0f
                    when {
                        pct <= 0f -> "0%"
                        pct < 1f -> "<1%"
                        else -> String.format(Locale.US, "%.1f%%", pct).replace('.', ',')
                    }
                }

                Text(
                    text = stringResource(R.string.storage_device_share_desc, devicePercentStr),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    lineHeight = 18.sp,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )

                Spacer(modifier = Modifier.height(12.dp))

                val deviceUsedRatio = remember(snapshot) {
                    val s = snapshot
                    if (s != null && s.totalDeviceBytes > 0L) {
                        (s.usedDeviceBytes.toFloat() / s.totalDeviceBytes.toFloat()).coerceIn(0.05f, 0.96f)
                    } else {
                        0.35f
                    }
                }
                val appBarFraction by animateFloatAsState(
                    targetValue = if (totalAllBytes <= 0L) 0.02f else 0.06f,
                    animationSpec = tween(420, easing = FastOutSlowInEasing),
                    label = "app_bar_fraction",
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth(0.72f)
                        .height(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .weight(appBarFraction.coerceAtLeast(0.03f))
                            .height(5.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF32ADE6))
                    )
                    Box(
                        modifier = Modifier
                            .weight(deviceUsedRatio.coerceAtLeast(0.15f))
                            .height(4.dp)
                            .clip(CircleShape)
                            .background(
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (isDark) 0.42f else 0.30f)
                            )
                    )
                    Box(
                        modifier = Modifier
                            .weight((1f - deviceUsedRatio).coerceAtLeast(0.15f))
                            .height(4.dp)
                            .clip(CircleShape)
                            .background(
                                MaterialTheme.colorScheme.outline.copy(alpha = if (isDark) 0.24f else 0.16f)
                            )
                    )
                }

                val snap = snapshot
                if (snap != null && snap.totalDeviceBytes > 0L) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = stringResource(
                            R.string.storage_device_free_total_format,
                            StorageCacheManager.formatBytes(snap.freeDeviceBytes),
                            StorageCacheManager.formatBytes(snap.totalDeviceBytes),
                        ),
                        fontSize = 11.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.80f),
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 3. SPRING-ANIMATED PROTECTED CATEGORY WARNING TOAST
                val currentWarning = activeWarningToast
                AnimatedVisibility(
                    visible = currentWarning != null,
                    enter = expandVertically(
                        animationSpec = spring(dampingRatio = 0.76f, stiffness = Spring.StiffnessMediumLow)
                    ) + fadeIn(tween(220)) + scaleIn(
                        initialScale = 0.90f,
                        animationSpec = spring(dampingRatio = 0.76f, stiffness = Spring.StiffnessMediumLow),
                    ),
                    exit = shrinkVertically(tween(200)) + fadeOut(tween(160)) + scaleOut(targetScale = 0.92f),
                ) {
                    if (currentWarning != null) {
                        ProtectedCategoryWarningToastCard(
                            data = currentWarning,
                            isDark = isDark,
                            onDismiss = { activeWarningToast = null },
                            onUnselectCategory = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                selectedMap[currentWarning.categoryType] = false
                                activeWarningToast = null
                            },
                        )
                    }
                }

                // 4. ANIMATED SUCCESS BANNER AFTER PURGE
                val currentSuccess = clearedBannerText
                AnimatedVisibility(
                    visible = currentSuccess != null,
                    enter = expandVertically(spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)) +
                        fadeIn(tween(200)) + scaleIn(initialScale = 0.92f),
                    exit = shrinkVertically(tween(180)) + fadeOut(tween(150)),
                ) {
                    if (currentSuccess != null) {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 14.dp)
                                .testTag("storage_cleared_success_banner"),
                            shape = RoundedCornerShape(16.dp),
                            color = StatusGreen.copy(alpha = if (isDark) 0.18f else 0.12f),
                            border = BorderStroke(1.2.dp, StatusGreen.copy(alpha = 0.45f)),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(CircleShape)
                                        .background(StatusGreen.copy(alpha = 0.22f)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        tint = StatusGreen,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                                Text(
                                    text = currentSuccess,
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = StatusGreen,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }

                // 5. TELEGRAM CATEGORY CHECKLIST CARD + ACTIONS
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("storage_categories_card"),
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isDark) {
                            Color(0xFF18222D).copy(alpha = 0.92f)
                        } else {
                            MaterialTheme.colorScheme.surfaceContainer
                        }
                    ),
                    border = BorderStroke(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outline.copy(alpha = if (isDark) 0.28f else 0.22f),
                    ),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                    ) {
                        val denominatorBytes = if (totalAllBytes > 0L) totalAllBytes else 1L

                        categories.forEachIndexed { index, metric ->
                            val type = metric.type
                            val isChecked = selectedMap[type] == true
                            val isExpanded = expandedDetailsMap[type] == true
                            val rawPct = if (totalAllBytes > 0L) {
                                (metric.sizeBytes.toDouble() / denominatorBytes.toDouble()) * 100.0
                            } else {
                                0.0
                            }
                            val pctLabel = when {
                                metric.sizeBytes <= 0L -> "0%"
                                rawPct < 1.0 -> "<1,0%"
                                else -> String.format(Locale.US, "%.0f%%", rawPct)
                            }

                            StorageCategoryChecklistRow(
                                metric = metric,
                                title = getCategoryTitle(type),
                                subtitleDetail = getCategoryDetailDescription(metric),
                                percentageText = pctLabel,
                                formattedSize = StorageCacheManager.formatBytes(metric.sizeBytes),
                                isChecked = isChecked,
                                isExpanded = isExpanded,
                                isDark = isDark,
                                onToggleCheck = {
                                    val nextState = !isChecked
                                    selectedMap[type] = nextState
                                    if (nextState && type.isProtectedCategory) {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        triggerProtectedWarning(type)
                                    } else {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        if (!nextState && activeWarningToast?.categoryType == type) {
                                            activeWarningToast = null
                                        }
                                    }
                                },
                                onToggleExpand = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    expandedDetailsMap[type] = !isExpanded
                                },
                            )

                            if (index < categories.lastIndex) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(start = 58.dp, end = 16.dp),
                                    thickness = 0.7.dp,
                                    color = MaterialTheme.colorScheme.outline.copy(alpha = if (isDark) 0.16f else 0.12f),
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        val canClearCache = !isClearingCache && !isAnalyzing && selectedCount > 0
                        val hasProtectedSelected = selectedProtectedTypes.isNotEmpty()
                        val targetBtnColor = when {
                            !canClearCache -> Color(0xFF5A8FDB).copy(alpha = 0.42f)
                            hasProtectedSelected -> StatusRed
                            else -> Color(0xFF5A8FDB)
                        }
                        val buttonBgColor by animateColorAsState(
                            targetValue = targetBtnColor,
                            animationSpec = tween(220),
                            label = "clear_cache_btn_color",
                        )

                        Button(
                            onClick = {
                                if (!canClearCache) return@Button
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                showConfirmClearSelected = true
                            },
                            enabled = canClearCache,
                            shape = RoundedCornerShape(26.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = buttonBgColor,
                                contentColor = Color.White,
                                disabledContainerColor = Color(0xFF5A8FDB).copy(alpha = 0.38f),
                                disabledContentColor = Color.White.copy(alpha = 0.65f),
                            ),
                            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
                                .height(52.dp)
                                .testTag("clear_selected_cache_button"),
                        ) {
                            AnimatedContent(
                                targetState = isClearingCache,
                                transitionSpec = {
                                    (fadeIn(tween(180)) + scaleIn(initialScale = 0.9f))
                                        .togetherWith(fadeOut(tween(140)) + scaleOut(targetScale = 0.9f))
                                },
                                label = "clear_btn_content",
                            ) { clearing ->
                                if (clearing) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center,
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(18.dp),
                                            color = Color.White,
                                            strokeWidth = 2.2.dp,
                                        )
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Text(
                                            text = stringResource(R.string.storage_clearing_in_progress),
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                } else {
                                    val smoothButtonBytes by animateFloatAsState(
                                        targetValue = selectedBytes.toFloat(),
                                        animationSpec = tween(
                                            durationMillis = 420,
                                            easing = CubicBezierEasing(0.22f, 1.0f, 0.36f, 1.0f),
                                        ),
                                        label = "clear_btn_bytes_smooth",
                                    )
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center,
                                    ) {
                                        Text(
                                            text = if (hasProtectedSelected) {
                                                stringResource(R.string.storage_btn_clear_selected)
                                            } else {
                                                stringResource(R.string.storage_btn_clear_cache)
                                            },
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = StorageCacheManager.formatBytes(
                                                smoothButtonBytes.toLong().coerceAtLeast(0L)
                                            ),
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = Color.White.copy(alpha = 0.82f),
                                            maxLines = 1,
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Dedicated 1-Tap WebView Cookies & DOM Storage Purge Row
                        OutlinedButton(
                            onClick = {
                                if (isClearingCookies || isClearingCache) return@OutlinedButton
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                showConfirmClearCookies = true
                            },
                            enabled = !isClearingCookies && !isClearingCache,
                            shape = RoundedCornerShape(20.dp),
                            border = BorderStroke(1.dp, Color(0xFFEAB308).copy(alpha = 0.48f)),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = Color(0xFFEAB308).copy(alpha = if (isDark) 0.10f else 0.08f),
                                contentColor = Color(0xFFEAB308),
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
                                .height(46.dp)
                                .testTag("clear_webview_cookies_only_button"),
                        ) {
                            if (isClearingCookies) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    color = Color(0xFFEAB308),
                                    strokeWidth = 2.dp,
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Cookie,
                                    contentDescription = null,
                                    tint = Color(0xFFEAB308),
                                    modifier = Modifier.size(17.dp),
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            Text(
                                text = stringResource(R.string.storage_btn_clear_webview_cookies),
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isDark) Color(0xFFFACC15) else Color(0xFFB45309),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))
                    }
                }

                // 6. PER-TARGET STORAGE BREAKDOWN CARD
                val targets = snapshot?.targetBreakdown.orEmpty()
                if (targets.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(16.dp))

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("storage_targets_breakdown_card"),
                        shape = RoundedCornerShape(22.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isDark) {
                                Color(0xFF18222D).copy(alpha = 0.92f)
                            } else {
                                MaterialTheme.colorScheme.surfaceContainer
                            }
                        ),
                        border = BorderStroke(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.outline.copy(alpha = if (isDark) 0.28f else 0.22f),
                        ),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = stringResource(R.string.storage_per_target_title),
                                    fontSize = 14.5.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = stringResource(R.string.storage_targets_count_format, targets.size),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color(0xFF32ADE6),
                                )
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            val maxTargetBytes = targets.maxOfOrNull { it.totalBytes }?.coerceAtLeast(1L) ?: 1L
                            targets.forEachIndexed { idx, tMetric ->
                                TargetStorageBreakdownRow(
                                    metric = tMetric,
                                    maxBytes = maxTargetBytes,
                                    isClearing = clearingTargetUsername == tMetric.username,
                                    isDark = isDark,
                                    onClearClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        targetPendingClear = tMetric
                                    },
                                )
                                if (idx < targets.lastIndex) {
                                    HorizontalDivider(
                                        modifier = Modifier.padding(start = 58.dp, end = 16.dp),
                                        thickness = 0.7.dp,
                                        color = MaterialTheme.colorScheme.outline.copy(alpha = if (isDark) 0.16f else 0.12f),
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // 7. FOOTER PRIVACY & VAULT NOTICE
                Text(
                    text = stringResource(R.string.storage_footer_notice),
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.80f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                )

                if (extraFooterContent != null) {
                    Spacer(modifier = Modifier.height(16.dp))
                    extraFooterContent()
                }
            }
        }

    // Confirmation Dialog: Clear Selected Categories
    if (showConfirmClearSelected) {
        val chosenTypes = StorageCategoryType.entries.filter { selectedMap[it] == true }.toSet()
        val includesProtected = chosenTypes.any { it.isProtectedCategory }
        AlertDialog(
            onDismissRequest = { showConfirmClearSelected = false },
            title = {
                Text(
                    text = if (includesProtected) {
                        stringResource(R.string.storage_confirm_protected_title)
                    } else {
                        stringResource(R.string.storage_confirm_clear_title)
                    },
                    fontWeight = FontWeight.Bold,
                )
            },
            text = {
                Text(
                    text = if (includesProtected) {
                        stringResource(
                            R.string.storage_confirm_protected_desc,
                            chosenTypes.size,
                            StorageCacheManager.formatBytes(selectedBytes),
                        )
                    } else {
                        stringResource(
                            R.string.storage_confirm_clear_desc,
                            chosenTypes.size,
                            StorageCacheManager.formatBytes(selectedBytes),
                        )
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showConfirmClearSelected = false
                        coroutineScope.launch {
                            isClearingCache = true
                            activeWarningToast = null
                            val (freed, newSnapshot) = StorageCacheManager.clearSelectedCategories(
                                context = context,
                                selectedTypes = chosenTypes,
                            )
                            snapshot = newSnapshot
                            onStorageChanged(newSnapshot)
                            StorageCategoryType.entries
                                .filter { it.isProtectedCategory }
                                .forEach { selectedMap[it] = false }
                            isClearingCache = false
                            clearedBannerText = context.getString(
                                R.string.storage_cleared_banner_format,
                                StorageCacheManager.formatBytes(freed),
                            )
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (includesProtected) StatusRed else AccentBlue,
                    ),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text(stringResource(R.string.storage_confirm_action_clear))
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmClearSelected = false }) {
                    Text(stringResource(R.string.dialog_cancel))
                }
            },
            shape = RoundedCornerShape(24.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        )
    }

    // Confirmation Dialog: Clear WebView Cookies
    if (showConfirmClearCookies) {
        AlertDialog(
            onDismissRequest = { showConfirmClearCookies = false },
            title = {
                Text(
                    text = stringResource(R.string.storage_confirm_cookies_title),
                    fontWeight = FontWeight.Bold,
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.storage_confirm_cookies_desc),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showConfirmClearCookies = false
                        coroutineScope.launch {
                            isClearingCookies = true
                            val (freed, newSnapshot) = StorageCacheManager.clearOnlyWebViewCookies(context)
                            snapshot = newSnapshot
                            onStorageChanged(newSnapshot)
                            isClearingCookies = false
                            clearedBannerText = context.getString(
                                R.string.storage_cookies_cleared_format,
                                StorageCacheManager.formatBytes(freed),
                            )
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD97706)),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text(stringResource(R.string.storage_confirm_action_clear), color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmClearCookies = false }) {
                    Text(stringResource(R.string.dialog_cancel))
                }
            },
            shape = RoundedCornerShape(24.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        )
    }

    // Confirmation Dialog: Clear Single Target's Archived Media
    val pendingTarget = targetPendingClear
    if (pendingTarget != null) {
        AlertDialog(
            onDismissRequest = { targetPendingClear = null },
            title = {
                Text(
                    text = stringResource(R.string.storage_confirm_target_title, "@${pendingTarget.username}"),
                    fontWeight = FontWeight.Bold,
                )
            },
            text = {
                Text(
                    text = stringResource(
                        R.string.storage_confirm_target_desc,
                        "@${pendingTarget.username}",
                        StorageCacheManager.formatBytes(pendingTarget.totalBytes),
                        pendingTarget.totalFiles,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val user = pendingTarget.username
                        targetPendingClear = null
                        coroutineScope.launch {
                            clearingTargetUsername = user
                            val (freed, newSnapshot) = StorageCacheManager.clearTargetMedia(context, user)
                            snapshot = newSnapshot
                            onStorageChanged(newSnapshot)
                            clearingTargetUsername = null
                            clearedBannerText = context.getString(
                                R.string.storage_target_cleared_format,
                                "@$user",
                                StorageCacheManager.formatBytes(freed),
                            )
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = StatusRed),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { targetPendingClear = null }) {
                    Text(stringResource(R.string.dialog_cancel))
                }
            },
            shape = RoundedCornerShape(24.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        )
    }
}

@Composable
private fun StorageSegmentedDonutChart(
    categories: List<StorageCategoryMetric>,
    selectedMap: Map<StorageCategoryType, Boolean>,
    centerBytes: Long,
    isAnalyzing: Boolean,
    isDark: Boolean,
) {
    val density = LocalDensity.current
    val smoothEasing = remember { CubicBezierEasing(0.22f, 1.0f, 0.36f, 1.0f) }
    val allTypes = remember { StorageCategoryType.entries }

    val sweepAnims = remember { allTypes.associateWith { Animatable(0f) } }
    val gapAnims = remember { allTypes.associateWith { Animatable(0f) } }
    val pctAnims = remember { allTypes.associateWith { Animatable(0f) } }
    var hasInitializedSegments by remember { mutableStateOf(false) }

    val selectedBitmask = remember(selectedMap.toMap()) {
        var mask = 0
        allTypes.forEachIndexed { idx, type ->
            if (selectedMap[type] == true) mask = mask or (1 shl idx)
        }
        mask
    }

    val activeCategories = remember(categories, selectedBitmask) {
        categories.filter { selectedMap[it.type] == true && it.sizeBytes > 0L }
    }
    val fallbackCategories = remember(categories) {
        categories.filter { it.sizeBytes > 0L }
    }
    val chartCategories = if (activeCategories.isNotEmpty()) activeCategories else fallbackCategories
    val chartTotalBytes = remember(chartCategories) {
        chartCategories.sumOf { it.sizeBytes }.coerceAtLeast(1L)
    }

    LaunchedEffect(chartCategories, isAnalyzing) {
        val targetSweeps = mutableMapOf<StorageCategoryType, Float>()
        val targetGaps = mutableMapOf<StorageCategoryType, Float>()
        val targetPcts = mutableMapOf<StorageCategoryType, Float>()

        if (chartCategories.isNotEmpty()) {
            val count = chartCategories.size
            val gapDeg = if (count > 1) 2.4f else 0f
            val totalAvailable = (360f - gapDeg * count).coerceAtLeast(1f)
            val rawAngles = chartCategories.map { metric ->
                val ratio = metric.sizeBytes.toFloat() / chartTotalBytes.toFloat()
                (ratio * totalAvailable).coerceAtLeast(
                    if (totalAvailable > count * 14f) 14f else totalAvailable / count
                )
            }
            val sumRaw = rawAngles.sum().coerceAtLeast(1f)
            chartCategories.forEachIndexed { idx, metric ->
                targetSweeps[metric.type] = rawAngles[idx] / sumRaw * totalAvailable
                targetGaps[metric.type] = gapDeg
                targetPcts[metric.type] = ((metric.sizeBytes.toDouble() / chartTotalBytes.toDouble()) * 100.0)
                    .toFloat()
                    .coerceIn(1f, 100f)
            }
        }

        if (!hasInitializedSegments && chartCategories.isNotEmpty() && !isAnalyzing) {
            allTypes.forEach { type ->
                sweepAnims[type]?.snapTo(targetSweeps[type] ?: 0f)
                gapAnims[type]?.snapTo(targetGaps[type] ?: 0f)
                pctAnims[type]?.snapTo(targetPcts[type] ?: 0f)
            }
            hasInitializedSegments = true
        } else if (hasInitializedSegments) {
            val animSpec = tween<Float>(durationMillis = 460, easing = smoothEasing)
            allTypes.forEach { type ->
                val tSweep = targetSweeps[type] ?: 0f
                val tGap = targetGaps[type] ?: 0f
                val tPct = targetPcts[type] ?: 0f
                launch { sweepAnims[type]?.animateTo(tSweep, animSpec) }
                launch { gapAnims[type]?.animateTo(tGap, animSpec) }
                if (tSweep > 0.5f) {
                    launch { pctAnims[type]?.animateTo(tPct, animSpec) }
                }
            }
        }
    }

    val chartRevealProgress by animateFloatAsState(
        targetValue = if (isAnalyzing) 0.14f else 1f,
        animationSpec = tween(durationMillis = 520, easing = smoothEasing),
        label = "donut_reveal",
    )

    val animatedCenterBytes by animateFloatAsState(
        targetValue = centerBytes.toFloat(),
        animationSpec = tween(durationMillis = 420, easing = smoothEasing),
        label = "donut_center_bytes",
    )
    val (valueText, unitText) = StorageCacheManager.splitBytesValueAndUnit(
        animatedCenterBytes.toLong().coerceAtLeast(0L)
    )

    val labelPaint = remember(density) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = with(density) { 12.5.sp.toPx() }
        }
    }
    val labelVerticalOffset = remember(labelPaint) {
        (labelPaint.descent() + labelPaint.ascent()) / 2f
    }

    val innerHoleBg = if (isDark) Color(0xFF10151C) else MaterialTheme.colorScheme.surface
    val emptyRingBrush = remember {
        Brush.sweepGradient(
            listOf(
                StatusGreen.copy(alpha = 0.85f),
                AccentBlue.copy(alpha = 0.85f),
                StatusGreen.copy(alpha = 0.85f),
            )
        )
    }

    Box(
        modifier = Modifier
            .size(214.dp)
            .testTag("storage_donut_chart"),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(208.dp)) {
            val strokeWidthPx = 43.dp.toPx()
            val diameter = size.minDimension - strokeWidthPx
            val topLeft = Offset(strokeWidthPx / 2f, strokeWidthPx / 2f)
            val arcSize = Size(diameter, diameter)
            val center = Offset(size.width / 2f, size.height / 2f)
            val midRadius = diameter / 2f

            var totalSweepPlusGap = 0f
            var largestSweep = 0f
            var largestType: StorageCategoryType? = null
            for (type in allTypes) {
                val sw = sweepAnims[type]?.value ?: 0f
                val gp = gapAnims[type]?.value ?: 0f
                if (sw > 0.05f) {
                    totalSweepPlusGap += sw + gp
                    if (sw > largestSweep) {
                        largestSweep = sw
                        largestType = type
                    }
                }
            }

            if (totalSweepPlusGap <= 0.1f) {
                drawArc(
                    brush = emptyRingBrush,
                    startAngle = -90f,
                    sweepAngle = 360f * chartRevealProgress,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokeWidthPx, cap = StrokeCap.Butt),
                )
            } else {
                val normScale = (360f * chartRevealProgress) / totalSweepPlusGap
                var currentStartAngle = -150f

                for (type in allTypes) {
                    val rawSweep = sweepAnims[type]?.value ?: 0f
                    if (rawSweep <= 0.05f) continue
                    val rawGap = gapAnims[type]?.value ?: 0f
                    val sweep = rawSweep * normScale
                    val gap = rawGap * normScale
                    val segColor = Color(type.colorHex)

                    drawArc(
                        color = segColor,
                        startAngle = currentStartAngle,
                        sweepAngle = sweep,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = strokeWidthPx, cap = StrokeCap.Butt),
                    )

                    if (type == largestType && sweep >= 42f) {
                        val decoAlpha = ((sweep - 42f) / 26f).coerceIn(0f, 1f) * 0.22f * chartRevealProgress
                        if (decoAlpha > 0.01f) {
                            val decoCount = (sweep / 28f).toInt().coerceIn(2, 6)
                            for (d in 0 until decoCount) {
                                val frac = (d + 0.6f) / (decoCount + 0.2f)
                                val angleRad = Math.toRadians((currentStartAngle + sweep * frac).toDouble())
                                val radialOffset = if (d % 2 == 0) midRadius - 9.dp.toPx() else midRadius + 8.dp.toPx()
                                val dx = center.x + (cos(angleRad) * radialOffset).toFloat()
                                val dy = center.y + (sin(angleRad) * radialOffset).toFloat()
                                drawRoundRect(
                                    color = Color.White.copy(alpha = decoAlpha),
                                    topLeft = Offset(dx - 4.5.dp.toPx(), dy - 5.5.dp.toPx()),
                                    size = Size(9.dp.toPx(), 11.dp.toPx()),
                                    cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx()),
                                )
                            }
                        }
                    }

                    val labelAlpha = ((sweep - 18f) / 14f).coerceIn(0f, 1f) *
                        ((chartRevealProgress - 0.55f) / 0.35f).coerceIn(0f, 1f)
                    if (labelAlpha > 0.02f) {
                        val pctInt = (pctAnims[type]?.value ?: 0f).toInt().coerceIn(1, 100)
                        val midAngleRad = Math.toRadians((currentStartAngle + sweep / 2f).toDouble())
                        val tx = center.x + (cos(midAngleRad) * midRadius).toFloat()
                        val ty = center.y + (sin(midAngleRad) * midRadius).toFloat() - labelVerticalOffset
                        labelPaint.alpha = (labelAlpha * 255f).toInt().coerceIn(0, 255)
                        drawContext.canvas.nativeCanvas.drawText("$pctInt%", tx, ty, labelPaint)
                    }

                    currentStartAngle += sweep + gap
                }
            }

            drawCircle(
                color = innerHoleBg,
                radius = (diameter - strokeWidthPx) / 2f + 2.dp.toPx(),
                center = center,
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (isAnalyzing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(28.dp),
                    color = AccentBlue,
                    strokeWidth = 2.5.dp,
                )
            } else {
                Text(
                    text = valueText,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = unitText,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun ProtectedCategoryWarningToastCard(
    data: ProtectedWarningToastData,
    isDark: Boolean,
    onDismiss: () -> Unit,
    onUnselectCategory: () -> Unit,
) {
    val pulseTransition = rememberInfiniteTransition(label = "protected_warning_pulse")
    val borderAlphaState = pulseTransition.animateFloat(
        initialValue = 0.45f,
        targetValue = 0.92f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 850, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "protected_warning_border_alpha",
    )

    val cardScale = remember(data.triggerId) { Animatable(0.93f) }
    LaunchedEffect(data.triggerId) {
        cardScale.animateTo(
            targetValue = 1f,
            animationSpec = spring(dampingRatio = 0.68f, stiffness = Spring.StiffnessMediumLow),
        )
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 14.dp)
            .graphicsLayer {
                scaleX = cardScale.value
                scaleY = cardScale.value
            }
            .drawWithContent {
                drawContent()
                val strokePx = 1.4.dp.toPx()
                val radiusPx = 18.dp.toPx()
                drawRoundRect(
                    color = data.accentColor.copy(alpha = borderAlphaState.value),
                    topLeft = Offset(strokePx / 2f, strokePx / 2f),
                    size = Size(size.width - strokePx, size.height - strokePx),
                    cornerRadius = CornerRadius(radiusPx, radiusPx),
                    style = Stroke(width = strokePx),
                )
            }
            .testTag("protected_category_warning_toast"),
        shape = RoundedCornerShape(18.dp),
        color = data.accentColor.copy(alpha = if (isDark) 0.16f else 0.10f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(data.accentColor.copy(alpha = 0.22f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Default.WarningAmber,
                            contentDescription = null,
                            tint = data.accentColor,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Text(
                        text = data.title,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = data.accentColor,
                    )
                }

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = null,
                        tint = data.accentColor,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = data.message,
                fontSize = 12.sp,
                lineHeight = 16.5.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.90f),
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = data.accentColor.copy(alpha = 0.18f),
                    border = BorderStroke(1.dp, data.accentColor.copy(alpha = 0.42f)),
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(onClick = onUnselectCategory)
                        .testTag("keep_protected_category_safe_button"),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Shield,
                            contentDescription = null,
                            tint = data.accentColor,
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            text = stringResource(R.string.storage_keep_safe_unselect),
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = data.accentColor,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StorageCategoryChecklistRow(
    metric: StorageCategoryMetric,
    title: String,
    subtitleDetail: String,
    percentageText: String,
    formattedSize: String,
    isChecked: Boolean,
    isExpanded: Boolean,
    isDark: Boolean,
    onToggleCheck: () -> Unit,
    onToggleExpand: () -> Unit,
) {
    val categoryColor = Color(metric.type.colorHex)
    val checkScale by animateFloatAsState(
        targetValue = if (isChecked) 1f else 0.90f,
        animationSpec = spring(dampingRatio = 0.76f, stiffness = Spring.StiffnessMedium),
        label = "category_check_scale",
    )
    val checkBgColor by animateColorAsState(
        targetValue = if (isChecked) categoryColor else Color.Transparent,
        animationSpec = tween(220, easing = FastOutSlowInEasing),
        label = "category_check_bg",
    )
    val checkBorderColor by animateColorAsState(
        targetValue = if (isChecked) categoryColor else categoryColor.copy(alpha = 0.62f),
        animationSpec = tween(220, easing = FastOutSlowInEasing),
        label = "category_check_border",
    )
    val rowContentAlpha by animateFloatAsState(
        targetValue = if (isChecked) 1f else 0.68f,
        animationSpec = tween(240, easing = FastOutSlowInEasing),
        label = "category_row_alpha",
    )
    val chevronRotation by animateFloatAsState(
        targetValue = if (isExpanded) 180f else 0f,
        animationSpec = tween(240, easing = FastOutSlowInEasing),
        label = "category_chevron_rot",
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggleCheck)
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .testTag("storage_category_row_${metric.type.id}"),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .graphicsLayer {
                            scaleX = checkScale
                            scaleY = checkScale
                        }
                        .clip(CircleShape)
                        .background(checkBgColor)
                        .border(width = 2.dp, color = checkBorderColor, shape = CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    androidx.compose.animation.AnimatedVisibility(
                        visible = isChecked,
                        enter = scaleIn(
                            initialScale = 0.55f,
                            animationSpec = spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMedium),
                        ) + fadeIn(tween(150)),
                        exit = scaleOut(targetScale = 0.55f, animationSpec = tween(140)) + fadeOut(tween(130)),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }

                Spacer(modifier = Modifier.width(14.dp))

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .graphicsLayer { alpha = rowContentAlpha },
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = title,
                            fontSize = 14.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = percentageText,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }

                    if (metric.type.isProtectedCategory) {
                        Spacer(modifier = Modifier.height(3.dp))
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = categoryColor.copy(alpha = 0.14f),
                        ) {
                            Text(
                                text = stringResource(R.string.storage_badge_protected),
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = categoryColor,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.5.dp),
                            )
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onToggleExpand),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.ExpandMore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f),
                        modifier = Modifier
                            .size(18.dp)
                            .graphicsLayer { rotationZ = chevronRotation },
                    )
                }
            }

            Text(
                text = formattedSize,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = if (isDark) Color(0xFF5AC8FA) else AccentBlue,
                maxLines = 1,
                modifier = Modifier.graphicsLayer { alpha = rowContentAlpha },
            )
        }

        AnimatedVisibility(
            visible = isExpanded,
            enter = expandVertically(tween(220, easing = FastOutSlowInEasing)) + fadeIn(tween(180)),
            exit = shrinkVertically(tween(180, easing = FastOutSlowInEasing)) + fadeOut(tween(140)),
        ) {
            Text(
                text = subtitleDetail,
                fontSize = 11.5.sp,
                lineHeight = 16.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                modifier = Modifier.padding(start = 40.dp, top = 4.dp, end = 8.dp),
            )
        }
    }
}

@Composable
private fun TargetStorageBreakdownRow(
    metric: TargetStorageMetric,
    maxBytes: Long,
    isClearing: Boolean,
    isDark: Boolean,
    onClearClick: () -> Unit,
) {
    val barFraction by animateFloatAsState(
        targetValue = (metric.totalBytes.toFloat() / maxBytes.toFloat()).coerceIn(0.06f, 1f),
        animationSpec = tween(420, easing = FastOutSlowInEasing),
        label = "target_bar_${metric.username}",
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(AccentBlue.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Person,
                contentDescription = null,
                tint = AccentBlue,
                modifier = Modifier.size(18.dp),
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "@${metric.username}",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = StorageCacheManager.formatBytes(metric.totalBytes),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isDark) Color(0xFF5AC8FA) else AccentBlue,
                    maxLines = 1,
                )
            }

            Spacer(modifier = Modifier.height(5.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.outline.copy(alpha = if (isDark) 0.22f else 0.15f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(barFraction)
                        .height(5.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.horizontalGradient(
                                listOf(Color(0xFF9B59B6), Color(0xFF32ADE6))
                            )
                        )
                )
            }

            Spacer(modifier = Modifier.height(3.dp))

            Text(
                text = stringResource(R.string.storage_target_files_format, metric.totalFiles),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(StatusRed.copy(alpha = 0.12f))
                .bouncyClickable(pressScale = 0.90f, enabled = !isClearing, onClick = onClearClick),
            contentAlignment = Alignment.Center,
        ) {
            if (isClearing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    color = StatusRed,
                    strokeWidth = 2.dp,
                )
            } else {
                Icon(
                    imageVector = Icons.Default.DeleteOutline,
                    contentDescription = stringResource(R.string.action_delete),
                    tint = StatusRed,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun getCategoryTitle(type: StorageCategoryType): String = when (type) {
    StorageCategoryType.IMAGE_CACHE -> stringResource(R.string.storage_cat_image_cache)
    StorageCategoryType.TEMP_STAGING -> stringResource(R.string.storage_cat_temp_staging)
    StorageCategoryType.WEBVIEW_CACHE -> stringResource(R.string.storage_cat_webview_cache)
    StorageCategoryType.LOGS_AND_ALERTS -> stringResource(R.string.storage_cat_logs_alerts)
    StorageCategoryType.VAULT_POSTS -> stringResource(R.string.storage_cat_vault_posts)
    StorageCategoryType.VAULT_STORIES_HIGHLIGHTS -> stringResource(R.string.storage_cat_vault_stories)
    StorageCategoryType.VAULT_AVATARS -> stringResource(R.string.storage_cat_vault_avatars)
    StorageCategoryType.COMMENTS_AND_ARCHIVES -> stringResource(R.string.storage_cat_comments_archives)
}

@Composable
private fun getCategoryDetailDescription(metric: StorageCategoryMetric): String = when (metric.type) {
    StorageCategoryType.IMAGE_CACHE ->
        stringResource(R.string.storage_desc_image_cache, metric.itemCount)
    StorageCategoryType.TEMP_STAGING ->
        stringResource(R.string.storage_desc_temp_staging, metric.itemCount)
    StorageCategoryType.WEBVIEW_CACHE ->
        stringResource(R.string.storage_desc_webview_cache, metric.itemCount)
    StorageCategoryType.LOGS_AND_ALERTS ->
        stringResource(R.string.storage_desc_logs_alerts, metric.itemCount)
    StorageCategoryType.VAULT_POSTS ->
        stringResource(R.string.storage_desc_vault_posts, metric.itemCount)
    StorageCategoryType.VAULT_STORIES_HIGHLIGHTS ->
        stringResource(R.string.storage_desc_vault_stories, metric.itemCount)
    StorageCategoryType.VAULT_AVATARS ->
        stringResource(R.string.storage_desc_vault_avatars, metric.itemCount)
    StorageCategoryType.COMMENTS_AND_ARCHIVES ->
        stringResource(R.string.storage_desc_comments_archives, metric.itemCount)
}
