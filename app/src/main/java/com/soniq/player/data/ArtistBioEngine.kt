package com.soniq.player.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class ArtistBio(
    val artistName: String,
    val extract: String,
    val description: String?,
    val photoUrl: String?
)

object ArtistBioEngine {
    private val memoryCache = mutableMapOf<String, ArtistBio>()

    suspend fun getArtistBio(artistName: String): ArtistBio? = withContext(Dispatchers.IO) {
        val clean = artistName.replace(Regex("""(?i)\b(feat|featuring|ft\.|vs|x)\b.*"""), "")
            .replace(Regex("""[_,]"""), " ")
            .trim()

        if (clean.isBlank() || clean.equals("Unknown Artist", ignoreCase = true)) return@withContext null
        if (memoryCache.containsKey(clean)) return@withContext memoryCache[clean]

        val bio = fetchWikipediaBio(clean) ?: fetchWikipediaBio("$clean (musician)")
        if (bio != null) memoryCache[clean] = bio
        bio
    }

    private fun fetchWikipediaBio(query: String): ArtistBio? {
        return try {
            val encoded = URLEncoder.encode(query.replace(" ", "_"), "UTF-8")
            val url = URL("https://en.wikipedia.org/api/rest_v1/page/summary/$encoded")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 3500
                readTimeout = 3500
                setRequestProperty("User-Agent", "SONIQ-Player/0.6.0 (Android Music Player)")
            }

            if (conn.responseCode != 200) return null

            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val root = JSONObject(body)

            val extract = root.optString("extract", "")
            if (extract.isBlank()) return null

            val description = root.optString("description", "")
            val thumbnail = root.optJSONObject("thumbnail")?.optString("source", null)

            ArtistBio(
                artistName = root.optString("title", query),
                extract = extract,
                description = description.ifBlank { null },
                photoUrl = thumbnail
            )
        } catch (_: Exception) {
            null
        }
    }
}
