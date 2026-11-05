package com.soniq.player.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.BufferedReader
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.regex.Pattern

data class TimedWord(val text: String, val startMs: Long, val endMs: Long)

data class LyricLine(
    val timestampMs: Long,
    val text: String,
    val words: List<TimedWord> = emptyList()
)

object LyricsEngine {
    private val LRC_PATTERN = Pattern.compile("""\[(\d{2}):(\d{2})(?:\.(\d{2,3}))?\](.*)""")

    suspend fun loadLyricsForSong(context: Context, song: Song): List<LyricLine> = withContext(Dispatchers.IO) {
        val localLyrics = loadLocalLrc(context, song)
        if (localLyrics.isNotEmpty()) return@withContext localLyrics

        val onlineLyrics = fetchOnlineLyricsSmart(song)
        if (onlineLyrics.isNotEmpty()) {
            cacheLyricsToFile(context, song.id, onlineLyrics)
            return@withContext onlineLyrics
        }

        emptyList()
    }

    private fun cleanForLyricsSearch(raw: String): String {
        return raw
            .replace("_", " ")
            .replace(Regex("""(?i)\[.*?\]|\(.*?\)"""), " ")
            .replace(Regex("""(?i)\b(official|video|audio|lyric|lyrics|full|hd|4k|mp3|download|remix|mashup|slowed|reverb|lofi|320kbps|128kbps|ek\s*dee|non-s.*)\b"""), " ")
            .replace(Regex("""^[0-9]+[\s._-]+"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun fetchOnlineLyricsSmart(song: Song): List<LyricLine> {
        val cleanTitle = cleanForLyricsSearch(song.title)
        val cleanArtist = if (song.artist.contains("unknown", true) || song.artist.contains("various", true) || song.artist.contains("original artist", true)) {
            ""
        } else cleanForLyricsSearch(song.artist)

        val queries = mutableListOf<String>()

        if (cleanTitle.contains(" X ", ignoreCase = true)) {
            val parts = cleanTitle.split(Regex("""(?i)\s+x\s+"""))
            val first = cleanForLyricsSearch(parts.firstOrNull().orEmpty())
            if (first.length >= 3) queries.add(first)
            val second = cleanForLyricsSearch(parts.getOrNull(1).orEmpty())
            if (second.length >= 3) queries.add(second)
        }

        if (cleanArtist.isNotBlank() && cleanTitle.isNotBlank()) {
            queries.add("$cleanTitle $cleanArtist")
        }
        if (cleanTitle.isNotBlank()) {
            queries.add(cleanTitle)
            if (cleanTitle.contains("-")) {
                val mainPart = cleanTitle.split("-").first().trim()
                if (mainPart.length >= 3) queries.add(mainPart)
            }
        }

        for (query in queries.distinct()) {
            val found = searchLrcLib(query, song)
            if (found.isNotEmpty()) return postProcessWordTimings(found, song.durationMs)
        }

        return emptyList()
    }

    private fun searchLrcLib(query: String, song: Song): List<LyricLine> {
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = URL("https://lrclib.net/api/search?q=$encoded")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 4000
                readTimeout = 4000
                setRequestProperty("User-Agent", "SONIQ-Player/0.9.8")
            }
            if (conn.responseCode != 200) return emptyList()

            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val array = JSONArray(body)
            if (array.length() == 0) return emptyList()

            val expectedTitle = cleanForLyricsSearch(song.title).lowercase()
            val expectedArtist = cleanForLyricsSearch(song.artist).lowercase()
            val candidates = (0 until array.length()).map { array.getJSONObject(it) }.mapNotNull { obj ->
                val candidateTitle = cleanForLyricsSearch(obj.optString("trackName", "")).lowercase()
                val candidateArtist = cleanForLyricsSearch(obj.optString("artistName", "")).lowercase()
                val candidateDuration = (obj.optDouble("duration", 0.0) * 1000.0).toLong()
                var score = when {
                    candidateTitle == expectedTitle -> 100
                    candidateTitle.isNotBlank() && expectedTitle.isNotBlank() &&
                        (candidateTitle.contains(expectedTitle) || expectedTitle.contains(candidateTitle)) -> 45
                    else -> 0
                }
                if (expectedArtist.isNotBlank() && candidateArtist == expectedArtist) score += 80
                else if (expectedArtist.isNotBlank() && candidateArtist.isNotBlank() &&
                    (candidateArtist.contains(expectedArtist) || expectedArtist.contains(candidateArtist))) score += 35
                else if (expectedArtist.isNotBlank() && candidateArtist.isNotBlank()) score -= 35
                if (candidateDuration > 0L && song.durationMs > 0L) {
                    val difference = kotlin.math.abs(candidateDuration - song.durationMs)
                    score += when {
                        difference <= 3000L -> 50
                        difference <= 8000L -> 25
                        difference <= 15000L -> 5
                        else -> -40
                    }
                }
                val candidateLyrics = obj.optString("syncedLyrics", "")
                // Prefer Hindi-script results when the provider exposes multiple exact matches.
                if (candidateLyrics.contains(Regex("""[\u0900-\u097F]"""))) score += 15
                if (score >= 65) obj to score else null
            }.sortedByDescending { it.second }.map { it.first }

            for (obj in candidates) {
                val synced = obj.optString("syncedLyrics", "")
                if (synced.isNotBlank() && synced != "null") {
                    val parsed = synced.lines().mapNotNull { parseLrcLine(it) }.sortedBy { it.timestampMs }
                    if (parsed.isNotEmpty()) return parsed
                }
            }
            // Plain lyrics have no timing data. Do not fabricate timestamps or pretend they sync.

        } catch (_: Exception) {
            emptyList()
        }
    }

    // Only use per-word timestamps supplied by Enhanced LRC. Never invent evenly spaced
    // word timings: line-synced lyrics do not contain enough timing data for karaoke accuracy.
    private fun postProcessWordTimings(lines: List<LyricLine>, totalDurationMs: Long): List<LyricLine> =
        lines.sortedBy { it.timestampMs }.map { line ->
            if (line.words.isEmpty()) line else line.copy(
                words = line.words.map { word ->
                    word.copy(
                        startMs = word.startMs.coerceIn(0L, totalDurationMs.coerceAtLeast(0L)),
                        endMs = word.endMs.coerceIn(word.startMs, totalDurationMs.coerceAtLeast(word.startMs))
                    )
                }
            )
        }

    private fun loadLocalLrc(context: Context, song: Song): List<LyricLine> {
        val lines = mutableListOf<LyricLine>()
        runCatching {
            val cacheFile = File(context.filesDir, "lyrics_${song.id}.lrc")
            val reader: BufferedReader? = if (cacheFile.exists()) {
                cacheFile.bufferedReader()
            } else {
                val audioUri = Uri.parse(song.uri)
                if (audioUri.scheme == "file") {
                    val lrcFile = File(audioUri.path?.substringBeforeLast('.') + ".lrc")
                    if (lrcFile.exists()) lrcFile.bufferedReader() else null
                } else null
            }

            reader?.useLines { seq ->
                seq.forEach { line ->
                    parseLrcLine(line)?.let { lines.add(it) }
                }
            }
        }
        return if (lines.isNotEmpty()) postProcessWordTimings(lines.sortedBy { it.timestampMs }, song.durationMs) else emptyList()
    }

    private fun parseLrcLine(rawLine: String): LyricLine? {
        val matcher = LRC_PATTERN.matcher(rawLine)
        if (!matcher.find()) return null

        val min = matcher.group(1)?.toLongOrNull() ?: 0L
        val sec = matcher.group(2)?.toLongOrNull() ?: 0L
        val msStr = matcher.group(3) ?: "0"
        val ms = when (msStr.length) {
            1 -> msStr.toLong() * 100
            2 -> msStr.toLong() * 10
            3 -> msStr.toLong()
            else -> 0L
        }
        val lineTime = (min * 60 + sec) * 1000 + ms
        val rawText = matcher.group(4)?.trim().orEmpty()
        if (rawText.isBlank()) return null

        // Enhanced LRC word markers: <mm:ss.xx> immediately before each word.
        val marker = Regex("""<(\\d{2}):(\\d{2})(?:\\.(\\d{2,3}))?>""")
        val matches = marker.findAll(rawText).toList()
        if (matches.isEmpty()) return LyricLine(lineTime, rawText)

        fun markerTime(m: MatchResult): Long {
            val fraction = m.groupValues[3]
            val fractionMs = when (fraction.length) {
                1 -> fraction.toLong() * 100
                2 -> fraction.toLong() * 10
                3 -> fraction.toLong()
                else -> 0L
            }
            return (m.groupValues[1].toLong() * 60 + m.groupValues[2].toLong()) * 1000 + fractionMs
        }

        val words = mutableListOf<TimedWord>()
        matches.forEachIndexed { index, match ->
            val wordStart = markerTime(match)
            val textStart = match.range.last + 1
            val textEnd = if (index + 1 < matches.size) matches[index + 1].range.first else rawText.length
            val wordText = rawText.substring(textStart, textEnd).trim()
            if (wordText.isNotBlank()) {
                val nextStart = matches.getOrNull(index + 1)?.let(::markerTime)
                    ?: (wordStart + 650L)
                words += TimedWord(wordText, wordStart, nextStart.coerceAtLeast(wordStart + 1L))
            }
        }
        val cleanText = marker.replace(rawText, "").replace(Regex("""\\s+"""), " ").trim()
        return LyricLine(lineTime, cleanText.ifBlank { rawText }, words)
    }

    private fun cacheLyricsToFile(context: Context, songId: Long, lines: List<LyricLine>) {
        if (songId <= 0) return
        runCatching {
            val cacheFile = File(context.filesDir, "lyrics_$songId.lrc")
            cacheFile.bufferedWriter().use { writer ->
                lines.forEach { line ->
                    val totalSec = line.timestampMs / 1000
                    val min = totalSec / 60
                    val sec = totalSec % 60
                    val hundredths = (line.timestampMs % 1000) / 10
                    writer.write(String.format("[%02d:%02d.%02d]%s\n", min, sec, hundredths, line.text))
                }
            }
        }
    }
}
