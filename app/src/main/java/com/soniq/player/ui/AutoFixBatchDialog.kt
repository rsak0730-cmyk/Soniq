package com.soniq.player.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

@Composable
fun AutoFixBatchDialog(
    isRunning: Boolean,
    current: Int,
    total: Int,
    currentTrackTitle: String,
    summaryMessage: String?,
    onDismissSummary: () -> Unit
) {
    if (isRunning) {
        Dialog(onDismissRequest = {}) {
            Card(
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF160E2E)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoFixHigh,
                        contentDescription = "Auto-Fixing",
                        tint = Color(0xFF53F6FF),
                        modifier = Modifier.size(46.dp)
                    )

                    Spacer(Modifier.height(16.dp))

                    Text(
                        text = "Auto-Fixing Unknown Songs",
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp,
                        color = Color.White
                    )

                    Spacer(Modifier.height(14.dp))

                    val progressFraction = if (total > 0) current.toFloat() / total else 0f
                    LinearProgressIndicator(
                        progress = { progressFraction },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp),
                        color = Color(0xFF53F6FF),
                        trackColor = Color(0x351F143D)
                    )

                    Spacer(Modifier.height(12.dp))

                    Text(
                        text = "$current / $total",
                        color = Color(0xFFC7BCE0),
                        fontSize = 13.sp
                    )

                    Spacer(Modifier.height(6.dp))

                    Text(
                        text = currentTrackTitle,
                        color = Color.White,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(Modifier.height(4.dp))

                    Text(
                        text = "Decoding waveform & checking fingerprints...",
                        color = Color(0xFF53F6FF),
                        fontSize = 11.sp
                    )
                }
            }
        }
    }

    AnimatedVisibility(visible = summaryMessage != null) {
        AlertDialog(
            onDismissRequest = onDismissSummary,
            containerColor = Color(0xFF1E1436),
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "Finished",
                        tint = Color(0xFF53F6FF)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Auto-Fix Complete", color = Color.White)
                }
            },
            text = {
                Text(
                    text = summaryMessage ?: "",
                    color = Color(0xFFC7BCE0)
                )
            },
            confirmButton = {
                TextButton(onClick = onDismissSummary) {
                    Text("OK", color = Color(0xFF53F6FF))
                }
            }
        )
    }
}
