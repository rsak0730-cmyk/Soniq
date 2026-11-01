@file:OptIn(
    androidx.compose.animation.ExperimentalAnimationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class
)
package com.soniq.player.ui

import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import com.soniq.player.audio.AudioOutput
import com.soniq.player.data.LyricLine
import com.soniq.player.data.LyricsEngine
import com.soniq.player.data.MusicRepository
import com.soniq.player.data.Song
import com.soniq.player.playback.PlaybackService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.*

private val DeepBlack = Color(0xFF080711)
private val Purple = Color(0xFF9B6CFF)
private val VioletGlow = Color(0xFF713CFF)
private val ElectricCyan = Color(0xFF62F5FF)
private val MutedLavender = Color(0xFFB9A9D3)
private val GlassSurface = Color(0x33281E48)
private val GlassBorder = Color(0x4462F5FF)

enum class MainTab(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Default.Home),
    LIBRARY("Library", Icons.Default.LibraryMusic),
    EQUALIZER("Equalizer", Icons.Default.Equalizer),
    PLAYLISTS("Playlists", Icons.Default.QueueMusic),
    SETTINGS("Settings", Icons.Default.Settings)
}

@Composable
fun SoniqApp() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("soniq_user_data", 0) }

    var songs by remember { mutableStateOf(emptyList<Song>()) }
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

    // Dynamic Artwork Palette extraction
    var dynamicPrimaryColor by remember { mutableStateOf(Purple) }
    var dynamicSecondaryColor by remember { mutableStateOf(ElectricCyan) }

    var outputs by remember { mutableStateOf(emptyList<AudioOutput>()) }
    var favorites by remember { mutableStateOf(prefs.getStringSet("favorites", emptySet())?.toSet() ?: emptySet()) }
    var recents by remember { mutableStateOf(prefs.getStringSet("recents", emptySet())?.toSet() ?: emptySet()) }
    var searchHistory by remember { mutableStateOf(prefs.getStringSet("search_history", emptySet())?.toList()?.take(8) ?: emptyList()) }

    var playlists by remember { mutableStateOf(prefs.getStringSet("playlists", setOf("Favorites"))?.toList()?.sorted() ?: listOf("Favorites")) }
    var selectedPlaylist by remember { mutableStateOf("Favorites") }
    var playlistSongs by remember { mutableStateOf(prefs.getString("playlist_Favorites", "")?.split("|")?.filter { it.isNotBlank() }?.toSet() ?: emptySet()) }

    var timerMinutes by remember { mutableIntStateOf(0) }
    var customTimer by remember { mutableStateOf("25") }
    var timerLeft by remember { mutableIntStateOf(0) }

    var showCreatePlaylist by remember { mutableStateOf(false) }
    var newPlaylistName by remember { mutableStateOf("") }
    var addToPlaylistSong by remember { mutableStateOf<Song?>(null) }
    var editTagSong by remember { mutableStateOf<Song?>(null) }

    var libraryMode by remember { mutableStateOf("Songs") }
    val listState = rememberLazyListState()
    var selectedFilterKey by remember { mutableStateOf<String?>(null) }

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
        recents = (recents + song.id.toString()).toList().takeLast(30).toSet()
        saveRecents()
        showPlayer = true
    }

    // Extract palette dynamically when current song changes
    LaunchedEffect(currentSong?.id) {
        val song = currentSong ?: return@LaunchedEffect
        withContext(Dispatchers.IO) {
            val bytes = MusicRepository.extractEmbeddedPicture(context, song.uri)
            val bmp: Bitmap? = if (bytes != null) {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } else null

            if (bmp != null) {
                Palette.from(bmp).generate().let { palette ->
                    val dom = palette.getVibrantColor(palette.getDominantColor(Purple.toArgb()))
                    val sec = palette.getLightVibrantColor(palette.getMutedColor(ElectricCyan.toArgb()))
                    dynamicPrimaryColor = Color(dom)
                    dynamicSecondaryColor = Color(sec)
                }
            } else {
                dynamicPrimaryColor = Purple
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
            delay(250)
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

    Box(Modifier.fillMaxSize()) {
        // Multi-Layered Palette Reactive Shimmer
        LiquidWaterShimmerBackground(
            isPlaying = isPlaying,
            primaryAccent = dynamicPrimaryColor,
            secondaryAccent = dynamicSecondaryColor
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
                        letterSpacing = 1.sp
                    )
                    Text("Liquid Sound Experience", color = MutedLavender, fontSize = 11.sp)
                }
                if (currentSong != null) {
                    IconButton(onClick = { showPlayer = true }) {
                        Icon(Icons.Default.GraphicEq, contentDescription = "Now Playing", tint = dynamicSecondaryColor)
                    }
                }
            }

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
                        color = MutedLavender.copy(alpha = 0.7f),
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
                    focusedContainerColor = GlassSurface,
                    unfocusedContainerColor = GlassSurface
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
                        playlistSongs = playlistSongs,
                        onPlay = { song, list -> playSong(song, list) },
                        onFavorite = { song ->
                            favorites = if (song.id.toString() in favorites) favorites - song.id.toString()
                            else favorites + song.id.toString()
                            saveFavorites()
                        },
                        onAdd = { addToPlaylistSong = it },
                        onEdit = { editTagSong = it },
                        modifier = Modifier.fillMaxSize()
                    )
                    MainTab.EQUALIZER -> EqualizerScreen(Modifier.fillMaxSize())
                    MainTab.PLAYLISTS -> PlaylistScreen(
                        playlists = playlists,
                        selected = selectedPlaylist,
                        songs = songs,
                        ids = playlistSongs,
                        onOpen = {
                            selectedPlaylist = it
                            playlistSongs = prefs.getString("playlist_$it", "")?.split("|")?.filter(String::isNotBlank)?.toSet() ?: emptySet()
                            libraryMode = "Playlist"
                            selectedFilterKey = null
                            currentTab = MainTab.LIBRARY
                        },
                        onCreate = { showCreatePlaylist = true },
                        modifier = Modifier.fillMaxSize()
                    )
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
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }

        // Floating Bottom Bar
        FloatingBottomNavBar(
            currentTab = currentTab,
            activeColor = dynamicSecondaryColor,
            onSelect = { currentTab = it },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 18.dp, vertical = 12.dp)
        )

        // Full Screen Player
        if (showPlayer && currentSong != null) {
            FullPlayer(
                song = currentSong!!,
                isPlaying = isPlaying,
                position = position,
                duration = duration,
                shuffle = shuffle,
                repeatMode = repeatMode,
                primaryAccent = dynamicPrimaryColor,
                secondaryAccent = dynamicSecondaryColor,
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
                onQueue = { currentTab = MainTab.LIBRARY; showPlayer = false }
            )
        }

        // Tag Editor Dialog
        editTagSong?.let { song ->
            TagEditorDialog(
                song = song,
                onDismiss = { editTagSong = null },
                onSave = { updatedTitle, updatedArtist ->
                    songs = songs.map {
                        if (it.id == song.id) it.copy(title = updatedTitle, artist = updatedArtist) else it
                    }
                    editTagSong = null
                }
            )
        }

        if (showCreatePlaylist) {
            AlertDialog(
                onDismissRequest = { showCreatePlaylist = false },
                containerColor = Color(0xFF1E1736),
                title = { Text("Create Playlist", color = Color.White) },
                text = {
                    OutlinedTextField(
                        newPlaylistName,
                        { newPlaylistName = it },
                        label = { Text("Playlist Name", color = MutedLavender) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = ElectricCyan
                        )
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        val name = newPlaylistName.trim()
                        if (name.isNotEmpty()) {
                            playlists = (playlists + name).distinct()
                            prefs.edit().putStringSet("playlists", playlists.toSet()).apply()
                            selectedPlaylist = name
                            playlistSongs = emptySet()
                            prefs.edit().putString("playlist_$name", "").apply()
                        }
                        newPlaylistName = ""
                        showCreatePlaylist = false
                    }) { Text("Create", color = ElectricCyan) }
                },
                dismissButton = {
                    TextButton(onClick = { showCreatePlaylist = false }) { Text("Cancel", color = MutedLavender) }
                }
            )
        }

        addToPlaylistSong?.let { song ->
            AlertDialog(
                onDismissRequest = { addToPlaylistSong = null },
                containerColor = Color(0xFF1E1736),
                title = { Text("Add to Playlist", color = Color.White) },
                text = {
                    Column {
                        playlists.forEach { name ->
                            TextButton(
                                onClick = {
                                    val ids = (prefs.getString("playlist_$name", "") ?: "")
                                        .split("|").filter(String::isNotBlank).toSet() + song.id.toString()
                                    prefs.edit().putString("playlist_$name", ids.joinToString("|")).apply()
                                    if (name == selectedPlaylist) playlistSongs = ids
                                    addToPlaylistSong = null
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(name, color = ElectricCyan)
                            }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { addToPlaylistSong = null }) { Text("Cancel", color = MutedLavender) }
                }
            )
        }
    }
}

@Composable
fun LiquidWaterShimmerBackground(
    isPlaying: Boolean,
    primaryAccent: Color = Purple,
    secondaryAccent: Color = ElectricCyan,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "liquid_shimmer_trans")
    val waveT by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 6.28318f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (isPlaying) 6500 else 12000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wave_radian"
    )

    val causticShift by infiniteTransition.animateFloat(
        initialValue = -0.3f,
        targetValue = 1.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (isPlaying) 4000 else 8500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "caustic_shift"
    )

    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height

        drawRect(color = DeepBlack)

        val orb1X = w * (0.68f + 0.18f * cos(waveT.toDouble()).toFloat())
        val orb1Y = h * (0.24f + 0.12f * sin(waveT.toDouble()).toFloat())
        val orb1Radius = w * (if (isPlaying) 0.85f else 0.70f)

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    primaryAccent.copy(alpha = if (isPlaying) 0.44f else 0.22f),
                    primaryAccent.copy(alpha = if (isPlaying) 0.20f else 0.08f),
                    Color.Transparent
                ),
                center = Offset(orb1X, orb1Y),
                radius = orb1Radius
            ),
            radius = orb1Radius,
            center = Offset(orb1X, orb1Y)
        )

        val orb2X = w * (0.28f + 0.15f * sin((waveT * 0.8f).toDouble()).toFloat())
        val orb2Y = h * (0.75f + 0.10f * cos((waveT * 0.8f).toDouble()).toFloat())
        val orb2Radius = w * (if (isPlaying) 0.90f else 0.75f)

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    secondaryAccent.copy(alpha = if (isPlaying) 0.32f else 0.14f),
                    secondaryAccent.copy(alpha = if (isPlaying) 0.14f else 0.05f),
                    Color.Transparent
                ),
                center = Offset(orb2X, orb2Y),
                radius = orb2Radius
            ),
            radius = orb2Radius,
            center = Offset(orb2X, orb2Y)
        )

        drawRect(
            brush = Brush.linearGradient(
                0.0f to Color.Transparent,
                0.45f to secondaryAccent.copy(alpha = if (isPlaying) 0.08f else 0.03f),
                0.55f to Color.White.copy(alpha = if (isPlaying) 0.06f else 0.02f),
                0.65f to primaryAccent.copy(alpha = if (isPlaying) 0.05f else 0.02f),
                1.0f to Color.Transparent,
                start = Offset(w * causticShift - w * 0.4f, 0f),
                end = Offset(w * causticShift + w * 0.4f, h)
            )
        )
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
            .height(64.dp),
        shape = RoundedCornerShape(32.dp),
        color = Color(0xDD120E22),
        border = BorderStroke(1.dp, GlassBorder)
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

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .clickable { onSelect(tab) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.scale(animScale)
                    ) {
                        Icon(
                            tab.icon,
                            contentDescription = tab.label,
                            tint = if (selected) activeColor else MutedLavender.copy(alpha = 0.6f),
                            modifier = Modifier.size(24.dp)
                        )
                        if (selected) {
                            Box(
                                Modifier
                                    .padding(top = 3.dp)
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

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 12.dp)
    ) {
        Surface(
            color = GlassSurface,
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, GlassBorder),
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
                    Text("${favorites.size}", color = Purple, fontSize = 24.sp, fontWeight = FontWeight.Black)
                    Text("Favorites", color = MutedLavender, fontSize = 12.sp)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${songs.map { it.artist }.distinct().size}", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Black)
                    Text("Artists", color = MutedLavender, fontSize = 12.sp)
                }
            }
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
        Text("FAVORITE TRACKS", color = Purple, fontWeight = FontWeight.Bold, fontSize = 13.sp)
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
    playlistSongs: Set<String>,
    onPlay: (Song, List<Song>) -> Unit,
    onFavorite: (Song) -> Unit,
    onAdd: (Song) -> Unit,
    onEdit: (Song) -> Unit,
    modifier: Modifier = Modifier
) {
    val artistGroups = remember(allSongs) {
        allSongs.groupBy { it.artist }.toList().sortedBy { it.first.lowercase() }
    }
    val albumGroups = remember(allSongs) {
        allSongs.groupBy { it.album }.toList().sortedBy { it.first.lowercase() }
    }
    val folderGroups = remember(allSongs) {
        allSongs.groupBy { it.folder }.toList().sortedBy { it.first.lowercase() }
    }

    val songsToDisplay = remember(allSongs, mode, filterKey, query, favorites, recents, playlistSongs) {
        val base = when {
            filterKey != null -> {
                when (mode) {
                    "Artists" -> allSongs.filter { it.artist == filterKey }
                    "Albums" -> allSongs.filter { it.album == filterKey }
                    "Folders" -> allSongs.filter { it.folder == filterKey }
                    else -> allSongs
                }
            }
            mode == "Favorites" -> allSongs.filter { it.id.toString() in favorites }
            mode == "Recent" -> allSongs.sortedByDescending { if (it.id.toString() in recents) recents.indexOf(it.id.toString()) else -1 }
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
                    label = { Text(item) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Purple.copy(alpha = 0.35f),
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
            val countText = when (mode) {
                "Artists" -> "${artistGroups.size} ARTISTS"
                "Albums" -> "${albumGroups.size} ALBUMS"
                "Folders" -> "${folderGroups.size} FOLDERS"
                else -> "${songsToDisplay.size} TRACKS • ${allSongs.size} IN REPOSITORY"
            }
            Text(
                countText,
                color = MutedLavender,
                fontSize = 11.sp,
                modifier = Modifier.padding(vertical = 7.dp)
            )
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
                        GroupCard(
                            title = album,
                            subtitle = "${songList.firstOrNull()?.artist ?: "Unknown Artist"} • ${songList.size} Tracks",
                            icon = Icons.Default.Album,
                            representativeSong = songList.firstOrNull(),
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
                                    onAdd = { onAdd(song) },
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
    onClick: () -> Unit
) {
    Surface(
        color = GlassSurface,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, GlassBorder),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (representativeSong != null) {
                SongThumb(song = representativeSong, modifier = Modifier.size(48.dp))
            } else {
                Box(
                    Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Brush.linearGradient(listOf(Purple.copy(alpha = 0.5f), ElectricCyan.copy(alpha = 0.5f)))),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, null, tint = Color.White)
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, color = MutedLavender, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Default.ChevronRight, null, tint = ElectricCyan)
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
                        val itemHeight = size.height.toFloat() / alphabet.size
                        val index = (offset.y / itemHeight).toInt().coerceIn(0, alphabet.lastIndex)
                        scrollToLetter(alphabet[index])
                        tryAwaitRelease()
                        activeLetter = null
                    }
                )
            }
            .pointerInput(alphabet, songs) {
                detectVerticalDragGestures(
                    onDragStart = { offset ->
                        val itemHeight = railHeightPx / alphabet.size
                        val index = (offset.y / itemHeight).toInt().coerceIn(0, alphabet.lastIndex)
                        scrollToLetter(alphabet[index])
                    },
                    onDragEnd = { activeLetter = null },
                    onDragCancel = { activeLetter = null },
                    onVerticalDrag = { change, _ ->
                        change.consume()
                        val itemHeight = railHeightPx / alphabet.size
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
                    .background(Brush.radialGradient(listOf(ElectricCyan, Purple)))
                    .border(2.dp, Color.White, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = letter.toString(),
                    color = DeepBlack,
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
    onAdd: () -> Unit,
    onEdit: () -> Unit
) {
    Surface(
        color = GlassSurface,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, GlassBorder),
        modifier = Modifier.fillMaxWidth().clickable { onPlay() }
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
            IconButton(onClick = onAdd) {
                Icon(Icons.Default.PlaylistAdd, null, tint = ElectricCyan)
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Default.Edit, null, tint = MutedLavender.copy(alpha = 0.7f), modifier = Modifier.size(18.dp))
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
                .background(Brush.linearGradient(listOf(Purple, ElectricCyan)), RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.MusicNote, null, tint = Color.White)
        }
    }
}

/**
 * Full Player with:
 * - Touch-Draggable Circular Seeking with Haptics
 * - Dynamic Artwork Palette Bloom
 * - Synchronized Karaoke Lyrics View
 */
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
    onClose: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
    onShuffle: () -> Unit,
    onRepeat: () -> Unit,
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

    // Auto-scroll lyrics to match playback time
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
        initialValue = 0.7f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(tween(2000, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "glow"
    )

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures { } }
    ) {
        LiquidWaterShimmerBackground(
            isPlaying = isPlaying,
            primaryAccent = primaryAccent,
            secondaryAccent = secondaryAccent
        )

        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.Default.KeyboardArrowDown, null, tint = Color.White) }
                Text("NOW PLAYING", color = MutedLavender, modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                IconButton(onClick = { showLyrics = !showLyrics }) {
                    Icon(Icons.Default.Lyrics, contentDescription = "Lyrics", tint = if (showLyrics) secondaryAccent else MutedLavender)
                }
                IconButton(onClick = onQueue) { Icon(Icons.Default.QueueMusic, null, tint = secondaryAccent) }
            }

            Spacer(Modifier.height(10.dp))

            if (showLyrics) {
                // Interactive Synchronized Karaoke Lyrics Display
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(24.dp))
                        .background(GlassSurface)
                        .border(1.dp, GlassBorder, RoundedCornerShape(24.dp))
                        .padding(16.dp)
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
                                    fontSize = if (isCurrent) 20.sp else 15.sp,
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
                // Interactive Circular Liquid Waveform Scrubber
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    var canvasCenter by remember { mutableStateOf(Offset.Zero) }

                    Canvas(
                        Modifier
                            .size(285.dp)
                            .pointerInput(duration) {
                                detectDragGestures { change, _ ->
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
                        val strokeWidth = 9.dp.toPx()
                        val outerGlowWidth = 18.dp.toPx()
                        val arcSize = size.width - outerGlowWidth

                        drawCircle(
                            color = Color(0x334E3577),
                            radius = arcSize / 2,
                            center = center,
                            style = Stroke(strokeWidth, cap = StrokeCap.Round)
                        )

                        val sweepAngle = 360f * progress
                        if (sweepAngle > 0f) {
                            drawArc(
                                brush = Brush.sweepGradient(
                                    listOf(
                                        primaryAccent.copy(alpha = 0.35f * pulseGlow),
                                        secondaryAccent.copy(alpha = 0.5f * pulseGlow),
                                        primaryAccent.copy(alpha = 0.35f * pulseGlow)
                                    )
                                ),
                                startAngle = -90f,
                                sweepAngle = sweepAngle,
                                useCenter = false,
                                topLeft = Offset((size.width - arcSize) / 2, (size.height - arcSize) / 2),
                                size = Size(arcSize, arcSize),
                                style = Stroke(outerGlowWidth, cap = StrokeCap.Round)
                            )

                            drawArc(
                                brush = Brush.sweepGradient(
                                    0.0f to secondaryAccent,
                                    0.5f to primaryAccent,
                                    1.0f to secondaryAccent
                                ),
                                startAngle = -90f,
                                sweepAngle = sweepAngle,
                                useCenter = false,
                                topLeft = Offset((size.width - arcSize) / 2, (size.height - arcSize) / 2),
                                size = Size(arcSize, arcSize),
                                style = Stroke(strokeWidth, cap = StrokeCap.Round)
                            )

                            val angleRad = Math.toRadians((sweepAngle - 90).toDouble())
                            val radius = arcSize / 2
                            val headX = center.x + (radius * cos(angleRad)).toFloat()
                            val headY = center.y + (radius * sin(angleRad)).toFloat()

                            drawCircle(color = Color.White, radius = 6.5.dp.toPx(), center = Offset(headX, headY))
                            drawCircle(color = secondaryAccent, radius = 4.dp.toPx(), center = Offset(headX, headY))
                        }
                    }

                    Box(
                        modifier = Modifier
                            .size(230.dp)
                            .clip(CircleShape)
                            .border(1.5.dp, GlassBorder, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        SongThumb(song, Modifier.fillMaxSize())
                    }

                    if (isPlaying) {
                        MiniVisualizer(
                            accent1 = secondaryAccent,
                            accent2 = primaryAccent,
                            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            Text(song.title, color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(song.artist, color = MutedLavender, fontSize = 13.sp)

            Spacer(Modifier.height(12.dp))

            Slider(
                value = if (duration > 0) position.toFloat().coerceIn(0f, duration.toFloat()) else 0f,
                onValueChange = { onSeek(it.toLong()) },
                valueRange = 0f..duration.coerceAtLeast(1L).toFloat(),
                colors = SliderDefaults.colors(thumbColor = secondaryAccent, activeTrackColor = primaryAccent)
            )

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(timeText(position), color = MutedLavender, fontSize = 12.sp)
                Text(timeText(duration), color = MutedLavender, fontSize = 12.sp)
            }

            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onShuffle) { Icon(Icons.Default.Shuffle, null, tint = if (shuffle) secondaryAccent else MutedLavender) }
                IconButton(onClick = onPrevious) { Icon(Icons.Default.SkipPrevious, null, tint = Color.White, modifier = Modifier.size(36.dp)) }
                Surface(
                    shape = CircleShape,
                    color = primaryAccent,
                    border = BorderStroke(2.dp, secondaryAccent),
                    modifier = Modifier.size(68.dp).clickable(onClick = onPlayPause)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.size(38.dp))
                    }
                }
                IconButton(onClick = onNext) { Icon(Icons.Default.SkipNext, null, tint = Color.White, modifier = Modifier.size(36.dp)) }
                IconButton(onClick = onRepeat) {
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
private fun TagEditorDialog(
    song: Song,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit
) {
    var title by remember { mutableStateOf(song.title) }
    var artist by remember { mutableStateOf(song.artist) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1E1736),
        title = { Text("Edit Song Metadata", color = Color.White) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Song Title", color = MutedLavender) },
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White)
                )
                OutlinedTextField(
                    value = artist,
                    onValueChange = { artist = it },
                    label = { Text("Artist Name", color = MutedLavender) },
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(title.trim(), artist.trim()) }) {
                Text("Save", color = ElectricCyan)
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
    accent2: Color = Purple,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "visualizer")
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(20) { i ->
            val h by transition.animateFloat(
                initialValue = 4f,
                targetValue = (12 + (i * 9 % 24)).toFloat(),
                animationSpec = infiniteRepeatable(tween(260 + i * 18, easing = FastOutSlowInEasing), RepeatMode.Reverse),
                label = "bar$i"
            )
            Box(Modifier.width(3.dp).height(h.dp).clip(CircleShape).background(Brush.verticalGradient(listOf(accent1, accent2))))
        }
    }
}

@Composable
private fun PlaylistScreen(
    playlists: List<String>,
    selected: String,
    songs: List<Song>,
    ids: Set<String>,
    onOpen: (String) -> Unit,
    onCreate: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("PLAYLISTS", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Button(
                onClick = onCreate,
                colors = ButtonDefaults.buttonColors(containerColor = Purple)
            ) {
                Icon(Icons.Default.Add, null)
                Spacer(Modifier.width(4.dp))
                Text("New")
            }
        }
        Spacer(Modifier.height(12.dp))
        playlists.forEach { name ->
            Surface(
                color = GlassSurface,
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, GlassBorder),
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { onOpen(name) }
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.QueueMusic, null, tint = ElectricCyan)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(name, color = Color.White, fontWeight = FontWeight.SemiBold)
                        Text(if (name == selected) "${ids.size} tracks active" else "Tap to browse", color = MutedLavender, fontSize = 11.sp)
                    }
                    Icon(Icons.Default.ChevronRight, null, tint = MutedLavender)
                }
            }
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
    modifier: Modifier = Modifier
) {
    Column(modifier.verticalScroll(rememberScrollState()).padding(vertical = 10.dp)) {
        Text("SETTINGS & AUDIO SUITE", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(14.dp))

        Text("ACTIVE OUTPUT ROUTING", color = ElectricCyan, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Spacer(Modifier.height(6.dp))
        outputs.forEach { output ->
            Surface(
                color = GlassSurface,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, GlassBorder),
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

        Text("SLEEP TIMER", color = Purple, fontWeight = FontWeight.Bold, fontSize = 13.sp)
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
                colors = ButtonDefaults.buttonColors(containerColor = Purple)
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
        Button(onClick = onRescan, colors = ButtonDefaults.buttonColors(containerColor = GlassSurface)) {
            Icon(Icons.Default.Refresh, null, tint = ElectricCyan)
            Spacer(Modifier.width(6.dp))
            Text("Rescan Local Music", color = ElectricCyan)
        }

        Spacer(Modifier.height(20.dp))
        Text("SONIQ v0.3.0 • Premium Glass Edition", color = MutedLavender.copy(alpha = 0.5f), fontSize = 11.sp)
    }
}

private fun timeText(ms: Long): String {
    val s = (ms.coerceAtLeast(0L) / 1000).toInt()
    return "%d:%02d".format(s / 60, s % 60)
}
