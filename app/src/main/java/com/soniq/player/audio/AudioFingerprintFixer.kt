package com.soniq.player.audio

import android.content.Context
import android.net.Uri
import com.soniq.player.data.ArtworkScraper
import com.soniq.player.data.Song
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
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
        Regex("""(?i)\[.*?(official\vert{}audio\vert{}video\vert{}lyric\vert{}lyrics\vert{}hd\vert{}4k\vert{}download\vert{}mp3\vert{}remix\vert{}full\vert{}song).*?\]"""),
        Regex("""(?i)\(.*?(official\vert{}audio\vert{}video\vert{}lyric\vert{}lyrics\vert{}hd\vert{}4k\vert{}download\vert{}mp3\vert{}remix\vert{}full\vert{}song).*?\)"""),
        Regex("""(?i)__?(official|audio|video|lyric|lyrics|hd|4k|download|mp3|remix|full).*"""),
        Regex("""(?i)_320kbps|_128kbps|\.mp3|\.m4a|\.flac|\.wav"""),
        Regex("""^[0-9]+[\s._-]+"""),
        Regex("""(?i)pagalworld|mr-jatt|djpunjab|ytmp3|yt-dlp|vevo|tseries|zee\s*music|speed\s*records""")
    )

    private val acoustIdClient = AcoustIdClient()

    fun cleanLocalTitle(rawTitle: String): String {
        var cleaned = rawTitle.replace("_", " ")
        for (pattern in SPAM_PATTERNS) {
            cleaned = cleaned.replace(pattern, " ")
        }
        return cleaned.replace(Regex("""\s+"""), " ").trim()
    }

    fun isSongUnknown(song: Song): Boolean {
        // Skip radio streams or negative temporary IDs
        if (song.id < 0 || song.folder.equals("Radio", ignoreCase = true)) return false

        val unknownWords = listOf("<unknown>", "unknown", "untitled", "track")
        val artistUnknown = song.artist.isBlank() || unknownWords.any { song.artist.contains(it, ignoreCase = true) }
        val titleIsRaw = song.title.endsWith(".mp3", ignoreCase = true) ||
                unknownWords.any { song.title.equals(it, ignoreCase = true) } ||
                song.title.contains("lyric", ignoreCase = true) ||
                song.title.contains("video", ignoreCase = true) ||
                song.title.contains("_") ||
                song.title.startsWith("audio_", ignoreCase = true)
        return artistUnknown || titleIsRaw
    }

    suspend fun autoFixSong(context: Context, song: Song): FingerprintResult = withContext(Dispatchers.IO) {
        val cleanedTitle = cleanLocalTitle(song.title)

        // Pass 1: Localized & Title-Only Online Search
        val onlineMatch = queryOnlineSmart(cleanedTitle, song)
        if (onlineMatch != null && onlineMatch.matchConfidence >= 0.60f) {
            return@withContext onlineMatch
        }

        // Pass 2: Waveform Fingerprinting (Safe check)
        runCatching {
            val audioUri = Uri.parse(song.uri)
            if (audioUri.scheme != null && !audioUri.toString().startsWith("http")) {
                val pcmData = PcmAudioDecoder.extractFingerprintData(context, audioUri)
                if (pcmData != null) {
                    val (durationSec, fingerprint) = pcmData
                    val acoustResult = acoustIdClient.lookup(durationSec, fingerprint)
                    if (acoustResult != null && acoustResult.matchConfidence >= 0.55f) {
                        val coverArt = ArtworkScraper.searchCoverArtUrl("${acoustResult.title}${acoustResult.artist}")
                        return@withContext acoustResult.copy(albumArtUrl = coverArt)
                    }
                }
            }
        }

        // Pass 3: Fallback cleaned metadata
        val safeArtist = if (song.artist.contains("unknown", true) || song.artist.isBlank()) "Various Artists" else song.artist
        onlineMatch ?: FingerprintResult(
            title = cleanedTitle.ifBlank { song.title },
            artist = safeArtist,
            album = song.album.ifBlank { "Unknown Album" },
            albumArtUrl = null,
            matchConfidence = 0.50f
        )
    }

    private suspend fun queryOnlineSmart(cleanedTitle: String, song: Song): FingerprintResult? = withContext(Dispatchers.IO) {
        val cleanArtist = if (song.artist.contains("Unknown", true)) "" 
        else song.artist.replace(Regex("""(?i)various|unknown"""), "").trim()

        val queries = mutableListOf<String>()
        if (cleanArtist.isNotBlank()) queries.add("$cleanedTitle$cleanArtist")
        queries.add(cleanedTitle)

        for (query in queries) {
            val match = searchStore(query, song.durationMs)
            if (match != null) return@withContext match
        }
        null
    }

    private fun searchStore(query: String, targetDurationMs: Long): FingerprintResult? {
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = URL("https://itunes.apple.com/search?term=$encoded&entity=song&limit=6&country=IN")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 3000
                readTimeout = 3000
            }

            val jsonStr = conn.inputStream.bufferedReader().use { it.readText() }
            val root = JSONObject(jsonStr)
            val results = root.optJSONArray("results") ?: return null

            if (results.length() > 0) {
                var bestIndex = 0
                var minDiff = Long.MAX_VALUE

                for (i in 0 until results.length()) {
                    val item = results.getJSONObject(i)
                    val durationMs = item.optLong("trackTimeMillis", 0L)
                    if (durationMs > 0 && targetDurationMs > 0) {
                        val diff = abs(durationMs - targetDurationMs)
                        if (diff < minDiff) {
                            minDiff = diff
                            bestIndex = i
                        }
                    }
                }

                val best = results.getJSONObject(bestIndex)
                val fixedTitle = best.optString("trackName", query)
                val fixedArtist = best.optString("artistName", "Unknown Artist")
                val fixedAlbum = best.optString("collectionName", "Single")
                val rawArt = best.optString("artworkUrl100", "")
                val hdArt = rawArt.replace("100x100bb", "600x600bb").ifBlank { null }

                val confidence = when {
                    minDiff < 8000 -> 0.95f
                    minDiff < 45000 -> 0.85f
                    else -> 0.70f
                }

                FingerprintResult(
                    title = fixedTitle,
                    artist = fixedArtist,
                    album = fixedAlbum,
                    albumArtUrl = hdArt,
                    matchConfidence = confidence
                )
            } else null
        } catch (_: Exception) {
            null
        }
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
        val semaphore = Semaphore(2)
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
