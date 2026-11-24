package com.instalogger.app.engine.media

import com.instalogger.app.data.local.TargetEntity
import com.instalogger.app.engine.InstagramEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class MediaEngineUnitTest {

    @Test
    fun netscapeCookieBridge_formatsValidHeaderAndWipesFile() {
        val raw = "sessionid=abc123SECRET; csrftoken=tok999; ds_user_id=424242"
        val content = NetscapeCookieBridge.buildNetscapeContent(raw)
        assertNotNull(content)
        assertTrue(content!!.startsWith("# Netscape HTTP Cookie File"))
        assertTrue(content.contains(".instagram.com\tTRUE\t/\tTRUE\t2147483647\tsessionid\tabc123SECRET"))
        assertTrue(content.contains("\tcsrftoken\ttok999"))
        assertTrue(content.contains("\tds_user_id\t424242"))

        val tmp = Files.createTempFile("ck_test_", ".txt").toFile()
        tmp.writeText(content)
        assertTrue(tmp.exists())
        NetscapeCookieBridge.secureWipeAndDelete(tmp)
        assertFalse(tmp.exists())
    }

    @Test
    fun logSanitizer_redactsCookiesAndSignedTokens() {
        val leaked = "Error fetching https://scontent.cdninstagram.com/v/pic.jpg?oh=SECRET_OH&oe=SECRET_OE Cookie: sessionid=SUPER_SECRET; csrftoken=CSRF_SECRET"
        val safe = LogSanitizer.redact(leaked)
        assertFalse(safe.contains("SUPER_SECRET"))
        assertFalse(safe.contains("CSRF_SECRET"))
        assertFalse(safe.contains("SECRET_OH"))
        assertFalse(safe.contains("SECRET_OE"))
        assertTrue(safe.contains("[REDACTED]"))
    }

    @Test
    fun mediaUrlGuard_allowsMetaCdnAndBlocksUntrustedHosts() {
        assertTrue(MediaUrlGuard.isAllowedMediaUrl("https://scontent-fra3-1.cdninstagram.com/v/t51.2885-15/test.jpg"))
        assertTrue(MediaUrlGuard.isAllowedMediaUrl("https://www.instagram.com/p/C123456/"))
        assertTrue(MediaUrlGuard.isAllowedMediaUrl("https://video.fna.fbcdn.net/v/video.mp4"))
        assertFalse(MediaUrlGuard.isAllowedMediaUrl("http://www.instagram.com/p/C123456/"))
        assertFalse(MediaUrlGuard.isAllowedMediaUrl("https://evil-instagram.com.attacker.net/x.mp4"))
        assertFalse(MediaUrlGuard.isAllowedMediaUrl("file:///etc/passwd"))
        assertTrue(MediaUrlGuard.isAllowedMediaUrl("http://127.0.0.1:3000/archives/123/posts/a.mp4", allowLocalHost = true))
    }

    @Test
    fun mediaFileValidator_validatesJpegAndMp4AndRejectsHtmlLoginPages() {
        val dir = Files.createTempDirectory("media_val_").toFile()
        try {
            // 1. Valid JPEG
            val jpg = File(dir, "valid.jpg")
            val jpgBytes = ByteArray(256)
            jpgBytes[0] = 0xFF.toByte()
            jpgBytes[1] = 0xD8.toByte()
            jpgBytes[2] = 0xFF.toByte()
            jpgBytes[3] = 0xE0.toByte()
            jpg.writeBytes(jpgBytes)

            val jpgRes = MediaFileValidator.validateAndHash(jpg)
            assertNotNull(jpgRes)
            assertEquals("image/jpeg", jpgRes!!.mimeType)
            assertEquals("jpg", jpgRes.extension)
            assertFalse(jpgRes.isVideo)
            assertEquals(64, jpgRes.sha256.length)

            // 2. Valid MP4 (ftypisom)
            val mp4 = File(dir, "valid.mp4")
            val mp4Bytes = ByteArray(256)
            mp4Bytes[4] = 'f'.code.toByte()
            mp4Bytes[5] = 't'.code.toByte()
            mp4Bytes[6] = 'y'.code.toByte()
            mp4Bytes[7] = 'p'.code.toByte()
            mp4Bytes[8] = 'i'.code.toByte()
            mp4Bytes[9] = 's'.code.toByte()
            mp4Bytes[10] = 'o'.code.toByte()
            mp4Bytes[11] = 'm'.code.toByte()
            mp4.writeBytes(mp4Bytes)

            val mp4Res = MediaFileValidator.validateAndHash(mp4)
            assertNotNull(mp4Res)
            assertEquals("video/mp4", mp4Res!!.mimeType)
            assertEquals("mp4", mp4Res.extension)
            assertTrue(mp4Res.isVideo)

            // 3. Reject HTML login wall masquerading as .jpg
            val htmlFake = File(dir, "fake_login.jpg")
            htmlFake.writeText("<!DOCTYPE html><html><head><title>Login • Instagram</title></head><body>" + "x".repeat(200) + "</body></html>")
            assertNull(MediaFileValidator.validateAndHash(htmlFake))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun ytDlpErrorClassification_mapsKnownInstagramFailureModes() {
        val noVid = YtDlpEmbeddedProvider.classifyError("ERROR: [Instagram] C12345: There is no video in this post")
        assertTrue(noVid is ProviderOutcome.UnsupportedMedia)

        val rateLim = YtDlpEmbeddedProvider.classifyError("ERROR: HTTP Error 429: Too Many Requests")
        assertTrue(rateLim is ProviderOutcome.RateLimited)

        val loginReq = YtDlpEmbeddedProvider.classifyError("ERROR: [Instagram] Instagram sent an empty media response. Login required.")
        assertTrue(loginReq is ProviderOutcome.LoginRequired)

        val cancelled = YtDlpEmbeddedProvider.classifyError("anything", isCancelled = true)
        assertTrue(cancelled is ProviderOutcome.Cancelled)

        assertEquals("2.45MiB/s", YtDlpEmbeddedProvider.extractSpeedDetail("[download]  42.3% of 10.00MiB at 2.45MiB/s ETA 00:02"))
        assertEquals(YtDlpQualityPreset.HD_720, YtDlpQualityPreset.fromKey("HD_720"))
        assertEquals(YtDlpQualityPreset.BEST, YtDlpQualityPreset.fromKey("invalid_preset"))
    }

    @Test
    fun desktopSsrParsers_extractPolarisTimelineAndCarouselSlides() {
        val engine = InstagramEngine(null)
        val profileHtml = """
            <html><head></head><body>
            <script type="application/json">{"data":{"xig_user_by_username":{"polaris_ordered_timeline_connection":{"edges":[
              {"node":{"__typename":"XIGPolarisVideoMedia","code":"CUmMvHwDAgn","media_type":2,"display_uri":"https://scontent.cdninstagram.com/v/thumb1.jpg","caption":{"text":"Video caption"}}},
              {"node":{"__typename":"XIGPolarisCarouselMedia","code":"CQ6VrjTHHR7","media_type":8,"carousel_media_count":5,"display_uri":"https://scontent.cdninstagram.com/v/thumb2.jpg","caption":{"text":"Carousel caption"}}}
            ]}}}}</script>
            </body></html>
        """.trimIndent()

        val posts = engine.parsePostsFromSsrHtml(profileHtml, limit = 10)
        assertEquals(2, posts.size)
        assertEquals("CUmMvHwDAgn", posts[0].shortcode)
        assertEquals("video", posts[0].mediaType)
        assertEquals("Video caption", posts[0].caption)
        assertEquals("CQ6VrjTHHR7", posts[1].shortcode)
        assertEquals("carousel", posts[1].mediaType)

        val postHtml = """
            <html><body>
            <script type="application/json">{"result":{"data":{"xig_polaris_media":{"if_not_gated_logged_out":{
              "code":"CQ6VrjTHHR7","like_count":1200,"comment_count":44,"taken_at":1625400000,"caption":{"text":"Pompaké"},
              "carousel_media":[
                {"media_type":1,"original_width":1080,"original_height":1080,"image_versions2":{"candidates":[{"url":"https://scontent.cdninstagram.com/v/s1.jpg","width":1080,"height":1080}]}},
                {"media_type":2,"original_width":1080,"original_height":1350,"image_versions2":{"candidates":[{"url":"https://scontent.cdninstagram.com/v/s2.jpg","width":1080,"height":1350}]},"video_versions":[{"url":"https://scontent.cdninstagram.com/v/s2.mp4"}]}
              ]
            }}}}}</script>
            </body></html>
        """.trimIndent()

        val details = engine.parsePostDetailsFromSsrHtml(postHtml, "CQ6VrjTHHR7")
        assertNotNull(details)
        assertEquals(2, details!!.slides.size)
        assertEquals("image", details.slides[0].mediaType)
        assertEquals("video", details.slides[1].mediaType)
        assertEquals("https://scontent.cdninstagram.com/v/s2.mp4", details.slides[1].videoUrl)
        assertEquals(1200, details.metadata?.likeCount)
        assertEquals(44, details.metadata?.commentCount)
    }

    @Test
    fun highlightsSsrParsers_extractTrayAndStoryItems() {
        val engine = InstagramEngine(null)
        val trayHtml = """
            <html><body>
            <script type="application/json">{"result":{"data":{"user":{"lox_highlights_connection":{"edges":[
              {"node":{"id":"18023864549864045","title":"BOTW 2027","cover_media_cropped_thumbnail_url":"https://scontent.cdninstagram.com/v/cover1.jpg","__typename":"XIGHighlightReel"}},
              {"node":{"id":"highlight:18032893448824228","title":"Travel","cover_media_cropped_thumbnail_url":"https://scontent.cdninstagram.com/v/cover2.jpg","__typename":"XIGHighlightReel"}}
            ]}}}}}</script>
            </body></html>
        """.trimIndent()

        val trays = engine.parseHighlightsFromSsrHtml(trayHtml)
        assertEquals(2, trays.size)
        assertEquals("highlight:18023864549864045", trays[0].id)
        assertEquals("BOTW 2027", trays[0].title)
        assertEquals("https://scontent.cdninstagram.com/v/cover1.jpg", trays[0].coverUrl)
        assertEquals("highlight:18032893448824228", trays[1].id)

        val storiesHtml = """
            <html><body>
            <script type="application/json">{"result":{"data":{"xdt_api__v1__feed__reels_media__connection":{"edges":[
              {"node":{"items":[
                {"pk":"1001","media_type":1,"taken_at":1700000000,"image_versions2":{"candidates":[{"url":"https://scontent.cdninstagram.com/v/story1.jpg"}]}},
                {"pk":"1002","media_type":2,"taken_at":1700000500,"video_versions":[{"url":"https://scontent.cdninstagram.com/v/story2.mp4"}],"image_versions2":{"candidates":[{"url":"https://scontent.cdninstagram.com/v/story2.jpg"}]}}
              ]}}
            ]}}}}</script>
            </body></html>
        """.trimIndent()

        val items = engine.parseHighlightStoriesFromSsrHtml(storiesHtml, "highlight:18023864549864045")
        assertEquals(2, items.size)
        assertEquals("image", items[0].mediaType)
        assertEquals("https://scontent.cdninstagram.com/v/story1.jpg", items[0].mediaUrl)
        assertEquals("video", items[1].mediaType)
        assertEquals("https://scontent.cdninstagram.com/v/story2.mp4", items[1].mediaUrl)
    }

    @Test
    fun targetEntity_hasActiveStory_respects24HourWindow() {
        val now = System.currentTimeMillis()
        val noStory = TargetEntity(username = "alpha", latestStoryAt = 0L)
        assertFalse(noStory.hasActiveStory)

        val recentStory = TargetEntity(username = "beta", latestStoryAt = now - 2L * 60L * 60L * 1000L)
        assertTrue(recentStory.hasActiveStory)

        val expiredStory = TargetEntity(username = "gamma", latestStoryAt = now - 25L * 60L * 60L * 1000L)
        assertFalse(expiredStory.hasActiveStory)
    }
}
