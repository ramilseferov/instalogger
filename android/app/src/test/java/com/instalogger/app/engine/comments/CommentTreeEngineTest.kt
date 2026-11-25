package com.instalogger.app.engine.comments

import com.instalogger.app.data.local.PostCommentEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommentTreeEngineTest {

    private fun sampleComments(): List<PostCommentEntity> = listOf(
        PostCommentEntity(
            id = "c1",
            postId = "CxABC123",
            mediaId = "3200000001",
            username = "target",
            parentCommentId = null,
            authorUsername = "alice",
            authorId = "101",
            isAuthorVerified = true,
            text = "Great video! Love the editing.",
            likeCount = 145,
            replyCount = 2,
            createdAt = 1_700_000_100_000L,
            collectionStatus = "complete"
        ),
        PostCommentEntity(
            id = "c1_r1",
            postId = "CxABC123",
            mediaId = "3200000001",
            username = "target",
            parentCommentId = "c1",
            authorUsername = "bob",
            authorId = "102",
            isAuthorVerified = false,
            text = "Agreed, especially the soundtrack!",
            likeCount = 12,
            replyCount = 0,
            createdAt = 1_700_000_200_000L,
            collectionStatus = "complete"
        ),
        PostCommentEntity(
            id = "c1_r2",
            postId = "CxABC123",
            mediaId = "3200000001",
            username = "target",
            parentCommentId = "c1_r1", // reply to reply -> flattened cleanly under c1
            authorUsername = "charlie",
            authorId = "103",
            isAuthorVerified = false,
            text = "What camera was used?",
            likeCount = 4,
            replyCount = 0,
            createdAt = 1_700_000_300_000L,
            collectionStatus = "complete"
        ),
        PostCommentEntity(
            id = "c2",
            postId = "CxABC123",
            mediaId = "3200000001",
            username = "target",
            parentCommentId = null,
            authorUsername = "zeynab",
            authorId = "104",
            isAuthorVerified = false,
            text = "Super paylaşım, uğurlar!",
            likeCount = 25,
            replyCount = 0,
            createdAt = 1_700_000_500_000L,
            collectionStatus = "complete"
        )
    )

    @Test
    fun shortcodeToMediaId_decodesKnownInstagramShortcode() {
        // Known Instagram shortcode "B" = index 1
        assertEquals("1", CommentTreeEngine.shortcodeToMediaId("B"))
        // Standard 11-char shortcode produces deterministic numeric ID
        val mediaId = CommentTreeEngine.shortcodeToMediaId("CxABC123456")
        assertNotNull(mediaId)
        assertTrue(mediaId!!.all { it.isDigit() })
    }

    @Test
    fun buildCommentTree_nestsRepliesAndFlattensDeepReplies() {
        val tree = CommentTreeEngine.buildCommentTree(sampleComments())
        assertEquals(2, tree.size)

        val c1 = tree.first { it.comment.id == "c1" }
        assertEquals(2, c1.replies.size)
        assertEquals(listOf("c1_r1", "c1_r2"), c1.replies.map { it.id })

        val c2 = tree.first { it.comment.id == "c2" }
        assertTrue(c2.replies.isEmpty())
    }

    @Test
    fun queryTree_preservesParentWhenNestedReplyMatchesSearch() {
        val results = CommentTreeEngine.queryTree(
            sampleComments(),
            CommentQueryOptions(searchQuery = "soundtrack")
        )
        assertEquals(1, results.size)
        assertEquals("c1", results[0].comment.id)
        assertEquals(1, results[0].matchedReplyCount)
        assertEquals(1, results[0].replies.size)
        assertEquals("c1_r1", results[0].replies[0].id)
    }

    @Test
    fun queryTree_supportsSortingAndFiltering() {
        val comments = sampleComments()

        val byNewest = CommentTreeEngine.queryTree(comments, CommentQueryOptions(sortOrder = CommentSortOrder.NEWEST))
        assertEquals(listOf("c2", "c1"), byNewest.map { it.comment.id })

        val byLikes = CommentTreeEngine.queryTree(comments, CommentQueryOptions(sortOrder = CommentSortOrder.MOST_LIKED))
        assertEquals(listOf("c1", "c2"), byLikes.map { it.comment.id })

        val withReplies = CommentTreeEngine.queryTree(comments, CommentQueryOptions(filterMode = CommentFilterMode.WITH_REPLIES))
        assertEquals(listOf("c1"), withReplies.map { it.comment.id })

        val verifiedOnly = CommentTreeEngine.queryTree(comments, CommentQueryOptions(filterMode = CommentFilterMode.VERIFIED_ONLY))
        assertEquals(listOf("c1"), verifiedOnly.map { it.comment.id })
        assertTrue(verifiedOnly[0].replies.isEmpty())

        val byAuthor = CommentTreeEngine.queryTree(comments, CommentQueryOptions(authorFilter = "bob"))
        assertEquals(1, byAuthor.size)
        assertEquals("c1", byAuthor[0].comment.id)
        assertEquals(listOf("c1_r1"), byAuthor[0].replies.map { it.id })
    }

    @Test
    fun computeStats_detectsPartialWhenReportedTotalExceedsFetched() {
        val statsComplete = CommentTreeEngine.computeStats(sampleComments(), reportedTotalCount = 4)
        assertEquals(4, statsComplete.totalCount)
        assertEquals(2, statsComplete.topLevelCount)
        assertEquals(2, statsComplete.replyCount)
        assertEquals(4, statsComplete.uniqueAuthorsCount)
        assertEquals("complete", statsComplete.collectionStatus)

        val statsPartial = CommentTreeEngine.computeStats(sampleComments(), reportedTotalCount = 20)
        assertEquals("partial", statsPartial.collectionStatus)
    }

    @Test
    fun commentExporter_rendersStructuredJsonCsvTxtAndMarkdown() {
        val comments = sampleComments()
        val tree = CommentTreeEngine.buildCommentTree(comments)
        val stats = CommentTreeEngine.computeStats(comments, reportedTotalCount = 4)

        val json = CommentExporter.renderJson("CxABC123", "3200000001", "target", tree, stats)
        assertTrue(json.contains("\"comment_id\": \"c1\""))
        assertTrue(json.contains("\"parent_comment_id\": \"c1\""))
        assertTrue(json.contains("\"collection_status\": \"complete\""))

        val csv = CommentExporter.renderCsv(tree)
        assertTrue(csv.startsWith("\uFEFFcomment_id,parent_comment_id,depth"))
        assertTrue(csv.contains("\"Super paylaşım, uğurlar!\""))

        val txt = CommentExporter.renderTxt("CxABC123", "target", tree, stats)
        assertTrue(txt.contains("├─ @bob"))
        assertTrue(txt.contains("└─ @charlie"))

        val md = CommentExporter.renderMarkdown("CxABC123", "target", tree, stats)
        assertTrue(md.contains("### @alice `(verified)`"))
        assertTrue(md.contains("> **@bob**"))
    }
}
