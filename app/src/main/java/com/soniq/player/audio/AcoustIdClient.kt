package com.soniq.player.audio

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class AcoustIdClient(
    private val clientApiKey: String = "8XaBELgH"
) {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun lookup(durationSeconds: Int, fingerprint: String): FingerprintResult? = withContext(Dispatchers.IO) {
        val formBody = FormBody.Builder()
            .add("client", clientApiKey)
            .add("meta", "recordings releasegroups")
            .add("duration", durationSeconds.toString())
            .add("fingerprint", fingerprint)
            .build()

        val request = Request.Builder()
            .url("https://api.acoustid.org/v2/lookup")
            .post(formBody)
            .header("User-Agent", "SONIQ-Player/0.5.0")
            .build()

        try {
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) return@withContext null

            val responseBody = response.body?.string() ?: return@withContext null
            val root = JSONObject(responseBody)

            if (root.optString("status") != "ok") return@withContext null

            val results = root.optJSONArray("results") ?: return@withContext null
            if (results.length() == 0) return@withContext null

            var bestScore = 0.0
            var matchedTitle: String? = null
            var matchedArtist: String? = null
            var matchedAlbum: String? = null

            for (i in 0 until results.length()) {
                val item = results.getJSONObject(i)
                val score = item.optDouble("score", 0.0)
                if (score < 0.65 || score <= bestScore) continue

                val recordings = item.optJSONArray("recordings") ?: continue
                for (j in 0 until recordings.length()) {
                    val rec = recordings.getJSONObject(j)
                    val title = rec.optString("title", "")
                    if (title.isNotBlank()) {
                        bestScore = score
                        matchedTitle = title

                        val artists = rec.optJSONArray("artists")
                        matchedArtist = if (artists != null && artists.length() > 0) {
                            artists.getJSONObject(0).optString("name", "Unknown Artist")
                        } else "Unknown Artist"

                        val releaseGroups = rec.optJSONArray("releasegroups")
                        matchedAlbum = if (releaseGroups != null && releaseGroups.length() > 0) {
                            releaseGroups.getJSONObject(0).optString("title", "Unknown Album")
                        } else "Unknown Album"
                        break
                    }
                }
            }

            if (matchedTitle != null && matchedArtist != null) {
                FingerprintResult(
                    title = matchedTitle,
                    artist = matchedArtist,
                    album = matchedAlbum ?: "Unknown Album",
                    albumArtUrl = null,
                    matchConfidence = bestScore.toFloat()
                )
            } else null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
