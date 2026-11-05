package com.soniq.player.audio

import android.content.Context
import android.media.MediaMetadataRetriever
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
        Regex("""(?i)\[.*?\]"""),
        Regex("""(?i)\(.*?\b(official|video|audio|lyric|lyrics|hd|4k|download|mp3|remix|mashup|slowed|reverb|full|song|ringtone)\b.*?\)"""),
        Regex("""(?i)__?(official|audio|video|lyric|lyrics|hd|4k|download|mp3|remix|full|song|ringtone).*"""),
        Regex("""(?i)_320kbps|_128kbps|\.mp3|\.m4a|\.flac|\.wav|\.aac"""),
        Regex("""^[0-9]+[\s._-]+"""),
        Regex("""(?i)\b(pagalworld|mr-jatt|djpunjab|ytmp3|yt-dlp|vevo|tseries|zee\s*music|speed\s*records|download|dhun|song|lyric\s*v.*)\b"""),
        Regex("""(?i)-\s*(non-s.*|non-stop.*|ek\s*dee.*|dj.*|remix.*|lyric.*)""")
    )

    fun cleanLocalTitle(rawTitle: String): String {
        var cleaned = rawTitle.replace("_", " ")
        for (pattern in SPAM_PATTERNS) {
            cleaned = cleaned.replace(pattern, " ")
        }
        cleaned = cleaned.replace(Regex("""[^a-zA-Z0-9\s-]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim(' ', '-', '_')
        return cleaned
    }

    fun isSongUnknown(song: Song): Boolean {
        if (song.id < 0) return false
        val unknownWords = listOf("<unknown>", "unknown", "untitled", "various", "download", "original artist", "track", "downlo")
        val artistUnknown = song.artist.isBlank() || unknownWords.any { song.artist.contains(it, ignoreCase = true) }
        val titleIsRaw = song.title.endsWith(".mp3", ignoreCase = true) ||
                unknownWords.any { song.title.equals(it, ignoreCase = true) } ||
                song.title.contains("_") ||
                song.title.contains("download", ignoreCase = true) ||
                song.title.contains("Non-S", ignoreCase = true) ||
                song.title.startsWith("audio_", ignoreCase = true) ||
                song.title.startsWith("Dhun", ignoreCase = true) ||
                song.title.contains("Lyric", ignoreCase = true)
        return artistUnknown || titleIsRaw
    }

    suspend fun autoFixSong(context: Context, song: Song): FingerprintResult = withContext(Dispatchers.IO) {
        val embeddedResult = extractEmbeddedMetadata(context, song)
        if (embeddedResult != null && embeddedResult.matchConfidence >= 0.85f) {
            return@withContext embeddedResult
        }

        val cleanedTitle = cleanLocalTitle(song.title)
        val queries = mutableListOf<String>()

        if (cleanedTitle.contains(" X ", ignoreCase = true)) {
            val parts = cleanedTitle.split(Regex("""(?i)\s+x\s+"""))
            parts.firstOrNull()?.let { cleanLocalTitle(it).takeIf { s -> s.length >= 3 }?.let(queries::add) }
            parts.getOrNull(1)?.let { cleanLocalTitle(it).takeIf { s -> s.length >= 3 }?.let(queries::add) }
        }

        if (cleanedTitle.contains("-")) {
            val primary = cleanLocalTitle(cleanedTitle.split("-").first())
            if (primary.length >= 3) queries.add(primary)
        }

        if (cleanedTitle.isNotBlank()) {
            queries.add("$cleanedTitle hindi")
            queries.add(cleanedTitle)
        }

        val words = cleanedTitle.split(" ").filter { it.isNotBlank() }
        if (words.size >= 3) {
            queries.add(words.take(3).joinToString(" "))
        }

        for (q in queries.distinct()) {
            if (q.length < 3) continue

            val saavnMatch = queryJioSaavn(q, song.durationMs)
            if (saavnMatch != null && saavnMatch.matchConfidence >= 0.60f) {
                val localArt = saavnMatch.albumArtUrl?.let { saveArtworkLocally(context, song.id, it) }
                return@withContext saavnMatch.copy(albumArtUrl = localArt ?: saavnMatch.albumArtUrl)
            }

            val itunesMatch = queryItunes(q, song.durationMs)
            if (itunesMatch != null && itunesMatch.matchConfidence >= 0.60f) {
                val localArt = itunesMatch.albumArtUrl?.let { saveArtworkLocally(context, song.id, it) }
                return@withContext itunesMatch.copy(albumArtUrl = localArt ?: itunesMatch.albumArtUrl)
            }
        }

        if (cleanedTitle.length >= 3) {
            return@withContext FingerprintResult(
                title = cleanedTitle,
                artist = if (song.artist.contains("unknown", true) || song.artist.contains("downlo", true)) "Original Track" else song.artist,
                album = if (song.album.contains("downlo", true)) "Single" else song.album,
                albumArtUrl = song.albumArtUri,
                matchConfidence = 0.60f
            )
        }

        FingerprintResult(song.title, song.artist, song.album, song.albumArtUri, 0.0f)
    }

    private fun extractEmbeddedMetadata(context: Context, song: Song): FingerprintResult? {
        val retriever = MediaMetadataRetriever()
        return try {
            val uri = Uri.parse(song.uri)
            retriever.setDataSource(context, uri)
            val metaTitle = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.trim()
            val metaArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.trim()
            val metaAlbum = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.trim()

            val unknownList = listOf("unknown", "download", "original artist", "various", "track", "downlo")
            val isTitleValid = !metaTitle.isNullOrBlank() && unknownList.none { metaTitle.equals(it, ignoreCase = true) } && !metaTitle.contains(".mp3")
            val isArtistValid = !metaArtist.isNullOrBlank() && unknownList.none { metaArtist.equals(it, ignoreCase = true) }

            if (isTitleValid && isArtistValid) {
                FingerprintResult(
                    title = metaTitle!!,
                    artist = metaArtist!!,
                    album = metaAlbum?.ifBlank { "Single" } ?: "Single",
                    albumArtUrl = song.albumArtUri,
                    matchConfidence = 0.90f
                )
            } else null
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
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
                    connectTimeout = 4000
                    readTimeout = 4000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Android; Mobile)")
                    setRequestProperty("Accept", "application/json")
                }
                if (conn.responseCode != 200) continue

                val text = conn.inputStream.bufferedReader().use { it.readText() }
                val root = JSONObject(text)
                val dataObj = root.optJSONObject("data") ?: continue
                val results = dataObj.optJSONArray("results") ?: continue

                if (results.length() > 0) {
                    var bestMatch: JSONObject? = null
                    var minDiff = Long.MAX_VALUE
                    var highestWordScore = 0
                    
                    val qWords = query.lowercase().split(Regex("""\s+""")).filter { it.length > 2 }

                    for (i in 0 until results.length()) {
                        val item = results.getJSONObject(i)
                        val resultTitle = item.optString("name", "").lowercase()
                        
                        // Strict Match Rule: Must contain at least one major word from our query to prevent random identity assignment
                        val matchCount = qWords.count { resultTitle.contains(it) }
                        
                        if (qWords.isEmpty() || matchCount > 0) {
                            val durSec = item.optLong("duration", 0L)
                            val durMs = durSec * 1000L
                            if (durMs > 0 && targetDurationMs > 0) {
                                val diff = abs(durMs - targetDurationMs)
                                if (matchCount > highestWordScore || (matchCount == highestWordScore && diff < minDiff)) {
                                    highestWordScore = matchCount
                                    minDiff = diff
                                    bestMatch = item
                                }
                            }
                        }
                    }

                    if (bestMatch != null) {
                        val title = bestMatch.optString("name", "").replace("&quot;", "\"").replace("&#039;", "'").replace("&amp;", "&").trim()
                        var artist = ""

                        val artistsObj = bestMatch.optJSONObject("artists")
                        if (artistsObj != null) {
                            val primaryArr = artistsObj.optJSONArray("primary")
                            if (primaryArr != null && primaryArr.length() > 0) {
                                artist = primaryArr.getJSONObject(0).optString("name", "")
                            }
                        }
                        if (artist.isBlank()) {
                            artist = bestMatch.optString("primaryArtists", "Various Artists")
                        }

                        val album = bestMatch.optJSONObject("album")?.optString("name", "Single") ?: "Single"
                        val images = bestMatch.optJSONArray("image")
                        val artUrl = if (images != null && images.length() > 0) {
                            images.getJSONObject(images.length() - 1).optString("url", null)
                                ?: images.getJSONObject(images.length() - 1).optString("link", null)
                        } else null

                        if (title.isNotBlank()) {
                            return FingerprintResult(
                                title = title,
                                artist = artist.ifBlank { "Original Artist" },
                                album = album.ifBlank { "Single" },
                                albumArtUrl = artUrl,
                                matchConfidence = 0.90f
                            )
                        }
                    }
                }
            } catch (_: Exception) {
                continue
            }
        }
        return null
    }

    private fun queryItunes(cleanedTitle: String, targetDurationMs: Long): FingerprintResult? {
        return try {
            val encoded = URLEncoder.encode(cleanedTitle, "UTF-8")
            val url = URL("https://itunes.apple.com/search?term=$encoded&entity=song&limit=5&country=IN")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 4000
                readTimeout = 4000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Mozilla/5.0")
                setRequestProperty("Accept", "application/json")
            }
            if (conn.responseCode != 200) return null

            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val root = JSONObject(text)
            val results = root.optJSONArray("results") ?: return null

            if (results.length() > 0) {
                var bestMatch: JSONObject? = null
                var minDiff = Long.MAX_VALUE
                var highestWordScore = 0
                val qWords = cleanedTitle.lowercase().split(Regex("""\s+""")).filter { it.length > 2 }

                for (i in 0 until results.length()) {
                    val item = results.getJSONObject(i)
                    val resultTitle = item.optString("trackName", "").lowercase()
                    
                    val matchCount = qWords.count { resultTitle.contains(it) }
                    
                    if (qWords.isEmpty() || matchCount > 0) {
                        val durationMs = item.optLong("trackTimeMillis", 0L)
                        if (durationMs > 0 && targetDurationMs > 0) {
                            val diff = abs(durationMs - targetDurationMs)
                            if (matchCount > highestWordScore || (matchCount == highestWordScore && diff < minDiff)) {
                                highestWordScore = matchCount
                                minDiff = diff
                                bestMatch = item
                            }
                        }
                    }
                }

                if (bestMatch != null) {
                    val fixedTitle = bestMatch.optString("trackName", "").trim()
                    val fixedArtist = bestMatch.optString("artistName", "").trim()
                    val fixedAlbum = bestMatch.optString("collectionName", "Single").trim()
                    val rawArt = bestMatch.optString("artworkUrl100", "")
                    val hdArt = rawArt.replace("100x100bb", "600x600bb").ifBlank { null }

                    if (fixedTitle.isNotBlank()) {
                        return FingerprintResult(
                            title = fixedTitle,
                            artist = fixedArtist.ifBlank { "Original Artist" },
                            album = fixedAlbum.ifBlank { "Single" },
                            albumArtUrl = hdArt,
                            matchConfidence = 0.85f
                        )
                    }
                }
            }
            return null
        } catch (_: Exception) {
            null
        }
    }

    private fun saveArtworkLocally(context: Context, songId: Long, urlString: String): String? {
        return runCatching {
            val destFile = File(context.filesDir, "cover_$songId.jpg")
            val url = URL(urlString)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 4500
                readTimeout = 4500
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Mozilla/5.0")
            }
            if (conn.responseCode == 200) {
                conn.inputStream.use { input ->
                    FileOutputStream(destFile).use { output ->
                        input.copyTo(output)
                    }
                }
                if (destFile.exists() && destFile.length() > 512) {
                    Uri.fromFile(destFile).toString()
                } else null
            } else null
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
        val semaphore = Semaphore(3)
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
