package com.instalogger.app.engine.media

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.instalogger.app.util.cleanDisplayText
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Embedded yt-dlp + FFmpeg Media Provider (powered by youtubedl-android).
 * Specializes in Instagram Reels, Video posts, DASH audio+video muxing, mixed-carousel videos,
 * rich info.json metadata extraction, real-time progress telemetry, and runtime self-updates.
 */
class YtDlpEmbeddedProvider(
    context: Context
) : InstagramMediaProvider {

    private val appContext = context.applicationContext
    private val gson = Gson()
    private val initMutex = Mutex()
    private val updateMutex = Mutex()
    private val warmUpScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cancelledProcesses = ConcurrentHashMap.newKeySet<String>()

    private val _engineState = MutableStateFlow<YtDlpEngineState>(YtDlpEngineState.Uninitialized)
    val engineState: StateFlow<YtDlpEngineState> = _engineState.asStateFlow()

    override val id: String = PROVIDER_ID
    override val displayName: String = "yt-dlp + FFmpeg"
    override val capabilities: Set<ProviderCapability> = setOf(
        ProviderCapability.VIDEO_POST,
        ProviderCapability.REEL,
        ProviderCapability.CAROUSEL,
        ProviderCapability.METADATA,
        ProviderCapability.SELF_UPDATABLE
    )

    fun warmUpAsync() {
        warmUpScope.launch {
            NetscapeCookieBridge.purgeOrphanedCookieFiles(appContext)
            ensureInitialized()
        }
    }

    suspend fun ensureInitialized(): Boolean = withContext(Dispatchers.IO) {
        if (_engineState.value is YtDlpEngineState.Ready) return@withContext true

        initMutex.withLock {
            if (_engineState.value is YtDlpEngineState.Ready) return@withContext true
            _engineState.value = YtDlpEngineState.Initializing
            return@withContext try {
                YoutubeDL.getInstance().init(appContext)
                try {
                    FFmpeg.getInstance().init(appContext)
                } catch (e: Exception) {
                    Log.w(TAG, "FFmpeg init warning (continuing with yt-dlp core): ${LogSanitizer.redact(e.message)}")
                }
                val version = readVersionSafe()
                _engineState.value = YtDlpEngineState.Ready(version)
                Log.i(TAG, "yt-dlp runtime initialized successfully (version=$version)")
                true
            } catch (e: Exception) {
                val msg = LogSanitizer.redact(e.message ?: "yt-dlp initialization failed")
                _engineState.value = YtDlpEngineState.Failed(msg)
                Log.e(TAG, "Failed to initialize yt-dlp runtime: $msg")
                false
            }
        }
    }

    suspend fun updateBinary(channel: YtDlpUpdateChannel): Result<String> = withContext(Dispatchers.IO) {
        if (!ensureInitialized()) {
            return@withContext Result.failure(IllegalStateException("yt-dlp runtime not initialized"))
        }

        updateMutex.withLock {
            val previousState = _engineState.value
            _engineState.value = YtDlpEngineState.Updating
            return@withContext try {
                val ytChannel = when (channel) {
                    YtDlpUpdateChannel.NIGHTLY -> YoutubeDL.UpdateChannel.NIGHTLY
                    YtDlpUpdateChannel.STABLE -> YoutubeDL.UpdateChannel.STABLE
                }
                val status = YoutubeDL.getInstance().updateYoutubeDL(appContext, ytChannel)
                val newVersion = readVersionSafe()
                _engineState.value = YtDlpEngineState.Ready(newVersion)
                val label = when (status) {
                    YoutubeDL.UpdateStatus.DONE -> "Updated to $newVersion"
                    YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE -> "Up to date ($newVersion)"
                    else -> newVersion
                }
                Result.success(label)
            } catch (e: Exception) {
                val msg = LogSanitizer.redact(e.message ?: "Update failed")
                _engineState.value = if (previousState is YtDlpEngineState.Ready) previousState else YtDlpEngineState.Failed(msg)
                Result.failure(Exception(msg))
            }
        }
    }

    override suspend fun isAvailable(): Boolean {
        return ensureInitialized()
    }

    override suspend fun downloadPost(
        request: MediaDownloadRequest,
        stagingDir: File,
        onProgress: (PostDownloadState) -> Unit
    ): ProviderOutcome = withContext(Dispatchers.IO) {
        if (!ensureInitialized()) {
            return@withContext ProviderOutcome.Failure("yt-dlp engine unavailable", isTransient = true)
        }

        cancelledProcesses.remove(request.processId)
        stagingDir.mkdirs()
        onProgress(PostDownloadState.Extracting)

        val candidateUrls = buildCandidateUrls(request.shortcode, request.mediaTypeHint)

        return@withContext NetscapeCookieBridge.withEphemeralCookieFile(
            context = appContext,
            rawCookieHeader = request.cookieString
        ) { cookieFile ->
            var lastOutcome: ProviderOutcome = ProviderOutcome.Failure("yt-dlp produced no media")

            for (targetUrl in candidateUrls) {
                if (cancelledProcesses.contains(request.processId)) {
                    return@withEphemeralCookieFile ProviderOutcome.Cancelled
                }

                // Clear staging dir between candidate URL attempts
                stagingDir.listFiles()?.forEach { it.deleteRecursively() }

                val ytRequest = buildYoutubeDlRequest(
                    url = targetUrl,
                    stagingDir = stagingDir,
                    qualityPreset = request.qualityPreset,
                    cookieFile = cookieFile
                )

                try {
                    YoutubeDL.getInstance().execute(ytRequest, request.processId) { progress, etaInSeconds, line ->
                        if (cancelledProcesses.contains(request.processId)) return@execute
                        val cleanLine = line.orEmpty()
                        when {
                            cleanLine.contains("[Merger]", ignoreCase = true) ||
                                cleanLine.contains("[ffmpeg]", ignoreCase = true) ||
                                cleanLine.contains("Merging formats", ignoreCase = true) -> {
                                onProgress(PostDownloadState.Merging)
                            }
                            progress >= 0f -> {
                                onProgress(
                                    PostDownloadState.Downloading(
                                        progress = progress.coerceIn(0f, 100f),
                                        etaSeconds = etaInSeconds,
                                        detail = extractSpeedDetail(cleanLine)
                                    )
                                )
                            }
                        }
                    }

                    val collected = collectStagedResults(stagingDir)
                    if (collected.first.isNotEmpty()) {
                        return@withEphemeralCookieFile ProviderOutcome.Success(
                            assets = collected.first,
                            metadata = collected.second,
                            providerId = PROVIDER_ID
                        )
                    } else {
                        lastOutcome = ProviderOutcome.UnsupportedMedia("No downloadable video stream found")
                    }
                } catch (e: CancellationException) {
                    cancel(request.processId)
                    return@withEphemeralCookieFile ProviderOutcome.Cancelled
                } catch (e: InterruptedException) {
                    return@withEphemeralCookieFile ProviderOutcome.Cancelled
                } catch (e: YoutubeDLException) {
                    val classified = classifyError(
                        rawMessage = e.message.orEmpty(),
                        isCancelled = cancelledProcesses.contains(request.processId)
                    )
                    if (classified is ProviderOutcome.Cancelled ||
                        classified is ProviderOutcome.RateLimited ||
                        classified is ProviderOutcome.LoginRequired ||
                        classified is ProviderOutcome.UnsupportedMedia
                    ) {
                        return@withEphemeralCookieFile classified
                    }
                    lastOutcome = classified
                } catch (e: Exception) {
                    lastOutcome = classifyError(
                        rawMessage = e.message.orEmpty(),
                        isCancelled = cancelledProcesses.contains(request.processId)
                    )
                    if (lastOutcome is ProviderOutcome.Cancelled) {
                        return@withEphemeralCookieFile ProviderOutcome.Cancelled
                    }
                }
            }

            lastOutcome
        }
    }

    override fun cancel(processId: String) {
        cancelledProcesses.add(processId)
        try {
            YoutubeDL.getInstance().destroyProcessById(processId)
        } catch (_: Exception) {
            // Process may already have exited
        }
    }

    private fun readVersionSafe(): String {
        return try {
            YoutubeDL.getInstance().version(appContext)?.trim()?.ifBlank { "embedded" } ?: "embedded"
        } catch (_: Exception) {
            "embedded"
        }
    }

    private fun buildYoutubeDlRequest(
        url: String,
        stagingDir: File,
        qualityPreset: YtDlpQualityPreset,
        cookieFile: File?
    ): YoutubeDLRequest {
        val outputTemplate = File(stagingDir, "%(playlist_autonumber|01)02d_%(id)s.%(ext)s").absolutePath
        return YoutubeDLRequest(url).apply {
            addOption("-f", qualityPreset.formatSelector)
            addOption("--merge-output-format", "mp4")
            addOption("--write-thumbnail")
            addOption("--convert-thumbnails", "jpg")
            addOption("--write-info-json")
            addOption("--no-mtime")
            addOption("--no-part")
            addOption("--no-warnings")
            addOption("--retries", "3")
            addOption("--fragment-retries", "3")
            addOption("--socket-timeout", "20")
            addOption("--user-agent", CHROME_UA)
            addOption("-o", outputTemplate)
            if (cookieFile != null && cookieFile.exists()) {
                addOption("--cookies", cookieFile.absolutePath)
            }
        }
    }

    private fun collectStagedResults(stagingDir: File): Pair<List<StagedMediaAsset>, ExtractedPostMetadata?> {
        val files = stagingDir.listFiles()?.sortedBy { it.name } ?: return emptyList<StagedMediaAsset>() to null

        val infoJsonFiles = files.filter { it.name.endsWith(".info.json", ignoreCase = true) }
        val metadata = infoJsonFiles.firstOrNull()?.let { parseInfoJson(it) }

        val videoExts = setOf("mp4", "mkv", "webm", "mov", "m4v")
        val thumbExts = setOf("jpg", "jpeg", "png", "webp")

        val videoFiles = files.filter { it.isFile && it.extension.lowercase(Locale.ROOT) in videoExts && it.length() > 0L }
        val imageFiles = files.filter { it.isFile && it.extension.lowercase(Locale.ROOT) in thumbExts && it.length() > 0L }

        val assets = mutableListOf<StagedMediaAsset>()
        videoFiles.forEachIndexed { index, videoFile ->
            val basePrefix = videoFile.nameWithoutExtension
            val matchingThumb = imageFiles.firstOrNull { it.nameWithoutExtension == basePrefix }
                ?: imageFiles.getOrNull(index)
                ?: imageFiles.firstOrNull()

            val itemInfo = infoJsonFiles.firstOrNull { it.name.startsWith(basePrefix) }
                ?.let { parseInfoJson(it) }

            assets.add(
                StagedMediaAsset(
                    position = index,
                    mediaType = "video",
                    mediaFile = videoFile,
                    thumbFile = matchingThumb,
                    remoteUrl = itemInfo?.displayUrl.orEmpty(),
                    durationSeconds = itemInfo?.durationSeconds ?: metadata?.durationSeconds
                )
            )
        }

        return assets to metadata
    }

    internal fun parseInfoJson(file: File): ExtractedPostMetadata? {
        return try {
            parseInfoJsonString(file.readText(Charsets.UTF_8))
        } catch (_: Exception) {
            null
        }
    }

    internal fun parseInfoJsonString(rawJson: String): ExtractedPostMetadata? {
        return try {
            val obj = gson.fromJson(rawJson, JsonObject::class.java) ?: return null
            val caption = obj.get("description")?.takeIf { !it.isJsonNull }?.asString?.cleanDisplayText()
                ?.ifBlank { null }
                ?: obj.get("title")?.takeIf { !it.isJsonNull }?.asString?.cleanDisplayText()?.ifBlank { null }
            val likes = obj.get("like_count")?.takeIf { !it.isJsonNull }?.asInt
            val comments = obj.get("comment_count")?.takeIf { !it.isJsonNull }?.asInt
            val timestampSec = obj.get("timestamp")?.takeIf { !it.isJsonNull }?.asLong
            val duration = obj.get("duration")?.takeIf { !it.isJsonNull }?.asDouble?.toInt()
            val thumbnail = obj.get("thumbnail")?.takeIf { !it.isJsonNull }?.asString

            ExtractedPostMetadata(
                caption = caption,
                likeCount = likes,
                commentCount = comments,
                postedAtMillis = timestampSec?.let { it * 1000L },
                durationSeconds = duration,
                displayUrl = thumbnail
            )
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        const val PROVIDER_ID = "yt-dlp"
        private const val TAG = "YtDlpEmbeddedProvider"
        private const val CHROME_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        fun buildCandidateUrls(shortcode: String, mediaTypeHint: String): List<String> {
            val clean = shortcode.trim()
            return if (mediaTypeHint.equals("video", ignoreCase = true)) {
                listOf(
                    "https://www.instagram.com/reel/$clean/",
                    "https://www.instagram.com/p/$clean/"
                )
            } else {
                listOf("https://www.instagram.com/p/$clean/")
            }
        }

        fun extractSpeedDetail(line: String): String? {
            val match = Regex("at\\s+([0-9.]+\\s*[KMG]i?B/s)").find(line)
            return match?.groupValues?.getOrNull(1)
        }

        fun classifyError(rawMessage: String, isCancelled: Boolean = false): ProviderOutcome {
            if (isCancelled) return ProviderOutcome.Cancelled
            val lower = rawMessage.lowercase(Locale.ROOT)
            val safe = LogSanitizer.redact(rawMessage).take(240)

            return when {
                lower.contains("there is no video in this post") ||
                    lower.contains("no video formats found") ||
                    lower.contains("requested format is not available") ->
                    ProviderOutcome.UnsupportedMedia(safe.ifBlank { "No video in post" })

                lower.contains("429") ||
                    lower.contains("rate-limit") ||
                    lower.contains("too many requests") ||
                    lower.contains("please wait a few minutes") ->
                    ProviderOutcome.RateLimited(
                        retryAfterMs = 120_000L,
                        detail = safe.ifBlank { "Instagram rate limit (429)" }
                    )

                lower.contains("login required") ||
                    lower.contains("sign in") ||
                    lower.contains("cookies") ||
                    lower.contains("checkpoint") ||
                    lower.contains("empty media response") ->
                    ProviderOutcome.LoginRequired(safe.ifBlank { "Instagram authentication required" })

                lower.contains("interrupted") || lower.contains("destroyed") || lower.contains("terminated") ->
                    ProviderOutcome.Cancelled

                else ->
                    ProviderOutcome.Failure(safe.ifBlank { "yt-dlp execution error" }, isTransient = true)
            }
        }
    }
}
