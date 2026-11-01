package com.soniq.player.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.regex.Pattern

data class LyricLine(val timestampMs: Long, val text: String)

object LyricsEngine {
    private val LRC_PATTERN = Pattern.compile("\\[(\\d{2}):(\\d{2})(?:\\.(\\d{2,3}))?\\](.*)")

    suspend fun loadLyricsForSong(context: Context, song: Song): List<LyricLine> = withContext(Dispatchers.IO) {
        val lines = mutableListOf<LyricLine>()
        runCatching {
            val audioUri = Uri.parse(song.uri)
            val scheme = audioUri.scheme
            
            val reader: BufferedReader? = if (scheme == "file") {
                val lrcFile = File(audioUri.path?.substringBeforeLast('.') + ".lrc")
                if (lrcFile.exists()) lrcFile.bufferedReader() else null
            } else {
                context.contentResolver.openInputStream(audioUri)?.use { stream ->
                    null
                }
            }

            reader?.useLines { sequence ->
                sequence.forEach { line ->
                    val matcher = LRC_PATTERN.matcher(line)
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
                        if (text.isNotEmpty()) {
                            lines.add(LyricLine(time, text))
                        }
                    }
                }
            }
        }
        lines.sortedBy { it.timestampMs }
    }
}
