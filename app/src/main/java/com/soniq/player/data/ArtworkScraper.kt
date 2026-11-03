package com.soniq.player.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object ArtworkScraper {

    suspend fun searchCoverArtUrl(query: String): String? = withContext(Dispatchers.IO) {
        // Source 1: iTunes Store 600x600 HD artwork
        val itunesUrl = runCatching {
            val encoded = URLEncoder.encode(query.trim(), "UTF-8")
            val url = URL("https://itunes.apple.com/search?term=$encoded&entity=song&limit=1&country=IN")
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 3500
                readTimeout = 3500
            }
            val json = connection.inputStream.bufferedReader().use { it.readText() }
            val results = JSONObject(json).optJSONArray("results")
            if (results != null && results.length() > 0) {
                val rawUrl = results.getJSONObject(0).optString("artworkUrl100", "")
                rawUrl.replace("100x100bb", "600x600bb").ifBlank { null }
            } else null
        }.getOrNull()

        if (!itunesUrl.isNullOrBlank()) return@withContext itunesUrl

        // Source 2: Deezer Public Gateway (1000x1000 Lossless cover_xl)
        runCatching {
            val encoded = URLEncoder.encode(query.trim(), "UTF-8")
            val url = URL("https://api.deezer.com/search?q=$encoded&limit=1")
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 3500
                readTimeout = 3500
                setRequestProperty("User-Agent", "SONIQ-Music/0.6.0")
            }
            val json = connection.inputStream.bufferedReader().use { it.readText() }
            val root = JSONObject(json)
            val data = root.optJSONArray("data")
            if (data != null && data.length() > 0) {
                val album = data.getJSONObject(0).optJSONObject("album")
                album?.optString("cover_xl", null)?.ifBlank { null }
                    ?: album?.optString("cover_big", null)?.ifBlank { null }
            } else null
        }.getOrNull()
    }

    suspend fun saveDownloadedCoverToFile(context: Context, song: Song, imageUrl: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val destFile = File(context.filesDir, "cover_${song.id}.jpg")
            val url = URL(imageUrl)
            url.openStream().use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            Uri.fromFile(destFile).toString()
        }.getOrNull()
    }
}
