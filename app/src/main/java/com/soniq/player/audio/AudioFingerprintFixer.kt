package com.soniq.player.audio

import android.content.Context
import android.net.Uri
import com.soniq.player.data.Song
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.abs

data class FingerprintResult(
    val title: String,
    val artist: String,
    val album: String,
    val albumArtUrl: String?,
    val matchConfidence: Float
)

object AudioFingerprintFixer {

    private val SPAM_PATTERNS = listOf(
        Regex("""(?i)\[.*?(official|audio|video|lyric|lyrics|hd|4k|download|mp3|remix|full|song).*?\]"""),
        Regex("""(?i)\(.*?(official|audio|video|lyric|lyrics|hd|4k|download|mp3|remix|full|song).*?\)"""),
        Regex("""(?i)__?(official|audio|video|lyric|lyrics|hd|4k|download|mp3|remix|full|song).*"""),
        Regex("""(?i)_320kbps|_128kbps|\.mp3|\.m4a|\.flac|\.wav"""),
        Regex("""^[0-9]+[\s._-]+"""),
        Regex("""(?i)\b(pagalworld|mr-jatt|djpunjab|ytmp3|yt-dlp|vevo|tseries|zee\s*music|speed\s*records|download|dhun|song)\b"""),
        Regex("""(?i)-\s*(ek\s*dee|dj|remix).*""")
    )

    fun cleanLocalTitle(rawTitle: String): String {
        var cleaned = rawTitle.replace("_", " ")
        for (pattern in SPAM_PATTERNS) {
            cleaned = cleaned.replace(pattern, " ")
        }
        return cleaned.replace(Regex("""\s+"""), " ").trim()
    }

    fun isSongUnknown(song: Song): Boolean {
        if (song.id < 0) return false
        val unknownWords = listOf("<unknown>", "unknown", "untitled", "various", "download", "track")
        val artistUnknown = song.artist.isBlank() || unknownWords.any { song.artist.contains(it, ignoreCase = true) }
        val titleIsRaw = song.title.endsWith(".mp3", ignoreCase = true) ||
                unknownWords.any { song.title.equals(it, ignoreCase = true) } ||
                song.title.contains("_") ||
                song.title.contains("download", ignoreCase = true) ||
                song.title.startsWith("audio_", ignoreCase = true) ||
                song.title.startsWith("Dhun", ignoreCase = true)
        return artistUnknown || titleIsRaw
    }

    suspend fun autoFixSong(context: Context, song: Song): FingerprintResult = withContext(Dispatchers.IO) {
        val cleanedTitle = cleanLocalTitle(song.title)

        // Pass 1: JioSaavn Catalog Gateway (Deep Indian & Bollywood/Pakistani OST catalog)
        val saavnResult = queryJioSaavn(cleanedTitle, song.durationMs)
        if (saavnResult != null && saavnResult.matchConfidence >= 0.60f) {
            val localArt = saavnResult.albumArtUrl?.let { saveArtworkLocally(context, song.id, it) }
            return@withContext saavnResult.copy(albumArtUrl = localArt ?: saavnResult.albumArtUrl)
        }

        // Pass 2: Apple Music / iTunes Store Gateway
        val itunesResult = queryItunes(cleanedTitle, song)
        if (itunesResult != null && itunesResult.matchConfidence >= 0.60f) {
            val localArt = itunesResult.albumArtUrl?.let { saveArtworkLocally(context, song.id, it) }
            return@withContext itunesResult.copy(albumArtUrl = localArt ?: itunesResult.albumArtUrl)
        }

        // Pass 3: Sanitized Local Title
        val safeArtist = if (song.artist.contains("unknown", true) || song.artist.contains("various", true) || song.artist.isBlank()) {
            "Original Artist"
        } else song.artist

        FingerprintResult(
            title = cleanedTitle.ifBlank { song.title },
            artist = safeArtist,
            album = song.album.ifBlank { "Single" },
            albumArtUrl = song.albumArtUri,
            matchConfidence = 0.50f
        )
    }

    private fun queryJioSaavn(query: String, targetDurationMs: Long): FingerprintResult? {
        val endpoints = listOf(
            "https://saavn.dev/api/search/songs",
            "https://jiosaavn-api-privatecvc2.vercel.app/search/songs"
        )
        for (baseUrl in endpoints) {
            try {
                val encoded = URLEncoder.encode(query, "UTF-8")
                val url = URL("$baseUrl?query=$encoded&limit=5")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 3000
                    readTimeout = 3000
                    setRequestProperty("User-Agent", "SONIQ-Player/0.9.5")
                }
                if (conn.responseCode != 200) continue

                val text = conn.inputStream.bufferedReader().use { it.readText() }
                val root = JSONObject(text)
                val dataObj = root.optJSONObject("data") ?: continue
                val results = dataObj.optJSONArray("results") ?: continue

                if (results.length() > 0) {
                    var bestIdx = 0
                    var minDiff = Long.MAX_VALUE
                    for (i in 0 until results.length()) {
                        val item = results.getJSONObject(i)
                        val durSec = item.optLong("duration", 0L)
                        val durMs = durSec * 1000L
                        if (durMs > 0 && targetDurationMs > 0) {
                            val diff = abs(durMs - targetDurationMs)
                            if (diff < minDiff) {
                                minDiff = diff
                                bestIdx = i
                            }
                        }
                    }

                    val best = results.getJSONObject(bestIdx)
                    val title = best.optString("name", query).replace("&quot;", "\"").replace("&#039;", "'")
                    val artist = best.optJSONObject("artists")
                        ?.optJSONArray("primary")
                        ?.optJSONObject(0)
                        ?.optString("name", "")
                        ?.ifBlank { null }
                        ?: best.optString("primaryArtists", "Various Artists")

                    val album = best.optJSONObject("album")?.optString("name", "Single") ?: "Single"

                    val images = best.optJSONArray("image")
                    val artUrl = if (images != null && images.length() > 0) {
                        images.getJSONObject(images.length() - 1).optString("url", null)
                            ?: images.getJSONObject(images.length() - 1).optString("link", null)
                    } else null

                    return FingerprintResult(
                        title = title,
                        artist = artist,
                        album = album,
                        albumArtUrl = artUrl,
                        matchConfidence = if (minDiff < 10000) 0.95f else 0.85f
                    )
                }
            } catch (_: Exception) {
                continue
            }
        }
        return null
    }

    private fun queryItunes(cleanedTitle: String, song: Song): FingerprintResult? {
        return try {
            val encoded = URLEncoder.encode(cleanedTitle, "UTF-8")
            val url = URL("https://itunes.apple.com/search?term=$encoded&entity=song&limit=6&country=IN")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 3000
                readTimeout = 3000
            }
            if (conn.responseCode != 200) return null

            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val root = JSONObject(text)
            val results = root.optJSONArray("results") ?: return null

            if (results.length() > 0) {
                var bestIndex = 0
                var minDiff = Long.MAX_VALUE

                for (i in 0 until results.length()) {
                    val item = results.getJSONObject(i)
                    val durationMs = item.optLong("trackTimeMillis", 0L)
                    if (durationMs > 0 && song.durationMs > 0) {
                        val diff = abs(durationMs - song.durationMs)
                        if (diff < minDiff) {
                            minDiff = diff
                            bestIndex = i
                        }
                    }
                }

                val best = results.getJSONObject(bestIndex)
                val fixedTitle = best.optString("trackName", cleanedTitle)
                val fixedArtist = best.optString("artistName", "Unknown Artist")
                val fixedAlbum = best.optString("collectionName", "Single")
                val rawArt = best.optString("artworkUrl100", "")
                val hdArt = rawArt.replace("100x100bb", "600x600bb").ifBlank { null }

                FingerprintResult(
                    title = fixedTitle,
                    artist = fixedArtist,
                    album = fixedAlbum,
                    albumArtUrl = hdArt,
                    matchConfidence = if (minDiff < 15000) 0.92f else 0.75f
                )
            } else null
        } catch (_: Exception) {
            null
        }
    }

    private fun saveArtworkLocally(context: Context, songId: Long, urlString: String): String? {
        return runCatching {
            val destFile = File(context.filesDir, "cover_$songId.jpg")
            val url = URL(urlString)
            url.openStream().use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
            Uri.fromFile(destFile).toString()
        }.getOrNull()
    }

    suspend fun batchFixAllUnknown(
        context: Context,
        songs: List<Song>,
        onProgress: (current: Int, total: Int, songName: String) -> Unit,
        onSongFixed: (Song, FingerprintResult) -> Unit
    ): Int = withContext(Dispatchers.IO) {
        val unknownList = songs.filter { isSongUnknown(it) }
        val total = unknownList.size
        if (total == 0) return@withContext 0

        var fixedCount = 0
        val semaphore = Semaphore(4)
        var completedSoFar = 0

        val jobs = unknownList.map { song ->
            async {
                semaphore.withPermit {
                    try {
                        val result = autoFixSong(context, song)
                        synchronized(this@AudioFingerprintFixer) {
                            completedSoFar++
                        }
                        withContext(Dispatchers.Main) {
                            onProgress(completedSoFar, total, song.title)
                        }
                        if (result.matchConfidence >= 0.50f) {
                            withContext(Dispatchers.Main) {
                                onSongFixed(song, result)
                            }
                            synchronized(this@AudioFingerprintFixer) {
                                fixedCount++
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        synchronized(this@AudioFingerprintFixer) {
                            completedSoFar++
                        }
                    }
                }
            }
        }
        jobs.awaitAll()
        fixedCount
    }
}
