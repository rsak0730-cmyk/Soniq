package com.soniq.player.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import java.util.Calendar

data class ListeningHeatmapData(
    val hourlyActivity: IntArray, // 24 values
    val dayOfWeekActivity: IntArray, // 7 values
    val totalPlayCount: Int,
    val topArtists: List<Pair<String, Int>>
)

/**
 * Sonic Heatmap & Listening Analytics Engine.
 * Records play counts by hour of the day and day of week into persistent local SharedPreferences.
 */
object SonicStatsEngine {

    private const val PREFS_NAME = "soniq_sonic_analytics"
    private var prefs: SharedPreferences? = null

    var heatmapData by mutableStateOf(ListeningHeatmapData(IntArray(24), IntArray(7), 0, emptyList()))
        private set

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        refresh()
    }

    fun recordPlayEvent(song: Song) {
        val sp = prefs ?: return
        val cal = Calendar.getInstance()
        val hour = cal.get(Calendar.HOUR_OF_DAY) // 0-23
        val day = (cal.get(Calendar.DAY_OF_WEEK) - 1).coerceIn(0, 6) // 0 (Sun) - 6 (Sat)

        val hourKey = "hour_$hour"
        val dayKey = "day_$day"
        val totalKey = "total_plays"
        val artistKey = "artist_${song.artist.replace(".", "_")}"

        val curHour = sp.getInt(hourKey, 0)
        val curDay = sp.getInt(dayKey, 0)
        val curTotal = sp.getInt(totalKey, 0)
        val curArtist = sp.getInt(artistKey, 0)

        sp.edit()
            .putInt(hourKey, curHour + 1)
            .putInt(dayKey, curDay + 1)
            .putInt(totalKey, curTotal + 1)
            .putInt(artistKey, curArtist + 1)
            .apply()

        refresh()
    }

    fun refresh() {
        val sp = prefs ?: return
        val hours = IntArray(24) { sp.getInt("hour_$it", 0) }
        val days = IntArray(7) { sp.getInt("day_$it", 0) }
        val total = sp.getInt("total_plays", 0)

        val artistMap = mutableListOf<Pair<String, Int>>()
        for ((k, v) in sp.all) {
            if (k.startsWith("artist_") && v is Int) {
                artistMap.add(k.removePrefix("artist_") to v)
            }
        }
        artistMap.sortByDescending { it.second }

        heatmapData = ListeningHeatmapData(
            hourlyActivity = hours,
            dayOfWeekActivity = days,
            totalPlayCount = total,
            topArtists = artistMap.take(5)
        )
    }

    fun clearStats() {
        prefs?.edit()?.clear()?.apply()
        refresh()
    }
}
