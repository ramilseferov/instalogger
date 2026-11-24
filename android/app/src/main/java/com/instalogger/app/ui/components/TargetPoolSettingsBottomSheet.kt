package com.instalogger.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.instalogger.app.InstaLoggerApp
import com.instalogger.app.R
import com.instalogger.app.data.local.TargetEntity
import com.instalogger.app.engine.pool.FollowerPoolEngine
import com.instalogger.app.engine.pool.FollowerPoolMode
import com.instalogger.app.engine.pool.TargetPoolOverride
import com.instalogger.app.ui.theme.AccentBlue
import com.instalogger.app.ui.theme.ShapeTokens
import com.instalogger.app.util.FormatUtils
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TargetPoolSettingsBottomSheet(
    target: TargetEntity,
    onDismissRequest: () -> Unit,
    onFlushed: (Long) -> Unit = {}
) {
    val context = LocalContext.current
    val app = context.applicationContext as InstaLoggerApp
    val settingsManager = app.settingsManager
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current

    val globalMode by settingsManager.poolMode.collectAsStateWithLifecycle()
    val globalFixed by settingsManager.poolFixedThreshold.collectAsStateWithLifecycle()
    val globalPercent by settingsManager.poolPercentThreshold.collectAsStateWithLifecycle()
    val globalDigest by settingsManager.poolDigestWindowMinutes.collectAsStateWithLifecycle()
    val globalCooldown by settingsManager.poolCooldownMinutes.collectAsStateWithLifecycle()
    val globalFollowing by settingsManager.poolApplyToFollowing.collectAsStateWithLifecycle()

    var selectedOverride by remember(target.username) {
        mutableStateOf(TargetPoolOverride.fromKey(target.poolOverrideMode))
    }
    var customThresholdText by remember(target.username) {
        mutableStateOf(target.poolCustomThreshold.coerceAtLeast(1L).toString())
    }
    var customPercentText by remember(target.username) {
        mutableStateOf(target.poolCustomPercent.coerceIn(0.01, 100.0).toString())
    }
    var customDigest by remember(target.username) { mutableIntStateOf(target.poolCustomDigestMinutes) }
    var customCooldown by remember(target.username) { mutableIntStateOf(target.poolCustomCooldownMinutes) }
    var applyFollowing by remember(target.username) { mutableIntStateOf(target.poolApplyToFollowing) }

    val effectiveMode = when (selectedOverride) {
        TargetPoolOverride.GLOBAL -> globalMode
        TargetPoolOverride.OFF -> FollowerPoolMode.OFF
        TargetPoolOverride.FIXED_COUNT -> FollowerPoolMode.FIXED_COUNT
        TargetPoolOverride.PERCENTAGE -> FollowerPoolMode.PERCENTAGE
        TargetPoolOverride.AUTO_ADAPTIVE -> FollowerPoolMode.AUTO_ADAPTIVE
    }

    val effectiveFixed = if (selectedOverride == TargetPoolOverride.FIXED_COUNT) {
        customThresholdText.toLongOrNull()?.coerceIn(1L, 1_000_000L) ?: target.poolCustomThreshold
    } else {
        globalFixed
    }
    val effectivePercent = if (selectedOverride == TargetPoolOverride.PERCENTAGE) {
        customPercentText.replace(',', '.').toDoubleOrNull()?.coerceIn(0.01, 100.0) ?: target.poolCustomPercent
    } else {
        globalPercent
    }

    val effectiveThreshold = FollowerPoolEngine.resolveEffectiveThreshold(
        mode = effectiveMode,
        fixedThreshold = effectiveFixed,
        percentThreshold = effectivePercent,
        referenceCount = max(target.followersCount, target.poolFollowerBaseline)
    )

    val baseline = if (target.poolFollowerBaseline > 0L) target.poolFollowerBaseline else target.followersCount
    val netDelta = target.followersCount - baseline
    val progress = if (effectiveMode == FollowerPoolMode.OFF) {
        0f
    } else {
        (abs(netDelta).toFloat() / effectiveThreshold.coerceAtLeast(1L).toFloat()).coerceIn(0f, 1f)
    }

    fun persistConfig(
        override: TargetPoolOverride = selectedOverride,
        fixedText: String = customThresholdText,
        percentText: String = customPercentText,
        digest: Int = customDigest,
        cooldown: Int = customCooldown,
        following: Int = applyFollowing
    ) {
        val fixedVal = fixedText.toLongOrNull()?.coerceIn(1L, 1_000_000L) ?: target.poolCustomThreshold.coerceAtLeast(1L)
        val pctVal = percentText.replace(',', '.').toDoubleOrNull()?.coerceIn(0.01, 100.0) ?: target.poolCustomPercent
        scope.launch {
            app.database.targetDao().updatePoolConfig(
                username = target.username,
                overrideMode = override.key,
                customThreshold = fixedVal,
                customPercent = pctVal,
                customDigestMinutes = digest,
                customCooldownMinutes = cooldown,
                applyToFollowing = following,
                nowMs = System.currentTimeMillis()
            )
        }
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = {
            BottomSheetDefaults.DragHandle(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )
        },
        shape = ShapeTokens.LargeShape
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(ShapeTokens.SmallShape)
                            .background(AccentBlue.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = null,
                            tint = AccentBlue,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Column {
                        Text(
                            text = stringResource(R.string.pool_target_sheet_title, target.username),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = stringResource(R.string.pool_target_sheet_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                IconButton(
                    onClick = onDismissRequest,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.dialog_cancel),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            // Live Pool Status Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = ShapeTokens.LargeShape,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                ),
                border = BorderStroke(ShapeTokens.BorderStandard, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.pool_live_status_title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        val deltaSign = if (netDelta > 0L) "+$netDelta" else "$netDelta"
                        Text(
                            text = if (effectiveMode == FollowerPoolMode.OFF) {
                                stringResource(R.string.pool_mode_off)
                            } else {
                                "$deltaSign / $effectiveThreshold"
                            },
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = AccentBlue
                        )
                    }

                    if (effectiveMode != FollowerPoolMode.OFF) {
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(CircleShape),
                            color = AccentBlue,
                            trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text = stringResource(R.string.pool_baseline_label),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = FormatUtils.formatCompactNumber(baseline),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = stringResource(R.string.pool_current_label),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = FormatUtils.formatCompactNumber(target.followersCount),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = stringResource(R.string.pool_churn_label),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "+${target.poolFollowerGrossGain} / -${target.poolFollowerGrossLoss}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    OutlinedButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            scope.launch {
                                val flushed = app.profileInspector.flushTargetPool(target.username, writeSummaryLog = netDelta != 0L)
                                onFlushed(flushed)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = ShapeTokens.MediumShape
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(text = stringResource(R.string.pool_action_flush_reset))
                    }
                }
            }

            // Mode Override Selector Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = ShapeTokens.LargeShape,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                ),
                border = BorderStroke(ShapeTokens.BorderStandard, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.pool_mode_section_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val modeOptions = listOf(
                            TargetPoolOverride.GLOBAL to stringResource(R.string.pool_override_global),
                            TargetPoolOverride.OFF to stringResource(R.string.pool_mode_off),
                            TargetPoolOverride.FIXED_COUNT to stringResource(R.string.pool_mode_fixed),
                            TargetPoolOverride.PERCENTAGE to stringResource(R.string.pool_mode_percent),
                            TargetPoolOverride.AUTO_ADAPTIVE to stringResource(R.string.pool_mode_auto)
                        )
                        modeOptions.forEach { (override, label) ->
                            FilterChip(
                                selected = selectedOverride == override,
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    selectedOverride = override
                                    persistConfig(override = override)
                                },
                                label = { Text(label, maxLines = 1) }
                            )
                        }
                    }

                    // Fixed count controls when FIXED_COUNT override selected
                    AnimatedVisibility(visible = selectedOverride == TargetPoolOverride.FIXED_COUNT) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                text = stringResource(R.string.pool_threshold_label),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                listOf(10L, 50L, 100L, 250L, 500L, 1000L).forEach { preset ->
                                    FilterChip(
                                        selected = customThresholdText.toLongOrNull() == preset,
                                        onClick = {
                                            customThresholdText = preset.toString()
                                            persistConfig(fixedText = customThresholdText)
                                        },
                                        label = { Text("±$preset") }
                                    )
                                }
                            }
                            OutlinedTextField(
                                value = customThresholdText,
                                onValueChange = { input ->
                                    val digits = input.filter { it.isDigit() }.take(7)
                                    customThresholdText = digits
                                    if ((digits.toLongOrNull() ?: 0L) >= 1L) {
                                        persistConfig(fixedText = digits)
                                    }
                                },
                                label = { Text(stringResource(R.string.pool_custom_value_hint)) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    // Percentage controls when PERCENTAGE override selected
                    AnimatedVisibility(visible = selectedOverride == TargetPoolOverride.PERCENTAGE) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                text = stringResource(R.string.pool_percent_label),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                listOf(0.1, 0.5, 1.0, 2.0, 5.0).forEach { pct ->
                                    FilterChip(
                                        selected = customPercentText.replace(',', '.').toDoubleOrNull() == pct,
                                        onClick = {
                                            customPercentText = pct.toString()
                                            persistConfig(percentText = customPercentText)
                                        },
                                        label = { Text("$pct%") }
                                    )
                                }
                            }
                            OutlinedTextField(
                                value = customPercentText,
                                onValueChange = { input ->
                                    val filtered = input.filter { it.isDigit() || it == '.' || it == ',' }.take(6)
                                    customPercentText = filtered
                                    val parsed = filtered.replace(',', '.').toDoubleOrNull()
                                    if (parsed != null && parsed in 0.01..100.0) {
                                        persistConfig(percentText = filtered)
                                    }
                                },
                                label = { Text(stringResource(R.string.pool_custom_percent_hint)) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    // Auto-adaptive explanation
                    AnimatedVisibility(visible = selectedOverride == TargetPoolOverride.AUTO_ADAPTIVE) {
                        Text(
                            text = stringResource(R.string.pool_auto_resolved_hint, effectiveThreshold),
                            style = MaterialTheme.typography.bodySmall,
                            color = AccentBlue
                        )
                    }
                }
            }

            // Per-Target Timing & Following Override Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = ShapeTokens.LargeShape,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                ),
                border = BorderStroke(ShapeTokens.BorderStandard, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.pool_digest_title),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val digestOptions = listOf(
                            -1 to stringResource(R.string.pool_override_global) + " (${formatMinutesLabel(context, globalDigest)})",
                            0 to stringResource(R.string.pool_option_off),
                            15 to "15m",
                            60 to "1h",
                            360 to "6h",
                            1440 to "24h"
                        )
                        digestOptions.forEach { (mins, label) ->
                            FilterChip(
                                selected = customDigest == mins,
                                onClick = {
                                    customDigest = mins
                                    persistConfig(digest = mins)
                                },
                                label = { Text(label) }
                            )
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

                    Text(
                        text = stringResource(R.string.pool_cooldown_title),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val cooldownOptions = listOf(
                            -1 to stringResource(R.string.pool_override_global) + " (${formatMinutesLabel(context, globalCooldown)})",
                            0 to stringResource(R.string.pool_option_off),
                            1 to "1m",
                            5 to "5m",
                            15 to "15m",
                            60 to "1h"
                        )
                        cooldownOptions.forEach { (mins, label) ->
                            FilterChip(
                                selected = customCooldown == mins,
                                onClick = {
                                    customCooldown = mins
                                    persistConfig(cooldown = mins)
                                },
                                label = { Text(label) }
                            )
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

                    Text(
                        text = stringResource(R.string.pool_apply_following_title),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val followingChoices = listOf(
                            -1 to stringResource(R.string.pool_override_global) + if (globalFollowing) " (ON)" else " (OFF)",
                            0 to stringResource(R.string.pool_option_off),
                            1 to stringResource(R.string.pool_option_on)
                        )
                        followingChoices.forEach { (flag, label) ->
                            FilterChip(
                                selected = applyFollowing == flag,
                                onClick = {
                                    applyFollowing = flag
                                    persistConfig(following = flag)
                                },
                                label = { Text(label) }
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun formatMinutesLabel(context: android.content.Context, minutes: Int): String {
    return when {
        minutes <= 0 -> context.getString(R.string.pool_option_off)
        minutes < 60 -> "${minutes}m"
        else -> "${minutes / 60}h"
    }
}
