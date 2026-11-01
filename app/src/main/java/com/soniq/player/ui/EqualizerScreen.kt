package com.soniq.player.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.SpeakerGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.soniq.player.audio.AudioEffectsBridge
import com.soniq.player.audio.AudioStemsEngine
import kotlin.math.roundToInt

private data class EqPreset(val name: String, val curve: List<Float>)
private val eqPresets = listOf(
    EqPreset("Flat", listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)),
    EqPreset("Bass", listOf(5f, 4f, 2f, 0f, -1f, -1f, 0f, 1f)),
    EqPreset("Pop", listOf(-1f, 1f, 3f, 4f, 2f, 0f, -1f, -1f)),
    EqPreset("Rock", listOf(4f, 2f, -1f, -2f, 2f, 4f, 5f, 4f)),
    EqPreset("Vocal", listOf(-2f, -1f, 1f, 3f, 4f, 3f, 1f, 0f)),
    EqPreset("Electronic", listOf(4f, 3f, 0f, -1f, 2f, 0f, 3f, 4f))
)

@Composable
fun EqualizerScreen(modifier: Modifier = Modifier) {
    var available by remember { mutableStateOf(AudioEffectsBridge.isAvailable()) }
    var bands by remember { mutableIntStateOf(AudioEffectsBridge.bandCount()) }
    var range by remember { mutableStateOf(AudioEffectsBridge.bandLevelRange()) }
    var selected by remember { mutableStateOf("Custom") }
    var refresh by remember { mutableIntStateOf(0) }
    var bass by remember { mutableFloatStateOf(0f) }
    var virtual by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(Unit) {
        repeat(20) {
            if (AudioEffectsBridge.isAvailable()) {
                available = true
                bands = AudioEffectsBridge.bandCount()
                range = AudioEffectsBridge.bandLevelRange()
                return@LaunchedEffect
            }
            kotlinx.coroutines.delay(500)
        }
    }

    val levels = remember(bands, refresh) { (0 until bands).map { AudioEffectsBridge.bandLevel(it).toFloat() } }

    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(top = 8.dp, bottom = 32.dp)
    ) {
        Text("SONIQ EQUALIZER & STEMS", color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(if (available) "$bands native bands active on device" else "Play a track to activate hardware equalizer & stems.", color = Color(0xFFB9A9D3), fontSize = 12.sp)

        Spacer(Modifier.height(18.dp))

        // SMART "AUDIO STEMS" / VOCAL & BASS ISOLATOR SECTION
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = Color(0x351F143D),
            border = BorderStroke(1.2.dp, Color(0x5553F6FF)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.SpeakerGroup, null, tint = Color(0xFF53F6FF))
                    Spacer(Modifier.width(8.dp))
                    Text("SMART AUDIO STEMS (ISOLATOR)", color = Color(0xFF53F6FF), fontWeight = FontWeight.Black, fontSize = 14.sp)
                }
                Text("Real-time vocal isolation, sub-bass extractor & instrumental cut", color = Color(0xFFC7BCE0), fontSize = 11.sp)

                Spacer(Modifier.height(14.dp))

                // Vocal Stem Slider
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Mic, null, tint = Color(0xFFB68CFF), modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Vocal Stem Isolator", color = Color.White, modifier = Modifier.weight(1f), fontSize = 13.sp)
                    Text("%.1fx".format(AudioStemsEngine.vocalStem), color = Color(0xFF53F6FF), fontWeight = FontWeight.Bold)
                }
                Slider(
                    value = AudioStemsEngine.vocalStem,
                    onValueChange = { AudioStemsEngine.updateVocalStem(it) },
                    valueRange = 0f..2f,
                    colors = SliderDefaults.colors(thumbColor = Color(0xFF53F6FF), activeTrackColor = Color(0xFF9B6CFF))
                )

                // Bass Stem Slider
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.MusicNote, null, tint = Color(0xFF53F6FF), modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Sub / Mid Bass Stem", color = Color.White, modifier = Modifier.weight(1f), fontSize = 13.sp)
                    Text("%.1fx".format(AudioStemsEngine.bassStem), color = Color(0xFF53F6FF), fontWeight = FontWeight.Bold)
                }
                Slider(
                    value = AudioStemsEngine.bassStem,
                    onValueChange = { AudioStemsEngine.updateBassStem(it) },
                    valueRange = 0f..2f,
                    colors = SliderDefaults.colors(thumbColor = Color(0xFF53F6FF), activeTrackColor = Color(0xFF53F6FF))
                )

                // Instrumental Stem Slider
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.SpeakerGroup, null, tint = Color(0xFF26E0FA), modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Instruments & Treble", color = Color.White, modifier = Modifier.weight(1f), fontSize = 13.sp)
                    Text("%.1fx".format(AudioStemsEngine.instrumentalStem), color = Color(0xFF53F6FF), fontWeight = FontWeight.Bold)
                }
                Slider(
                    value = AudioStemsEngine.instrumentalStem,
                    onValueChange = { AudioStemsEngine.updateInstrumentalStem(it) },
                    valueRange = 0f..2f,
                    colors = SliderDefaults.colors(thumbColor = Color(0xFF53F6FF), activeTrackColor = Color(0xFF26E0FA))
                )

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { AudioStemsEngine.resetStems() }) {
                        Text("Reset Stems", color = Color(0xFFFF5286))
                    }
                }
            }
        }

        Spacer(Modifier.height(18.dp))

        Text("HARDWARE PRESETS", color = Color(0xFF67E8F9), fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Spacer(Modifier.height(8.dp))
        eqPresets.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { preset ->
                    FilterChip(
                        selected = selected == preset.name,
                        onClick = {
                            selected = preset.name
                            repeat(bands) { i ->
                                val x = if (bands <= 1) 0f else i.toFloat() / (bands - 1)
                                val j = (x * (preset.curve.size - 1)).roundToInt()
                                AudioEffectsBridge.setBandLevel(i, (preset.curve[j] * 100).roundToInt())
                            }
                            refresh++
                        },
                        label = { Text(preset.name) },
                        enabled = available
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        if (available && bands > 0) {
            repeat(bands) { index ->
                val hz = AudioEffectsBridge.centerFrequencyHz(index)
                var value by remember(bands, index, refresh) { mutableFloatStateOf(levels.getOrElse(index) { 0f }) }
                Row {
                    Text(if (hz >= 1000) "%.1f kHz".format(hz / 1000f) else "$hz Hz", color = Color.White, modifier = Modifier.weight(1f))
                    Text("%.1f dB".format(value / 100f), color = Color(0xFFB9A9D3))
                }
                Slider(
                    value = value,
                    onValueChange = {
                        value = it
                        selected = "Custom"
                        AudioEffectsBridge.setBandLevel(index, it.roundToInt())
                    },
                    valueRange = range.first.toFloat()..range.last.toFloat()
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        Text("BASS BOOST", color = Color(0xFF67E8F9), fontWeight = FontWeight.Bold)
        Slider(
            value = bass,
            onValueChange = {
                bass = it
                AudioEffectsBridge.setBassStrength((it * 10).roundToInt())
            },
            valueRange = 0f..100f,
            enabled = available && AudioEffectsBridge.bassSupported()
        )

        Text("VIRTUALIZER / 3D SPATIAL", color = Color(0xFF67E8F9), fontWeight = FontWeight.Bold)
        Slider(
            value = virtual,
            onValueChange = {
                virtual = it
                AudioEffectsBridge.setVirtualizerStrength((it * 10).roundToInt())
            },
            valueRange = 0f..100f,
            enabled = available && AudioEffectsBridge.virtualizerSupported()
        )

        OutlinedButton(
            onClick = {
                AudioEffectsBridge.reset()
                AudioStemsEngine.resetStems()
                bass = 0f
                virtual = 0f
                selected = "Flat"
                refresh++
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Reset All Effects & DSP")
        }
    }
}
