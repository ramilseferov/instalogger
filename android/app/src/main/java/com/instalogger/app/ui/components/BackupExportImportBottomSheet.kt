package com.instalogger.app.ui.components

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.instalogger.app.InstaLoggerApp
import com.instalogger.app.R
import com.instalogger.app.ui.theme.AccentBlue
import com.instalogger.app.ui.theme.ShapeTokens
import com.instalogger.app.ui.theme.StatusAmber
import com.instalogger.app.ui.theme.StatusGreen
import com.instalogger.app.util.ConfigBackupManager
import com.instalogger.app.util.LanguageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class BackupSheetTab { EXPORT, IMPORT }

private enum class PendingExportType { FULL_JSON, TARGETS_JSON, TARGETS_CSV }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupExportImportBottomSheet(
    onDismissRequest: () -> Unit,
    onCompletedMessage: (String) -> Unit = {}
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        LanguageManager.ProvideLocalizedContext {
            BackupExportImportSheetContent(
                onDismiss = onDismissRequest,
                onCompletedMessage = onCompletedMessage
            )
        }
    }
}

@Composable
private fun BackupExportImportSheetContent(
    onDismiss: () -> Unit,
    onCompletedMessage: (String) -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as InstaLoggerApp
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current

    val targets by app.database.targetDao().getAllTargets().collectAsStateWithLifecycle(initialValue = emptyList())

    var activeTab by remember { mutableStateOf(BackupSheetTab.EXPORT) }
    var pendingExportType by remember { mutableStateOf<PendingExportType?>(null) }
    var isWorking by remember { mutableStateOf(false) }

    var statusBanner by remember { mutableStateOf<Pair<Boolean, String>?>(null) } // isSuccess to message

    // Import state
    var importPreview by remember { mutableStateOf<ConfigBackupManager.ImportPreview?>(null) }
    var optTargets by remember { mutableStateOf(true) }
    var optSettings by remember { mutableStateOf(true) }
    var optNotifRules by remember { mutableStateOf(true) }
    var optAppearance by remember { mutableStateOf(true) }
    var mergeExisting by remember { mutableStateOf(true) }

    val msgExportSuccess = stringResource(R.string.backup_export_saved_msg)
    val msgExportError = stringResource(R.string.backup_export_failed_msg)
    val msgNoTargets = stringResource(R.string.backup_no_targets_msg)
    val msgInvalidFile = stringResource(R.string.backup_invalid_file_msg)
    val msgImportDone = stringResource(R.string.backup_import_done_msg)
    val shareChooserTitle = stringResource(R.string.backup_share_chooser_title)

    fun generateExportContent(type: PendingExportType): Triple<String, String, String> {
        return when (type) {
            PendingExportType.FULL_JSON -> Triple(
                ConfigBackupManager.buildSuggestedFileName("instalogger-backup", "json"),
                ConfigBackupManager.exportFullBackupJson(context, app, targets),
                "application/json"
            )
            PendingExportType.TARGETS_JSON -> Triple(
                ConfigBackupManager.buildSuggestedFileName("instalogger-targets", "json"),
                ConfigBackupManager.exportTargetsJson(targets),
                "application/json"
            )
            PendingExportType.TARGETS_CSV -> Triple(
                ConfigBackupManager.buildSuggestedFileName("instalogger-targets", "csv"),
                ConfigBackupManager.exportTargetsCsv(targets),
                "text/csv"
            )
        }
    }

    val createJsonLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        val type = pendingExportType
        pendingExportType = null
        if (uri != null && type != null) {
            scope.launch {
                isWorking = true
                val ok = withContext(Dispatchers.IO) {
                    val (_, content, _) = generateExportContent(type)
                    ConfigBackupManager.writeTextToUri(context, uri, content)
                }
                isWorking = false
                if (ok) {
                    statusBanner = true to msgExportSuccess
                    onCompletedMessage(msgExportSuccess)
                } else {
                    statusBanner = false to msgExportError
                }
            }
        }
    }

    val createCsvLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv")
    ) { uri: Uri? ->
        val type = pendingExportType
        pendingExportType = null
        if (uri != null && type != null) {
            scope.launch {
                isWorking = true
                val ok = withContext(Dispatchers.IO) {
                    val (_, content, _) = generateExportContent(type)
                    ConfigBackupManager.writeTextToUri(context, uri, content)
                }
                isWorking = false
                if (ok) {
                    statusBanner = true to msgExportSuccess
                    onCompletedMessage(msgExportSuccess)
                } else {
                    statusBanner = false to msgExportError
                }
            }
        }
    }

    val openImportFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                isWorking = true
                statusBanner = null
                val parsed = withContext(Dispatchers.IO) {
                    val text = ConfigBackupManager.readTextFromUri(context, uri) ?: return@withContext null
                    val displayName = resolveFileName(context, uri) ?: "backup.json"
                    ConfigBackupManager.inspectImport(text, displayName, targets)
                }
                isWorking = false
                if (parsed == null) {
                    importPreview = null
                    statusBanner = false to msgInvalidFile
                } else {
                    importPreview = parsed
                    optTargets = parsed.targets.isNotEmpty()
                    optSettings = parsed.settingsKeyCount > 0
                    optNotifRules = parsed.notificationRuleCount > 0
                    optAppearance = parsed.hasAppearance
                    mergeExisting = true
                }
            }
        }
    }

    fun launchSave(type: PendingExportType) {
        if ((type == PendingExportType.TARGETS_JSON || type == PendingExportType.TARGETS_CSV) && targets.isEmpty()) {
            statusBanner = false to msgNoTargets
            return
        }
        statusBanner = null
        pendingExportType = type
        when (type) {
            PendingExportType.FULL_JSON -> createJsonLauncher.launch(
                ConfigBackupManager.buildSuggestedFileName("instalogger-backup", "json")
            )
            PendingExportType.TARGETS_JSON -> createJsonLauncher.launch(
                ConfigBackupManager.buildSuggestedFileName("instalogger-targets", "json")
            )
            PendingExportType.TARGETS_CSV -> createCsvLauncher.launch(
                ConfigBackupManager.buildSuggestedFileName("instalogger-targets", "csv")
            )
        }
    }

    fun launchShare(type: PendingExportType) {
        if ((type == PendingExportType.TARGETS_JSON || type == PendingExportType.TARGETS_CSV) && targets.isEmpty()) {
            statusBanner = false to msgNoTargets
            return
        }
        scope.launch {
            isWorking = true
            statusBanner = null
            val intent = withContext(Dispatchers.IO) {
                val (fileName, content, mime) = generateExportContent(type)
                ConfigBackupManager.createShareIntent(context, fileName, content, mime)
            }
            isWorking = false
            if (intent != null) {
                runCatching {
                    context.startActivity(Intent.createChooser(intent, shareChooserTitle))
                }.onFailure {
                    statusBanner = false to msgExportError
                }
            } else {
                statusBanner = false to msgExportError
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(AccentBlue.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.ImportExport,
                    contentDescription = null,
                    tint = AccentBlue,
                    modifier = Modifier.size(22.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.backup_sheet_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(R.string.backup_sheet_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .testTag("backup_sheet_close_btn")
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.dialog_cancel),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        // Export / Import Segmented Switcher
        AnimatedSegmentedSelector(
            options = listOf(
                SegmentedOption(stringResource(R.string.backup_tab_export)),
                SegmentedOption(stringResource(R.string.backup_tab_import))
            ),
            selectedIndex = if (activeTab == BackupSheetTab.EXPORT) 0 else 1,
            onSelected = { idx ->
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                activeTab = if (idx == 0) BackupSheetTab.EXPORT else BackupSheetTab.IMPORT
                statusBanner = null
            }
        )

        // Status Feedback Banner
        AnimatedVisibility(
            visible = statusBanner != null,
            enter = fadeIn(tween(200)) + expandVertically(tween(220)),
            exit = fadeOut(tween(150)) + shrinkVertically(tween(180))
        ) {
            statusBanner?.let { (ok, message) ->
                val tint = if (ok) StatusGreen else MaterialTheme.colorScheme.error
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = tint.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, tint.copy(alpha = 0.3f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = if (ok) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
                            contentDescription = null,
                            tint = tint,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        AnimatedContent(
            targetState = activeTab,
            transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(150)) },
            label = "backup_tab_content"
        ) { tab ->
            if (tab == BackupSheetTab.EXPORT) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // Security Note
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = StatusGreen.copy(alpha = 0.09f),
                        border = BorderStroke(0.75.dp, StatusGreen.copy(alpha = 0.25f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.VerifiedUser,
                                contentDescription = null,
                                tint = StatusGreen,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = stringResource(R.string.backup_security_note),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // Card 1: Full App Configuration Backup
                    BackupActionCard(
                        icon = Icons.Default.SettingsBackupRestore,
                        iconTint = AccentBlue,
                        title = stringResource(R.string.backup_full_export_title),
                        subtitle = stringResource(R.string.backup_full_export_desc),
                        badgeText = "JSON"
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { launchSave(PendingExportType.FULL_JSON) },
                                enabled = !isWorking,
                                shape = ShapeTokens.MediumShape,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp)
                                    .testTag("backup_export_full_save_btn")
                            ) {
                                Icon(Icons.Default.SaveAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = stringResource(R.string.backup_btn_save),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            OutlinedButton(
                                onClick = { launchShare(PendingExportType.FULL_JSON) },
                                enabled = !isWorking,
                                shape = ShapeTokens.MediumShape,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp)
                                    .testTag("backup_export_full_share_btn")
                            ) {
                                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = stringResource(R.string.backup_btn_share),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    // Card 2: Target List Export (JSON & CSV)
                    BackupActionCard(
                        icon = Icons.Default.PeopleAlt,
                        iconTint = Color(0xFF8B5CF6),
                        title = stringResource(R.string.backup_targets_export_title),
                        subtitle = stringResource(R.string.backup_targets_export_desc),
                        badgeText = "${targets.size}"
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                FilledTonalButton(
                                    onClick = { launchSave(PendingExportType.TARGETS_JSON) },
                                    enabled = !isWorking,
                                    shape = ShapeTokens.MediumShape,
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(44.dp)
                                        .testTag("backup_export_targets_json_btn")
                                ) {
                                    Icon(Icons.Default.DataObject, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("JSON", maxLines = 1)
                                }
                                FilledTonalButton(
                                    onClick = { launchSave(PendingExportType.TARGETS_CSV) },
                                    enabled = !isWorking,
                                    shape = ShapeTokens.MediumShape,
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(44.dp)
                                        .testTag("backup_export_targets_csv_btn")
                                ) {
                                    Icon(Icons.Default.TableChart, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("CSV", maxLines = 1)
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = { launchShare(PendingExportType.TARGETS_JSON) },
                                    enabled = !isWorking,
                                    shape = ShapeTokens.MediumShape,
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(40.dp)
                                        .testTag("backup_share_targets_json_btn")
                                ) {
                                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(5.dp))
                                    Text(
                                        text = stringResource(R.string.backup_btn_share_json),
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                OutlinedButton(
                                    onClick = { launchShare(PendingExportType.TARGETS_CSV) },
                                    enabled = !isWorking,
                                    shape = ShapeTokens.MediumShape,
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(40.dp)
                                        .testTag("backup_share_targets_csv_btn")
                                ) {
                                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(5.dp))
                                    Text(
                                        text = stringResource(R.string.backup_btn_share_csv),
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                // IMPORT TAB
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        border = BorderStroke(1.dp, AccentBlue.copy(alpha = 0.35f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .clickable(enabled = !isWorking) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                openImportFileLauncher.launch(
                                    arrayOf(
                                        "application/json",
                                        "text/csv",
                                        "text/comma-separated-values",
                                        "text/plain",
                                        "*/*"
                                    )
                                )
                            }
                            .testTag("backup_pick_import_file_btn")
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(46.dp)
                                    .clip(CircleShape)
                                    .background(AccentBlue.copy(alpha = 0.14f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.UploadFile,
                                    contentDescription = null,
                                    tint = AccentBlue,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                            Text(
                                text = stringResource(R.string.backup_pick_file_title),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = stringResource(R.string.backup_pick_file_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // Preview Panel
                    AnimatedVisibility(
                        visible = importPreview != null,
                        enter = fadeIn(tween(220)) + expandVertically(tween(240)),
                        exit = fadeOut(tween(150)) + shrinkVertically(tween(180))
                    ) {
                        importPreview?.let { preview ->
                            val formatLabel = when (preview.kind) {
                                ConfigBackupManager.BackupKind.FULL_BACKUP_JSON -> stringResource(R.string.backup_format_full)
                                ConfigBackupManager.BackupKind.TARGETS_JSON -> stringResource(R.string.backup_format_targets_json)
                                ConfigBackupManager.BackupKind.TARGETS_CSV -> stringResource(R.string.backup_format_targets_csv)
                            }
                            Surface(
                                shape = RoundedCornerShape(18.dp),
                                color = MaterialTheme.colorScheme.surfaceContainer,
                                border = BorderStroke(0.75.dp, MaterialTheme.colorScheme.outlineVariant),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = stringResource(R.string.backup_preview_title),
                                                style = MaterialTheme.typography.titleSmall,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Text(
                                                text = preview.fileName,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        Surface(
                                            shape = ShapeTokens.PillShape,
                                            color = AccentBlue.copy(alpha = 0.14f)
                                        ) {
                                            Text(
                                                text = formatLabel,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = AccentBlue,
                                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                                            )
                                        }
                                    }

                                    // Summary Pills
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        if (preview.targets.isNotEmpty()) {
                                            PreviewStatPill(
                                                label = stringResource(
                                                    R.string.backup_preview_targets_fmt,
                                                    preview.targets.size,
                                                    preview.newTargetsCount
                                                ),
                                                tint = StatusGreen,
                                                modifier = Modifier.weight(1f)
                                            )
                                        }
                                        if (preview.settingsKeyCount > 0) {
                                            PreviewStatPill(
                                                label = stringResource(
                                                    R.string.backup_preview_settings_fmt,
                                                    preview.settingsKeyCount
                                                ),
                                                tint = AccentBlue,
                                                modifier = Modifier.weight(1f)
                                            )
                                        }
                                        if (preview.notificationRuleCount > 0) {
                                            PreviewStatPill(
                                                label = stringResource(
                                                    R.string.backup_preview_rules_fmt,
                                                    preview.notificationRuleCount
                                                ),
                                                tint = StatusAmber,
                                                modifier = Modifier.weight(1f)
                                            )
                                        }
                                    }

                                    // Selective Checkboxes
                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        if (preview.targets.isNotEmpty()) {
                                            ImportOptionCheckRow(
                                                label = stringResource(R.string.backup_opt_targets),
                                                checked = optTargets,
                                                onCheckedChange = { optTargets = it }
                                            )
                                        }
                                        if (preview.settingsKeyCount > 0) {
                                            ImportOptionCheckRow(
                                                label = stringResource(R.string.backup_opt_settings),
                                                checked = optSettings,
                                                onCheckedChange = { optSettings = it }
                                            )
                                        }
                                        if (preview.notificationRuleCount > 0) {
                                            ImportOptionCheckRow(
                                                label = stringResource(R.string.backup_opt_rules),
                                                checked = optNotifRules,
                                                onCheckedChange = { optNotifRules = it }
                                            )
                                        }
                                        if (preview.hasAppearance) {
                                            ImportOptionCheckRow(
                                                label = stringResource(R.string.backup_opt_appearance),
                                                checked = optAppearance,
                                                onCheckedChange = { optAppearance = it }
                                            )
                                        }
                                    }

                                    // Conflict Mode Selector
                                    if (optTargets && preview.existingTargetsCount > 0) {
                                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Text(
                                                text = stringResource(R.string.backup_conflict_label),
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            AnimatedSegmentedSelector(
                                                options = listOf(
                                                    SegmentedOption(stringResource(R.string.backup_conflict_merge)),
                                                    SegmentedOption(stringResource(R.string.backup_conflict_skip))
                                                ),
                                                selectedIndex = if (mergeExisting) 0 else 1,
                                                onSelected = { idx -> mergeExisting = idx == 0 }
                                            )
                                        }
                                    }

                                    val canApply = (optTargets && preview.targets.isNotEmpty()) ||
                                        (optSettings && preview.settingsKeyCount > 0) ||
                                        (optNotifRules && preview.notificationRuleCount > 0) ||
                                        (optAppearance && preview.hasAppearance)

                                    Button(
                                        onClick = {
                                            scope.launch {
                                                isWorking = true
                                                val result = withContext(Dispatchers.IO) {
                                                    ConfigBackupManager.applyImport(
                                                        context = context,
                                                        app = app,
                                                        preview = preview,
                                                        options = ConfigBackupManager.ImportOptions(
                                                            importTargets = optTargets,
                                                            importSettings = optSettings,
                                                            importNotificationRules = optNotifRules,
                                                            importAppearance = optAppearance,
                                                            mergeExistingTargets = mergeExisting
                                                        )
                                                    )
                                                }
                                                isWorking = false
                                                importPreview = null
                                                val summaryMsg = "$msgImportDone (+${result.targetsAdded}, ~${result.targetsUpdated}, ${result.settingsApplied + result.rulesApplied})"
                                                statusBanner = true to summaryMsg
                                                onCompletedMessage(summaryMsg)
                                            }
                                        },
                                        enabled = canApply && !isWorking,
                                        shape = ShapeTokens.MediumShape,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(48.dp)
                                            .testTag("backup_apply_import_btn")
                                    ) {
                                        if (isWorking) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(18.dp),
                                                strokeWidth = 2.dp,
                                                color = MaterialTheme.colorScheme.onPrimary
                                            )
                                            Spacer(Modifier.width(8.dp))
                                        } else {
                                            Icon(Icons.Default.DownloadDone, contentDescription = null, modifier = Modifier.size(18.dp))
                                            Spacer(Modifier.width(8.dp))
                                        }
                                        Text(
                                            text = stringResource(R.string.backup_btn_apply_import),
                                            fontWeight = FontWeight.SemiBold
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

@Composable
private fun BackupActionCard(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    subtitle: String,
    badgeText: String,
    actions: @Composable () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(0.75.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(iconTint.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(20.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Surface(
                    shape = ShapeTokens.PillShape,
                    color = iconTint.copy(alpha = 0.14f)
                ) {
                    Text(
                        text = badgeText,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = iconTint,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp)
                    )
                }
            }
            actions()
        }
    }
}

@Composable
private fun PreviewStatPill(
    label: String,
    tint: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = tint.copy(alpha = 0.12f),
        modifier = modifier
    ) {
        Text(
            text = label,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun ImportOptionCheckRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

private fun resolveFileName(context: android.content.Context, uri: Uri): String? {
    return runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')
}
