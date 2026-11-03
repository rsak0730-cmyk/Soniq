package com.soniq.player.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object SimilarTracksEngine {

    suspend fun getSimilarTracksOnline(song: Song): List<Song> = withContext(Dispatchers.IO) {
        val query = song.artist.replace(Regex("""(?i)unknown|various|feat\..*"""), "").trim()
        if (query.isBlank()) return@withContext emptyList()

        try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = URL("https://itunes.apple.com/search?term=$encoded&entity=song&limit=10&country=IN")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 3500
                readTimeout = 3500
            }

            if (conn.responseCode != 200) return@withContext emptyList()

            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val root = JSONObject(text)
            val results = root.optJSONArray("results") ?: return@withContext emptyList()

            val list = mutableListOf<Song>()
            for (i in 0 until results.length()) {
                val item = results.getJSONObject(i)
                val trackName = item.optString("trackName", "")
                val preview = item.optString("previewUrl", "")
                val durationMs = item.optLong("trackTimeMillis", 30000L)
                val artist = item.optString("artistName", song.artist)
                val album = item.optString("collectionName", "Single")
                val art = item.optString("artworkUrl100", "").replace("100x100bb", "600x600bb")

                // Skip if it's the exact same song
                if (trackName.isNotBlank() && preview.isNotBlank() && !trackName.equals(song.title, true)) {
                    list.add(
                        Song(
                            id = -(System.currentTimeMillis() + i),
                            title = trackName,
                            artist = artist,
                            album = album,
                            durationMs = durationMs,
                            uri = preview,
                            albumArtUri = art.ifBlank { null },
                            folder = "Smart Radio",
                            dateAdded = System.currentTimeMillis()
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
