@file:OptIn(
    androidx.compose.animation.ExperimentalAnimationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class
)
package com.soniq.player.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.Settings
import android.view.HapticFeedbackConstants
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.palette.graphics.Palette
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.google.common.util.concurrent.MoreExecutors
import com.soniq.player.audio.AudioDeviceMonitor
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
    STATS("Stats", Icons.Default.BarChart),
    SETTINGS("Settings", Icons.Default.Settings)
}

@Composable
fun SoniqApp() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("soniq_user_data", 0) }
    var songs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var currentTab by remember { mutableStateOf(MainTab.LIBRARY) }
    var controller by remember { mutableStateOf<MediaController?>(null) }
    var currentSong by remember { mutableStateOf<Song?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var shuffle by remember { mutableStateOf(false) }
    var repeatMode by remember { mutableIntStateOf(Player.REPEAT_MODE_OFF) }
    var showPlayer by remember { mutableStateOf(false) }
    var dynamicPrimaryColor by remember { mutableStateOf(NeonPurple) }
    var dynamicSecondaryColor by remember { mutableStateOf(ElectricCyan) }
    var outputs by remember { mutableStateOf<List<AudioOutput>>(emptyList()) }
    var favorites by remember { mutableStateOf<Set<String>>(prefs.getStringSet("favorites", emptySet())?.toSet() ?: emptySet()) }
    var recents by remember { mutableStateOf<Set<String>>(prefs.getStringSet("recents", emptySet())?.toSet() ?: emptySet()) }
    var searchHistory by remember { mutableStateOf<List<String>>(prefs.getStringSet("search_history", emptySet())?.toList()?.take(8) ?: emptyList()) }
    var customAlbums by remember { mutableStateOf<List<String>>(prefs.getStringSet("custom_albums", emptySet())?.toList()?.sorted() ?: emptyList()) }
    var showCreateAlbum by remember { mutableStateOf(false) }
    var newAlbumName by remember { mutableStateOf("") }
    var addToAlbumSong by remember { mutableStateOf<Song?>(null) }
    var timerMinutes by remember { mutableIntStateOf(0) }
    var customTimer by remember { mutableStateOf("25") }
    var timerLeft by remember { mutableIntStateOf(0) }
    var editTagSong by remember { mutableStateOf<Song?>(null) }
    var libraryMode by remember { mutableStateOf("Songs") }
    val listState = rememberLazyListState()
    var selectedFilterKey by remember { mutableStateOf<String?>(null) }
    var playerOffsetY by remember { mutableFloatStateOf(0f) }

    // Liquid Gyroscope Parallax Engine
    val gyroEngine = remember { GyroscopeParallaxEngine(context) }
    DisposableEffect(Unit) {
        gyroEngine.start()
        onDispose { gyroEngine.stop() }
    }

    BackHandler(enabled = showPlayer || selectedFilterKey != null) {
        if (showPlayer) showPlayer = false else selectedFilterKey = null
    }

    fun saveFavorites() { prefs.edit().putStringSet("favorites", favorites).apply() }
    fun saveRecents() { prefs.edit().putStringSet("recents", recents).apply() }

    fun playSong(song: Song, source: List<Song> = songs) {
        val c = controller ?: return
        val queue = source.ifEmpty { songs }.ifEmpty { listOf(song) }
        val index = queue.indexOfFirst { it.id == song.id }.coerceAtLeast(0)
        c.setMediaItems(queue.map { s ->
            MediaItem.Builder().setUri(s.uri).setMediaMetadata(
                MediaMetadata.Builder().setTitle(s.title).setArtist(s.artist)
                    .setAlbumTitle(s.album).setArtworkUri(s.albumArtUri?.let(Uri::parse)).build()
            ).build()
        }, index, 0L)
        c.prepare()
        c.play()
        currentSong = song
        isPlaying = true
        duration = song.durationMs
        position = 0L

        // Record play event into Sonic Stats Heatmap
        SonicStatsEngine.recordPlayEvent(song)

        recents = (recents + song.id.toString()).toList().takeLast(30).toSet()
        saveRecents()
        showPlayer = true
    }

    fun launchPartyMode() {
        // Launches Bluetooth Audio Sharing / Samsung Dual Audio
        try {
            val intent = Intent("com.samsung.android.settings.BLUETOOTH_CASTING_SETTINGS").apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            val fallback = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(fallback)
        }
    }

    LaunchedEffect(currentSong?.id) {
        val song = currentSong ?: return@LaunchedEffect
        withContext(Dispatchers.IO) {
            val bytes = MusicRepository.extractEmbeddedPicture(context, song.uri)
            val bmp: Bitmap? = if (bytes != null) {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } else null
            if (bmp != null) {
                Palette.from(bmp).generate().let { palette ->
                    val dom = palette.getVibrantColor(palette.getDominantColor(NeonPurple.toArgb()))
                    val sec = palette.getLightVibrantColor(palette.getMutedColor(ElectricCyan.toArgb()))
                    dynamicPrimaryColor = Color(dom)
                    dynamicSecondaryColor = Color(sec)
                }
            } else {
                dynamicPrimaryColor = NeonPurple
                dynamicSecondaryColor = ElectricCyan
            }
        }
    }

    LaunchedEffect(Unit) {
        songs = runCatching { MusicRepository(context).loadSongs() }.getOrDefault(emptyList())
    }

    DisposableEffect(context) {
        val monitor = AudioDeviceMonitor(context) { outputs = it }
        monitor.start()
        onDispose { monitor.stop() }
    }

    DisposableEffect(context) {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            controller = runCatching { future.get() }.getOrNull()?.also { c ->
                c.addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(value: Boolean) { isPlaying = value }
                    override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                        val title = item?.mediaMetadata?.title?.toString()
                        currentSong = songs.firstOrNull { it.title == title } ?: currentSong
                    }
                    override fun onPlaybackStateChanged(state: Int) {
                        duration = c.duration.coerceAtLeast(0L)
                    }
                })
            }
        }, MoreExecutors.directExecutor())
        onDispose { MediaController.releaseFuture(future) }
    }

    LaunchedEffect(controller) {
        while (true) {
            controller?.let { c ->
                position = c.currentPosition.coerceAtLeast(0L)
                duration = c.duration.coerceAtLeast(0L)
                isPlaying = c.isPlaying
                if (c.currentMediaItem != null && currentSong == null) {
                    val title = c.currentMediaItem?.mediaMetadata?.title?.toString()
                    currentSong = songs.firstOrNull { it.title == title }
                }
            }
            delay(150)
        }
    }

    LaunchedEffect(timerMinutes) {
        timerLeft = timerMinutes * 60
        while (timerLeft > 0 && timerMinutes > 0) {
            delay(1000)
            timerLeft--
        }
        if (timerMinutes > 0 && timerLeft == 0) {
            controller?.pause()
            isPlaying = false
            timerMinutes = 0
        }
    }

    // Bubbly Ripple Touch Physics (Water Droplet Splashes)
    FluidTouchCanvas(rippleColor = dynamicSecondaryColor) {
        Box(Modifier.fillMaxSize()) {
            LiquidWaterWaveBackground(
                isPlaying = isPlaying,
                primaryColor = dynamicPrimaryColor,
                secondaryColor = dynamicSecondaryColor,
                pitch = gyroEngine.pitch,
                roll = gyroEngine.roll
            )

            Column(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .padding(bottom = 86.dp)
                    .padding(horizontal = 16.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "SONIQ",
                            color = Color.White,
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.2.sp
                        )
                        Text("Liquid Gyro Acoustics • Party Sync", color = MutedLavender, fontSize = 11.sp)
                    }

                    // Dual Audio Party Mode Quick Toggle
                    BouncyIconButton(onClick = { launchPartyMode() }) {
                        Icon(Icons.Default.Headphones, contentDescription = "Dual Headphone Party Share", tint = dynamicSecondaryColor)
                    }

                    if (currentSong != null) {
                        BouncyIconButton(onClick = { showPlayer = true }) {
                            Icon(Icons.Default.GraphicEq, contentDescription = "Now Playing", tint = dynamicSecondaryColor)
                        }
                    }
                }

                // Frosted Glass Search Bar
                OutlinedTextField(
                    value = query,
                    onValueChange = {
                        query = it
                        if (it.length >= 2) {
                            searchHistory = (listOf(it) + searchHistory.filterNot { old -> old == it }).take(8)
                            prefs.edit().putStringSet("search_history", searchHistory.toSet()).apply()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(
                            "Search songs, artists, albums, folders...",
                            color = MutedLavender.copy(alpha = 0.6f),
                            fontSize = 13.sp
                        )
                    },
                    leadingIcon = { Icon(Icons.Default.Search, null, tint = dynamicSecondaryColor) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, "Clear", tint = MutedLavender) }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(26.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = dynamicSecondaryColor,
                        unfocusedBorderColor = GlassBorder,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        cursorColor = dynamicSecondaryColor,
                        focusedContainerColor = Color(0x6618102C),
                        unfocusedContainerColor = Color(0x44140D26)
                    )
                )

                Spacer(Modifier.height(10.dp))

                AnimatedContent(
                    targetState = currentTab,
                    transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(200)) },
                    label = "screen_transition"
                ) { target ->
                    when (target) {
                        MainTab.HOME -> HomeScreen(
                            songs = songs,
                            recents = recents,
                            favorites = favorites,
                            onPlay = { playSong(it, songs) },
                            onNavigateLibrary = { currentTab = MainTab.LIBRARY }
                        )
                        MainTab.LIBRARY -> LibraryScreen(
                            allSongs = songs,
                            mode = libraryMode,
                            onMode = {
                                libraryMode = it
                                selectedFilterKey = null
                            },
                            filterKey = selectedFilterKey,
                            onFilterKey = { selectedFilterKey = it },
                            query = query,
                            listState = listState,
                            favorites = favorites,
                            recents = recents,
                            customAlbums = customAlbums,
                            getCustomAlbumSongs = { albumName ->
                                val raw = prefs.getString("album_$albumName", "") ?: ""
                                raw.split("|").filter { it.isNotBlank() }.toSet()
                            },
                            onCreateAlbum = { showCreateAlbum = true },
                            onPlay = { song, list -> playSong(song, list) },
                            onFavorite = { song ->
                                favorites = if (song.id.toString() in favorites) favorites - song.id.toString()
                                else favorites + song.id.toString()
                                saveFavorites()
                            },
                            onAddToAlbum = { addToAlbumSong = it },
                            onEdit = { editTagSong = it },
                            modifier = Modifier.fillMaxSize()
                        )
                        MainTab.EQUALIZER -> EqualizerScreen(Modifier.fillMaxSize())
                        MainTab.STATS -> {
                            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                                SonicHeatmapView()
                                Spacer(Modifier.height(16.dp))
                            }
                        }
                        MainTab.SETTINGS -> SettingsScreen(
                            songCount = songs.size,
                            outputs = outputs,
                            timerMinutes = timerMinutes,
                            timerLeft = timerLeft,
                            customTimer = customTimer,
                            onCustomTimerChange = { customTimer = it },
                            onSetTimer = { timerMinutes = it },
                            onCancelTimer = { timerMinutes = 0 },
                            onRescan = { songs = MusicRepository(context).loadSongs() },
                            onLaunchDualAudio = { launchPartyMode() },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }

            // Bottom Navigation Bar
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(horizontal = 18.dp, vertical = 12.dp)
            ) {
                FloatingBottomNavBar(
                    currentTab = currentTab,
                    activeColor = dynamicSecondaryColor,
                    onSelect = { currentTab = it }
                )
            }

            // Full Screen Player with Liquid Gyroscope Parallax
            if (showPlayer && currentSong != null) {
                val animatedOffsetY by animateFloatAsState(
                    targetValue = playerOffsetY,
                    animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMedium),
                    label = "player_drag"
                )
                Box(
                    Modifier
                        .fillMaxSize()
                        .offset { IntOffset(0, animatedOffsetY.roundToInt()) }
                        .draggable(
                            orientation = Orientation.Vertical,
                            state = rememberDraggableState { delta ->
                                playerOffsetY = (playerOffsetY + delta).coerceAtLeast(0f)
                            },
                            onDragStopped = {
                                if (playerOffsetY > 380f) {
                                    showPlayer = false
                                }
                                playerOffsetY = 0f
                            }
                        )
                ) {
                    FullPlayer(
                        song = currentSong!!,
                        isPlaying = isPlaying,
                        position = position,
                        duration = duration,
                        shuffle = shuffle,
                        repeatMode = repeatMode,
                        primaryAccent = dynamicPrimaryColor,
                        secondaryAccent = dynamicSecondaryColor,
                        gyroPitch = gyroEngine.pitch,
                        gyroRoll = gyroEngine.roll,
                        onClose = { showPlayer = false },
                        onPlayPause = { controller?.let { if (it.isPlaying) it.pause() else it.play() } },
                        onNext = { controller?.seekToNextMediaItem() },
                        onPrevious = { controller?.seekToPreviousMediaItem() },
                        onSeek = { controller?.seekTo(it) },
                        onShuffle = { shuffle = !shuffle; controller?.shuffleModeEnabled = shuffle },
                        onRepeat = {
                            repeatMode = when (repeatMode) {
                                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                                else -> Player.REPEAT_MODE_OFF
                            }
                            controller?.repeatMode = repeatMode
                        },
                        onLaunchParty = { launchPartyMode() },
                        onQueue = { currentTab = MainTab.LIBRARY; showPlayer = false }
                    )
                }
            }

            // Create Album Dialog
            if (showCreateAlbum) {
                AlertDialog(
                    onDismissRequest = { showCreateAlbum = false },
                    containerColor = Color(0xFF1E1436),
                    title = { Text("Create New Album", color = Color.White) },
                    text = {
                        OutlinedTextField(
                            value = newAlbumName,
                            onValueChange = { newAlbumName = it },
                            label = { Text("Album Name", color = MutedLavender) },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = ElectricCyan
                            )
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            val name = newAlbumName.trim()
                            if (name.isNotEmpty()) {
                                customAlbums = (customAlbums + name).distinct().sorted()
                                prefs.edit().putStringSet("custom_albums", customAlbums.toSet()).apply()
                                prefs.edit().putString("album_$name", "").apply()
                            }
                            newAlbumName = ""
                            showCreateAlbum = false
                        }) { Text("Create", color = ElectricCyan) }
                    },
                    dismissButton = {
                        TextButton(onClick = { showCreateAlbum = false }) { Text("Cancel", color = MutedLavender) }
                    }
                )
            }

            addToAlbumSong?.let { song ->
                AlertDialog(
                    onDismissRequest = { addToAlbumSong = null },
                    containerColor = Color(0xFF1E1436),
                    title = { Text("Add Track to Album", color = Color.White) },
                    text = {
                        if (customAlbums.isEmpty()) {
                            Text("No custom albums created yet. Click '+ Album' in the Albums tab to create one.", color = MutedLavender)
                        } else {
                            Column {
                                for (albName in customAlbums) {
                                    TextButton(
                                        onClick = {
                                            val raw = prefs.getString("album_$albName", "") ?: ""
                                            val existing: Set<String> = raw.split("|").filter { it.isNotBlank() }.toSet()
                                            val updated: Set<String> = existing + song.id.toString()
                                            prefs.edit().putString("album_$albName", updated.joinToString("|")).apply()
                                            addToAlbumSong = null
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(albName, color = ElectricCyan)
                                    }
                                }
                            }
                        }
                    },
                    confirmButton = {},
                    dismissButton = {
                        TextButton(onClick = { addToAlbumSong = null }) { Text("Cancel", color = MutedLavender) }
                    }
                )
            }

            // Tag Editor & Audio Fingerprint Auto-Fixer Dialog
            editTagSong?.let { song ->
                TagEditorAndFingerprintDialog(
                    song = song,
                    onDismiss = { editTagSong = null },
                    onSave = { updatedTitle, updatedArtist, updatedAlbum, newArtUri ->
                        songs = songs.map {
                            if (it.id == song.id) it.copy(
                                title = updatedTitle,
                                artist = updatedArtist,
                                album = updatedAlbum,
                                albumArtUri = newArtUri ?: it.albumArtUri
                            ) else it
                        }
                        editTagSong = null
                    }
                )
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
    val fftBass = RealtimeFftMonitor.bassEnergy
    val t1 by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 2f * PI.toFloat(),
        animationSpec = infiniteRepeatable(tween(if (isPlaying) 5500 else 11000, easing = LinearEasing), RepeatMode.Restart),
        label = "wave1"
    )
    val t2 by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 2f * PI.toFloat(),
        animationSpec = infiniteRepeatable(tween(if (isPlaying) 7500 else 15000, easing = LinearEasing), RepeatMode.Restart),
        label = "wave2"
    )

    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        drawRect(OLEDBlack)
        val reactiveFactor = if (isPlaying) 1f + (fftBass * 0.45f) else 1f

        // Apply Gyroscope Parallax shift
        val gyroShiftX = roll * 45.dp.toPx()
        val gyroShiftY = pitch * 45.dp.toPx()

        val x1 = w * (0.65f + 0.18f * cos(t1.toDouble()).toFloat()) + gyroShiftX
        val y1 = h * (0.28f + 0.14f * sin(t2.toDouble()).toFloat()) + gyroShiftY
        val r1 = w * (if (isPlaying) 0.95f else 0.75f) * reactiveFactor

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    primaryColor.copy(alpha = if (isPlaying) (0.50f + fftBass * 0.15f).coerceAtMost(0.85f) else 0.28f),
                    DeepViolet.copy(alpha = if (isPlaying) 0.35f else 0.15f),
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
        val r2 = w * (if (isPlaying) 0.98f else 0.80f) * reactiveFactor

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    secondaryColor.copy(alpha = if (isPlaying) (0.40f + fftBass * 0.15f).coerceAtMost(0.75f) else 0.20f),
                    AquaGlow.copy(alpha = if (isPlaying) 0.22f else 0.08f),
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
            val waveHeight = (24.dp.toPx() * reactiveFactor)
            val baseline = h * 0.5f + (gyroShiftY * 0.5f)
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
                    colors = listOf(secondaryColor.copy(alpha = 0.08f), Color.Transparent)
                )
            )
        }
    }
}

@Composable
private fun LiquidOrbWaveformCenterpiece(
    song: Song,
    isPlaying: Boolean,
    primaryColor: Color,
    secondaryColor: Color,
    gyroPitch: Float,
    gyroRoll: Float,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "orb_fluid")
    val fftBass = RealtimeFftMonitor.bassEnergy
    val wavePhase by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 2f * PI.toFloat(),
        animationSpec = infiniteRepeatable(tween(if (isPlaying) 3000 else 8000, easing = LinearEasing), RepeatMode.Restart),
        label = "phase"
    )

    Box(
        modifier = modifier
            .graphicsLayer {
                rotationX = gyroPitch * 14f
                rotationY = gyroRoll * 14f
                cameraDistance = 16f * density
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val r = size.minDimension / 2f
            val c = center
            drawCircle(
                brush = Brush.sweepGradient(
                    listOf(secondaryColor.copy(alpha = 0.8f), primaryColor.copy(alpha = 0.8f), secondaryColor.copy(alpha = 0.8f))
                ),
                radius = r - 2.dp.toPx(),
                center = c,
                style = Stroke(3.dp.toPx())
            )

            val wavePath = Path()
            val amp = if (isPlaying) (16.dp.toPx() + fftBass * 18.dp.toPx()) else 6.dp.toPx()
            val waterLevel = c.y + (r * 0.15f) + (gyroPitch * 15f)
            wavePath.moveTo(c.x - r, waterLevel)
            var x = c.x - r
            while (x <= c.x + r) {
                val relX = (x - (c.x - r)) / (2f * r)
                val y = waterLevel + sin((relX * 2.5 * PI + wavePhase).toDouble()).toFloat() * amp
                wavePath.lineTo(x, y)
                x += 8f
            }
            wavePath.lineTo(c.x + r, c.y + r)
            wavePath.lineTo(c.x - r, c.y + r)
            wavePath.close()

            drawContext.canvas.save()
            val circleClip = Path().apply {
                addOval(androidx.compose.ui.geometry.Rect(c.x - r + 4f, c.y - r + 4f, c.x + r - 4f, c.y + r - 4f))
            }
            drawContext.canvas.clipPath(circleClip)
            drawRect(
                brush = Brush.verticalGradient(
                    listOf(Color(0x2253F6FF), Color(0x666B2BEE))
                )
            )
            drawPath(
                path = wavePath,
                brush = Brush.verticalGradient(
                    listOf(secondaryColor.copy(alpha = 0.65f), primaryColor.copy(alpha = 0.85f))
                )
            )
            drawContext.canvas.restore()
        }
        SongThumb(song = song, modifier = Modifier.fillMaxSize(0.96f).clip(CircleShape))
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
    val amplitudes = RealtimeFftMonitor.amplitudes
    val progress = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val barCount = 38

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
                val amp = amplitudes[i % amplitudes.size]
                val barHeight = (size.height * 0.85f * amp).coerceAtLeast(6.dp.toPx())
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
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx())
                )
            }
        }
    }
}

@Composable
private fun FullPlayer(
    song: Song,
    isPlaying: Boolean,
    position: Long,
    duration: Long,
    shuffle: Boolean,
    repeatMode: Int,
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
    onLaunchParty: () -> Unit,
    onQueue: () -> Unit
) {
    val context = LocalContext.current
    val view = LocalView.current
    var showLyrics by remember { mutableStateOf(false) }
    var lyrics by remember { mutableStateOf<List<LyricLine>>(emptyList()) }
    val lyricsListState = rememberLazyListState()

    LaunchedEffect(song.id) {
        lyrics = LyricsEngine.loadLyricsForSong(context, song)
    }

    val currentLyricIndex = remember(position, lyrics) {
        lyrics.indexOfLast { it.timestampMs <= position }.coerceAtLeast(0)
    }

    LaunchedEffect(currentLyricIndex) {
        if (showLyrics && lyrics.isNotEmpty()) {
            lyricsListState.animateScrollToItem((currentLyricIndex - 2).coerceAtLeast(0))
        }
    }

    val progress = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val infiniteTransition = rememberInfiniteTransition(label = "player_liquid")
    val pulseGlow by infiniteTransition.animateFloat(
        initialValue = 0.75f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "glow"
    )
    val wavePhaseAnim by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 2f * PI.toFloat(),
        animationSpec = infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart),
        label = "timeline_wave_phase"
    )
    val fftBass = RealtimeFftMonitor.bassEnergy

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
                .padding(horizontal = 24.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                Modifier
                    .size(width = 44.dp, height = 5.dp)
                    .clip(CircleShape)
                    .background(MutedLavender.copy(alpha = 0.45f))
            )
            Spacer(Modifier.height(8.dp))

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                BouncyIconButton(onClick = onClose) { Icon(Icons.Default.KeyboardArrowDown, null, tint = Color.White) }
                Text("NOW PLAYING", color = MutedLavender, modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 1.sp)

                // Party Mode quick launch
                BouncyIconButton(onClick = onLaunchParty) {
                    Icon(Icons.Default.Headphones, contentDescription = "Dual Headphone Share", tint = secondaryAccent)
                }

                BouncyIconButton(onClick = { showLyrics = !showLyrics }) {
                    Icon(Icons.Default.Lyrics, contentDescription = "Lyrics", tint = if (showLyrics) secondaryAccent else MutedLavender)
                }
                BouncyIconButton(onClick = onQueue) { Icon(Icons.Default.QueueMusic, null, tint = secondaryAccent) }
            }

            Spacer(Modifier.height(8.dp))

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
                    if (lyrics.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("No local .lrc lyrics file found.", color = MutedLavender, fontSize = 14.sp)
                        }
                    } else {
                        LazyColumn(state = lyricsListState, modifier = Modifier.fillMaxSize()) {
                            itemsIndexed(lyrics) { idx, line ->
                                val isCurrent = idx == currentLyricIndex
                                Text(
                                    text = line.text,
                                    color = if (isCurrent) secondaryAccent else MutedLavender.copy(alpha = 0.45f),
                                    fontSize = if (isCurrent) 21.sp else 15.sp,
                                    fontWeight = if (isCurrent) FontWeight.Black else FontWeight.Normal,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 10.dp)
                                        .clickable { onSeek(line.timestampMs) }
                                )
                            }
                        }
                    }
                }
            } else {
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    var canvasCenter by remember { mutableStateOf(Offset.Zero) }
                    Canvas(
                        Modifier
                            .size(310.dp)
                            .pointerInput(duration) {
                                detectDragGestures { change: PointerInputChange, _ ->
                                    val touch = change.position
                                    val dx = touch.x - canvasCenter.x
                                    val dy = touch.y - canvasCenter.y
                                    var angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat() + 90f
                                    if (angle < 0) angle += 360f
                                    val seekPercent = (angle / 360f).coerceIn(0f, 1f)
                                    val targetMs = (seekPercent * duration).toLong()
                                    onSeek(targetMs)
                                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                }
                            }
                    ) {
                        canvasCenter = center
                        val strokeWidth = 10.dp.toPx()
                        val outerGlowWidth = 22.dp.toPx()
                        val baseRadius = (size.width - outerGlowWidth) / 2f

                        drawCircle(
                            color = Color(0x352B194E),
                            radius = baseRadius,
                            center = center,
                            style = Stroke(strokeWidth, cap = StrokeCap.Round)
                        )

                        val sweepAngle = 360f * progress
                        if (sweepAngle > 0f) {
                            val wavePath = Path()
                            val steps = (sweepAngle * 1.5f).toInt().coerceAtLeast(8)
                            val waveAmplitude = if (isPlaying) (3.5.dp.toPx() + fftBass * 6.5.dp.toPx()) else 1.5.dp.toPx()
                            val waveFreq = 8.0
                            var tipX = center.x
                            var tipY = center.y

                            for (i in 0..steps) {
                                val currentDeg = -90f + (sweepAngle * (i.toFloat() / steps))
                                val rad = Math.toRadians(currentDeg.toDouble())
                                val waveOffset = sin((i.toDouble() / steps) * waveFreq * PI + wavePhaseAnim) * waveAmplitude
                                val r = baseRadius + waveOffset.toFloat()
                                val px = center.x + (r * cos(rad)).toFloat()
                                val py = center.y + (r * sin(rad)).toFloat()
                                if (i == 0) wavePath.moveTo(px, py) else wavePath.lineTo(px, py)
                                if (i == steps) {
                                    tipX = px
                                    tipY = py
                                }
                            }

                            drawPath(
                                path = wavePath,
                                brush = Brush.sweepGradient(
                                    listOf(
                                        primaryAccent.copy(alpha = 0.45f * pulseGlow),
                                        secondaryAccent.copy(alpha = 0.65f * pulseGlow),
                                        primaryAccent.copy(alpha = 0.45f * pulseGlow)
                                    )
                                ),
                                style = Stroke(outerGlowWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
                            )

                            drawPath(
                                path = wavePath,
                                brush = Brush.sweepGradient(
                                    0.0f to secondaryAccent,
                                    0.5f to primaryAccent,
                                    1.0f to secondaryAccent
                                ),
                                style = Stroke(strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
                            )

                            val dropletRadius = (5.dp.toPx() + fftBass * 3.dp.toPx())
                            drawCircle(color = secondaryAccent.copy(alpha = 0.6f * pulseGlow), radius = dropletRadius + 4.dp.toPx(), center = Offset(tipX, tipY))
                            drawCircle(color = Color.White, radius = dropletRadius, center = Offset(tipX, tipY))
                            drawCircle(color = secondaryAccent, radius = (dropletRadius * 0.6f), center = Offset(tipX, tipY))
                        }
                    }

                    // Gyroscope 3D Parallax Orb
                    LiquidOrbWaveformCenterpiece(
                        song = song,
                        isPlaying = isPlaying,
                        primaryColor = primaryAccent,
                        secondaryColor = secondaryAccent,
                        gyroPitch = gyroPitch,
                        gyroRoll = gyroRoll,
                        modifier = Modifier.size(225.dp)
                    )

                    if (isPlaying) {
                        MiniVisualizer(
                            accent1 = secondaryAccent,
                            accent2 = primaryAccent,
                            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Text(song.title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(song.artist, color = MutedLavender, fontSize = 13.sp)

            Spacer(Modifier.height(10.dp))
            WaveformSeekScrubber(
                position = position,
                duration = duration,
                primaryColor = primaryAccent,
                secondaryColor = secondaryAccent,
                onSeek = onSeek
            )

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(timeText(position), color = MutedLavender, fontSize = 12.sp)
                Text(timeText(duration), color = MutedLavender, fontSize = 12.sp)
            }

            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                BouncyIconButton(onClick = onShuffle) {
                    Icon(Icons.Default.Shuffle, null, tint = if (shuffle) secondaryAccent else MutedLavender)
                }
                BouncyIconButton(onClick = onPrevious) {
                    Icon(Icons.Default.SkipPrevious, null, tint = Color.White, modifier = Modifier.size(38.dp))
                }
                BouncyButton(
                    onClick = onPlayPause,
                    modifier = Modifier.size(72.dp)
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
                                modifier = Modifier.size(40.dp)
                            )
                        }
                    }
                }
                BouncyIconButton(onClick = onNext) {
                    Icon(Icons.Default.SkipNext, null, tint = Color.White, modifier = Modifier.size(38.dp))
                }
                BouncyIconButton(onClick = onRepeat) {
                    Icon(
                        if (repeatMode == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                        null,
                        tint = if (repeatMode != Player.REPEAT_MODE_OFF) secondaryAccent else MutedLavender
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
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
    content: @Composable () -> Unit
) {
    BouncyButton(onClick = onClick, modifier = modifier.size(48.dp)) {
        content()
    }
}

@Composable
private fun FloatingBottomNavBar(
    currentTab: MainTab,
    activeColor: Color,
    onSelect: (MainTab) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(66.dp),
        shape = RoundedCornerShape(33.dp),
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
                    targetValue = if (selected) 1.20f else 1f,
                    animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
                    label = "tab_scale"
                )
                BouncyButton(
                    onClick = { onSelect(tab) },
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.scale(animScale)
                    ) {
                        Icon(
                            tab.icon,
                            contentDescription = tab.label,
                            tint = if (selected) activeColor else MutedLavender.copy(alpha = 0.5f),
                            modifier = Modifier.size(24.dp)
                        )
                        if (selected) {
                            Box(
                                Modifier
                                    .padding(top = 4.dp)
                                    .size(4.dp)
                                    .clip(CircleShape)
                                    .background(activeColor)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeScreen(
    songs: List<Song>,
    recents: Set<String>,
    favorites: Set<String>,
    onPlay: (Song) -> Unit,
    onNavigateLibrary: () -> Unit
) {
    val recentSongs = remember(songs, recents) {
        songs.filter { it.id.toString() in recents }.take(6)
    }
    val favSongs = remember(songs, favorites) {
        songs.filter { it.id.toString() in favorites }.take(6)
    }
    val lastAddedSongs = remember(songs) { songs.sortedByDescending { it.dateAdded }.take(6) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 12.dp)
    ) {
        Surface(
            color = GlassPill,
            shape = RoundedCornerShape(26.dp),
            border = BorderStroke(1.2.dp, GlassBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                Modifier.padding(18.dp),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${songs.size}", color = ElectricCyan, fontSize = 24.sp, fontWeight = FontWeight.Black)
                    Text("Tracks", color = MutedLavender, fontSize = 12.sp)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${favorites.size}", color = NeonPurple, fontSize = 24.sp, fontWeight = FontWeight.Black)
                    Text("Favorites", color = MutedLavender, fontSize = 12.sp)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${songs.map { it.artist }.distinct().size}", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Black)
                    Text("Artists", color = MutedLavender, fontSize = 12.sp)
                }
            }
        }

        Spacer(Modifier.height(18.dp))

        // Sonic Heatmap Preview on Home Screen
        SonicHeatmapView()

        Spacer(Modifier.height(18.dp))
        Text("SMART: NEWLY DISCOVERED (LAST 30 DAYS)", color = AquaGlow, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Spacer(Modifier.height(8.dp))
        lastAddedSongs.forEach { song ->
            SongCard(song, song.id.toString() in favorites, { onPlay(song) }, {}, {}, {})
            Spacer(Modifier.height(6.dp))
        }

        Spacer(Modifier.height(18.dp))
        Text("RECENTLY PLAYED", color = ElectricCyan, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Spacer(Modifier.height(8.dp))
        if (recentSongs.isEmpty()) {
            Text("No recently played songs yet.", color = MutedLavender, fontSize = 12.sp)
        } else {
            recentSongs.forEach { song ->
                SongCard(song, song.id.toString() in favorites, { onPlay(song) }, {}, {}, {})
                Spacer(Modifier.height(6.dp))
            }
        }

        Spacer(Modifier.height(18.dp))
        Text("FAVORITE TRACKS", color = NeonPurple, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Spacer(Modifier.height(8.dp))
        if (favSongs.isEmpty()) {
            Text("No favorites added yet.", color = MutedLavender, fontSize = 12.sp)
        } else {
            favSongs.forEach { song ->
                SongCard(song, true, { onPlay(song) }, {}, {}, {})
                Spacer(Modifier.height(6.dp))
            }
        }
    }
}

@Composable
private fun LibraryScreen(
    allSongs: List<Song>,
    mode: String,
    onMode: (String) -> Unit,
    filterKey: String?,
    onFilterKey: (String?) -> Unit,
    query: String,
    listState: LazyListState,
    favorites: Set<String>,
    recents: Set<String>,
    customAlbums: List<String>,
    getCustomAlbumSongs: (String) -> Set<String>,
    onCreateAlbum: () -> Unit,
    onPlay: (Song, List<Song>) -> Unit,
    onFavorite: (Song) -> Unit,
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

    val songsToDisplay = remember(allSongs, mode, filterKey, query, favorites, recents, customAlbums) {
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
            mode == "Recent" -> allSongs.sortedByDescending { if (it.id.toString() in recents) recents.indexOf(it.id.toString()) else -1 }
            else -> allSongs
        }
        if (query.isBlank()) base else base.filter {
            it.title.contains(query, true) || it.artist.contains(query, true) ||
                    it.album.contains(query, true) || it.folder.contains(query, true)
        }
    }

    Column(modifier) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Songs", "Artists", "Albums", "Folders", "Favorites", "Recent").forEach { item ->
                FilterChip(
                    selected = mode == item,
                    onClick = { onMode(item) },
                    label = { Text(item) },
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
                Text(filterKey, color = ElectricCyan, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Spacer(Modifier.width(6.dp))
                Text("(${songsToDisplay.size} tracks)", color = MutedLavender, fontSize = 12.sp)
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val countText = when (mode) {
                    "Artists" -> "${artistGroups.size} ARTISTS"
                    "Albums" -> "${albumGroups.size} ALBUMS"
                    "Folders" -> "${folderGroups.size} FOLDERS"
                    else -> "${songsToDisplay.size} TRACKS   ${allSongs.size} IN REPOSITORY"
                }
                Text(
                    countText,
                    color = MutedLavender,
                    fontSize = 11.sp,
                    modifier = Modifier.weight(1f)
                )
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
                            subtitle = if (isUserAlbum) "Custom Album   ${songList.size} Tracks" else "${songList.firstOrNull()?.artist ?: "Various"}   ${songList.size} Tracks",
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
private fun GroupCard(
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
private fun SamsungAlphabetScroller(
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
        val targetIndex = songs.indexOfFirst {
            it.title.trim().firstOrNull()?.uppercaseChar() == letter
        }
        if (targetIndex >= 0) {
            scope.launch { listState.scrollToItem(targetIndex) }
        }
    }

    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(28.dp)
            .onGloballyPositioned { railHeightPx = it.size.height.toFloat() }
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
                    fontSize = 10.sp,
                    fontWeight = if (isSelected) FontWeight.Black else FontWeight.Normal
                )
            }
        }
        activeLetter?.let { letter ->
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = (-46).dp)
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(Brush.radialGradient(listOf(ElectricCyan, NeonPurple)))
                    .border(2.dp, Color.White, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = letter.toString(),
                    color = OLEDBlack,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Black
                )
            }
        }
    }
}

@Composable
private fun SongCard(
    song: Song,
    favorite: Boolean,
    onPlay: () -> Unit,
    onFavorite: () -> Unit,
    onAddToAlbum: () -> Unit,
    onEdit: () -> Unit
) {
    BouncyButton(onClick = onPlay, modifier = Modifier.fillMaxWidth()) {
        Surface(
            color = GlassPill,
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.2.dp, GlassBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                SongThumb(song, Modifier.size(50.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(song.title, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${song.artist} • ${song.album}", color = MutedLavender, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(song.folder, color = ElectricCyan.copy(alpha = 0.8f), fontSize = 10.sp)
                }
                IconButton(onClick = onFavorite) {
                    Icon(
                        if (favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        null,
                        tint = if (favorite) Color(0xFFFF5286) else MutedLavender
                    )
                }
                IconButton(onClick = onAddToAlbum) {
                    Icon(Icons.Default.Album, "Add to Album", tint = NeonPurple)
                }
                IconButton(onClick = onEdit) {
                    Icon(Icons.Default.AutoFixHigh, "Auto-Fix Metadata", tint = ElectricCyan, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

@Composable
private fun SongThumb(song: Song, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var embeddedBitmap by remember(song.id) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(song.id) {
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

    if (embeddedBitmap != null) {
        Image(
            bitmap = embeddedBitmap!!,
            contentDescription = "Artwork",
            contentScale = ContentScale.Crop,
            modifier = modifier.clip(RoundedCornerShape(14.dp))
        )
    } else if (!song.albumArtUri.isNullOrBlank()) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(song.albumArtUri)
                .crossfade(true)
                .build(),
            contentDescription = "Artwork",
            contentScale = ContentScale.Crop,
            modifier = modifier.clip(RoundedCornerShape(14.dp))
        )
    } else {
        Box(
            modifier = modifier
                .background(Brush.linearGradient(listOf(NeonPurple, ElectricCyan)), RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.MusicNote, null, tint = Color.White)
        }
    }
}

/**
 * Metadata Editor + "Audio Fingerprint" Auto-Fixer Dialog
 */
@Composable
private fun TagEditorAndFingerprintDialog(
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

                // AUTO-FIX TRIGGER BUTTON
                Button(
                    onClick = {
                        scope.launch {
                            isFingerprinting = true
                            val fix = AudioFingerprintFixer.autoFixSong(context, song)
                            title = fix.title
                            artist = fix.artist
                            album = fix.album
                            matchConfidence = fix.matchConfidence

                            if (fix.albumArtUrl != null) {
                                onlineCoverUri = ArtworkScraper.saveDownloadedCoverToFile(context, song, fix.albumArtUrl)
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
                    Text(if (isFingerprinting) "Analyzing Acoustic Match..." else "Magic Audio Fingerprint Fix")
                }

                if (matchConfidence > 0f) {
                    Text("✓ Confidence Match: ${(matchConfidence * 100).toInt()}% • HD Art Saved", color = ElectricCyan, fontSize = 12.sp)
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
private fun MiniVisualizer(
    accent1: Color = ElectricCyan,
    accent2: Color = NeonPurple,
    modifier: Modifier = Modifier
) {
    val amplitudes = RealtimeFftMonitor.amplitudes
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        for (i in 0 until 16) {
            val h = (amplitudes[i] * 24.dp.value).coerceIn(4f, 26f)
            Box(Modifier.width(3.dp).height(h.dp).clip(CircleShape).background(Brush.verticalGradient(listOf(accent1, accent2))))
        }
    }
}

@Composable
private fun SettingsScreen(
    songCount: Int,
    outputs: List<AudioOutput>,
    timerMinutes: Int,
    timerLeft: Int,
    customTimer: String,
    onCustomTimerChange: (String) -> Unit,
    onSetTimer: (Int) -> Unit,
    onCancelTimer: () -> Unit,
    onRescan: () -> Unit,
    onLaunchDualAudio: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.verticalScroll(rememberScrollState()).padding(vertical = 10.dp)) {
        Text("SETTINGS & AUDIO SUITE", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)

        Spacer(Modifier.height(14.dp))

        // DUAL HEADPHONE AUDIO SHARE (PARTY MODE)
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
                        Text("Connect 2 pairs of Bluetooth headphones simultaneously", color = MutedLavender, fontSize = 11.sp)
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
                    Text("Launch Dual Audio Settings")
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
        Text("Automatically ducks volume to 18% during incoming notifications/alerts instead of abrupt cuts.", color = MutedLavender, fontSize = 12.sp)

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
        Text("MEDIA STORE INDEX", color = ElectricCyan, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Spacer(Modifier.height(6.dp))
        Text("$songCount tracks loaded from device storage.", color = MutedLavender, fontSize = 12.sp)
        Spacer(Modifier.height(6.dp))
        Button(onClick = onRescan, colors = ButtonDefaults.buttonColors(containerColor = GlassPill)) {
            Icon(Icons.Default.Refresh, null, tint = ElectricCyan)
            Spacer(Modifier.width(6.dp))
            Text("Rescan Local Music", color = ElectricCyan)
        }

        Spacer(Modifier.height(20.dp))
        Text("SONIQ v0.4.0 • Master Edition with Stems & Parallax", color = MutedLavender.copy(alpha = 0.5f), fontSize = 11.sp)
    }
}

private fun timeText(ms: Long): String {
    val s = (ms.coerceAtLeast(0L) / 1000).toInt()
    return "%d:%02d".format(s / 60, s % 60)
}
