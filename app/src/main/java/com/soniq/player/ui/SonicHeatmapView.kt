package com.soniq.player.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
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

@Composable
fun SonicHeatmapView(
    showClearButton: Boolean = false,
    modifier: Modifier = Modifier
) {
    val stats = SonicStatsEngine.heatmapData
    val maxHourly = (stats.hourlyActivity.maxOrNull() ?: 1).coerceAtLeast(1)
    val maxDaily = (stats.dayOfWeekActivity.maxOrNull() ?: 1).coerceAtLeast(1)
    val peakHourIndex = stats.hourlyActivity.indices.maxByOrNull { stats.hourlyActivity[it] } ?: 0
    val peakHourLabel = when {
        stats.totalPlayCount == 0 -> "No data yet"
        peakHourIndex == 0 -> "12 AM"
        peakHourIndex < 12 -> "$peakHourIndex AM"
        peakHourIndex == 12 -> "12 PM"
        else -> "${peakHourIndex - 12} PM"
    }

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
                Column(Modifier.weight(1f)) {
                    Text("SONIC LISTENING HEATMAP", color = Color(0xFF53F6FF), fontWeight = FontWeight.Black, fontSize = 13.sp)
                    Text("Peak Hour: $peakHourLabel • ${stats.totalPlayCount} Total Streams", color = Color(0xFFC7BCE0), fontSize = 11.sp)
                }
                if (showClearButton && stats.totalPlayCount > 0) {
                    IconButton(onClick = { SonicStatsEngine.clearStats() }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = "Reset Stats", tint = Color(0xFFFF5286), modifier = Modifier.size(18.dp))
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            Text("24-HOUR ACOUSTIC ACTIVITY", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                stats.hourlyActivity.forEach { count ->
                    val ratio = if (stats.totalPlayCount == 0) 0.12f else (count.toFloat() / maxHourly).coerceAtLeast(0.12f)
                    val barColor = when {
                        count == 0 -> Color(0x286B2BEE)
                        ratio > 0.75f -> Color(0xFF53F6FF)
                        ratio > 0.40f -> Color(0xFF9B6CFF)
                        else -> Color(0xFF6B2BEE)
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(ratio)
                            .clip(RoundedCornerShape(3.dp))
                            .background(barColor)
                    )
                }
            }

            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("12a", color = Color(0x99C7BCE0), fontSize = 9.sp)
                Text("6a", color = Color(0x99C7BCE0), fontSize = 9.sp)
                Text("12p", color = Color(0x99C7BCE0), fontSize = 9.sp)
                Text("6p", color = Color(0x99C7BCE0), fontSize = 9.sp)
                Text("11p", color = Color(0x99C7BCE0), fontSize = 9.sp)
            }

            Spacer(Modifier.height(14.dp))

            Text("7-DAY WEEKLY CADENCE", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                days.forEachIndexed { index, dayName ->
                    val count = stats.dayOfWeekActivity[index]
                    val intensity = if (stats.totalPlayCount == 0) 0f else (count.toFloat() / maxDaily).coerceIn(0f, 1f)

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(
                                    Brush.radialGradient(
                                        listOf(
                                            Color(0xFF53F6FF).copy(alpha = 0.20f + intensity * 0.75f),
                                            Color(0xFF6B2BEE).copy(alpha = 0.20f + intensity * 0.55f)
                                        )
                                    )
                                )
                                .border(1.dp, Color(0x4453F6FF), CircleShape),
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
                HorizontalDivider(color = Color(0x2253F6FF))
                Spacer(Modifier.height(10.dp))
                Text("TOP ROTATION ARTISTS", color = Color(0xFF67E8F9), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                stats.topArtists.take(3).forEachIndexed { idx, (artist, plays) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("#${idx + 1}", color = Color(0xFF53F6FF), fontSize = 11.sp, fontWeight = FontWeight.Black)
                        Spacer(Modifier.width(8.dp))
                        Text(artist, color = Color.White, fontSize = 12.sp, modifier = Modifier.weight(1f), maxLines = 1)
                        Text("$plays plays", color = Color(0xFFC7BCE0), fontSize = 11.sp)
                    }
                }
            }
        }
    }
}
