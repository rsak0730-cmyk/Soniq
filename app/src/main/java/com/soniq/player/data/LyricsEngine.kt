package com.soniq.player.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
        // 1. Try local storage (.lrc alongside song or cached in app files)
        val localLyrics = loadLocalLrc(context, song)
        if (localLyrics.isNotEmpty()) return@withContext localLyrics

        // 2. Auto-fetch synced lyrics from public LRCLIB without any API key
        val onlineLyrics = fetchOnlineSyncedLyrics(song)
        if (onlineLyrics.isNotEmpty()) {
            cacheLyricsToFile(context, song.id, onlineLyrics)
            return@withContext onlineLyrics
        }

        emptyList()
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

    private fun fetchOnlineSyncedLyrics(song: Song): List<LyricLine> {
        val cleanTitle = song.title.replace(Regex("""(?i)\(.*?\)|\\[.*?\\]|__?.*|\.mp3"""), "").trim()
        val cleanArtist = song.artist.replace(Regex("""(?i)unknown|various"""), "").trim()

        return runCatching {
            val trackParam = URLEncoder.encode(cleanTitle, "UTF-8")
            val artistParam = URLEncoder.encode(cleanArtist, "UTF-8")
            val durationSec = (song.durationMs / 1000).toInt()

            val url = URL("https://lrclib.net/api/get?track_name=$trackParam&artist_name=$artistParam&duration=$durationSec")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 4000
                readTimeout = 4000
                setRequestProperty("User-Agent", "SONIQ-Player/0.6.0")
            }

            val jsonStr = conn.inputStream.bufferedReader().use { it.readText() }
            val root = JSONObject(jsonStr)
            val syncedLrc = root.optString("syncedLyrics", "")

            if (syncedLrc.isNotBlank()) {
                syncedLrc.lines().mapNotNull { parseLrcLine(it) }.sortedBy { it.timestampMs }
            } else {
                emptyList()
            }
        }.getOrDefault(emptyList())
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
