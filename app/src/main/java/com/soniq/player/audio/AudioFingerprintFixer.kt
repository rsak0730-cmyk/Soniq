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
        Regex("""(?i)\[.*?(official|audio|video|lyric|lyrics|hd|4k|download|mp3|remix|full).*?\]"""),
        Regex("""(?i)\(.*?(official|audio|video|lyric|lyrics|hd|4k|download|mp3|remix|full).*?\)"""),
        Regex("""(?i)__?(official|audio|video|lyric|lyrics|hd|4k|download|mp3|remix|full).*"""),
        Regex("""(?i)_320kbps|_128kbps|\.mp3|\.m4a|\.flac|\.wav"""),
        Regex("""^[0-9]+[\s._-]+"""),
        Regex("""(?i)pagalworld|mr-jatt|djpunjab|ytmp3|yt-dlp|vevo""")
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

        // STEP 1: Ultra-Fast Text Search with Millisecond Proximity Match (< 200ms)
        val onlineFastMatch = queryOnlineFast(cleanedTitle, song)
        if (onlineFastMatch != null && onlineFastMatch.matchConfidence >= 0.85f) {
            return@withContext onlineFastMatch
        }

        // STEP 2: Waveform AcoustID Fingerprinting only when search is inconclusive
        val audioUri = Uri.parse(song.uri)
        val pcmData = PcmAudioDecoder.extractFingerprintData(context, audioUri)
        if (pcmData != null) {
            val (durationSec, fingerprint) = pcmData
            val acoustResult = acoustIdClient.lookup(durationSec, fingerprint)
            if (acoustResult != null && acoustResult.matchConfidence >= 0.65f) {
                val coverArt = ArtworkScraper.searchCoverArtUrl("${acoustResult.title} ${acoustResult.artist}")
                return@withContext acoustResult.copy(albumArtUrl = coverArt)
            }
        }

        // Return the best fallback available
        onlineFastMatch ?: FingerprintResult(cleanedTitle, song.artist, song.album, null, 0.4f)
    }

    private fun queryOnlineFast(cleanedTitle: String, song: Song): FingerprintResult? {
        val cleanedArtist = if (song.artist.contains("Unknown", true)) "" 
        else song.artist.replace(Regex("""(?i)various|unknown"""), "").trim()

        val searchQuery = if (cleanedArtist.isNotBlank()) "$cleanedTitle $cleanedArtist" else cleanedTitle

        return try {
            val encoded = URLEncoder.encode(searchQuery, "UTF-8")
            val url = URL("https://itunes.apple.com/search?term=$encoded&entity=song&limit=4")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 3000
                readTimeout = 3000
            }

            val jsonStr = conn.inputStream.bufferedReader().use { it.readText() }
            val root = JSONObject(jsonStr)
            val results = root.getJSONArray("results")

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
                val fixedArtist = best.optString("artistName", song.artist)
                val fixedAlbum = best.optString("collectionName", song.album)
                val rawArt = best.optString("artworkUrl100", "")
                val hdArt = rawArt.replace("100x100bb", "600x600bb").ifBlank { null }
                val durationMatches = minDiff < 8000 // Within 8 seconds of duration

                FingerprintResult(
                    title = fixedTitle,
                    artist = fixedArtist,
                    album = fixedAlbum,
                    albumArtUrl = hdArt,
                    matchConfidence = if (durationMatches) 0.95f else 0.70f
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
        var fixedCount = 0

        // Concurrency limiter to prevent network throttling while maximizing speed
        val semaphore = Semaphore(2)
        var completedSoFar = 0

        unknownList.map { song ->
            async {
                semaphore.withPermit {
                    val result = autoFixSong(context, song)

                    synchronized(this@AudioFingerprintFixer) {
                        completedSoFar++
                    }

                    withContext(Dispatchers.Main) {
                        onProgress(completedSoFar, total, song.title)
                    }

                    if (result.matchConfidence >= 0.65f) {
                        withContext(Dispatchers.Main) {
                            onSongFixed(song, result)
                        }
                        synchronized(this@AudioFingerprintFixer) {
                            fixedCount++
                        }
                    }
                }
            }
        }.awaitAll()

        fixedCount
    }
}
