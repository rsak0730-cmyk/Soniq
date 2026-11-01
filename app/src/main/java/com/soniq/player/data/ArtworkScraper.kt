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
        runCatching {
            val encoded = URLEncoder.encode(query.trim(), "UTF-8")
            val url = URL("https://itunes.apple.com/search?term=$encoded&entity=song&limit=1")
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 5000
            }
            val json = connection.inputStream.bufferedReader().use { it.readText() }
            val results = JSONObject(json).getJSONArray("results")
            if (results.length() > 0) {
                val rawUrl = results.getJSONObject(0).getString("artworkUrl100")
                rawUrl.replace("100x100bb", "600x600bb")
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
