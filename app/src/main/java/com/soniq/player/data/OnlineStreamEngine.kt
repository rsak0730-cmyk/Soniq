package com.soniq.player.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
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

    private val SAAVN_APIS = listOf(
        "https://saavn.dev/api/search/songs",
        "https://jiosaavn-api-privatecvc2.vercel.app/search/songs"
    )

    suspend fun searchOnlineSongs(query: String): List<OnlineTrack> = withContext(Dispatchers.IO) {
        if (query.trim().length < 2) return@withContext emptyList()

        // 1. Try high-speed full-track M4A streaming API
        for (endpoint in SAAVN_APIS) {
            val tracks = fetchSaavnStreams(endpoint, query.trim())
            if (tracks.isNotEmpty()) return@withContext tracks
        }

        // 2. Fallback to Apple Music / iTunes direct M4A stream previews (100% uptime worldwide)
        fetchItunesStreams(query.trim())
    }

    private fun fetchSaavnStreams(baseUrl: String, query: String): List<OnlineTrack> {
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = URL("$baseUrl?query=$encoded&limit=12")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 3500
                readTimeout = 3500
                setRequestProperty("User-Agent", "SONIQ-Player/0.8.0")
            }

            if (conn.responseCode != 200) return emptyList()

            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val root = JSONObject(body)
            val dataObj = root.optJSONObject("data") ?: return emptyList()
            val results = dataObj.optJSONArray("results") ?: return emptyList()

            val list = mutableListOf<OnlineTrack>()
            for (i in 0 until results.length()) {
                val item = results.getJSONObject(i)
                val id = item.optString("id", i.toString())
                val name = item.optString("name", "Online Track").replace("&quot;", "\"")
                val duration = item.optLong("duration", 180L)

                val artist = item.optJSONObject("artists")
                    ?.optJSONArray("primary")
                    ?.optJSONObject(0)
                    ?.optString("name", "")
                    ?.takeIf { it.isNotBlank() }
                    ?: item.optString("primaryArtists", "Online Artist")

                val images = item.optJSONArray("image")
                val thumb = if (images != null && images.length() > 0) {
                    images.getJSONObject(images.length() - 1).optString("url", "")
                        .ifBlank { images.getJSONObject(images.length() - 1).optString("link", "") }
                } else null

                val downloads = item.optJSONArray("downloadUrl")
                var streamUrl = ""
                if (downloads != null && downloads.length() > 0) {
                    val best = downloads.getJSONObject(downloads.length() - 1)
                    streamUrl = best.optString("url", "").ifBlank { best.optString("link", "") }
                }

                if (streamUrl.startsWith("http")) {
                    list.add(
                        OnlineTrack(
                            videoId = id,
                            title = name,
                            artist = artist,
                            thumbnail = thumb,
                            durationSec = duration,
                            streamUrl = streamUrl
                        )
                    )
                }
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun fetchItunesStreams(query: String): List<OnlineTrack> {
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = URL("https://itunes.apple.com/search?term=$encoded&entity=song&limit=12&country=IN")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 3500
                readTimeout = 3500
            }

            if (conn.responseCode != 200) return emptyList()

            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val results = JSONObject(body).optJSONArray("results") ?: return emptyList()
            val list = mutableListOf<OnlineTrack>()

            for (i in 0 until results.length()) {
                val obj = results.getJSONObject(i)
                val previewUrl = obj.optString("previewUrl", "")
                if (previewUrl.startsWith("http")) {
                    val trackId = obj.optLong("trackId", System.currentTimeMillis() + i).toString()
                    val title = obj.optString("trackName", "Online Track")
                    val artist = obj.optString("artistName", "Artist")
                    val art = obj.optString("artworkUrl100", "").replace("100x100bb", "600x600bb")
                    val durSec = (obj.optLong("trackTimeMillis", 30000L) / 1000L).coerceAtLeast(30L)

                    list.add(
                        OnlineTrack(
                            videoId = trackId,
                            title = title,
                            artist = artist,
                            thumbnail = art.ifBlank { null },
                            durationSec = durSec,
                            streamUrl = previewUrl
                        )
                    )
                }
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }
}
