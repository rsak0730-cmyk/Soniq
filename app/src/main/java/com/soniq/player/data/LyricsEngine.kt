package com.soniq.player.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.regex.Pattern

data class LyricLine(val timestampMs: Long, val text: String)

object LyricsEngine {
    private val LRC_PATTERN = Pattern.compile("""\[(\d{2}):(\d{2})(?:\.(\d{2,3}))?\](.*)""")

    suspend fun loadLyricsForSong(context: Context, song: Song): List<LyricLine> = withContext(Dispatchers.IO) {
        // 1. Check cached .lrc or local .lrc file
        val localLyrics = loadLocalLrc(context, song)
        if (localLyrics.isNotEmpty()) return@withContext localLyrics

        // 2. Fetch via LRCLIB Fuzzy Search + Exact Match Pipeline
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
            .replace(Regex("""(?i)\b(official|video|audio|lyric|lyrics|full|hd|4k|mp3|download|remix|mashup|slowed|reverb|lofi|320kbps|128kbps)\b"""), " ")
            .replace(Regex("""^[0-9]+[\s._-]+"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun fetchOnlineLyricsSmart(song: Song): List<LyricLine> {
        val cleanTitle = cleanForLyricsSearch(song.title)
        val isUnknownArtist = song.artist.contains("unknown", true) ||
                song.artist.contains("various", true) ||
                song.artist.contains("download", true)
        val cleanArtist = if (isUnknownArtist) "" else cleanForLyricsSearch(song.artist)

        // Build progressive search queries from most specific to broadest
        val queries = mutableListOf<String>()
        if (cleanArtist.isNotBlank() && cleanTitle.isNotBlank()) {
            queries.add("$cleanTitle $cleanArtist")
        }
        if (cleanTitle.isNotBlank()) {
            queries.add(cleanTitle)
            // If it's a mashup like "Kalyani X Shreya Ghoshal", also try first part
            if (cleanTitle.contains(" X ", ignoreCase = true)) {
                val firstPart = cleanTitle.split(Regex("""(?i)\s+x\s+""")).firstOrNull()?.trim()
                if (!firstPart.isNullOrBlank() && firstPart.length >= 3) {
                    queries.add(firstPart)
                }
            }
        }

        for (query in queries.distinct()) {
            val found = searchLrcLib(query, song.durationMs)
            if (found.isNotEmpty()) return found
        }

        return emptyList()
    }

    private fun searchLrcLib(query: String, songDurationMs: Long): List<LyricLine> {
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = URL("https://lrclib.net/api/search?q=$encoded")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 4500
                readTimeout = 4500
                setRequestProperty("User-Agent", "SONIQ-Android-Player/0.8.0")
            }

            if (conn.responseCode != 200) return emptyList()

            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val array = JSONArray(body)
            if (array.length() == 0) return emptyList()

            // First priority: any result with syncedLyrics
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val synced = obj.optString("syncedLyrics", "")
                if (synced.isNotBlank() && synced != "null") {
                    val parsed = synced.lines().mapNotNull { parseLrcLine(it) }.sortedBy { it.timestampMs }
                    if (parsed.isNotEmpty()) return parsed
                }
            }

            // Second priority: convert plainLyrics into evenly timed lines across the song duration
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val plain = obj.optString("plainLyrics", "")
                if (plain.isNotBlank() && plain != "null") {
                    val rawLines = plain.lines().map { it.trim() }.filter { it.isNotBlank() }
                    if (rawLines.isNotEmpty()) {
                        val totalDuration = if (songDurationMs > 15_000L) songDurationMs else 180_000L
                        val stepMs = (totalDuration / (rawLines.size + 1)).coerceAtLeast(2500L)
                        return rawLines.mapIndexed { idx, text ->
                            LyricLine(timestampMs = (idx + 1) * stepMs, text = text)
                        }
                    }
                }
            }

            emptyList()
        } catch (_: Exception) {
            emptyList()
        }
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
        return lines.sortedBy { it.timestampMs }
    }

    private fun parseLrcLine(rawLine: String): LyricLine? {
        val matcher = LRC_PATTERN.matcher(rawLine)
        if (matcher.find()) {
            val min = matcher.group(1)?.toLongOrNull() ?: 0L
            val sec = matcher.group(2)?.toLongOrNull() ?: 0L
            val msStr = matcher.group(3) ?: "0"
            val ms = when (msStr.length) {
                2 -> msStr.toLong() * 10
                3 -> msStr.toLong()
                else -> 0L
            }
            val time = (min * 60 + sec) * 1000 + ms
            val text = matcher.group(4)?.trim().orEmpty()
            if (text.isNotBlank()) {
                return LyricLine(time, text)
            }
        }
        return null
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
