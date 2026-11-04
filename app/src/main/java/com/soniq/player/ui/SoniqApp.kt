@file:OptIn(
    androidx.compose.animation.ExperimentalAnimationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class
)
package com.soniq.player.ui

import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.palette.graphics.Palette
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.google.common.util.concurrent.MoreExecutors
import com.soniq.player.audio.AudioDeviceMonitor
import com.soniq.player.audio.AudioEffectsBridge
import com.soniq.player.audio.AudioFingerprintFixer
import com.soniq.player.audio.AudioOutput
import com.soniq.player.audio.GyroscopeParallaxEngine
import com.soniq.player.audio.RealtimeFftMonitor
import com.soniq.player.data.*
import com.soniq.player.playback.PlaybackService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import kotlin.math.*

private val OLEDBlack = Color(0xFF06040A)
private val NeonPurple = Color(0xFF9B6CFF)
private val DeepViolet = Color(0xFF6B2BEE)
private val ElectricCyan = Color(0xFF53F6FF)
private val AquaGlow = Color(0xFF26E0FA)
private val MutedLavender = Color(0xFFC7BCE0)
private val GlassPill = Color(0x351F143D)
private val GlassBorder = Color(0x5553F6FF)

enum class MainTab(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Default.Home),
    LIBRARY("Library", Icons.Default.LibraryMusic),
    EQUALIZER("Equalizer", Icons.Default.Equalizer),
    PLAYLISTS("Playlists", Icons.Default.QueueMusic),
    SETTINGS("Settings", Icons.Default.Settings)
}

private fun applySavedMetadataOverrides(rawSongs: List<Song>, prefs: SharedPreferences): List<Song> {
    val overridesJson = prefs.getString("song_overrides", null) ?: return rawSongs
    return runCatching {
        val root = JSONObject(overridesJson)
        rawSongs.map { song ->
            val key = song.id.toString()
            if (root.has(key)) {
                val obj = root.getJSONObject(key)
                song.copy(
                    title = obj.optString("title", song.title),
                    artist = obj.optString("artist", song.artist),
                    album = obj.optString("album", song.album),
                    albumArtUri = obj.optString("art", "").ifBlank { song.albumArtUri }
                )
            } else song
        }
    }.getOrDefault(rawSongs)
}

private fun saveMetadataOverride(prefs: SharedPreferences, songId: Long, title: String, artist: String, album: String, artUri: String?) {
    runCatching {
        val raw = prefs.getString("song_overrides", "{}") ?: "{}"
        val root = JSONObject(raw)
        val entry = JSONObject().apply {
            put("title", title)
            put("artist", artist)
            put("album", album)
            if (!artUri.isNullOrBlank()) put("art", artUri)
        }
        root.put(songId.toString(), entry)
        prefs.edit().putString("song_overrides", root.toString()).apply()
    }
}

private fun timeText(ms: Long): String {
    val s = (ms.coerceAtLeast(0L) / 1000).toInt()
    return "%d:%02d".format(s / 60, s % 60)
}

@Composable
fun BouncyButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.88f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "bouncy_scale"
    )
    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

@Composable
fun BouncyIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    content: @Composable () -> Unit
) {
    BouncyButton(onClick = onClick, modifier = modifier.size(size)) {
        content()
    }
}

@Composable
fun FloatingMiniPlayer(
    song: Song,
    isPlaying: Boolean,
    progress: Float,
    primaryColor: Color,
    secondaryColor: Color,
    onOpen: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(62.dp)
            .clickable { onOpen() },
        shape = RoundedCornerShape(22.dp),
        color = Color(0xEE160E2E),
        border = BorderStroke(1.2.dp, secondaryColor.copy(alpha = 0.55f))
    ) {
        Column(Modifier.fillMaxSize()) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.5.dp),
                color = secondaryColor,
                trackColor = Color.Transparent
            )
            Row(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SongThumb(song = song, modifier = Modifier.size(42.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        song.title,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        song.artist,
                        color = MutedLavender,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = onPlayPause) {
                    Icon(
                        if (isPlaying) Icons.Default.PauseCircleFilled else Icons.Default.PlayCircleFilled,
                        contentDescription = "Play/Pause",
                        tint = secondaryColor,
                        modifier = Modifier.size(32.dp)
                    )
                }
                IconButton(onClick = onNext) {
                    Icon(
                        Icons.Default.SkipNext,
                        contentDescription = "Next",
                        tint = Color.White,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun FloatingBottomNavBar(
    currentTab: MainTab,
    activeColor: Color,
    onSelect: (MainTab) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp),
        shape = RoundedCornerShape(32.dp),
        color = Color(0xEE100A24),
        border = BorderStroke(1.2.dp, GlassBorder)
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 6.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            MainTab.entries.forEach { tab ->
                val selected = currentTab == tab
                val animScale by animateFloatAsState(
                    targetValue = if (selected) 1.15f else 1f,
                    animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
                    label = "tab_scale"
                )
                BouncyButton(
                    onClick = { onSelect(tab) },
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.scale(animScale)
                    ) {
                        Icon(
                            tab.icon,
                            contentDescription = tab.label,
                            tint = if (selected) activeColor else MutedLavender.copy(alpha = 0.5f),
                            modifier = Modifier.size(22.dp)
                        )
                        Text(
                            tab.label,
                            color = if (selected) activeColor else MutedLavender.copy(alpha = 0.5f),
                            fontSize = 9.sp,
                            maxLines = 1,
                            softWrap = false,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun LiquidWaterWaveBackground(
    isPlaying: Boolean,
    primaryColor: Color = NeonPurple,
    secondaryColor: Color = ElectricCyan,
    pitch: Float = 0f,
    roll: Float = 0f,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "water_caustics")
    val t1 by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 2f * PI.toFloat(),
        animationSpec = infiniteRepeatable(tween(if (isPlaying) 6500 else 12000, easing = LinearEasing), RepeatMode.Restart),
        label = "wave1"
    )
    val t2 by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 2f * PI.toFloat(),
        animationSpec = infiniteRepeatable(tween(if (isPlaying) 8500 else 16000, easing = LinearEasing), RepeatMode.Restart),
        label = "wave2"
    )
    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        drawRect(OLEDBlack)
        val gyroShiftX = roll * 35.dp.toPx()
        val gyroShiftY = pitch * 35.dp.toPx()
        val x1 = w * (0.65f + 0.18f * cos(t1.toDouble()).toFloat()) + gyroShiftX
        val y1 = h * (0.28f + 0.14f * sin(t2.toDouble()).toFloat()) + gyroShiftY
        val r1 = w * 0.85f
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    primaryColor.copy(alpha = if (isPlaying) 0.35f else 0.22f),
                    DeepViolet.copy(alpha = if (isPlaying) 0.20f else 0.10f),
                    Color.Transparent
                ),
                center = Offset(x1, y1),
                radius = r1
            ),
            radius = r1,
            center = Offset(x1, y1)
        )
        val x2 = w * (0.32f + 0.16f * sin((t1 * 1.2f).toDouble()).toFloat()) - gyroShiftX
        val y2 = h * (0.72f + 0.12f * cos((t2 * 0.9f).toDouble()).toFloat()) - gyroShiftY
        val r2 = w * 0.90f
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    secondaryColor.copy(alpha = if (isPlaying) 0.30f else 0.18f),
                    AquaGlow.copy(alpha = if (isPlaying) 0.15f else 0.08f),
                    Color.Transparent
                ),
                center = Offset(x2, y2),
                radius = r2
            ),
            radius = r2,
            center = Offset(x2, y2)
        )
        if (isPlaying) {
            val wavePath = Path()
            val waveHeight = 18.dp.toPx()
            val baseline = h * 0.5f + (gyroShiftY * 0.4f)
            wavePath.moveTo(0f, baseline)
            var px = 0f
            while (px <= w) {
                val py = baseline + sin((px / w * 4f * PI + t1).toDouble()).toFloat() * waveHeight
                wavePath.lineTo(px, py)
                px += 20f
            }
            wavePath.lineTo(w, h)
            wavePath.lineTo(0f, h)
            wavePath.close()
            drawPath(
                path = wavePath,
                brush = Brush.verticalGradient(
                    colors = listOf(secondaryColor.copy(alpha = 0.06f), Color.Transparent)
                )
            )
        }
    }
}

@Composable
fun RoundedRectTimelineCenterpiece(
    song: Song,
    isPlaying: Boolean,
    primaryColor: Color,
    secondaryColor: Color,
    gyroPitch: Float,
    gyroRoll: Float,
    modifier: Modifier = Modifier
) {
    val amplitudes = RealtimeFftMonitor.amplitudes
    val numBars = 22

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .graphicsLayer {
                rotationX = gyroPitch * 10f
                rotationY = gyroRoll * 10f
                cameraDistance = 16f * density
            },
        contentAlignment = Alignment.Center
    ) {
        SongThumb(
            song = song,
            modifier = Modifier
                .fillMaxSize(0.92f)
                .clip(RoundedCornerShape(32.dp))
        )

        Canvas(modifier = Modifier.fillMaxSize(0.92f)) {
            val w = size.width
            val h = size.height
            val corner = 32.dp.toPx()

            drawRoundRect(
                brush = Brush.sweepGradient(
                    listOf(secondaryColor.copy(alpha = 0.85f), primaryColor.copy(alpha = 0.85f), secondaryColor.copy(alpha = 0.85f))
                ),
                size = size,
                cornerRadius = CornerRadius(corner, corner),
                style = Stroke(2.5.dp.toPx())
            )

            drawRoundRect(
                brush = Brush.verticalGradient(
                    0.55f to Color.Transparent,
                    1.0f to Color(0xDD0B071A)
                ),
                size = size,
                cornerRadius = CornerRadius(corner, corner)
            )

            val barWidth = 4.dp.toPx()
            val totalBarsWidth = numBars * barWidth
            val spacing = (w * 0.78f - totalBarsWidth) / (numBars - 1).coerceAtLeast(1)
            val startX = (w * 0.11f)
            val bottomY = h - 20.dp.toPx()

            for (i in 0 until numBars) {
                val amp = if (isPlaying) amplitudes[i % amplitudes.size] else 0.08f
                val barHeight = (44.dp.toPx() * amp).coerceIn(4.dp.toPx(), 42.dp.toPx())
                val bx = startX + i * (barWidth + spacing)

                drawRoundRect(
                    brush = Brush.verticalGradient(
                        listOf(secondaryColor, primaryColor)
                    ),
                    topLeft = Offset(bx, bottomY - barHeight),
                    size = Size(barWidth, barHeight),
                    cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
                )
            }
        }
    }
}

@Composable
fun WaveformSeekScrubber(
    position: Long,
    duration: Long,
    primaryColor: Color,
    secondaryColor: Color,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val progress = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val barCount = 38
    val staticPattern = remember {
        listOf(
            0.25f, 0.45f, 0.70f, 0.35f, 0.80f, 0.95f, 0.60f, 0.40f,
            0.65f, 0.85f, 0.50f, 0.30f, 0.75f, 0.90f, 0.60f, 0.45f,
            0.85f, 0.70f, 0.55f, 0.40f, 0.65f, 0.80f, 0.95f, 0.50f,
            0.35f, 0.60f, 0.75f, 0.85f, 0.60f, 0.45f, 0.70f, 0.85f,
            0.55f, 0.40f, 0.65f, 0.50f, 0.35f, 0.20f
        )
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .pointerInput(duration) {
                detectTapGestures { offset ->
                    val pct = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                    onSeek((pct * duration).toLong())
                }
            }
            .pointerInput(duration) {
                detectDragGestures { change: PointerInputChange, _ ->
                    val pct = (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                    onSeek((pct * duration).toLong())
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val barWidth = 4.dp.toPx()
            val spacing = (size.width - (barCount * barWidth)) / (barCount - 1)
            val midY = size.height / 2f
            for (i in 0 until barCount) {
                val heightFraction = staticPattern[i % staticPattern.size]
                val barHeight = (size.height * 0.75f * heightFraction).coerceAtLeast(6.dp.toPx())
                val x = i * (barWidth + spacing)
                val isFilled = (i.toFloat() / barCount) <= progress
                val brush = if (isFilled) {
                    Brush.verticalGradient(listOf(secondaryColor, primaryColor))
                } else {
                    Brush.verticalGradient(listOf(Color(0x446B2BEE), Color(0x2253F6FF)))
                }
                drawRoundRect(
                    brush = brush,
                    topLeft = Offset(x, midY - barHeight / 2),
                    size = Size(barWidth, barHeight),
                    cornerRadius = CornerRadius(4.dp.toPx())
                )
            }
        }
    }
}

@Composable
fun LiveSyncedKaraokeLyrics(
    lyrics: List<LyricLine>,
    positionMs: Long,
    activeColor: Color,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val activeLineIdx = remember(positionMs, lyrics) {
        lyrics.indexOfLast { it.timestampMs <= positionMs }.coerceAtLeast(0)
    }

    LaunchedEffect(activeLineIdx) {
        if (lyrics.isNotEmpty()) {
            listState.animateScrollToItem((activeLineIdx - 2).coerceAtLeast(0))
        }
    }

    LazyColumn(state = listState, modifier = modifier.fillMaxSize()) {
        itemsIndexed(lyrics) { idx, line ->
            val isCurrentLine = idx == activeLineIdx

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp)
                    .clickable { onSeek(line.timestampMs) },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (!isCurrentLine || line.words.isEmpty()) {
                    Text(
                        text = line.text,
                        color = if (isCurrentLine) activeColor else MutedLavender.copy(alpha = 0.40f),
                        fontSize = if (isCurrentLine) 21.sp else 15.sp,
                        fontWeight = if (isCurrentLine) FontWeight.Black else FontWeight.Normal,
                        textAlign = TextAlign.Center
                    )
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        line.words.forEach { word ->
                            val isWordSung = positionMs >= word.startMs
                            val isWordActive = positionMs in word.startMs..word.endMs

                            val wordColor = when {
                                isWordActive -> Color.White
                                isWordSung -> activeColor
                                else -> MutedLavender.copy(alpha = 0.45f)
                            }

                            val wordScale by animateFloatAsState(
                                targetValue = if (isWordActive) 1.15f else 1.0f,
                                animationSpec = spring(stiffness = Spring.StiffnessHigh),
                                label = "word_glow"
                            )

                            Text(
                                text = "${word.text} ",
                                color = wordColor,
                                fontSize = 21.sp,
                                fontWeight = if (isWordSung) FontWeight.Black else FontWeight.Bold,
                                modifier = Modifier.graphicsLayer {
                                    scaleX = wordScale
                                    scaleY = wordScale
                                    if (isWordActive) {
                                        shadowElevation = 18f
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun FullPlayer(
    song: Song,
    isPlaying: Boolean,
    position: Long,
    duration: Long,
    shuffle: Boolean,
    repeatMode: Int,
    speedMode: String,
    slowedSpeedFactor: Float,
    reverbStrengthFactor: Float,
    onSlowedSpeedChange: (Float) -> Unit,
    onReverbStrengthChange: (Float) -> Unit,
    onSpeedModeChange: (String) -> Unit,
    isFavorite: Boolean,
    primaryAccent: Color,
    secondaryAccent: Color,
    gyroPitch: Float,
    gyroRoll: Float,
    onClose: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
    onShuffle: () -> Unit,
    onRepeat: () -> Unit,
    onToggleFavorite: () -> Unit,
    onLaunchParty: () -> Unit,
    onOpenEqualizer: () -> Unit
) {
    val context = LocalContext.current
    var showLyrics by remember { mutableStateOf(false) }
    var lyrics by remember { mutableStateOf<List<LyricLine>>(emptyList()) }
    var isLoadingLyrics by remember { mutableStateOf(false) }

    LaunchedEffect(song.id, song.title) {
        isLoadingLyrics = true
        lyrics = LyricsEngine.loadLyricsForSong(context, song)
        isLoadingLyrics = false
    }

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures { } }
    ) {
        LiquidWaterWaveBackground(
            isPlaying = isPlaying,
            primaryColor = primaryAccent,
            secondaryColor = secondaryAccent,
            pitch = gyroPitch,
            roll = gyroRoll
        )
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                Modifier
                    .size(width = 44.dp, height = 5.dp)
                    .clip(CircleShape)
                    .background(MutedLavender.copy(alpha = 0.45f))
            )
            Spacer(Modifier.height(6.dp))

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BouncyIconButton(onClick = onClose, size = 38.dp) {
                        Icon(Icons.Default.KeyboardArrowDown, null, tint = Color.White, modifier = Modifier.size(26.dp))
                    }
                    Text(
                        "NOW PLAYING",
                        color = MutedLavender,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        letterSpacing = 1.sp,
                        maxLines = 1,
                        softWrap = false
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BouncyIconButton(onClick = onLaunchParty, size = 36.dp) {
                        Icon(Icons.Default.Headphones, "Party", tint = secondaryAccent, modifier = Modifier.size(20.dp))
                    }
                    BouncyIconButton(onClick = { showLyrics = !showLyrics }, size = 36.dp) {
                        Icon(Icons.Default.Lyrics, "Lyrics", tint = if (showLyrics) secondaryAccent else MutedLavender, modifier = Modifier.size(20.dp))
                    }
                    BouncyIconButton(onClick = onOpenEqualizer, size = 36.dp) {
                        Icon(Icons.Default.Equalizer, "EQ", tint = secondaryAccent, modifier = Modifier.size(20.dp))
                    }
                }
            }

            Spacer(Modifier.height(6.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically
            ) {
                listOf("Normal", "Nightcore", "Slowed").forEach { mode ->
                    val isSel = speedMode == mode
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = if (isSel) secondaryAccent.copy(alpha = 0.22f) else Color(0x2218102C),
                        border = BorderStroke(1.dp, if (isSel) secondaryAccent else Color(0x33C7BCE0)),
                        modifier = Modifier
                            .height(32.dp)
                            .clickable { onSpeedModeChange(mode) }
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 12.dp)) {
                            Text(
                                text = when (mode) {
                                    "Nightcore" -> "⚡ 1.25x Nightcore"
                                    "Slowed" -> "🌌 Slowed + Reverb"
                                    else -> "1.0x Normal"
                                },
                                color = if (isSel) secondaryAccent else MutedLavender,
                                fontSize = 11.sp,
                                maxLines = 1,
                                softWrap = false,
                                fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
            }

            AnimatedVisibility(visible = speedMode == "Slowed") {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color(0x6618102C),
                    border = BorderStroke(1.dp, GlassBorder),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                ) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Slowed Speed", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(
                                    onClick = { onSlowedSpeedChange((slowedSpeedFactor - 0.05f).coerceIn(0.60f, 0.95f)) },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(Icons.Default.Remove, null, tint = secondaryAccent, modifier = Modifier.size(16.dp))
                                }
                                Text(
                                    "%.2fx".format(slowedSpeedFactor),
                                    color = secondaryAccent,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp)
                                )
                                IconButton(
                                    onClick = { onSlowedSpeedChange((slowedSpeedFactor + 0.05f).coerceIn(0.60f, 0.95f)) },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(Icons.Default.Add, null, tint = secondaryAccent, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                        Slider(
                            value = slowedSpeedFactor,
                            onValueChange = onSlowedSpeedChange,
                            valueRange = 0.60f..0.95f,
                            colors = SliderDefaults.colors(thumbColor = secondaryAccent, activeTrackColor = secondaryAccent),
                            modifier = Modifier.height(24.dp)
                        )

                        Spacer(Modifier.height(4.dp))

                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Reverb Level", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(
                                    onClick = { onReverbStrengthChange((reverbStrengthFactor - 0.10f).coerceIn(0f, 1f)) },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(Icons.Default.Remove, null, tint = primaryAccent, modifier = Modifier.size(16.dp))
                                }
                                Text(
                                    "${(reverbStrengthFactor * 100).roundToInt()}%",
                                    color = primaryAccent,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp)
                                )
                                IconButton(
                                    onClick = { onReverbStrengthChange((reverbStrengthFactor + 0.10f).coerceIn(0f, 1f)) },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(Icons.Default.Add, null, tint = primaryAccent, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                        Slider(
                            value = reverbStrengthFactor,
                            onValueChange = onReverbStrengthChange,
                            valueRange = 0.0f..1.0f,
                            colors = SliderDefaults.colors(thumbColor = primaryAccent, activeTrackColor = primaryAccent),
                            modifier = Modifier.height(24.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(6.dp))

            if (showLyrics) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(26.dp))
                        .background(Color(0x6618102C))
                        .border(1.5.dp, GlassBorder, RoundedCornerShape(26.dp))
                        .padding(18.dp)
                ) {
                    if (isLoadingLyrics) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = secondaryAccent)
                        }
                    } else if (lyrics.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                "No synchronized lyrics found for '${song.title}'.\nAuto-fix the song title to match online databases.",
                                color = MutedLavender,
                                fontSize = 13.sp,
                                textAlign = TextAlign.Center
                            )
                        }
                    } else {
                        LiveSyncedKaraokeLyrics(
                            lyrics = lyrics,
                            positionMs = position,
                            activeColor = secondaryAccent,
                            onSeek = onSeek
                        )
                    }
                }
            } else {
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    RoundedRectTimelineCenterpiece(
                        song = song,
                        isPlaying = isPlaying,
                        primaryColor = primaryAccent,
                        secondaryColor = secondaryAccent,
                        gyroPitch = gyroPitch,
                        gyroRoll = gyroRoll,
                        modifier = Modifier.size(220.dp)
                    )
                }
            }

            Spacer(Modifier.height(6.dp))

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(song.title, color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${song.artist} • ${song.album}", color = MutedLavender, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = onToggleFavorite) {
                    Icon(
                        if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = "Favorite",
                        tint = if (isFavorite) Color(0xFFFF5286) else MutedLavender
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            WaveformSeekScrubber(
                position = position,
                duration = duration,
                primaryColor = primaryAccent,
                secondaryColor = secondaryAccent,
                onSeek = onSeek
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(timeText(position), color = MutedLavender, fontSize = 11.sp)
                Text(timeText(duration), color = MutedLavender, fontSize = 11.sp)
            }

            Spacer(Modifier.height(6.dp))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                BouncyIconButton(onClick = onShuffle) {
                    Icon(Icons.Default.Shuffle, null, tint = if (shuffle) secondaryAccent else MutedLavender)
                }
                BouncyIconButton(onClick = onPrevious) {
                    Icon(Icons.Default.SkipPrevious, null, tint = Color.White, modifier = Modifier.size(36.dp))
                }
                BouncyButton(
                    onClick = onPlayPause,
                    modifier = Modifier.size(66.dp)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = primaryAccent,
                        border = BorderStroke(2.5.dp, secondaryAccent),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                null,
                                tint = Color.White,
                                modifier = Modifier.size(36.dp)
                            )
                        }
                    }
                }
                BouncyIconButton(onClick = onNext) {
                    Icon(Icons.Default.SkipNext, null, tint = Color.White, modifier = Modifier.size(36.dp))
                }
                BouncyIconButton(onClick = onRepeat) {
                    Icon(
                        if (repeatMode == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                        null,
                        tint = if (repeatMode != Player.REPEAT_MODE_OFF) secondaryAccent else MutedLavender
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}

@Composable
fun HomeScreen(
    songs: List<Song>,
    recents: List<String>,
    favorites: Set<String>,
    onPlay: (Song, List<Song>) -> Unit,
    onFavorite: (Song) -> Unit,
    onAddToPlaylist: (Song) -> Unit,
    onAddToAlbum: (Song) -> Unit,
    onEdit: (Song) -> Unit,
    onNavigateLibrary: (String) -> Unit
) {
    val songMap = remember(songs) { songs.associateBy { it.id.toString() } }
    val recentSongs = remember(songs, recents) {
        recents.mapNotNull { songMap[it] }.take(5)
    }
    val favSongs = remember(songs, favorites) {
        songs.filter { it.id.toString() in favorites }.take(5)
    }
    val lastAddedSongs = remember(songs) { songs.sortedByDescending { it.dateAdded }.take(5) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp)
    ) {
        Surface(
            color = GlassPill,
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.2.dp, GlassBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                Modifier.padding(16.dp),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.clickable { onNavigateLibrary("Songs") }
                ) {
                    Text("${songs.size}", color = ElectricCyan, fontSize = 22.sp, fontWeight = FontWeight.Black)
                    Text("All Tracks", color = MutedLavender, fontSize = 11.sp)
                }
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.clickable { onNavigateLibrary("Favorites") }
                ) {
                    Text("${favorites.size}", color = NeonPurple, fontSize = 22.sp, fontWeight = FontWeight.Black)
                    Text("Favorites", color = MutedLavender, fontSize = 11.sp)
                }
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.clickable { onNavigateLibrary("Artists") }
                ) {
                    Text("${songs.map { it.artist }.distinct().size}", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Black)
                    Text("Artists", color = MutedLavender, fontSize = 11.sp)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("RECENTLY PLAYED", color = ElectricCyan, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            if (recentSongs.isNotEmpty()) {
                TextButton(onClick = { onNavigateLibrary("Recent") }) {
                    Text("See All", color = ElectricCyan, fontSize = 11.sp)
                }
            }
        }
        if (recentSongs.isEmpty()) {
            Text("Play any track to populate your recent timeline.", color = MutedLavender, fontSize = 12.sp)
        } else {
            recentSongs.forEach { song ->
                SongCard(
                    song = song,
                    favorite = song.id.toString() in favorites,
                    onPlay = { onPlay(song, recentSongs) },
                    onFavorite = { onFavorite(song) },
                    onAddToPlaylist = { onAddToPlaylist(song) },
                    onAddToAlbum = { onAddToAlbum(song) },
                    onEdit = { onEdit(song) }
                )
                Spacer(Modifier.height(6.dp))
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("NEWLY DISCOVERED", color = AquaGlow, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            TextButton(onClick = { onNavigateLibrary("Songs") }) {
                Text("Browse All", color = AquaGlow, fontSize = 11.sp)
            }
        }
        lastAddedSongs.forEach { song ->
            SongCard(
                song = song,
                favorite = song.id.toString() in favorites,
                onPlay = { onPlay(song, lastAddedSongs) },
                onFavorite = { onFavorite(song) },
                onAddToPlaylist = { onAddToPlaylist(song) },
                onAddToAlbum = { onAddToAlbum(song) },
                onEdit = { onEdit(song) }
            )
            Spacer(Modifier.height(6.dp))
        }
        if (favSongs.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Text("FAVORITE ROTATION", color = NeonPurple, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
            favSongs.forEach { song ->
                SongCard(
                    song = song,
                    favorite = true,
                    onPlay = { onPlay(song, favSongs) },
                    onFavorite = { onFavorite(song) },
                    onAddToPlaylist = { onAddToPlaylist(song) },
                    onAddToAlbum = { onAddToAlbum(song) },
                    onEdit = { onEdit(song) }
                )
                Spacer(Modifier.height(6.dp))
            }
        }
    }
}

@Composable
fun LibraryScreen(
    allSongs: List<Song>,
    mode: String,
    onMode: (String) -> Unit,
    filterKey: String?,
    onFilterKey: (String?) -> Unit,
    query: String,
    listState: LazyListState,
    favorites: Set<String>,
    recents: List<String>,
    playlistName: String,
    playlistSongs: Set<String>,
    customAlbums: List<String>,
    getCustomAlbumSongs: (String) -> Set<String>,
    onCreateAlbum: () -> Unit,
    onAutoFixAll: () -> Unit,
    onPlay: (Song, List<Song>) -> Unit,
    onFavorite: (Song) -> Unit,
    onAddToPlaylist: (Song) -> Unit,
    onAddToAlbum: (Song) -> Unit,
    onEdit: (Song) -> Unit,
    modifier: Modifier = Modifier
) {
    val artistGroups = remember(allSongs) {
        allSongs.groupBy { it.artist }.toList().sortedBy { it.first.lowercase() }
    }
    val albumGroups = remember(allSongs, customAlbums) {
        val mediaStoreAlbums = allSongs.groupBy { it.album }.toList()
        val userAlbums = customAlbums.map { name ->
            val trackIds = getCustomAlbumSongs(name)
            name to allSongs.filter { it.id.toString() in trackIds }
        }
        (userAlbums + mediaStoreAlbums.filter { item -> userAlbums.none { it.first.equals(item.first, true) } })
            .sortedBy { it.first.lowercase() }
    }
    val folderGroups = remember(allSongs) {
        allSongs.groupBy { it.folder }.toList().sortedBy { it.first.lowercase() }
    }
    val unknownCount = remember(allSongs) {
        allSongs.count { AudioFingerprintFixer.isSongUnknown(it) }
    }
    val songsToDisplay = remember(allSongs, mode, filterKey, query, favorites, recents, playlistSongs, customAlbums) {
        val songMap = allSongs.associateBy { it.id.toString() }
        val base = when {
            filterKey != null -> {
                when (mode) {
                    "Artists" -> allSongs.filter { it.artist == filterKey }
                    "Albums" -> {
                        if (filterKey in customAlbums) {
                            val ids = getCustomAlbumSongs(filterKey)
                            allSongs.filter { it.id.toString() in ids }
                        } else {
                            allSongs.filter { it.album == filterKey }
                        }
                    }
                    "Folders" -> allSongs.filter { it.folder == filterKey }
                    else -> allSongs
                }
            }
            mode == "Favorites" -> allSongs.filter { it.id.toString() in favorites }
            mode == "Recent" -> recents.mapNotNull { songMap[it] }
            mode == "Playlist" -> allSongs.filter { it.id.toString() in playlistSongs }
            else -> allSongs
        }
        if (query.isBlank()) base else base.filter {
            it.title.contains(query, true) || it.artist.contains(query, true) ||
                    it.album.contains(query, true) || it.folder.contains(query, true)
        }
    }

    Column(modifier) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Songs", "Artists", "Albums", "Folders", "Favorites", "Recent", "Playlist").forEach { item ->
                FilterChip(
                    selected = mode == item,
                    onClick = { onMode(item) },
                    label = { Text(if (item == "Playlist") "Playlist: $playlistName" else item) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = NeonPurple.copy(alpha = 0.35f),
                        selectedLabelColor = ElectricCyan,
                        labelColor = MutedLavender
                    )
                )
            }
        }
        if (filterKey != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clickable { onFilterKey(null) }
            ) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = ElectricCyan, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(filterKey, color = ElectricCyan, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(6.dp))
                Text("(${songsToDisplay.size})", color = MutedLavender, fontSize = 12.sp)
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val countText = when (mode) {
                    "Artists" -> "${artistGroups.size} ARTISTS"
                    "Albums" -> "${albumGroups.size} ALBUMS"
                    "Folders" -> "${folderGroups.size} FOLDERS"
                    else -> "${songsToDisplay.size} TRACKS • ${allSongs.size} TOTAL"
                }
                Text(
                    countText,
                    color = MutedLavender,
                    fontSize = 11.sp,
                    modifier = Modifier.weight(1f)
                )

                if (unknownCount > 0) {
                    TextButton(
                        onClick = onAutoFixAll,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Icon(Icons.Default.AutoFixHigh, null, tint = ElectricCyan, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Auto-Fix ($unknownCount)", color = ElectricCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }

                if (mode == "Albums") {
                    TextButton(onClick = onCreateAlbum, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
                        Icon(Icons.Default.Add, null, tint = ElectricCyan, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("+ Album", color = ElectricCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        when {
            filterKey == null && mode == "Artists" -> {
                val filteredArtists = remember(artistGroups, query) {
                    if (query.isBlank()) artistGroups else artistGroups.filter { it.first.contains(query, true) }
                }
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filteredArtists, key = { it.first }) { (artist, songList) ->
                        GroupCard(
                            title = artist,
                            subtitle = "${songList.size} Tracks",
                            icon = Icons.Default.Person,
                            representativeSong = songList.firstOrNull(),
                            isCustom = false,
                            onClick = { onFilterKey(artist) }
                        )
                    }
                }
            }
            filterKey == null && mode == "Albums" -> {
                val filteredAlbums = remember(albumGroups, query) {
                    if (query.isBlank()) albumGroups else albumGroups.filter { it.first.contains(query, true) }
                }
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filteredAlbums, key = { it.first }) { (album, songList) ->
                        val isUserAlbum = album in customAlbums
                        GroupCard(
                            title = album,
                            subtitle = if (isUserAlbum) "Custom Album • ${songList.size} Tracks" else "${songList.firstOrNull()?.artist ?: "Various"} • ${songList.size} Tracks",
                            icon = Icons.Default.Album,
                            representativeSong = songList.firstOrNull(),
                            isCustom = isUserAlbum,
                            onClick = { onFilterKey(album) }
                        )
                    }
                }
            }
            filterKey == null && mode == "Folders" -> {
                val filteredFolders = remember(folderGroups, query) {
                    if (query.isBlank()) folderGroups else folderGroups.filter { it.first.contains(query, true) }
                }
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filteredFolders, key = { it.first }) { (folder, songList) ->
                        GroupCard(
                            title = folder,
                            subtitle = "${songList.size} Tracks",
                            icon = Icons.Default.Folder,
                            representativeSong = null,
                            isCustom = false,
                            onClick = { onFilterKey(folder) }
                        )
                    }
                }
            }
            else -> {
                if (songsToDisplay.isEmpty()) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text("No matching tracks in this view.", color = MutedLavender)
                    }
                } else {
                    Row(Modifier.weight(1f)) {
                        LazyColumn(
                            Modifier.weight(1f),
                            state = listState,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(songsToDisplay, key = { it.id }) { song ->
                                SongCard(
                                    song = song,
                                    favorite = song.id.toString() in favorites,
                                    onPlay = { onPlay(song, songsToDisplay) },
                                    onFavorite = { onFavorite(song) },
                                    onAddToPlaylist = { onAddToPlaylist(song) },
                                    onAddToAlbum = { onAddToAlbum(song) },
                                    onEdit = { onEdit(song) }
                                )
                            }
                        }
                        SamsungAlphabetScroller(
                            songs = songsToDisplay,
                            listState = listState,
                            modifier = Modifier.padding(start = 6.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PlaylistScreen(
    playlists: List<String>,
    selected: String,
    allSongs: List<Song>,
    getPlaylistTrackIds: (String) -> Set<String>,
    onOpen: (String) -> Unit,
    onPlayAll: (String) -> Unit,
    onDelete: (String) -> Unit,
    onCreate: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("CUSTOM PLAYLISTS", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("Organize collections & launch instant party queues", color = MutedLavender, fontSize = 11.sp)
            }
            Button(
                onClick = onCreate,
                colors = ButtonDefaults.buttonColors(containerColor = DeepViolet)
            ) {
                Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("New")
            }
        }
        Spacer(Modifier.height(14.dp))
        playlists.forEach { name ->
            val trackIds = getPlaylistTrackIds(name)
            val matchedSongs = allSongs.filter { it.id.toString() in trackIds }
            BouncyButton(onClick = { onOpen(name) }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Surface(
                    color = GlassPill,
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(1.2.dp, if (name == selected) ElectricCyan else GlassBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (matchedSongs.isNotEmpty()) {
                            SongThumb(song = matchedSongs.first(), modifier = Modifier.size(48.dp))
                        } else {
                            Box(
                                Modifier
                                    .size(48.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(Brush.linearGradient(listOf(NeonPurple.copy(alpha = 0.5f), ElectricCyan.copy(alpha = 0.5f)))),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.QueueMusic, null, tint = Color.White)
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            Text("${matchedSongs.size} tracks", color = MutedLavender, fontSize = 12.sp)
                        }
                        if (matchedSongs.isNotEmpty()) {
                            IconButton(onClick = { onPlayAll(name) }) {
                                Icon(Icons.Default.PlayCircleFilled, contentDescription = "Play Playlist", tint = ElectricCyan, modifier = Modifier.size(30.dp))
                            }
                        }
                        if (name != "Favorites") {
                            IconButton(onClick = { onDelete(name) }) {
                                Icon(Icons.Default.DeleteOutline, contentDescription = "Delete", tint = Color(0xFFFF5286))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(
    songCount: Int,
    outputs: List<AudioOutput>,
    timerMinutes: Int,
    timerLeft: Int,
    customTimer: String,
    isScanningDuplicates: Boolean,
    onCustomTimerChange: (String) -> Unit,
    onSetTimer: (Int) -> Unit,
    onCancelTimer: () -> Unit,
    onRescan: () -> Unit,
    onAutoFixAll: () -> Unit,
    onScanDuplicates: () -> Unit,
    onLaunchDualAudio: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.verticalScroll(rememberScrollState()).padding(vertical = 10.dp)) {
        Text("SETTINGS & AUDIO SUITE", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(14.dp))
        Surface(
            color = GlassPill,
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.2.dp, ElectricCyan.copy(alpha = 0.8f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Headphones, null, tint = ElectricCyan, modifier = Modifier.size(26.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Dual Audio Share (Party Mode)", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text("Stream synchronized audio to 2 Bluetooth headphones", color = MutedLavender, fontSize = 11.sp)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = onLaunchDualAudio,
                    colors = ButtonDefaults.buttonColors(containerColor = DeepViolet),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.BluetoothAudio, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Launch Dual Audio Router")
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Text("ACTIVE OUTPUT ROUTING", color = ElectricCyan, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Spacer(Modifier.height(6.dp))
        outputs.forEach { output ->
            Surface(
                color = GlassPill,
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.2.dp, GlassBorder),
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            ) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        when (output.kind.label) {
                            "Bluetooth" -> Icons.Default.Bluetooth
                            "USB audio" -> Icons.Default.Usb
                            "Wired headphones" -> Icons.Default.Headphones
                            else -> Icons.Default.Speaker
                        },
                        null,
                        tint = ElectricCyan
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(output.kind.label, color = Color.White, fontWeight = FontWeight.SemiBold)
                        Text(output.name, color = MutedLavender, fontSize = 12.sp)
                    }
                }
            }
        }
        if (outputs.isEmpty()) Text("No output endpoints detected.", color = MutedLavender, fontSize = 12.sp)
        Spacer(Modifier.height(16.dp))
        Text("SMART NOTIFICATION AUTO-FADE", color = NeonPurple, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Text("Smoothly ducks playback volume down to 18% during incoming notifications and glides back to 100%.", color = MutedLavender, fontSize = 12.sp)
        Spacer(Modifier.height(16.dp))
        Text("SLEEP TIMER", color = NeonPurple, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Spacer(Modifier.height(6.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(5, 10, 15, 30, 45, 60).forEach { mins ->
                FilterChip(
                    selected = timerMinutes == mins,
                    onClick = { onSetTimer(mins) },
                    label = { Text("${mins}m") }
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
            OutlinedTextField(
                value = customTimer,
                onValueChange = onCustomTimerChange,
                label = { Text("Custom min", color = MutedLavender) },
                singleLine = true,
                modifier = Modifier.width(130.dp),
                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White)
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = { onSetTimer(customTimer.toIntOrNull()?.coerceIn(1, 720) ?: 25) },
                colors = ButtonDefaults.buttonColors(containerColor = NeonPurple)
            ) {
                Text("Start")
            }
        }
        if (timerLeft > 0) {
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Remaining: %02d:%02d".format(timerLeft / 60, timerLeft % 60), color = ElectricCyan, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(10.dp))
                TextButton(onClick = onCancelTimer) { Text("Cancel", color = Color(0xFFFF5286)) }
            }
        }
        Spacer(Modifier.height(16.dp))
        Text("LIBRARY & STORAGE SUITE", color = ElectricCyan, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Spacer(Modifier.height(6.dp))
        Text("$songCount tracks indexed from local storage.", color = MutedLavender, fontSize = 12.sp)
        Spacer(Modifier.height(10.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onRescan,
                colors = ButtonDefaults.buttonColors(containerColor = GlassPill),
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.Refresh, null, tint = ElectricCyan, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Rescan", color = ElectricCyan, fontSize = 12.sp, maxLines = 1)
            }
            Button(
                onClick = onAutoFixAll,
                colors = ButtonDefaults.buttonColors(containerColor = DeepViolet),
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.AutoFixHigh, null, tint = ElectricCyan, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Auto-Fix All", color = Color.White, fontSize = 12.sp, maxLines = 1)
            }
        }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = onScanDuplicates,
            enabled = !isScanningDuplicates,
            colors = ButtonDefaults.buttonColors(containerColor = GlassPill),
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.CleaningServices, null, tint = ElectricCyan, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                if (isScanningDuplicates) "Scanning..." else "Clean Duplicates & Low Bitrate Files",
                color = ElectricCyan,
                fontSize = 12.sp
            )
        }
        Spacer(Modifier.height(20.dp))
        Text("SONIQ v0.9.8 • Studio Suite", color = MutedLavender.copy(alpha = 0.5f), fontSize = 11.sp)
    }
}
