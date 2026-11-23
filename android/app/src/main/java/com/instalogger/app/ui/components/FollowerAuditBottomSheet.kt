package com.instalogger.app.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.instalogger.app.R
import com.instalogger.app.data.local.FollowerAuditEntity
import com.instalogger.app.engine.InstagramUserSummary
import com.instalogger.app.ui.animation.bouncyClickable
import com.instalogger.app.ui.theme.AccentBlue
import com.instalogger.app.ui.theme.ShapeTokens
import com.instalogger.app.util.ArchiveDirectoryManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

private val RedLost = Color(0xFFEF4444)
private val OrangeLost = Color(0xFFF97316)
private val GreenGained = Color(0xFF10B981)
private val BlueGained = AccentBlue

data class FilterPill(
    val eventType: String,
    val titleRes: Int,
    val color: Color,
    val icon: ImageVector
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FollowerAuditBottomSheet(
    targetUsername: String,
    initialTab: Int = 0,
    initialFilter: String = "follower_lost",
    audits: List<FollowerAuditEntity>,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as com.instalogger.app.InstaLoggerApp
    val scope = rememberCoroutineScope()

    var selectedTab by remember { mutableIntStateOf(initialTab) }
    var selectedFilter by remember { mutableStateOf(initialFilter) }
    var searchQuery by remember { mutableStateOf("") }

    var followersList by remember { mutableStateOf<List<InstagramUserSummary>>(emptyList()) }
    var followingList by remember { mutableStateOf<List<InstagramUserSummary>>(emptyList()) }
    var isLoadingLists by remember { mutableStateOf(true) }
    var isAuditingLive by remember { mutableStateOf(false) }

    val gson = remember { Gson() }

    suspend fun loadListsFromDisk() {
        val type = object : TypeToken<List<InstagramUserSummary>>() {}.type

        // 1. Read followers.json
        val followersJson = ArchiveDirectoryManager.getUserArchiveJson(context, targetUsername, "followers.json")
            ?: File("/storage/emulated/0/instalogger/archives/$targetUsername/followers.json").takeIf { it.exists() }?.readText()
        val parsedFollowers: List<InstagramUserSummary> = if (!followersJson.isNullOrBlank()) {
            try { gson.fromJson(followersJson, type) ?: emptyList() } catch (_: Exception) { emptyList() }
        } else emptyList()

        // 2. Read following.json
        val followingJson = ArchiveDirectoryManager.getUserArchiveJson(context, targetUsername, "following.json")
            ?: File("/storage/emulated/0/instalogger/archives/$targetUsername/following.json").takeIf { it.exists() }?.readText()
        val parsedFollowing: List<InstagramUserSummary> = if (!followingJson.isNullOrBlank()) {
            try { gson.fromJson(followingJson, type) ?: emptyList() } catch (_: Exception) { emptyList() }
        } else emptyList()

        withContext(Dispatchers.Main) {
            followersList = parsedFollowers
            followingList = parsedFollowing
            isLoadingLists = false
        }
    }

    LaunchedEffect(targetUsername) {
        isLoadingLists = true
        withContext(Dispatchers.IO) {
            loadListsFromDisk()
        }
    }

    fun triggerLiveFollowerFetch() {
        if (isAuditingLive) return
        scope.launch {
            isAuditingLive = true
            withContext(Dispatchers.IO) {
                try {
                    val targetEntity = app.database.targetDao().getTarget(targetUsername)
                    val cookie = app.sessionManager.getCookieString()
                    val targetId = targetEntity?.instagramId ?: targetUsername
                    val freshFollowers = app.instagramEngine.fetchFollowersList(targetId, cookie, limit = 100)
                    if (freshFollowers.isNotEmpty()) {
                        ArchiveDirectoryManager.saveUserArchiveJson(context, targetUsername, "followers.json", gson.toJson(freshFollowers))
                    }
                    val freshFollowing = app.instagramEngine.fetchFollowingList(targetId, cookie, limit = 100)
                    if (freshFollowing.isNotEmpty()) {
                        ArchiveDirectoryManager.saveUserArchiveJson(context, targetUsername, "following.json", gson.toJson(freshFollowing))
                    }
                    loadListsFromDisk()
                } catch (_: Exception) {}
            }
            isAuditingLive = false
        }
    }

    val filterPills = listOf(
        FilterPill("follower_lost", R.string.filter_unfollowers, RedLost, Icons.Default.PersonRemove),
        FilterPill("following_lost", R.string.filter_unfollowed_by_target, OrangeLost, Icons.Default.PersonOff),
        FilterPill("follower_gained", R.string.filter_new_followers, GreenGained, Icons.Default.PersonAdd),
        FilterPill("following_gained", R.string.filter_new_following, BlueGained, Icons.Default.GroupAdd)
    )

    val filteredAudits = remember(audits, selectedFilter) {
        audits.filter { it.eventType == selectedFilter }
    }

    val filteredFollowers = remember(followersList, searchQuery) {
        if (searchQuery.isBlank()) followersList
        else followersList.filter {
            it.username.contains(searchQuery.trim(), ignoreCase = true) ||
            it.fullName.contains(searchQuery.trim(), ignoreCase = true)
        }
    }

    val filteredFollowing = remember(followingList, searchQuery) {
        if (searchQuery.isBlank()) followingList
        else followingList.filter {
            it.username.contains(searchQuery.trim(), ignoreCase = true) ||
            it.fullName.contains(searchQuery.trim(), ignoreCase = true)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.88f)
                .padding(bottom = 16.dp)
        ) {
            // Sheet Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = stringResource(R.string.audit_sheet_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "@$targetUsername",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    RotatingRefreshButton(
                        isRefreshing = isAuditingLive || isLoadingLists,
                        onClick = { triggerLiveFollowerFetch() }
                    )

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Main 3-Tab Navigator (Followers, Following, Changes)
            SlidingPillTabRow(
                tabs = listOf(
                    TabItem(
                        title = stringResource(R.string.tab_followers_list, followersList.size),
                        icon = Icons.Default.People
                    ),
                    TabItem(
                        title = stringResource(R.string.tab_following_list, followingList.size),
                        icon = Icons.Default.PersonAdd
                    ),
                    TabItem(
                        title = stringResource(R.string.tab_audit_diffs, audits.size),
                        icon = Icons.Default.History
                    )
                ),
                selectedIndex = selectedTab,
                onTabSelected = { selectedTab = it },
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            Spacer(modifier = Modifier.height(12.dp))

            when (selectedTab) {
                0 -> {
                    // Followers Tab
                    UserListTabContent(
                        users = filteredFollowers,
                        searchQuery = searchQuery,
                        onSearchChange = { searchQuery = it },
                        emptyTitle = stringResource(R.string.audit_empty_title),
                        emptyDesc = stringResource(R.string.empty_followers_desc),
                        isLoading = isLoadingLists || isAuditingLive,
                        onRefresh = { triggerLiveFollowerFetch() },
                        context = context
                    )
                }
                1 -> {
                    // Following Tab
                    UserListTabContent(
                        users = filteredFollowing,
                        searchQuery = searchQuery,
                        onSearchChange = { searchQuery = it },
                        emptyTitle = stringResource(R.string.audit_empty_title),
                        emptyDesc = stringResource(R.string.empty_following_desc),
                        isLoading = isLoadingLists || isAuditingLive,
                        onRefresh = { triggerLiveFollowerFetch() },
                        context = context
                    )
                }
                2 -> {
                    // Changes / Audit Diffs Tab
                    AuditDiffsTabContent(
                        filterPills = filterPills,
                        selectedFilter = selectedFilter,
                        onFilterSelect = { selectedFilter = it },
                        audits = audits,
                        filteredAudits = filteredAudits,
                        context = context
                    )
                }
            }
        }
    }
}

@Composable
private fun UserListTabContent(
    users: List<InstagramUserSummary>,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    emptyTitle: String,
    emptyDesc: String,
    isLoading: Boolean,
    onRefresh: () -> Unit,
    context: Context
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Search Field
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            placeholder = { Text(stringResource(R.string.search_users_placeholder), fontSize = 14.sp) },
            leadingIcon = {
                Icon(Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { onSearchChange("") }) {
                        Icon(Icons.Default.Clear, contentDescription = "Clear", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            },
            singleLine = true,
            shape = ShapeTokens.PillShape,
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                focusedBorderColor = AccentBlue,
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
            )
        )

        Spacer(modifier = Modifier.height(8.dp))

        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = AccentBlue)
            }
        } else if (users.isEmpty()) {
            UnifiedEmptyState(
                title = emptyTitle,
                description = emptyDesc,
                icon = Icons.Default.Group,
                actionLabel = stringResource(R.string.action_refresh_list),
                actionIcon = Icons.Default.Refresh,
                onActionClick = onRefresh,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(users, key = { it.username }) { user ->
                    UserSummaryCard(
                        user = user,
                        onCopyUsername = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = ClipData.newPlainText("username", user.username)
                            clipboard.setPrimaryClip(clip)
                            Toast.makeText(context, context.getString(R.string.msg_username_copied, user.username), Toast.LENGTH_SHORT).show()
                        },
                        onOpenInstagram = {
                            val uri = Uri.parse("https://instagram.com/${user.username}")
                            val intent = Intent(Intent.ACTION_VIEW, uri)
                            try {
                                context.startActivity(intent)
                            } catch (_: Exception) {
                                Toast.makeText(context, "Instagram açıla bilmədi", Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun UserSummaryCard(
    user: InstagramUserSummary,
    onCopyUsername: () -> Unit,
    onOpenInstagram: () -> Unit
) {
    val cardShape = ShapeTokens.MediumShape
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(cardShape)
            .border(ShapeTokens.BorderSubtle, MaterialTheme.colorScheme.outlineVariant, cardShape),
        shape = cardShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // User Avatar
            if (!user.profilePicUrl.isNullOrBlank()) {
                AsyncImage(
                    model = user.profilePicUrl,
                    contentDescription = user.username,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .border(ShapeTokens.BorderSubtle, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            // User Info
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "@${user.username}",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (user.isPrivate) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "Private",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(13.dp)
                        )
                    }
                }

                if (user.fullName.isNotBlank()) {
                    Text(
                        text = user.fullName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Action Buttons
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(
                    onClick = onCopyUsername,
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "Copy",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(17.dp)
                    )
                }

                IconButton(
                    onClick = onOpenInstagram,
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.OpenInNew,
                        contentDescription = "Open",
                        tint = AccentBlue,
                        modifier = Modifier.size(17.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun AuditDiffsTabContent(
    filterPills: List<FilterPill>,
    selectedFilter: String,
    onFilterSelect: (String) -> Unit,
    audits: List<FollowerAuditEntity>,
    filteredAudits: List<FollowerAuditEntity>,
    context: Context
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Filter Tabs (Horizontal scrolling chips)
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(filterPills) { pill ->
                val isSelected = selectedFilter == pill.eventType
                val count = audits.count { it.eventType == pill.eventType }

                Surface(
                    modifier = Modifier
                        .clip(ShapeTokens.PillShape)
                        .bouncyClickable(pressScale = 0.94f) { onFilterSelect(pill.eventType) },
                    shape = ShapeTokens.PillShape,
                    color = if (isSelected) pill.color.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceContainerHigh,
                    border = if (isSelected) {
                        androidx.compose.foundation.BorderStroke(1.5.dp, pill.color)
                    } else {
                        androidx.compose.foundation.BorderStroke(ShapeTokens.BorderSubtle, MaterialTheme.colorScheme.outlineVariant)
                    }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = pill.icon,
                            contentDescription = null,
                            tint = if (isSelected) pill.color else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = stringResource(pill.titleRes),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = if (isSelected) pill.color else MaterialTheme.colorScheme.onSurface
                        )
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(if (isSelected) pill.color else MaterialTheme.colorScheme.outlineVariant)
                                .padding(horizontal = 6.dp, vertical = 1.dp)
                        ) {
                            Text(
                                text = "$count",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        if (filteredAudits.isEmpty()) {
            val currentPill = filterPills.firstOrNull { it.eventType == selectedFilter }
            UnifiedEmptyState(
                title = stringResource(R.string.audit_empty_title),
                description = stringResource(R.string.audit_empty_desc),
                icon = currentPill?.icon ?: Icons.Default.Group,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(filteredAudits, key = { it.id }) { audit ->
                    FollowerAuditItemCard(
                        audit = audit,
                        onCopyUsername = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = ClipData.newPlainText("username", audit.username)
                            clipboard.setPrimaryClip(clip)
                            Toast.makeText(context, context.getString(R.string.msg_username_copied, audit.username), Toast.LENGTH_SHORT).show()
                        },
                        onOpenInstagram = {
                            val uri = Uri.parse("https://instagram.com/${audit.username}")
                            val intent = Intent(Intent.ACTION_VIEW, uri)
                            try {
                                context.startActivity(intent)
                            } catch (_: Exception) {
                                Toast.makeText(context, "Instagram açıla bilmədi", Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun FollowerAuditItemCard(
    audit: FollowerAuditEntity,
    onCopyUsername: () -> Unit,
    onOpenInstagram: () -> Unit
) {
    val cardShape = ShapeTokens.MediumShape
    val timeFormat = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
    val formattedDate = timeFormat.format(Date(audit.timestamp))

    val badgeColor = when (audit.eventType) {
        "follower_lost" -> RedLost
        "following_lost" -> OrangeLost
        "follower_gained" -> GreenGained
        else -> BlueGained
    }

    val badgeIcon = when (audit.eventType) {
        "follower_lost" -> Icons.Default.Remove
        "following_lost" -> Icons.Default.ArrowForward
        "follower_gained" -> Icons.Default.Add
        else -> Icons.Default.Done
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(cardShape)
            .border(ShapeTokens.BorderSubtle, MaterialTheme.colorScheme.outlineVariant, cardShape),
        shape = cardShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Avatar with event badge
            Box(
                modifier = Modifier.size(52.dp),
                contentAlignment = Alignment.BottomEnd
            ) {
                val localFile = audit.localAvatarPath?.let { File(it) }
                val avatarModel: Any? = when {
                    localFile != null && localFile.exists() && localFile.length() > 0L -> localFile
                    !audit.profilePicUrl.isNullOrBlank() -> audit.profilePicUrl
                    else -> null
                }

                if (avatarModel != null) {
                    AsyncImage(
                        model = avatarModel,
                        contentDescription = audit.username,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(CircleShape)
                            .border(ShapeTokens.BorderSubtle, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }

                // Small circular status pill on bottom-right of avatar
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(badgeColor)
                        .border(1.5.dp, MaterialTheme.colorScheme.surfaceContainer, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = badgeIcon,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(11.dp)
                    )
                }
            }

            // User Info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "@${audit.username}",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                if (audit.fullName.isNotBlank()) {
                    Text(
                        text = audit.fullName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.height(2.dp))

                Text(
                    text = formattedDate,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    fontSize = 11.sp
                )
            }

            // Quick Actions
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(
                    onClick = onCopyUsername,
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "Copy",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }

                IconButton(
                    onClick = onOpenInstagram,
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.OpenInNew,
                        contentDescription = "Open",
                        tint = AccentBlue,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}
