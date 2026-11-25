package com.instalogger.app.engine.comments

import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.instalogger.app.util.ArchiveDirectoryManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

enum class CommentExportFormat(
    val extension: String,
    val mimeType: String,
    val label: String
) {
    JSON("json", "application/json", "JSON (Tree)"),
    CSV("csv", "text/csv", "CSV (Excel)"),
    TXT("txt", "text/plain", "TXT (Tree)"),
    MARKDOWN("md", "text/markdown", "Markdown (.md)")
}

data class CommentExportResult(
    val archiveFile: File,
    val shareUri: Uri,
    val mimeType: String,
    val exportedCount: Int
)

object CommentExporter {

    private val prettyGson = GsonBuilder().setPrettyPrinting().serializeNulls().create()

    private fun isoTimestamp(epochMs: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date(epochMs))
    }

    private fun humanTimestamp(epochMs: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        return sdf.format(Date(epochMs))
    }

    fun render(
        shortcode: String,
        mediaId: String?,
        targetUsername: String,
        nodes: List<CommentThreadNode>,
        stats: CommentTreeStats,
        format: CommentExportFormat
    ): String {
        return when (format) {
            CommentExportFormat.JSON -> renderJson(shortcode, mediaId, targetUsername, nodes, stats)
            CommentExportFormat.CSV -> renderCsv(nodes)
            CommentExportFormat.TXT -> renderTxt(shortcode, targetUsername, nodes, stats)
            CommentExportFormat.MARKDOWN -> renderMarkdown(shortcode, targetUsername, nodes, stats)
        }
    }

    fun renderJson(
        shortcode: String,
        mediaId: String?,
        targetUsername: String,
        nodes: List<CommentThreadNode>,
        stats: CommentTreeStats
    ): String {
        val root = JsonObject()
        root.addProperty("media_id", mediaId ?: CommentTreeEngine.shortcodeToMediaId(shortcode) ?: "")
        root.addProperty("shortcode", shortcode)
        root.addProperty("target_username", targetUsername)
        root.addProperty("exported_at", isoTimestamp(System.currentTimeMillis()))
        root.addProperty("collection_status", stats.collectionStatus)

        val statsObj = JsonObject().apply {
            addProperty("total_comments", stats.totalCount)
            addProperty("top_level_comments", stats.topLevelCount)
            addProperty("replies", stats.replyCount)
            addProperty("unique_authors", stats.uniqueAuthorsCount)
        }
        root.add("stats", statsObj)

        val commentsArr = JsonArray()
        for (node in nodes) {
            val c = node.comment
            val cObj = JsonObject().apply {
                addProperty("media_id", c.mediaId ?: mediaId ?: "")
                addProperty("shortcode", c.postId)
                addProperty("comment_id", c.id)
                if (c.parentCommentId.isNullOrBlank()) {
                    add("parent_comment_id", JsonNull.INSTANCE)
                } else {
                    addProperty("parent_comment_id", c.parentCommentId)
                }
                addProperty("author_username", c.authorUsername)
                addProperty("author_id", c.authorId ?: "")
                addProperty("author_full_name", c.authorFullName)
                addProperty("is_verified", c.isAuthorVerified)
                addProperty("text", c.text)
                addProperty("created_at", isoTimestamp(c.createdAt))
                addProperty("like_count", c.likeCount)
                addProperty("reply_count", node.totalReplyCount)
                addProperty("collection_status", c.collectionStatus)
            }

            val repliesArr = JsonArray()
            for (r in node.replies) {
                val rObj = JsonObject().apply {
                    addProperty("comment_id", r.id)
                    addProperty("parent_comment_id", r.parentCommentId ?: c.id)
                    addProperty("author_username", r.authorUsername)
                    addProperty("author_id", r.authorId ?: "")
                    addProperty("author_full_name", r.authorFullName)
                    addProperty("is_verified", r.isAuthorVerified)
                    addProperty("text", r.text)
                    addProperty("created_at", isoTimestamp(r.createdAt))
                    addProperty("like_count", r.likeCount)
                }
                repliesArr.add(rObj)
            }
            cObj.add("replies", repliesArr)
            commentsArr.add(cObj)
        }
        root.add("comments", commentsArr)
        return prettyGson.toJson(root)
    }

    fun renderCsv(nodes: List<CommentThreadNode>): String {
        val sb = StringBuilder()
        // UTF-8 BOM for proper Cyrillic/Azerbaijani character rendering in Excel
        sb.append('\uFEFF')
        sb.append("comment_id,parent_comment_id,depth,author_username,author_id,is_verified,created_at,like_count,reply_count,collection_status,text\n")

        fun esc(raw: String?): String {
            val s = raw.orEmpty()
            return if (s.contains(',') || s.contains('"') || s.contains('\n') || s.contains('\r')) {
                "\"" + s.replace("\"", "\"\"") + "\""
            } else {
                s
            }
        }

        for (node in nodes) {
            val c = node.comment
            sb.append(esc(c.id)).append(',')
                .append(esc(c.parentCommentId ?: "")).append(',')
                .append("0,")
                .append(esc(c.authorUsername)).append(',')
                .append(esc(c.authorId ?: "")).append(',')
                .append(c.isAuthorVerified).append(',')
                .append(esc(isoTimestamp(c.createdAt))).append(',')
                .append(c.likeCount).append(',')
                .append(node.totalReplyCount).append(',')
                .append(esc(c.collectionStatus)).append(',')
                .append(esc(c.text)).append('\n')

            for (r in node.replies) {
                sb.append(esc(r.id)).append(',')
                    .append(esc(r.parentCommentId ?: c.id)).append(',')
                    .append("1,")
                    .append(esc(r.authorUsername)).append(',')
                    .append(esc(r.authorId ?: "")).append(',')
                    .append(r.isAuthorVerified).append(',')
                    .append(esc(isoTimestamp(r.createdAt))).append(',')
                    .append(r.likeCount).append(',')
                    .append("0,")
                    .append(esc(r.collectionStatus)).append(',')
                    .append(esc(r.text)).append('\n')
            }
        }
        return sb.toString()
    }

    fun renderTxt(
        shortcode: String,
        targetUsername: String,
        nodes: List<CommentThreadNode>,
        stats: CommentTreeStats
    ): String {
        val sb = StringBuilder()
        sb.append("InstaLogger Comment Archive\n")
        sb.append("Post: https://www.instagram.com/p/").append(shortcode).append("/ (@").append(targetUsername).append(")\n")
        sb.append("Summary: total=").append(stats.totalCount)
            .append(", top_level=").append(stats.topLevelCount)
            .append(", replies=").append(stats.replyCount)
            .append(", authors=").append(stats.uniqueAuthorsCount)
            .append(", status=").append(stats.collectionStatus).append('\n')
        sb.append("==================================================================\n\n")

        for (node in nodes) {
            val c = node.comment
            sb.append("@").append(c.authorUsername)
            if (c.isAuthorVerified) sb.append(" [VERIFIED]")
            sb.append("  |  ").append(humanTimestamp(c.createdAt))
            sb.append("  |  likes: ").append(c.likeCount)
            if (node.totalReplyCount > 0) sb.append("  |  replies: ").append(node.totalReplyCount)
            sb.append('\n')
            sb.append("  ").append(c.text.replace("\n", "\n  ")).append('\n')

            node.replies.forEachIndexed { idx, r ->
                val branch = if (idx == node.replies.lastIndex) "└─" else "├─"
                sb.append("  ").append(branch).append(" @").append(r.authorUsername)
                if (r.isAuthorVerified) sb.append(" [VERIFIED]")
                sb.append(" (").append(humanTimestamp(r.createdAt)).append(", likes: ").append(r.likeCount).append(")\n")
                val pad = if (idx == node.replies.lastIndex) "     " else "  │  "
                sb.append(pad).append(r.text.replace("\n", "\n$pad")).append('\n')
            }
            sb.append('\n')
        }
        return sb.toString()
    }

    fun renderMarkdown(
        shortcode: String,
        targetUsername: String,
        nodes: List<CommentThreadNode>,
        stats: CommentTreeStats
    ): String {
        val sb = StringBuilder()
        sb.append("# Instagram Comments — @").append(targetUsername).append(" (`").append(shortcode).append("`)\n\n")
        sb.append("- **Post URL:** https://www.instagram.com/p/").append(shortcode).append("/\n")
        sb.append("- **Total Comments:** ").append(stats.totalCount)
            .append(" (Top-level: ").append(stats.topLevelCount)
            .append(", Replies: ").append(stats.replyCount)
            .append(", Authors: ").append(stats.uniqueAuthorsCount).append(")\n")
        sb.append("- **Collection Status:** `").append(stats.collectionStatus).append("`\n")
        sb.append("- **Exported At:** ").append(isoTimestamp(System.currentTimeMillis())).append("\n\n---\n\n")

        for (node in nodes) {
            val c = node.comment
            val ver = if (c.isAuthorVerified) " `(verified)`" else ""
            sb.append("### @").append(c.authorUsername).append(ver).append('\n')
            sb.append("*").append(humanTimestamp(c.createdAt)).append(" · ")
                .append(c.likeCount).append(" likes · ID `").append(c.id).append("`*\n\n")
            sb.append(c.text).append("\n\n")

            if (node.replies.isNotEmpty()) {
                for (r in node.replies) {
                    val rVer = if (r.isAuthorVerified) " `(verified)`" else ""
                    sb.append("> **@").append(r.authorUsername).append("**").append(rVer)
                        .append(" · *").append(humanTimestamp(r.createdAt))
                        .append(" · ").append(r.likeCount).append(" likes*\n")
                    r.text.lines().forEach { line ->
                        sb.append("> ").append(line).append('\n')
                    }
                    sb.append("\n")
                }
            }
            sb.append("---\n\n")
        }
        return sb.toString()
    }

    fun exportToShareableFile(
        context: Context,
        shortcode: String,
        mediaId: String?,
        targetUsername: String,
        nodes: List<CommentThreadNode>,
        stats: CommentTreeStats,
        format: CommentExportFormat
    ): CommentExportResult {
        val cleanUser = targetUsername.trim().trimStart('@').lowercase(Locale.ROOT).ifBlank { "unknown" }
        val cleanCode = shortcode.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val content = render(cleanCode, mediaId, cleanUser, nodes, stats, format)
        val fileName = "${cleanUser}_${cleanCode}_comments.${format.extension}"

        // 1. Save persistent archive copy inside user's archive directory
        val archiveDir = File(ArchiveDirectoryManager.getArchiveDirectory(context), "$cleanUser/comments").apply { mkdirs() }
        val archiveFile = File(archiveDir, fileName)
        archiveFile.writeText(content, Charsets.UTF_8)

        // Best-effort mirror to public Download/InstaLogger/comments if writable
        try {
            val dlDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "InstaLogger/comments")
            if (dlDir.exists() || dlDir.mkdirs()) {
                File(dlDir, fileName).writeText(content, Charsets.UTF_8)
            }
        } catch (_: Exception) {
            // Scoped storage fallback: archiveFile + ShareProvider still succeed cleanly
        }

        // 2. Write temporary shareable copy inside FileProvider's playback_tmp dir
        val shareDir = File(context.cacheDir, "playback_tmp").apply { mkdirs() }
        val shareFile = File(shareDir, fileName)
        shareFile.writeText(content, Charsets.UTF_8)
        val shareUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", shareFile)

        val count = nodes.sumOf { 1 + it.replies.size }
        return CommentExportResult(
            archiveFile = archiveFile,
            shareUri = shareUri,
            mimeType = format.mimeType,
            exportedCount = count
        )
    }
}
