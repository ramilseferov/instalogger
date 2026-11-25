package com.instalogger.app.engine.comments

import com.instalogger.app.data.local.PostCommentEntity
import java.math.BigInteger
import java.util.Locale

enum class CommentSortOrder {
    MOST_LIKED,
    NEWEST,
    OLDEST,
    MOST_REPLIES,
    AUTHOR_AZ
}

enum class CommentFilterMode {
    ALL,
    WITH_REPLIES,
    TOP_LEVEL_ONLY,
    VERIFIED_ONLY
}

data class CommentQueryOptions(
    val searchQuery: String = "",
    val sortOrder: CommentSortOrder = CommentSortOrder.MOST_LIKED,
    val filterMode: CommentFilterMode = CommentFilterMode.ALL,
    val minLikes: Int = 0,
    val authorFilter: String? = null
)

data class CommentThreadNode(
    val comment: PostCommentEntity,
    val replies: List<PostCommentEntity> = emptyList(),
    val matchedSelf: Boolean = false,
    val matchedReplyCount: Int = 0
) {
    val totalReplyCount: Int
        get() = maxOf(comment.replyCount, replies.size)
}

data class CommentTreeStats(
    val totalCount: Int,
    val topLevelCount: Int,
    val replyCount: Int,
    val uniqueAuthorsCount: Int,
    val collectionStatus: String // "complete", "partial", or "empty"
)

object CommentTreeEngine {

    private const val IG_SHORTCODE_ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    /**
     * Deterministically converts an Instagram post/reel shortcode into its numeric 64-bit media ID
     * without requiring a network round-trip.
     */
    fun shortcodeToMediaId(shortcode: String): String? {
        val clean = shortcode.trim().substringBefore('/').substringBefore('?')
        if (clean.isBlank()) return null
        // Strip private-post suffix if longer than standard 11-char shortcode
        val base = if (clean.length > 11 && clean.length <= 28) clean.take(11) else clean
        var id = BigInteger.ZERO
        val radix = BigInteger.valueOf(64L)
        for (ch in base) {
            val idx = IG_SHORTCODE_ALPHABET.indexOf(ch)
            if (idx < 0) return null
            id = id.multiply(radix).add(BigInteger.valueOf(idx.toLong()))
        }
        return if (id > BigInteger.ZERO) id.toString() else null
    }

    /**
     * Groups flat [PostCommentEntity] records into a parent -> replies tree keyed by comment_id.
     * Orphan replies whose parent was not fetched yet are safely preserved as root nodes so no data is dropped.
     */
    fun buildCommentTree(comments: List<PostCommentEntity>): List<CommentThreadNode> {
        if (comments.isEmpty()) return emptyList()

        val byId = LinkedHashMap<String, PostCommentEntity>(comments.size)
        for (c in comments) {
            byId[c.id] = c
        }

        val repliesByParent = LinkedHashMap<String, MutableList<PostCommentEntity>>()
        val roots = mutableListOf<PostCommentEntity>()

        for (c in byId.values) {
            val parentId = c.parentCommentId?.takeIf { it.isNotBlank() && it != c.id }
            if (parentId != null && byId.containsKey(parentId)) {
                repliesByParent.getOrPut(parentId) { mutableListOf() }.add(c)
            } else {
                roots.add(c)
            }
        }

        // Flatten multi-level replies (reply to a reply) under their root parent while preserving chronological order
        val resolvedNodes = roots.map { root ->
            val collected = mutableListOf<PostCommentEntity>()
            val queue = ArrayDeque<String>()
            queue.add(root.id)
            val visited = hashSetOf(root.id)
            while (queue.isNotEmpty()) {
                val cur = queue.removeFirst()
                val direct = repliesByParent[cur].orEmpty()
                for (child in direct) {
                    if (visited.add(child.id)) {
                        collected.add(child)
                        queue.add(child.id)
                    }
                }
            }
            CommentThreadNode(
                comment = root,
                replies = collected.sortedBy { it.createdAt }
            )
        }
        return resolvedNodes
    }

    fun computeStats(
        comments: List<PostCommentEntity>,
        reportedTotalCount: Int = 0
    ): CommentTreeStats {
        if (comments.isEmpty()) {
            return CommentTreeStats(
                totalCount = 0,
                topLevelCount = 0,
                replyCount = 0,
                uniqueAuthorsCount = 0,
                collectionStatus = "empty"
            )
        }
        val byId = comments.associateBy { it.id }
        var topLevel = 0
        var replies = 0
        val authors = HashSet<String>()
        var anyPartial = false

        for (c in byId.values) {
            val isReply = !c.parentCommentId.isNullOrBlank() && c.parentCommentId != c.id && byId.containsKey(c.parentCommentId)
            if (isReply) replies++ else topLevel++
            if (c.authorUsername.isNotBlank()) {
                authors.add(c.authorUsername.lowercase(Locale.ROOT))
            }
            if (c.collectionStatus.equals("partial", ignoreCase = true)) {
                anyPartial = true
            }
        }

        val status = when {
            anyPartial -> "partial"
            reportedTotalCount > 0 && byId.size < reportedTotalCount -> "partial"
            else -> "complete"
        }

        return CommentTreeStats(
            totalCount = byId.size,
            topLevelCount = topLevel,
            replyCount = replies,
            uniqueAuthorsCount = authors.size,
            collectionStatus = status
        )
    }

    /**
     * Tree-aware search, filtering, and sorting:
     * - If a nested reply matches the search query / author filter / minLikes, the parent thread stays visible
     *   with matching replies kept and [CommentThreadNode.matchedReplyCount] set so the UI can auto-expand it.
     */
    fun queryTree(
        comments: List<PostCommentEntity>,
        options: CommentQueryOptions
    ): List<CommentThreadNode> {
        val rawTree = buildCommentTree(comments)
        if (rawTree.isEmpty()) return emptyList()

        val q = options.searchQuery.trim().lowercase(Locale.ROOT)
        val authorQ = options.authorFilter?.trim()?.trimStart('@')?.lowercase(Locale.ROOT).orEmpty()
        val hasTextQuery = q.isNotBlank()
        val hasAuthorFilter = authorQ.isNotBlank()

        fun matchesItem(c: PostCommentEntity): Boolean {
            if (options.minLikes > 0 && c.likeCount < options.minLikes) return false
            if (options.filterMode == CommentFilterMode.VERIFIED_ONLY && !c.isAuthorVerified) return false
            if (hasAuthorFilter && !c.authorUsername.equals(authorQ, ignoreCase = true)) return false
            if (hasTextQuery) {
                val cleanQ = q.removePrefix("@")
                val hit = c.text.lowercase(Locale.ROOT).contains(q) ||
                    c.authorUsername.lowercase(Locale.ROOT).contains(cleanQ) ||
                    c.authorFullName.lowercase(Locale.ROOT).contains(q) ||
                    c.id.lowercase(Locale.ROOT).contains(q)
                if (!hit) return false
            }
            return true
        }

        val filtered = mutableListOf<CommentThreadNode>()

        for (node in rawTree) {
            if (options.filterMode == CommentFilterMode.WITH_REPLIES && node.replies.isEmpty() && node.comment.replyCount == 0) {
                continue
            }

            val parentMatches = matchesItem(node.comment)
            val candidateReplies = if (options.filterMode == CommentFilterMode.TOP_LEVEL_ONLY) {
                emptyList()
            } else {
                node.replies
            }

            val matchingReplies = candidateReplies.filter { matchesItem(it) }
            val anyConstraint = hasTextQuery || hasAuthorFilter || options.minLikes > 0 || options.filterMode == CommentFilterMode.VERIFIED_ONLY

            val includeNode = when {
                !anyConstraint -> true
                parentMatches -> true
                matchingReplies.isNotEmpty() -> true
                else -> false
            }

            if (!includeNode) continue

            val shownReplies = when {
                options.filterMode == CommentFilterMode.TOP_LEVEL_ONLY -> emptyList()
                !anyConstraint -> candidateReplies
                parentMatches && !hasAuthorFilter && options.minLikes == 0 && options.filterMode != CommentFilterMode.VERIFIED_ONLY -> {
                    // Parent matched search text: keep all replies accessible while marking how many also matched
                    candidateReplies
                }
                matchingReplies.isNotEmpty() -> matchingReplies
                else -> emptyList()
            }

            val sortedReplies = when (options.sortOrder) {
                CommentSortOrder.MOST_LIKED -> shownReplies.sortedWith(
                    compareByDescending<PostCommentEntity> { it.likeCount }.thenBy { it.createdAt }
                )
                CommentSortOrder.NEWEST -> shownReplies.sortedByDescending { it.createdAt }
                else -> shownReplies.sortedBy { it.createdAt }
            }

            filtered.add(
                CommentThreadNode(
                    comment = node.comment,
                    replies = sortedReplies,
                    matchedSelf = hasTextQuery && parentMatches,
                    matchedReplyCount = if (hasTextQuery || hasAuthorFilter || options.minLikes > 0 || options.filterMode == CommentFilterMode.VERIFIED_ONLY) {
                        matchingReplies.size
                    } else 0
                )
            )
        }

        val comparator: Comparator<CommentThreadNode> = when (options.sortOrder) {
            CommentSortOrder.MOST_LIKED -> compareByDescending<CommentThreadNode> { it.comment.likeCount }
                .thenByDescending { it.comment.createdAt }
            CommentSortOrder.NEWEST -> compareByDescending<CommentThreadNode> { it.comment.createdAt }
            CommentSortOrder.OLDEST -> compareBy<CommentThreadNode> { it.comment.createdAt }
            CommentSortOrder.MOST_REPLIES -> compareByDescending<CommentThreadNode> { it.totalReplyCount }
                .thenByDescending { it.comment.likeCount }
            CommentSortOrder.AUTHOR_AZ -> compareBy<CommentThreadNode> { it.comment.authorUsername.lowercase(Locale.ROOT) }
                .thenByDescending { it.comment.createdAt }
        }

        return filtered.sortedWith(comparator)
    }
}
