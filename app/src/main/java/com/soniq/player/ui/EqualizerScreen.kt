package com.soniq.player.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.soniq.player.audio.AudioEffectsBridge
import kotlin.math.roundToInt

private data class EqPreset(val name: String, val curve: List<Float>)
private val eqPresets = listOf(
    EqPreset("Flat", listOf(0f,0f,0f,0f,0f,0f,0f,0f)),
    EqPreset("Bass", listOf(5f,4f,2f,0f,-1f,-1f,0f,1f)),
    EqPreset("Pop", listOf(-1f,1f,3f,4f,2f,0f,-1f,-1f)),
    EqPreset("Rock", listOf(4f,2f,-1f,-2f,2f,4f,5f,4f)),
    EqPreset("Vocal", listOf(-2f,-1f,1f,3f,4f,3f,1f,0f)),
    EqPreset("Electronic", listOf(4f,3f,0f,-1f,2f,0f,3f,4f))
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
                available = true; bands = AudioEffectsBridge.bandCount()
                range = AudioEffectsBridge.bandLevelRange()
                return@LaunchedEffect
            }
            kotlinx.coroutines.delay(500)
        }
    }
    val levels = remember(bands, refresh) { (0 until bands).map { AudioEffectsBridge.bandLevel(it).toFloat() } }
    Column(modifier.verticalScroll(rememberScrollState()).padding(top = 8.dp, bottom = 28.dp)) {
        Text("SONIQ EQUALIZER", color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(if (available) "$bands native bands available on this device" else "Play a track to initialize the device equalizer.", color = Color(0xFFB9A9D3))
        Spacer(Modifier.height(16.dp))
        Text("PRESETS", color = Color(0xFF67E8F9), fontWeight = FontWeight.Bold)
        eqPresets.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { preset ->
                    FilterChip(selected == preset.name, { 
                        selected = preset.name
                        repeat(bands) { i ->
                            val x = if (bands <= 1) 0f else i.toFloat() / (bands - 1)
                            val j = (x * (preset.curve.size - 1)).roundToInt()
                            AudioEffectsBridge.setBandLevel(i, (preset.curve[j] * 100).roundToInt())
                        }
                        refresh++
                    }, label = { Text(preset.name) }, enabled = available)
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        if (available && bands > 0) {
            repeat(bands) { index ->
                val hz = AudioEffectsBridge.centerFrequencyHz(index)
                var value by remember(bands, index, refresh) { mutableFloatStateOf(levels.getOrElse(index) { 0f }) }
                Row { Text(if (hz >= 1000) "%.1f kHz".format(hz / 1000f) else "$hz Hz", color = Color.White, modifier = Modifier.weight(1f))
                    Text("%.1f dB".format(value / 100f), color = Color(0xFFB9A9D3)) }
                Slider(value, { value = it; selected = "Custom"; AudioEffectsBridge.setBandLevel(index, it.roundToInt()) },
                    valueRange = range.first.toFloat()..range.last.toFloat())
            }
        } else {
            Text("Native Android equalizer is unavailable until an audio session is created, or the device does not expose this effect.", color = Color.LightGray)
        }
        Spacer(Modifier.height(10.dp))
        Text("BASS BOOST", color = Color(0xFF67E8F9), fontWeight = FontWeight.Bold)
        Slider(bass, { bass = it; AudioEffectsBridge.setBassStrength((it * 10).roundToInt()) }, valueRange = 0f..100f, enabled = available && AudioEffectsBridge.bassSupported())
        Text("VIRTUALIZER / SPATIAL", color = Color(0xFF67E8F9), fontWeight = FontWeight.Bold)
        Slider(virtual, { virtual = it; AudioEffectsBridge.setVirtualizerStrength((it * 10).roundToInt()) }, valueRange = 0f..100f, enabled = available && AudioEffectsBridge.virtualizerSupported())
        OutlinedButton(onClick = { AudioEffectsBridge.reset(); bass = 0f; virtual = 0f; selected = "Flat"; refresh++ }, modifier = Modifier.fillMaxWidth()) { Text("Reset") }
        Text("The number of bands and available effects are vendor/device controlled. Presets are mapped onto the actual bands exposed by Android. A custom 10/20/31-band software DSP is not included in this build.", color = Color(0xFF9588AA), style = MaterialTheme.typography.bodySmall)
    }
}
