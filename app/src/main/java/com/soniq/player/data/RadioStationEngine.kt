package com.soniq.player.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class RadioStation(
    val stationuuid: String,
    val name: String,
    val streamUrl: String,
    val favicon: String?,
    val tags: String,
    val country: String,
    val bitrate: Int
)

object RadioStationEngine {
    private val PRESET_STATIONS = listOf(
        RadioStation("1", "Radio Mirchi 98.3", "https://stream.zeno.fm/41x4eug1u18uv", null, "Bollywood, Hindi, Hits", "India", 128),
        RadioStation("2", "Lofi Girl Chill Beats", "https://play.streamafrica.net/lofigirl", null, "Lofi, Study, Chill", "Global", 128),
        RadioStation("3", "Ibiza Global Radio", "https://listenssl.ibizaglobalradio.com:8024/ibizaglobalradio.mp3", null, "Electronic, Deep House", "Spain", 192),
        RadioStation("4", "Bollywood Dil Se", "https://stream.zeno.fm/fvrx45261n8uv", null, "Romantic, Bollywood, Classic", "India", 128),
        RadioStation("5", "BBC Radio 1", "http://stream.live.vc.bbcmedia.co.uk/bbc_radio_one", null, "Pop, Chart, Hits", "UK", 128),
        RadioStation("6", "Smooth Jazz 247", "https://stream.zeno.fm/0r0xa792kwzuv", null, "Jazz, Smooth, Relax", "USA", 128)
    )

    suspend fun getStations(category: String): List<RadioStation> = withContext(Dispatchers.IO) {
        if (category == "Featured") return@withContext PRESET_STATIONS

        val endpoint = when (category) {
            "India" -> "https://de1.api.radio-browser.info/json/stations/bycountrycodeexact/IN?limit=25&order=votes&reverse=true"
            "Lofi & Chill" -> "https://de1.api.radio-browser.info/json/stations/bytag/lofi?limit=25&order=votes&reverse=true"
            "Electronic" -> "https://de1.api.radio-browser.info/json/stations/bytag/edm?limit=25&order=votes&reverse=true"
            "Rock & Metal" -> "https://de1.api.radio-browser.info/json/stations/bytag/rock?limit=25&order=votes&reverse=true"
            else -> "https://de1.api.radio-browser.info/json/stations/topvote/25"
        }

        try {
            val url = URL(endpoint)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 4000
                readTimeout = 4000
                setRequestProperty("User-Agent", "SONIQ-Player/0.6.0")
            }

            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val array = JSONArray(body)
            val result = mutableListOf<RadioStation>()

            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val stream = obj.optString("url_resolved", obj.optString("url", ""))
                if (stream.isNotBlank() && stream.startsWith("http")) {
                    result.add(
                        RadioStation(
                            stationuuid = obj.optString("stationuuid", i.toString()),
                            name = obj.optString("name", "Live Station").trim(),
                            streamUrl = stream,
                            favicon = obj.optString("favicon", "").ifBlank { null },
                            tags = obj.optString("tags", "").take(32),
                            country = obj.optString("country", "Global"),
                            bitrate = obj.optInt("bitrate", 128)
                        )
                    )
                }
            }
            if (result.isNotEmpty()) result else PRESET_STATIONS
        } catch (_: Exception) {
            PRESET_STATIONS
        }
    }
}
