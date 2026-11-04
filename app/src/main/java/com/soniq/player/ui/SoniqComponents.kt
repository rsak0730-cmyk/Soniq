@file:OptIn(
    androidx.compose.animation.ExperimentalAnimationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class
)
package com.soniq.player.ui

import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
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
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.media3.common.Player
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.soniq.player.audio.AudioFingerprintFixer
import com.soniq.player.audio.RealtimeFftMonitor
import com.soniq.player.data.LyricLine
import com.soniq.player.data.LyricsEngine
import com.soniq.player.data.MusicRepository
import com.soniq.player.data.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

internal val OLEDBlack = Color(0xFF06040A)
internal val NeonPurple = Color(0xFF9B6CFF)
internal val DeepViolet = Color(0xFF6B2BEE)
internal val ElectricCyan = Color(0xFF53F6FF)
internal val AquaGlow = Color(0xFF26E0FA)
internal val MutedLavender = Color(0xFFC7BCE0)
internal val GlassPill = Color(0x351F143D)
internal val GlassBorder = Color(0x5553F6FF)

internal fun timeText(ms: Long): String {
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
                .background(Brush.radialGradient(listOf(Color(0xFF281850), Color(0xFF0F0922))), RoundedCornerShape(16.dp))
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
                    Text(song.title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${song.artist} • ${song.album}", color = MutedLavender, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${song.folder} • ${timeText(song.durationMs)}", color = ElectricCyan.copy(alpha = 0.8f), fontSize = 10.sp)
                }
                IconButton(onClick = onFavorite, modifier = Modifier.size(34.dp)) {
                    Icon(
                        if (favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        null,
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
            .height(62.dp)
            .clickable { onOpen() },
        shape = RoundedCornerShape(22.dp),
        color = Color(0xEE160E2E),
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
                SongThumb(song = song, modifier = Modifier.size(42.dp))
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
                        modifier = Modifier.size(32.dp)
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
                    brush = Brush.verticalGradient(listOf(secondaryColor, primaryColor)),
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
fun TagEditorAndFingerprintDialog(
    song: Song,
    onDismiss: () -> Unit,
    onSave: (String, String, String, String?) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf(song.title) }
    var artist by remember { mutableStateOf(song.artist) }
    var album by remember { mutableStateOf(song.album) }
    var onlineCoverUri by remember { mutableStateOf<String?>(null) }
    var isFingerprinting by remember { mutableStateOf(false) }
    var matchConfidence by remember { mutableFloatStateOf(0f) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1E1436),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.AutoFixHigh, null, tint = ElectricCyan)
                Spacer(Modifier.width(8.dp))
                Text("Fingerprint & Metadata Fixer", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Track Title", color = MutedLavender) },
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White)
                )
                OutlinedTextField(
                    value = artist,
                    onValueChange = { artist = it },
                    label = { Text("Artist", color = MutedLavender) },
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White)
                )
                OutlinedTextField(
                    value = album,
                    onValueChange = { album = it },
                    label = { Text("Album", color = MutedLavender) },
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White)
                )
                Button(
                    onClick = {
                        scope.launch {
                            isFingerprinting = true
                            val fix = AudioFingerprintFixer.autoFixSong(context, song)
                            if (fix.matchConfidence >= 0.60f) {
                                title = fix.title
                                artist = fix.artist
                                album = fix.album
                                matchConfidence = fix.matchConfidence
                                onlineCoverUri = fix.albumArtUrl
                            } else {
                                Toast.makeText(context, "No high-confidence match found for this song.", Toast.LENGTH_SHORT).show()
                            }
                            isFingerprinting = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = DeepViolet),
                    enabled = !isFingerprinting,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.GraphicEq, null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (isFingerprinting) "Analyzing Catalog Match..." else "Magic Audio Fingerprint Fix")
                }
                if (matchConfidence > 0f) {
                    Text("• Confidence Match: ${(matchConfidence * 100).toInt()}% • Verified", color = ElectricCyan, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(title.trim(), artist.trim(), album.trim(), onlineCoverUri) }) {
                Text("Apply & Save", color = ElectricCyan, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = MutedLavender) }
        }
    )
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
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
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
                    Modifier.weight(1f).fillMaxWidth(),
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
                BouncyButton(onClick = onPlayPause, modifier = Modifier.size(66.dp)) {
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
