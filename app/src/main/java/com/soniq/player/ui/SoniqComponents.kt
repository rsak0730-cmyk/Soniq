@file:OptIn(
    androidx.compose.animation.ExperimentalAnimationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class
)
package com.soniq.player.ui

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.soniq.player.audio.RealtimeFftMonitor
import com.soniq.player.data.LyricLine
import com.soniq.player.data.MusicRepository
import com.soniq.player.data.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

val OLEDBlack = Color(0xFF040308)
val NeonPurple = Color(0xFF9B6CFF)
val DeepViolet = Color(0xFF6B2BEE)
val ElectricCyan = Color(0xFF53F6FF)
val AquaGlow = Color(0xFF26E0FA)
val MutedLavender = Color(0xFFC7BCE0)
val GlassPill = Color(0x351F143D)
val GlassBorder = Color(0x5553F6FF)

// Deep Ocean Lock-Screen Color Palette (#18)
val DeepOceanAbyss = Color(0xFF030A16)
val OceanNavyDeep = Color(0xFF081C36)
val BioluminescentCyan = Color(0xFF38E1FF)
val OceanMistGlass = Color(0x280B2B4D)
val OceanGlassBorder = Color(0x4438E1FF)

fun timeText(ms: Long): String {
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
fun SongThumb(song: Song, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var embeddedBitmap by remember(song.id, song.albumArtUri) { mutableStateOf<ImageBitmap?>(null) }

    val validFileOverride = remember(song.albumArtUri) {
        if (!song.albumArtUri.isNullOrBlank() && song.albumArtUri.startsWith("file://")) {
            val path = Uri.parse(song.albumArtUri).path
            path != null && File(path).let { it.exists() && it.length() > 512 }
        } else false
    }

    val isHttpOverride = remember(song.albumArtUri) {
        !song.albumArtUri.isNullOrBlank() && song.albumArtUri.startsWith("http")
    }

    LaunchedEffect(song.id, song.albumArtUri) {
        if (!validFileOverride && !isHttpOverride) {
            withContext(Dispatchers.IO) {
                val bytes = MusicRepository.extractEmbeddedPicture(context, song.uri)
                if (bytes != null) {
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bmp != null) {
                        embeddedBitmap = bmp.asImageBitmap()
                    }
                }
            }
        }
    }

    val baseModifier = modifier.aspectRatio(1f)

    if ((validFileOverride || isHttpOverride) && !song.albumArtUri.isNullOrBlank()) {
        AsyncImage(
            model = ImageRequest.Builder(context).data(song.albumArtUri).crossfade(true).build(),
            contentDescription = "Artwork",
            contentScale = ContentScale.Crop,
            modifier = baseModifier.clip(RoundedCornerShape(16.dp))
        )
    } else if (embeddedBitmap != null) {
        Image(
            bitmap = embeddedBitmap!!,
            contentDescription = "Artwork",
            contentScale = ContentScale.Crop,
            modifier = baseModifier.clip(RoundedCornerShape(16.dp))
        )
    } else {
        Box(
            modifier = baseModifier
                .background(
                    Brush.radialGradient(listOf(Color(0xFF281850), Color(0xFF0F0922))),
                    RoundedCornerShape(16.dp)
                )
                .border(1.dp, GlassBorder, RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.MusicNote, null, tint = ElectricCyan.copy(alpha = 0.85f), modifier = Modifier.size(36.dp))
        }
    }
}

@Composable
fun SongCard(
    song: Song,
    favorite: Boolean,
    onPlay: () -> Unit,
    onFavorite: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onAddToAlbum: () -> Unit,
    onEdit: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    BouncyButton(onClick = onPlay, modifier = Modifier.fillMaxWidth()) {
        Surface(
            color = GlassPill,
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.2.dp, GlassBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                SongThumb(song, Modifier.size(48.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        song.title,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "${song.artist} •${song.album}",
                        color = MutedLavender,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "${song.folder} •${timeText(song.durationMs)}",
                        color = ElectricCyan.copy(alpha = 0.8f),
                        fontSize = 10.sp
                    )
                }
                IconButton(onClick = onFavorite, modifier = Modifier.size(34.dp)) {
                    Icon(
                        if (favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = "Favorite",
                        tint = if (favorite) Color(0xFFFF5286) else MutedLavender,
                        modifier = Modifier.size(19.dp)
                    )
                }
                IconButton(onClick = onEdit, modifier = Modifier.size(34.dp)) {
                    Icon(Icons.Default.AutoFixHigh, "Auto-Fix", tint = ElectricCyan, modifier = Modifier.size(19.dp))
                }
                Box {
                    IconButton(onClick = { menuExpanded = true }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More", tint = MutedLavender, modifier = Modifier.size(19.dp))
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                        modifier = Modifier.background(Color(0xFF1E1436))
                    ) {
                        DropdownMenuItem(
                            text = { Text("Add to Playlist", color = Color.White) },
                            leadingIcon = { Icon(Icons.Default.PlaylistAdd, null, tint = ElectricCyan) },
                            onClick = { menuExpanded = false; onAddToPlaylist() }
                        )
                        DropdownMenuItem(
                            text = { Text("Add to Custom Album", color = Color.White) },
                            leadingIcon = { Icon(Icons.Default.Album, null, tint = NeonPurple) },
                            onClick = { menuExpanded = false; onAddToAlbum() }
                        )
                        DropdownMenuItem(
                            text = { Text("Edit Metadata / Auto-Fix", color = Color.White) },
                            leadingIcon = { Icon(Icons.Default.AutoFixHigh, null, tint = ElectricCyan) },
                            onClick = { menuExpanded = false; onEdit() }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun GroupCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    representativeSong: Song?,
    isCustom: Boolean,
    onClick: () -> Unit
) {
    BouncyButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Surface(
            color = GlassPill,
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.2.dp, if (isCustom) ElectricCyan.copy(alpha = 0.7f) else GlassBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (representativeSong != null) {
                    SongThumb(song = representativeSong, modifier = Modifier.size(48.dp))
                } else {
                    Box(
                        Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Brush.linearGradient(listOf(NeonPurple.copy(alpha = 0.5f), ElectricCyan.copy(alpha = 0.5f)))),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(icon, null, tint = Color.White)
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (isCustom) {
                            Spacer(Modifier.width(6.dp))
                            Surface(shape = RoundedCornerShape(6.dp), color = NeonPurple.copy(alpha = 0.4f)) {
                                Text("CUSTOM", color = ElectricCyan, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
                            }
                        }
                    }
                    Text(subtitle, color = MutedLavender, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Icon(Icons.Default.ChevronRight, null, tint = ElectricCyan)
            }
        }
    }
}

@Composable
fun SamsungAlphabetScroller(
    songs: List<Song>,
    listState: LazyListState,
    modifier: Modifier = Modifier
) {
    val alphabet = remember { ('A'..'Z').toList() }
    val scope = rememberCoroutineScope()
    var activeLetter by remember { mutableStateOf<Char?>(null) }
    var railHeightPx by remember { mutableFloatStateOf(1f) }

    fun scrollToLetter(letter: Char) {
        activeLetter = letter
        val targetIndex = songs.indexOfFirst { it.title.trim().firstOrNull()?.uppercaseChar() == letter }
        if (targetIndex >= 0) {
            scope.launch { listState.scrollToItem(targetIndex) }
        }
    }

    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(24.dp)
            .onGloballyPositioned { coordinates -> railHeightPx = coordinates.size.height.toFloat() }
            .pointerInput(alphabet, songs) {
                detectTapGestures(
                    onPress = { offset ->
                        val itemHeight = size.height.toFloat() / alphabet.size.coerceAtLeast(1)
                        val index = (offset.y / itemHeight).toInt().coerceIn(0, alphabet.lastIndex)
                        scrollToLetter(alphabet[index])
                        tryAwaitRelease()
                        activeLetter = null
                    }
                )
            }
            .pointerInput(alphabet, songs) {
                detectDragGestures(
                    onDragStart = { offset ->
                        val itemHeight = railHeightPx / alphabet.size.coerceAtLeast(1)
                        val index = (offset.y / itemHeight).toInt().coerceIn(0, alphabet.lastIndex)
                        scrollToLetter(alphabet[index])
                    },
                    onDragEnd = { activeLetter = null },
                    onDragCancel = { activeLetter = null },
                    onDrag = { change: PointerInputChange, _ ->
                        change.consume()
                        val itemHeight = railHeightPx / alphabet.size.coerceAtLeast(1)
                        val index = (change.position.y / itemHeight).toInt().coerceIn(0, alphabet.lastIndex)
                        scrollToLetter(alphabet[index])
                    }
                )
            },
        contentAlignment = Alignment.CenterEnd
    ) {
        Column(
            modifier = Modifier.fillMaxHeight(),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            alphabet.forEach { letter ->
                val isSelected = activeLetter == letter
                Text(
                    text = letter.toString(),
                    color = if (isSelected) ElectricCyan else MutedLavender.copy(alpha = 0.5f),
                    fontSize = 9.sp,
                    fontWeight = if (isSelected) FontWeight.Black else FontWeight.Normal
                )
            }
        }
        activeLetter?.let { letter ->
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = (-46).dp)
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Brush.radialGradient(listOf(ElectricCyan, NeonPurple)))
                    .border(1.5.dp, Color.White, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(text = letter.toString(), color = OLEDBlack, fontSize = 20.sp, fontWeight = FontWeight.Black)
            }
        }
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
            .height(64.dp)
            .clickable { onOpen() },
        shape = RoundedCornerShape(22.dp),
        color = Color(0xEE140E2A),
        border = BorderStroke(1.2.dp, secondaryColor.copy(alpha = 0.55f))
    ) {
        Column(Modifier.fillMaxSize()) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(2.5.dp),
                color = secondaryColor,
                trackColor = Color.Transparent
            )
            Row(
                Modifier.fillMaxSize().padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SongThumb(song = song, modifier = Modifier.size(44.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(song.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(song.artist, color = MutedLavender, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = onPlayPause) {
                    Icon(
                        if (isPlaying) Icons.Default.PauseCircleFilled else Icons.Default.PlayCircleFilled,
                        contentDescription = "Play/Pause",
                        tint = secondaryColor,
                        modifier = Modifier.size(34.dp)
                    )
                }
                IconButton(onClick = onNext) {
                    Icon(Icons.Default.SkipNext, contentDescription = "Next", tint = Color.White, modifier = Modifier.size(26.dp))
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
        modifier = modifier.fillMaxWidth().height(64.dp),
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
    }
}

/**
 * Premium 3D Rounded-Rectangle Centerpiece Artwork.
 * Includes subtle perspective shift, soft neon rim lighting, and responsive bottom equalizer bars.
 */
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
                rotationX = (gyroPitch * 10f).coerceIn(-12f, 12f)
                rotationY = (gyroRoll * 10f).coerceIn(-12f, 12f)
                cameraDistance = 18f * density
                shadowElevation = 24.dp.toPx()
                shape = RoundedCornerShape(32.dp)
                clip = false
            },
        contentAlignment = Alignment.Center
    ) {
        // Outer diffuse neon aura shadow
        Canvas(modifier = Modifier.fillMaxSize(1.06f)) {
            drawRoundRect(
                brush = Brush.radialGradient(
                    colors = listOf(
                        secondaryColor.copy(alpha = if (isPlaying) 0.32f else 0.16f),
                        primaryColor.copy(alpha = if (isPlaying) 0.18f else 0.08f),
                        Color.Transparent
                    ),
                    center = center,
                    radius = size.minDimension / 1.7f
                ),
                size = size,
                cornerRadius = CornerRadius(36.dp.toPx(), 36.dp.toPx())
            )
        }

        // Base Album Artwork clipped to modern 3D rounded rectangle
        SongThumb(
            song = song,
            modifier = Modifier
                .fillMaxSize(0.92f)
                .clip(RoundedCornerShape(32.dp))
        )

        // Overlay with gradient glass shade & subtle beat-reactive timeline bars
        Canvas(modifier = Modifier.fillMaxSize(0.92f)) {
            val w = size.width
            val h = size.height
            val corner = 32.dp.toPx()

            // Outer Neon Rim
            drawRoundRect(
                brush = Brush.sweepGradient(
                    listOf(
                        secondaryColor.copy(alpha = 0.90f),
                        primaryColor.copy(alpha = 0.90f),
                        secondaryColor.copy(alpha = 0.90f)
                    )
                ),
                size = size,
                cornerRadius = CornerRadius(corner, corner),
                style = Stroke(2.5.dp.toPx())
            )

            // Bottom glass scrim so the subtle bars stand out gracefully
            drawRoundRect(
                brush = Brush.verticalGradient(
                    0.58f to Color.Transparent,
                    1.0f to Color(0xDD0B071A)
                ),
                size = size,
                cornerRadius = CornerRadius(corner, corner)
            )

            // Live Spectrum Bars
            val barWidth = 4.dp.toPx()
            val totalBarsWidth = numBars * barWidth
            val spacing = (w * 0.78f - totalBarsWidth) / (numBars - 1).coerceAtLeast(1)
            val startX = (w * 0.11f)
            val bottomY = h - 18.dp.toPx()

            for (i in 0 until numBars) {
                val amp = if (isPlaying) amplitudes[i % amplitudes.size] else 0.08f
                val barHeight = (44.dp.toPx() * amp).coerceIn(4.dp.toPx(), 40.dp.toPx())
                val bx = startX + i * (barWidth + spacing)

                drawRoundRect(
                    brush = Brush.verticalGradient(listOf(secondaryColor, primaryColor)),
                    topLeft = Offset(bx, bottomY - barHeight),
                    size = Size(barWidth, barHeight),
                    cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
                )
            }
        }
    }
}

/**
 * Animated Waveform Timeline with subtle sinusoidal wavy line and illuminated neon glowing scrubber head.
 * Fully interactive for seeking with zero drift or lag.
 */
@Composable
fun WaveformSeekScrubber(
    position: Long,
    duration: Long,
    isPlaying: Boolean,
    primaryColor: Color,
    secondaryColor: Color,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val progress = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f

    // Soft continuous phase movement along the line (paused gracefully when playback stops)
    val infiniteTransition = rememberInfiniteTransition(label = "wave_timeline")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 2f * PI.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (isPlaying) 2800 else 9000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )

    // Slight micro-breathing glow
    val glowPulse by infiniteTransition.animateFloat(
        initialValue = 0.82f,
        targetValue = 1.18f,
        animationSpec = infiniteRepeatable(
            animation = tween(1600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow_pulse"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
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
            val w = size.width
            val h = size.height
            val midY = h / 2f
            val activeX = w * progress

            // 1. Inactive Background Track (Soft translucent rounded line)
            drawLine(
                color = Color(0x352B194E),
                start = Offset(activeX, midY),
                end = Offset(w, midY),
                strokeWidth = 3.5.dp.toPx(),
                cap = StrokeCap.Round
            )

            // 2. Active Waveform Line (Subtle organic sinusoidal wavy movement along the progress path)
            if (activeX > 0f) {
                val wavePath = Path()
                val steps = (activeX / 3.dp.toPx()).toInt().coerceAtLeast(10)
                val waveAmplitude = if (isPlaying) 3.5.dp.toPx() else 1.2.dp.toPx()
                val waveFrequency = 0.045

                for (i in 0..steps) {
                    val px = (i.toFloat() / steps) * activeX
                    // Attenuate wave amplitude near zero and near the thumb for natural tapered flow
                    val taper = sin((i.toFloat() / steps) * PI.toFloat()).coerceIn(0f, 1f)
                    val py = midY + sin((px * waveFrequency + phase).toDouble()).toFloat() * (waveAmplitude * taper)

                    if (i == 0) wavePath.moveTo(px, py) else wavePath.lineTo(px, py)
                }

                // Diffuse ambient glow along active waveform
                drawPath(
                    path = wavePath,
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            primaryColor.copy(alpha = 0.35f * glowPulse),
                            secondaryColor.copy(alpha = 0.55f * glowPulse)
                        )
                    ),
                    style = Stroke(width = 8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                )

                // Crisp neon foreground wave line
                drawPath(
                    path = wavePath,
                    brush = Brush.horizontalGradient(
                        colors = listOf(primaryColor, secondaryColor)
                    ),
                    style = Stroke(width = 3.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                )

                // 3. Illuminated Scrubber Head (Neon floating orb with multi-layer radial aura)
                val headWaveY = midY + sin((activeX * waveFrequency + phase).toDouble()).toFloat() * 1.5.dp.toPx()
                val headCenter = Offset(activeX, headWaveY)

                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            secondaryColor.copy(alpha = 0.60f * glowPulse),
                            primaryColor.copy(alpha = 0.20f),
                            Color.Transparent
                        ),
                        center = headCenter,
                        radius = 16.dp.toPx()
                    ),
                    radius = 16.dp.toPx(),
                    center = headCenter
                )

                drawCircle(
                    color = Color.White,
                    radius = 5.dp.toPx(),
                    center = headCenter
                )
                drawCircle(
                    color = secondaryColor,
                    radius = 3.dp.toPx(),
                    center = headCenter
                )
            }
        }
    }
}

/**
 * Word-by-word Live Karaoke Sync with illuminating neon cyan glow.
 */
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

/**
 * Deep Ocean Lock Screen View (#18).
 * Oceanic bioluminescence with deep navy glassmorphism and fully functional playback controls.
 */
@Composable
fun DeepOceanLockScreenView(
    song: Song,
    isPlaying: Boolean,
    position: Long,
    duration: Long,
    shuffle: Boolean,
    repeatMode: Int,
    gyroPitch: Float,
    gyroRoll: Float,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
    onShuffle: () -> Unit,
    onRepeat: () -> Unit,
    onCloseLockScreen: () -> Unit,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "ocean_underwater")
    val waveT1 by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 2f * PI.toFloat(),
        animationSpec = infiniteRepeatable(tween(8000, easing = LinearEasing), RepeatMode.Restart),
        label = "ocean_wave1"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(DeepOceanAbyss)
    ) {
        // Deep Ocean Animated Gradient & Caustics Canvas
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height

            // Base ocean depth gradient
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(DeepOceanAbyss, OceanNavyDeep, DeepOceanAbyss)
                )
            )

            // Bioluminescent oceanic bloom
            val bloomX = w * (0.5f + 0.12f * sin(waveT1.toDouble()).toFloat()) + (gyroRoll * 30f)
            val bloomY = h * (0.35f + 0.08f * cos(waveT1.toDouble()).toFloat()) + (gyroPitch * 30f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        BioluminescentCyan.copy(alpha = if (isPlaying) 0.32f else 0.18f),
                        OceanNavyDeep.copy(alpha = 0.25f),
                        Color.Transparent
                    ),
                    center = Offset(bloomX, bloomY),
                    radius = w * 0.75f
                ),
                radius = w * 0.75f,
                center = Offset(bloomX, bloomY)
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Lock Screen Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = OceanMistGlass,
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(1.dp, OceanGlassBorder)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Lock, null, tint = BioluminescentCyan, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("DEEP OCEAN #18", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    }
                }

                BouncyIconButton(onClick = onCloseLockScreen, size = 36.dp) {
                    Icon(Icons.Default.LockOpen, "Unlock", tint = BioluminescentCyan, modifier = Modifier.size(20.dp))
                }
            }

            Spacer(Modifier.weight(0.6f))

            // Oceanic Floating 3D Artwork
            RoundedRectTimelineCenterpiece(
                song = song,
                isPlaying = isPlaying,
                primaryColor = OceanNavyDeep,
                secondaryColor = BioluminescentCyan,
                gyroPitch = gyroPitch,
                gyroRoll = gyroRoll,
                modifier = Modifier.size(240.dp)
            )

            Spacer(Modifier.height(24.dp))

            // Song Typography
            Text(
                song.title,
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Black,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "${song.artist} • ${song.album}",
                color = MutedLavender.copy(alpha = 0.85f),
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.weight(0.8f))

            // Wavy Oceanic Timeline
            WaveformSeekScrubber(
                position = position,
                duration = duration,
                isPlaying = isPlaying,
                primaryColor = OceanNavyDeep,
                secondaryColor = BioluminescentCyan,
                onSeek = onSeek
            )

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(timeText(position), color = MutedLavender, fontSize = 11.sp)
                Text(timeText(duration), color = MutedLavender, fontSize = 11.sp)
            }

            Spacer(Modifier.height(16.dp))

            // Ocean Glassmorphic Playback Controls
            Surface(
                color = OceanMistGlass,
                shape = RoundedCornerShape(32.dp),
                border = BorderStroke(1.2.dp, OceanGlassBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BouncyIconButton(onClick = onShuffle) {
                        Icon(Icons.Default.Shuffle, null, tint = if (shuffle) BioluminescentCyan else MutedLavender)
                    }
                    BouncyIconButton(onClick = onPrevious) {
                        Icon(Icons.Default.SkipPrevious, null, tint = Color.White, modifier = Modifier.size(34.dp))
                    }
                    BouncyButton(onClick = onPlayPause, modifier = Modifier.size(62.dp)) {
                        Surface(
                            shape = CircleShape,
                            color = BioluminescentCyan,
                            border = BorderStroke(2.dp, Color.White),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    null,
                                    tint = DeepOceanAbyss,
                                    modifier = Modifier.size(34.dp)
                                )
                            }
                        }
                    }
                    BouncyIconButton(onClick = onNext) {
                        Icon(Icons.Default.SkipNext, null, tint = Color.White, modifier = Modifier.size(34.dp))
                    }
                    BouncyIconButton(onClick = onRepeat) {
                        Icon(
                            if (repeatMode == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                            null,
                            tint = if (repeatMode != Player.REPEAT_MODE_OFF) BioluminescentCyan else MutedLavender
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
        }
    }
}
