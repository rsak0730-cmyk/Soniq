package com.soniq.player.audio

import android.content.Context
import android.net.Uri
import com.soniq.player.data.ArtworkScraper
import com.soniq.player.data.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
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
        Regex("""(?i)\[.*?(official|audio|video|lyrics|hd|4k|download|mp3).*?\]"""),
        Regex("""(?i)\(.*?(official|audio|video|lyrics|hd|4k|download|mp3).*?\)"""),
        Regex("""(?i)_320kbps|_128kbps|\.mp3|\.m4a|\.flac|\.wav"""),
        Regex("""^[0-9]+[\s._-]+"""),
        Regex("""(?i)pagalworld|mr-jatt|djpunjab|ytmp3|yt-dlp""")
    )

    private val acoustIdClient = AcoustIdClient()

    fun cleanLocalTitle(rawTitle: String): String {
        var cleaned = rawTitle
        for (pattern in SPAM_PATTERNS) {
            cleaned = cleaned.replace(pattern, " ")
        }
        return cleaned.replace(Regex("""\s+"""), " ").trim()
    }

    fun isSongUnknown(song: Song): Boolean {
        val unknownNames = listOf("<unknown>", "unknown", "untitled", "track")
        val artistUnknown = song.artist.isBlank() || unknownNames.any { song.artist.contains(it, ignoreCase = true) }
        val titleIsRaw = song.title.endsWith(".mp3", ignoreCase = true) ||
                unknownNames.any { song.title.equals(it, ignoreCase = true) } ||
                song.title.startsWith("audio_", ignoreCase = true) ||
                song.title.startsWith("track", ignoreCase = true)
        return artistUnknown || titleIsRaw
    }

    suspend fun autoFixSong(context: Context, song: Song): FingerprintResult = withContext(Dispatchers.IO) {
        val cleanedTitle = cleanLocalTitle(song.title)

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

        val cleanedArtist = if (song.artist.contains("Unknown", true)) {
            ""
        } else {
            song.artist.replace(Regex("""(?i)various|unknown"""), "").trim()
        }

        val searchQuery = if (cleanedArtist.isNotBlank()) "$cleanedTitle $cleanedArtist" else cleanedTitle

        try {
            val encoded = URLEncoder.encode(searchQuery, "UTF-8")
            val url = URL("https://itunes.apple.com/search?term=$encoded&entity=song&limit=5")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 6000
                readTimeout = 6000
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
                val durationMatches = minDiff < 7000
                val confidence = if (durationMatches) 0.95f else 0.75f

                FingerprintResult(
                    title = fixedTitle,
                    artist = fixedArtist,
                    album = fixedAlbum,
                    albumArtUrl = hdArt,
                    matchConfidence = confidence
                )
            } else {
                FingerprintResult(cleanedTitle, song.artist, song.album, null, 0.4f)
            }
        } catch (_: Exception) {
            FingerprintResult(cleanedTitle, song.artist, song.album, null, 0.3f)
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

        unknownList.forEachIndexed { index, song ->
            withContext(Dispatchers.Main) {
                onProgress(index + 1, total, song.title)
            }

            val result = autoFixSong(context, song)
            if (result.matchConfidence >= 0.65f) {
                withContext(Dispatchers.Main) {
                    onSongFixed(song, result)
                }
                fixedCount++
            }
            delay(350)
        }
        fixedCount
    }
}
