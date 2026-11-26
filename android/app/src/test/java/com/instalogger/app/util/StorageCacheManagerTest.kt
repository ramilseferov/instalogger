package com.instalogger.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class StorageCacheManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun formatBytes_and_splitBytesValueAndUnit_formatAllRangesCleanly() {
        assertEquals("0 B", StorageCacheManager.formatBytes(0L))
        assertEquals("0" to "B", StorageCacheManager.splitBytesValueAndUnit(0L))
        assertEquals("512 B", StorageCacheManager.formatBytes(512L))
        assertEquals("512" to "B", StorageCacheManager.splitBytesValueAndUnit(512L))
        assertEquals("2,0 KB", StorageCacheManager.formatBytes(2048L))
        assertEquals("2,0" to "KB", StorageCacheManager.splitBytesValueAndUnit(2048L))
        assertEquals("1,50 MB", StorageCacheManager.formatBytes(1572864L))
        assertEquals("1,5" to "MB", StorageCacheManager.splitBytesValueAndUnit(1572864L))
        assertEquals("2,00 GB", StorageCacheManager.formatBytes(2L * 1024L * 1024L * 1024L))
        assertEquals("2,00" to "GB", StorageCacheManager.splitBytesValueAndUnit(2L * 1024L * 1024L * 1024L))
    }

    @Test
    fun scanVaultAndArchives_separatesCategories_excludesSystemLogs_and_sortsTargets() {
        val root = tempFolder.newFolder("sandbox")
        val vaultRoot = File(root, "vault")
        val legacyAvatars = File(root, "avatars")
        val archivesDir = File(root, "archives")

        // System logs inside vault/_system must NOT be counted as user media
        writeBytes(File(vaultRoot, "_system/logs/dev_logs.jsonl.enc"), 4096)

        // Target @alice: 1000B posts, 500B highlights + 300B stories, 200B vault avatar + 100B legacy avatar
        writeBytes(File(vaultRoot, "alice/posts/p1.mp4.enc"), 1000)
        writeBytes(File(vaultRoot, "alice/posts/.nomedia"), 0)
        writeBytes(File(vaultRoot, "alice/highlights/h1.jpg.enc"), 500)
        writeBytes(File(vaultRoot, "alice/stories/s1.mp4.enc"), 300)
        writeBytes(File(vaultRoot, "alice/profile/avatar_current.jpg.enc"), 200)
        writeBytes(File(legacyAvatars, "alice/old.jpg"), 100)

        // Target @bob: 5000B posts + 400B JSON archive -> larger than alice
        writeBytes(File(vaultRoot, "bob/posts/p9.mp4.enc"), 5000)
        writeBytes(File(archivesDir, "bob/profile.json"), 400)

        // Pass the SAME archives dir as both internal and external to verify deduplication
        val result = StorageCacheManager.scanVaultAndArchives(
            vaultRoot = vaultRoot,
            legacyAvatarsDir = legacyAvatars,
            internalArchivesDir = archivesDir,
            externalArchivesDir = archivesDir,
        )

        assertEquals(6000L, result.postsBytes)
        assertEquals(2, result.postsFiles)
        assertEquals(800L, result.storiesHighlightsBytes)
        assertEquals(2, result.storiesHighlightsFiles)
        assertEquals(300L, result.avatarsBytes)
        assertEquals(2, result.avatarsFiles)
        assertEquals(400L, result.archivesBytes)
        assertEquals(1, result.archivesFiles)

        assertEquals(2, result.perTarget.size)
        assertEquals("bob", result.perTarget[0].username)
        assertEquals(5400L, result.perTarget[0].totalBytes)
        assertEquals(2, result.perTarget[0].totalFiles)

        assertEquals("alice", result.perTarget[1].username)
        assertEquals(2100L, result.perTarget[1].totalBytes)
        assertEquals(5, result.perTarget[1].totalFiles)
    }

    @Test
    fun deleteDirectoryContents_preservesNoMedia_and_returnsExactFreedBytes() {
        val dir = tempFolder.newFolder("vault_posts")
        val noMedia = File(dir, ".nomedia").apply { createNewFile() }
        writeBytes(File(dir, "video1.mp4.enc"), 2500)
        writeBytes(File(dir, "thumb1.jpg.enc"), 500)

        val freed = StorageCacheManager.deleteDirectoryContents(dir, preserveNoMedia = true)

        assertEquals(3000L, freed)
        assertTrue(noMedia.exists())
        assertEquals(0L, StorageCacheManager.calculateDirSize(dir))
        assertEquals(0, StorageCacheManager.countFilesInDir(dir))
    }

    @Test
    fun categoryDefaults_markVaultCategoriesProtected_andCachesSafe() {
        val safeCategories = setOf(
            StorageCategoryType.IMAGE_CACHE,
            StorageCategoryType.TEMP_STAGING,
            StorageCategoryType.WEBVIEW_CACHE,
            StorageCategoryType.LOGS_AND_ALERTS,
        )
        val protectedCategories = setOf(
            StorageCategoryType.VAULT_POSTS,
            StorageCategoryType.VAULT_STORIES_HIGHLIGHTS,
            StorageCategoryType.VAULT_AVATARS,
            StorageCategoryType.COMMENTS_AND_ARCHIVES,
        )

        safeCategories.forEach { type ->
            assertTrue("$type should be selected by default", type.selectedByDefault)
            assertFalse("$type should not be protected", type.isProtectedCategory)
        }
        protectedCategories.forEach { type ->
            assertFalse("$type should NOT be selected by default", type.selectedByDefault)
            assertTrue("$type should be protected", type.isProtectedCategory)
        }
    }

    private fun writeBytes(file: File, size: Int) {
        file.parentFile?.mkdirs()
        file.writeBytes(ByteArray(size) { 0x42 })
    }
}
