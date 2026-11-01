package com.soniq.player.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.soniq.player.data.SonicStatsEngine

private val days = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")

/**
 * Sonic Listening Heatmap: displays 24-hour hour-by-hour acoustic intensity
 * and 7-day listening patterns.
 */
@Composable
fun SonicHeatmapView(modifier: Modifier = Modifier) {
    val stats = SonicStatsEngine.heatmapData
    val maxHourly = (stats.hourlyActivity.maxOrNull() ?: 1).coerceAtLeast(1)
    val maxDaily = (stats.dayOfWeekActivity.maxOrNull() ?: 1).coerceAtLeast(1)

    Surface(
        color = Color(0x351F143D),
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.2.dp, Color(0x5553F6FF)),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.LocalFireDepartment, contentDescription = null, tint = Color(0xFF53F6FF))
                Spacer(Modifier.width(8.dp))
                Text("SONIC LISTENING HEATMAP", color = Color(0xFF53F6FF), fontWeight = FontWeight.Black, fontSize = 14.sp)
                Spacer(Modifier.weight(1f))
                Text("${stats.totalPlayCount} Plays", color = Color(0xFFC7BCE0), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }

            Spacer(Modifier.height(14.dp))

            // 24-Hour Listening Matrix Heatmap
            Text("24-HOUR HOURLY RHYTHM", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                stats.hourlyActivity.forEachIndexed { hour, count ->
                    val ratio = count.toFloat() / maxHourly
                    val barColor = when {
                        ratio > 0.75f -> Color(0xFF53F6FF)
                        ratio > 0.40f -> Color(0xFF9B6CFF)
                        ratio > 0.10f -> Color(0xFF6B2BEE)
                        else -> Color(0x336B2BEE)
                    }

                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height((34.dp.value * ratio.coerceAtLeast(0.12f)).dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(barColor)
                        )
                    }
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("12 AM", color = Color(0xFFC7BCE0).copy(alpha = 0.6f), fontSize = 9.sp)
                Text("6 AM", color = Color(0xFFC7BCE0).copy(alpha = 0.6f), fontSize = 9.sp)
                Text("12 PM", color = Color(0xFFC7BCE0).copy(alpha = 0.6f), fontSize = 9.sp)
                Text("6 PM", color = Color(0xFFC7BCE0).copy(alpha = 0.6f), fontSize = 9.sp)
                Text("11 PM", color = Color(0xFFC7BCE0).copy(alpha = 0.6f), fontSize = 9.sp)
            }

            Spacer(Modifier.height(16.dp))

            // 7-Day Matrix Activity
            Text("WEEKDAY INTENSITY", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                days.forEachIndexed { index, dayName ->
                    val count = stats.dayOfWeekActivity[index]
                    val intensity = (count.toFloat() / maxDaily).coerceIn(0f, 1f)

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(
                                    Brush.radialGradient(
                                        listOf(
                                            Color(0xFF53F6FF).copy(alpha = (0.25f + intensity * 0.75f)),
                                            Color(0xFF6B2BEE).copy(alpha = (0.25f + intensity * 0.5f))
                                        )
                                    )
                                )
                                .border(1.dp, Color(0x3353F6FF), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(count.toString(), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(dayName, color = Color(0xFFC7BCE0), fontSize = 10.sp)
                    }
                }
            }

            if (stats.topArtists.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                Text("MOST STREAMED ARTISTS", color = Color(0xFF67E8F9), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                stats.topArtists.take(3).forEach { (artist, plays) ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(artist, color = Color.White, fontSize = 12.sp)
                        Text("$plays plays", color = Color(0xFFC7BCE0), fontSize = 11.sp)
                    }
                }
            }
        }
    }
}
