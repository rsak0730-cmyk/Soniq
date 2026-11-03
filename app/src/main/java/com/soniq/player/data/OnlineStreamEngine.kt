package com.soniq.player.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class OnlineTrack(
    val videoId: String,
    val title: String,
    val artist: String,
    val thumbnail: String?,
    val durationSec: Long,
    val streamUrl: String
)

object OnlineStreamEngine {

    // Reliable public Invidious instances for keyless audio streaming
    private val INSTANCES = listOf(
        "https://inv.nadeko.net",
        "https://invidious.nerdvpn.de",
        "https://invidious.projectsegfau.lt"
    )

    suspend fun searchOnlineSongs(query: String): List<OnlineTrack> = withContext(Dispatchers.IO) {
        if (query.trim().length < 2) return@withContext emptyList()

        for (host in INSTANCES) {
            val list = fetchFromInstance(host, query.trim())
            if (list.isNotEmpty()) return@withContext list
        }
        emptyList()
    }

    private fun fetchFromInstance(host: String, query: String): List<OnlineTrack> {
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = URL("$host/api/v1/search?q=$encoded&type=video&page=1")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 3500
                readTimeout = 3500
                setRequestProperty("User-Agent", "SONIQ-Player/0.7.0")
            }

            if (conn.responseCode != 200) return emptyList()

            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val array = JSONArray(text)
            val result = mutableListOf<OnlineTrack>()

            for (i in 0 until array.length().coerceAtMost(15)) {
                val obj = array.getJSONObject(i)
                val videoId = obj.optString("videoId", "")
                val title = obj.optString("title", "Unknown Track")
                val author = obj.optString("author", "Online Stream")
                val lengthSec = obj.optLong("lengthSeconds", 0L)
                val thumbs = obj.optJSONArray("videoThumbnails")
                val thumb = if (thumbs != null && thumbs.length() > 0) {
                    thumbs.getJSONObject(0).optString("url", "")
                } else null

                // Invidious direct audio stream URL format
                val streamUrl = "$host/latest_version?id=$videoId&itag=140"

                if (videoId.isNotBlank()) {
                    result.add(
                        OnlineTrack(
                            videoId = videoId,
                            title = title,
                            artist = author,
                            thumbnail = thumb,
                            durationSec = lengthSec,
                            streamUrl = streamUrl
                        )
                    )
                }
            }
            result
        } catch (_: Exception) {
            emptyList()
        }
    }
}
