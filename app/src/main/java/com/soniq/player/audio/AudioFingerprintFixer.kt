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
import kotlin.math.max

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
        Regex("""(?i)\(.*?\b(official\vert{}video\vert{}audio\vert{}lyric\vert{}lyrics\vert{}hd\vert{}4k\vert{}download\vert{}mp3\vert{}remix\vert{}mashup\vert{}slowed\vert{}reverb\vert{}full\vert{}song\vert{}ringtone\vert{}whatsapp\vert{}status\vert{}lofi\vert{}8d\vert{}bass\vert{}boosted)\b.*?\)"""),
        Regex("""(?i)__?(official|audio|video|lyric|lyrics|hd|4k|download|mp3|remix|full|song|ringtone).*"""),
        Regex("""(?i)_320kbps|_128kbps|_64kbps|320kbps|128kbps|\.mp3|\.m4a|\.flac|\.wav|\.aac|\.ogg"""),
        Regex("""^[0-9]+[\s._-]+"""),
        Regex("""(?i)\b(pagalworld|mr-jatt|djpunjab|wapking|pendujatt|ytmp3|yt-dlp|vevo|tseries|t-series|zee\s*music|sony\s*music|speed\s*records|saregama|tips\s*official|yrf|eros\s*now|download|dhun\s*song|full\s*video|full\s*audio|lyric\s*video|lyrics\s*video|lyric\s*v.*|official\s*video|official\s*audio|new\s*song)\b"""),
        Regex("""(?i)-\s*(non-s.*|non-stop.*|ek\s*dee.*|dj.*|remix.*|lyric.*)""")
    )

    private val STOP_WORDS = setOf(
        "the", "and", "for", "with", "from", "song", "songs", "dhun", "mp3",
        "full", "video", "audio", "official", "lyric", "lyrics", "new", "hit",
        "track", "original", "version", "feat", "ft"
    )

    fun cleanLocalTitle(rawTitle: String): String {
        var cleaned = rawTitle
            .replace("_", " ")
            .replace(Regex("""(?i)\.(mp3|m4a|flac|wav|aac|ogg)$"""), " ")

        for (pattern in SPAM_PATTERNS) {
            cleaned = cleaned.replace(pattern, " ")
        }

        // Remove trailing incomplete truncated fragments like "Pa_" or "H_" or "V_"
        cleaned = cleaned.replace(Regex("""\b[a-zA-Z]{1,2}$"""), " ")

        cleaned = cleaned.replace(Regex("""[^a-zA-Z0-9\s-]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim(' ', '-', '_')
        return cleaned
    }

    /**
     * Normalizes Hindi/Urdu/Bollywood transliteration differences so
     * "Andaaz" == "Andaz", "Kaffara" == "Kafara", "Saiyaara" == "Saiyara".
     */
    private fun normalizePhonetic(word: String): String {
        return word.lowercase()
            .replace(Regex("""[^a-z0-9]"""), "")
            .replace("aa", "a")
            .replace("ee", "i")
            .replace("oo", "u")
            .replace("kh", "k")
            .replace("gh", "g")
            .replace("bh", "b")
            .replace("dh", "d")
            .replace("th", "t")
            .replace("ph", "f")
            .replace("sh", "s")
            .replace("z", "j")
            .replace("w", "v")
            .replace("q", "k")
            .replace(Regex("""(.)\1+"""), "$1") // collapse double letters ("ff" -> "f", "ll" -> "l")
    }

    private fun extractSignificantTokens(text: String): List<String> {
        return text.lowercase()
            .replace(Regex("""[^a-z0-9\s]"""), " ")
            .split(Regex("""\s+"""))
            .map { it.trim() }
            .filter { it.length >= 2 && it !in STOP_WORDS }
    }

    private fun tokensMatch(queryToken: String, candidateToken: String): Boolean {
        if (queryToken == candidateToken) return true
        val nq = normalizePhonetic(queryToken)
        val nc = normalizePhonetic(candidateToken)
        if (nq.isEmpty() || nc.isEmpty()) return false
        if (nq == nc) return true
        if (nq.length >= 4 && nc.length >= 4 && (nq.startsWith(nc) || nc.startsWith(nq))) return true
        if (nq.length >= 4 && nc.length >= 4 && levenshtein(nq, nc) <= 1) return true
        return false
    }

    private fun levenshtein(a: String, b: String): Int {
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..b.length) {
                val temp = dp[j]
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + cost)
                prev = temp
            }
        }
        return dp[b.length]
    }

    /**
     * Scores a candidate song against our cleaned query tokens.
     * Returns 0f if the candidate does NOT genuinely match the query title.
     */
    private fun scoreCandidate(
        queryTokens: List<String>,
        candidateTitle: String,
        candidateArtist: String,
        candidateDurationMs: Long,
        targetDurationMs: Long
    ): Float {
        if (queryTokens.isEmpty()) return 0f
        val titleTokens = extractSignificantTokens(candidateTitle)
        if (titleTokens.isEmpty()) return 0f

        val allCandidateTokens = (titleTokens + extractSignificantTokens(candidateArtist)).distinct()

        // How many query tokens match the candidate title/artist?
        var titleMatchedCount = 0
        var totalMatchedCount = 0
        for (qt in queryTokens) {
            if (titleTokens.any { ct -> tokensMatch(qt, ct) }) {
                titleMatchedCount++
                totalMatchedCount++
            } else if (allCandidateTokens.any { ct -> tokensMatch(qt, ct) }) {
                totalMatchedCount++
            }
        }

        // Strict requirement: At least 1 core word in the candidate TITLE must match,
        // and at least 60% of the query tokens must match overall.
        val queryCoverage = totalMatchedCount.toFloat() / queryTokens.size.toFloat()
        val titleCoverage = titleMatchedCount.toFloat() / queryTokens.size.coerceAtMost(4).toFloat()

        if (titleMatchedCount == 0) return 0f
        if (queryTokens.size >= 2 && totalMatchedCount < 2 && titleCoverage < 0.65f) return 0f
        if (queryCoverage < 0.55f && titleCoverage < 0.65f) return 0f

        // Bonus if the very first significant word of the song title matches
        val firstWordBonus = if (tokensMatch(queryTokens.first(), titleTokens.first())) 0.20f else 0f

        // Penalty if candidate title has lots of unrelated words
        val reverseMatched = titleTokens.count { ct -> queryTokens.any { qt -> tokensMatch(qt, ct) } }
        val precision = reverseMatched.toFloat() / max(titleTokens.size, 1).toFloat()

        // Duration similarity bonus (up to +0.15f if within 12 seconds)
        val durationBonus = if (candidateDurationMs > 0 && targetDurationMs in 10_000L..600_000L) {
            val diff = abs(candidateDurationMs - targetDurationMs)
            when {
                diff <= 5_000L -> 0.15f
                diff <= 15_000L -> 0.10f
                diff <= 30_000L -> 0.04f
                else -> 0f
            }
        } else 0f

        return (queryCoverage * 0.50f) + (precision * 0.25f) + firstWordBonus + durationBonus
    }

    fun isSongUnknown(song: Song): Boolean {
        if (song.id < 0) return false
        val unknownWords = listOf("<unknown>", "unknown", "untitled", "various", "download", "original artist", "original track", "track", "downlo")
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
        // 1. Check genuine embedded ID3 tags first
        val embeddedResult = extractEmbeddedMetadata(context, song)
        if (embeddedResult != null && embeddedResult.matchConfidence >= 0.85f) {
            return@withContext embeddedResult
        }

        // 2. Build clean search candidates from the local title
        val cleanedTitle = cleanLocalTitle(song.title)
        val allTokens = extractSignificantTokens(cleanedTitle)
        val queries = mutableListOf<String>()

        if (cleanedTitle.contains(" X ", ignoreCase = true)) {
            val parts = cleanedTitle.split(Regex("""(?i)\s+x\s+"""))
            parts.firstOrNull()?.let { cleanLocalTitle(it).takeIf { s -> s.length >= 3 }?.let(queries::add) }
        }

        if (cleanedTitle.contains("-")) {
            val primary = cleanLocalTitle(cleanedTitle.split("-").first())
            if (primary.length >= 3) queries.add(primary)
        }

        // Add 3-word and 4-word core title prefixes (e.g., "Andaaz e Karam", "Bol Kaffara Kya Hoga", "Dil Ka Jo Haal Hai")
        if (allTokens.size >= 4) {
            queries.add(allTokens.take(4).joinToString(" "))
        }
        if (allTokens.size >= 3) {
            queries.add(allTokens.take(3).joinToString(" "))
        }
        if (cleanedTitle.isNotBlank()) {
            queries.add(cleanedTitle)
        }
        if (allTokens.size >= 2) {
            queries.add(allTokens.take(2).joinToString(" "))
        }

        var bestOverall: FingerprintResult? = null

        for (q in queries.distinct()) {
            if (q.length < 3) continue
            val qTokens = extractSignificantTokens(q)
            if (qTokens.isEmpty()) continue

            val saavnMatch = queryJioSaavn(q, qTokens, song.durationMs)
            if (saavnMatch != null && (bestOverall == null || saavnMatch.matchConfidence > bestOverall.matchConfidence)) {
                bestOverall = saavnMatch
                if (saavnMatch.matchConfidence >= 0.82f) break
            }

            val itunesMatch = queryItunes(q, qTokens, song.durationMs)
            if (itunesMatch != null && (bestOverall == null || itunesMatch.matchConfidence > bestOverall.matchConfidence)) {
                bestOverall = itunesMatch
                if (itunesMatch.matchConfidence >= 0.82f) break
            }
        }

        if (bestOverall != null && bestOverall.matchConfidence >= 0.65f) {
            val localArt = bestOverall.albumArtUrl?.let { saveArtworkLocally(context, song.id, it) }
            return@withContext bestOverall.copy(albumArtUrl = localArt ?: bestOverall.albumArtUrl)
        }

        // If no verified online match passed our strict word-overlap check, return 0.0f so we never overwrite with a wrong song
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

            val unknownList = listOf("unknown", "download", "original artist", "original track", "various", "track", "downlo")
            val isTitleValid = !metaTitle.isNullOrBlank() &&
                    unknownList.none { metaTitle.contains(it, ignoreCase = true) } &&
                    !metaTitle.contains("_") &&
                    !metaTitle.endsWith(".mp3", true)
            val isArtistValid = !metaArtist.isNullOrBlank() &&
                    unknownList.none { metaArtist.contains(it, ignoreCase = true) }

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

    private fun queryJioSaavn(query: String, queryTokens: List<String>, targetDurationMs: Long): FingerprintResult? {
        val endpoints = listOf(
            "https://saavn.dev/api/search/songs",
            "https://jiosaavn-api-privatecvc2.vercel.app/search/songs"
        )
        for (baseUrl in endpoints) {
            try {
                val encoded = URLEncoder.encode(query, "UTF-8")
                val url = URL("$baseUrl?query=$encoded&limit=10")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 4500
                    readTimeout = 4500
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Android; Mobile)")
                    setRequestProperty("Accept", "application/json")
                }
                if (conn.responseCode != 200) continue

                val text = conn.inputStream.bufferedReader().use { it.readText() }
                val root = JSONObject(text)
                val dataObj = root.optJSONObject("data") ?: continue
                val results = dataObj.optJSONArray("results") ?: continue

                var bestItem: JSONObject? = null
                var bestScore = 0f

                for (i in 0 until results.length()) {
                    val item = results.getJSONObject(i)
                    val rawName = item.optString("name", "")
                        .replace("&quot;", "\"")
                        .replace("&#039;", "'")
                        .replace("&amp;", "&")
                        .trim()

                    var artistName = ""
                    val artistsObj = item.optJSONObject("artists")
                    if (artistsObj != null) {
                        val primaryArr = artistsObj.optJSONArray("primary")
                        if (primaryArr != null && primaryArr.length() > 0) {
                            artistName = (0 until primaryArr.length()).mapNotNull { idx ->
                                primaryArr.getJSONObject(idx).optString("name", "").takeIf { it.isNotBlank() }
                            }.joinToString(", ")
                        }
                    }
                    if (artistName.isBlank()) {
                        artistName = item.optString("primaryArtists", "")
                    }

                    val durMs = item.optLong("duration", 0L) * 1000L
                    val score = scoreCandidate(queryTokens, rawName, artistName, durMs, targetDurationMs)

                    if (score > bestScore) {
                        bestScore = score
                        bestItem = item
                    }
                }

                if (bestItem != null && bestScore >= 0.65f) {
                    val title = bestItem.optString("name", "")
                        .replace("&quot;", "\"")
                        .replace("&#039;", "'")
                        .replace("&amp;", "&")
                        .trim()

                    var artist = ""
                    val artistsObj = bestItem.optJSONObject("artists")
                    if (artistsObj != null) {
                        val primaryArr = artistsObj.optJSONArray("primary")
                        if (primaryArr != null && primaryArr.length() > 0) {
                            artist = (0 until primaryArr.length().coerceAtMost(2)).mapNotNull { idx ->
                                primaryArr.getJSONObject(idx).optString("name", "").takeIf { it.isNotBlank() }
                            }.joinToString(", ")
                        }
                    }
                    if (artist.isBlank()) {
                        artist = bestItem.optString("primaryArtists", "Unknown Artist")
                    }

                    val album = bestItem.optJSONObject("album")?.optString("name", "Single")
                        ?.replace("&quot;", "\"")
                        ?.replace("&amp;", "&")
                        ?: "Single"

                    val images = bestItem.optJSONArray("image")
                    val artUrl = if (images != null && images.length() > 0) {
                        images.getJSONObject(images.length() - 1).optString("url", null)
                            ?: images.getJSONObject(images.length() - 1).optString("link", null)
                    } else null

                    if (title.isNotBlank()) {
                        return FingerprintResult(
                            title = title,
                            artist = artist.ifBlank { "Unknown Artist" },
                            album = album.ifBlank { "Single" },
                            albumArtUrl = artUrl,
                            matchConfidence = bestScore.coerceIn(0.65f, 0.98f)
                        )
                    }
                }
            } catch (_: Exception) {
                continue
            }
        }
        return null
    }

    private fun queryItunes(query: String, queryTokens: List<String>, targetDurationMs: Long): FingerprintResult? {
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = URL("https://itunes.apple.com/search?term=$encoded&entity=song&limit=10&country=IN")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 4500
                readTimeout = 4500
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Mozilla/5.0")
                setRequestProperty("Accept", "application/json")
            }
            if (conn.responseCode != 200) return null

            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val root = JSONObject(text)
            val results = root.optJSONArray("results") ?: return null

            var bestItem: JSONObject? = null
            var bestScore = 0f

            for (i in 0 until results.length()) {
                val item = results.getJSONObject(i)
                val resultTitle = item.optString("trackName", "").trim()
                val resultArtist = item.optString("artistName", "").trim()
                val durationMs = item.optLong("trackTimeMillis", 0L)

                val score = scoreCandidate(queryTokens, resultTitle, resultArtist, durationMs, targetDurationMs)
                if (score > bestScore) {
                    bestScore = score
                    bestItem = item
                }
            }

            if (bestItem != null && bestScore >= 0.65f) {
                val fixedTitle = bestItem.optString("trackName", "").trim()
                val fixedArtist = bestItem.optString("artistName", "").trim()
                val fixedAlbum = bestItem.optString("collectionName", "Single").trim()
                val rawArt = bestItem.optString("artworkUrl100", "")
                val hdArt = rawArt.replace("100x100bb", "600x600bb").ifBlank { null }

                if (fixedTitle.isNotBlank()) {
                    return FingerprintResult(
                        title = fixedTitle,
                        artist = fixedArtist.ifBlank { "Unknown Artist" },
                        album = fixedAlbum.ifBlank { "Single" },
                        albumArtUrl = hdArt,
                        matchConfidence = bestScore.coerceIn(0.65f, 0.96f)
                    )
                }
            }
            null
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
                        if (result.matchConfidence >= 0.65f) {
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
