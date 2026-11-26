package com.instalogger.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubReleaseClientTest {

    @Test
    fun parsesManifestJsonAndLocalizedChangelog() {
        val sample = """
            {
              "schemaVersion": 1,
              "channel": "stable",
              "tag": "v1.0.27-b28",
              "versionName": "1.0.27",
              "versionCode": 28,
              "publishedAt": "2026-11-26T20:00:00Z",
              "minSdk": 26,
              "minSupportedVersionCode": 1,
              "forceUpdate": false,
              "apk": {
                "name": "InstaLogger.apk",
                "url": "https://github.com/ramilseferov/instalogger/releases/download/v1.0.27-b28/InstaLogger.apk",
                "sizeBytes": 76868639,
                "sha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
              },
              "changelog": {
                "az": "- Yenilik AZ",
                "en": "- Feature EN",
                "ru": "- Обновление RU"
              },
              "releaseUrl": "https://github.com/ramilseferov/instalogger/releases/tag/v1.0.27-b28"
            }
        """.trimIndent()

        val client = GitHubReleaseClient()
        val info = client.parseUpdateManifestJson(sample)
        assertNotNull(info)
        assertEquals(28, info!!.versionCode)
        assertEquals("1.0.27", info.versionName)
        assertEquals("- Yenilik AZ", info.changelogForLanguage("az"))
        assertEquals("- Feature EN", info.changelogForLanguage("en"))
        assertEquals("- Обновление RU", info.changelogForLanguage("ru"))
    }

    @Test
    fun versionComparisonPrefersVersionCodeThenSemVer() {
        val remote = ReleaseInfo(
            tag = "v1.0.27-b28",
            versionName = "1.0.27",
            versionCode = 28,
            apkUrl = "https://github.com/ramilseferov/instalogger/releases/download/v1.0.27-b28/InstaLogger.apk"
        )
        assertTrue(GitHubReleaseClient.isRemoteNewer(remote, 27, "1.0.26"))
        assertFalse(GitHubReleaseClient.isRemoteNewer(remote, 28, "1.0.27"))
        assertFalse(GitHubReleaseClient.isRemoteNewer(remote, 29, "1.0.28"))
        assertTrue(GitHubReleaseClient.compareSemVer("1.2.0", "1.1.9") > 0)
        assertEquals(0, GitHubReleaseClient.compareSemVer("v1.0.27", "1.0.27"))
    }

    @Test
    fun tagAndSha256ParsersWorkOnLegacyReleases() {
        assertEquals(27, GitHubReleaseClient.parseVersionCodeFromTag("v1.0.26-b27"))
        assertEquals("1.0.26", GitHubReleaseClient.parseVersionNameFromTag("v1.0.26-b27"))
        val sha = GitHubReleaseClient.extractSha256FromBody(
            "- **SHA-256:** `abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789`"
        )
        assertEquals("abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789", sha)
    }

    @Test
    fun domainAllowlistBlocksUntrustedUrls() {
        assertTrue(GitHubReleaseClient.isAllowedDownloadUrl("https://github.com/ramilseferov/instalogger/releases/download/v1.0.27-b28/InstaLogger.apk"))
        assertTrue(GitHubReleaseClient.isAllowedDownloadUrl("https://objects.githubusercontent.com/github-production-release-asset/123"))
        assertFalse(GitHubReleaseClient.isAllowedDownloadUrl("http://github.com/ramilseferov/instalogger/releases/download/v1.0.27-b28/InstaLogger.apk"))
        assertFalse(GitHubReleaseClient.isAllowedDownloadUrl("https://evil.example.com/InstaLogger.apk"))
    }
}
