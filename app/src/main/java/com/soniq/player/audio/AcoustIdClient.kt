package com.soniq.player.audio

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

@Serializable
data class AcoustIdApiResponse(
    val status: String,
    val results: List<AcoustIdResult> = emptyList()
)

@Serializable
data class AcoustIdResult(
    val id: String,
    val score: Double,
    val recordings: List<AcoustIdRecording> = emptyList()
)

@Serializable
data class AcoustIdRecording(
    val id: String,
    val title: String? = null,
    val artists: List<AcoustIdArtist> = emptyList(),
    @SerialName("releasegroups") val releaseGroups: List<AcoustIdReleaseGroup> = emptyList()
)

@Serializable
data class AcoustIdArtist(
    val id: String? = null,
    val name: String
)

@Serializable
data class AcoustIdReleaseGroup(
    val id: String? = null,
    val title: String,
    val type: String? = null
)

class AcoustIdClient(
    private val clientApiKey: String = "8XaBELgH" // Default public client application key
) {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

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
            val apiData = json.decodeFromString<AcoustIdApiResponse>(responseBody)

            if (apiData.status != "ok") return@withContext null

            val topResult = apiData.results
                .filter { it.score >= 0.65 }
                .maxByOrNull { it.score } ?: return@withContext null

            val topRecording = topResult.recordings.firstOrNull { !it.title.isNullOrBlank() }
                ?: return@withContext null

            val songTitle = topRecording.title ?: return@withContext null
            val songArtist = topRecording.artists.firstOrNull()?.name ?: "Unknown Artist"
            val songAlbum = topRecording.releaseGroups.firstOrNull()?.title ?: "Unknown Album"

            FingerprintResult(
                title = songTitle,
                artist = songArtist,
                album = songAlbum,
                albumArtUrl = null,
                matchConfidence = topResult.score.toFloat()
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
