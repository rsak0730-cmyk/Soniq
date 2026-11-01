@file:OptIn(
    androidx.compose.animation.ExperimentalAnimationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class
)
package com.soniq.player.ui

import android.content.ComponentName
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
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
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import coil.compose.AsyncImage
import com.google.common.util.concurrent.MoreExecutors
import com.soniq.player.audio.AudioDeviceMonitor
import com.soniq.player.audio.AudioOutput
import com.soniq.player.data.MusicRepository
import com.soniq.player.data.Song
import com.soniq.player.playback.PlaybackService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val DeepBlack = Color(0xFF080711)
private val Purple = Color(0xFF9B6CFF)
private val VioletGlow = Color(0xFF713CFF)
private val Cyan = Color(0xFF62F5FF)
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
    val scope = rememberCoroutineScope()
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
    var libraryMode by remember { mutableStateOf("Songs") }
    val listState = rememberLazyListState()

    fun saveFavorites() { prefs.edit().putStringSet("favorites", favorites).apply() }
    fun saveRecents() { prefs.edit().putStringSet("recents", recents).apply() }

    fun playSong(song: Song, source: List<Song> = songs) {
        val c = controller ?: return
        val queue = source.ifEmpty { songs }.ifEmpty { listOf(song) }
        val index = queue.indexOfFirst { it.id == song.id }.coerceAtLeast(0)
        c.setMediaItems(queue.map { s ->
            MediaItem.Builder().setUri(s.uri).setMediaMetadata(
                MediaMetadata.Builder().setTitle(s.title).setArtist(s.artist)
                    .setAlbumTitle(s.album).setArtworkUri(s.albumArtUri?.let(android.net.Uri::parse)).build()
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
            delay(350)
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

    val filtered = remember(songs, query) {
        if (query.isBlank()) songs else songs.filter {
            it.title.contains(query, true) || it.artist.contains(query, true) ||
                    it.album.contains(query, true) || it.folder.contains(query, true)
        }
    }

    val displayedSongs = when (libraryMode) {
        "Artists" -> filtered.filter { it.artist.contains(query, true) || query.isBlank() }
        "Albums" -> filtered.filter { it.album.contains(query, true) || query.isBlank() }
        "Folders" -> filtered.filter { it.folder.contains(query, true) }
        "Favorites" -> filtered.filter { it.id.toString() in favorites }
        "Recent" -> filtered.sortedByDescending { if (it.id.toString() in recents) recents.indexOf(it.id.toString()) else -1 }
        "Playlist" -> filtered.filter { it.id.toString() in playlistSongs }
        else -> filtered
    }

    Box(Modifier.fillMaxSize().background(DeepBlack)) {
        // Liquid Water-Shimmer Background
        val shimmerTrans = rememberInfiniteTransition(label = "shimmer")
        val phase by shimmerTrans.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(if (isPlaying) 3200 else 7000, easing = LinearEasing), RepeatMode.Reverse),
            label = "phase"
        )

        Canvas(Modifier.fillMaxSize()) {
            val canvasWidth = size.width
            val canvasHeight = size.height

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Purple.copy(alpha = if (isPlaying) 0.28f else 0.12f), Color.Transparent),
                    center = Offset(canvasWidth * (0.8f - phase * 0.2f), canvasHeight * (0.2f + phase * 0.15f)),
                    radius = 450f
                )
            )

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Cyan.copy(alpha = if (isPlaying) 0.22f else 0.08f), Color.Transparent),
                    center = Offset(canvasWidth * (0.2f + phase * 0.3f), canvasHeight * (0.75f - phase * 0.1f)),
                    radius = 400f
                )
            )

            if (isPlaying) {
                drawRect(
                    brush = Brush.linearGradient(
                        colors = listOf(Color.Transparent, Cyan.copy(alpha = 0.05f + phase * 0.04f), Color.Transparent),
                        start = Offset(canvasWidth * phase - 200f, 0f),
                        end = Offset(canvasWidth * phase + 300f, canvasHeight)
                    )
                )
            }
        }

        // Main Content Area
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(bottom = 86.dp)
                .padding(horizontal = 16.dp)
        ) {
            // Header Bar
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("SONIQ", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                    Text("Liquid Sound Experience", color = MutedLavender, fontSize = 11.sp)
                }
                if (currentSong != null) {
                    IconButton(onClick = { showPlayer = true }) {
                        Icon(Icons.Default.GraphicEq, contentDescription = "Now Playing", tint = Cyan)
                    }
                }
            }

            // Glass Search Bar
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
                placeholder = { Text("Search songs, artists, albums, folders...", color = MutedLavender.copy(alpha = 0.7f), fontSize = 13.sp) },
                leadingIcon = { Icon(Icons.Default.Search, null, tint = Cyan) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, "Clear", tint = MutedLavender) }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(26.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Cyan,
                    unfocusedBorderColor = GlassBorder,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    cursorColor = Cyan,
                    focusedContainerColor = GlassSurface,
                    unfocusedContainerColor = GlassSurface
                )
            )

            Spacer(Modifier.height(10.dp))

            // Tab Content Frame
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
                        songs = displayedSongs,
                        allSongs = songs,
                        mode = libraryMode,
                        onMode = { libraryMode = it },
                        listState = listState,
                        favorites = favorites,
                        onPlay = { playSong(it, displayedSongs) },
                        onFavorite = { song ->
                            favorites = if (song.id.toString() in favorites) favorites - song.id.toString()
                            else favorites + song.id.toString()
                            saveFavorites()
                        },
                        onAdd = { addToPlaylistSong = it },
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

        // Floating Sticky Glass Bottom Navigation Bar
        FloatingBottomNavBar(
            currentTab = currentTab,
            onSelect = { currentTab = it },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 18.dp, vertical = 12.dp)
        )

        // Full Screen Player Overlay
        if (showPlayer && currentSong != null) {
            FullPlayer(
                song = currentSong!!,
                isPlaying = isPlaying,
                position = position,
                duration = duration,
                shuffle = shuffle,
                repeatMode = repeatMode,
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

        // Playlist Dialogs
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
                            focusedBorderColor = Cyan
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
                    }) { Text("Create", color = Cyan) }
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
                                Text(name, color = Cyan)
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
private fun FloatingBottomNavBar(
    currentTab: MainTab,
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
                            tint = if (selected) Cyan else MutedLavender.copy(alpha = 0.6f),
                            modifier = Modifier.size(24.dp)
                        )
                        if (selected) {
                            Box(
                                Modifier
                                    .padding(top = 3.dp)
                                    .size(4.dp)
                                    .clip(CircleShape)
                                    .background(Purple)
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
        // Quick Stats Glass Card
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
                    Text("${songs.size}", color = Cyan, fontSize = 24.sp, fontWeight = FontWeight.Black)
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
        Text("RECENTLY PLAYED", color = Cyan, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Spacer(Modifier.height(8.dp))

        if (recentSongs.isEmpty()) {
            Text("No recently played songs yet.", color = MutedLavender, fontSize = 12.sp)
        } else {
            recentSongs.forEach { song ->
                SongCard(song, song.id.toString() in favorites, { onPlay(song) }, {}, {})
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
                SongCard(song, true, { onPlay(song) }, {}, {})
                Spacer(Modifier.height(6.dp))
            }
        }
    }
}

@Composable
private fun LibraryScreen(
    songs: List<Song>,
    allSongs: List<Song>,
    mode: String,
    onMode: (String) -> Unit,
    listState: LazyListState,
    favorites: Set<String>,
    onPlay: (Song) -> Unit,
    onFavorite: (Song) -> Unit,
    onAdd: (Song) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Songs", "Artists", "Albums", "Folders", "Favorites", "Recent", "Playlist").forEach {
                FilterChip(
                    selected = mode == it,
                    onClick = { onMode(it) },
                    label = { Text(it) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Purple.copy(alpha = 0.35f),
                        selectedLabelColor = Cyan,
                        labelColor = MutedLavender
                    )
                )
            }
        }

        Text(
            "${songs.size} TRACKS • ${allSongs.size} IN REPOSITORY",
            color = MutedLavender,
            fontSize = 11.sp,
            modifier = Modifier.padding(vertical = 7.dp)
        )

        if (songs.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    if (allSongs.isEmpty()) "No local music found. Grant audio permission."
                    else "No matching tracks in this view.",
                    color = MutedLavender
                )
            }
        } else {
            Row(Modifier.weight(1f)) {
                LazyColumn(
                    Modifier.weight(1f),
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(songs, key = { it.id }) { song ->
                        SongCard(
                            song = song,
                            favorite = song.id.toString() in favorites,
                            onPlay = { onPlay(song) },
                            onFavorite = { onFavorite(song) },
                            onAdd = { onAdd(song) }
                        )
                    }
                }

                // Samsung-Style Draggable Alphabet Quick-Scroller
                SamsungAlphabetScroller(
                    songs = songs,
                    listState = listState,
                    modifier = Modifier.padding(start = 4.dp)
                )
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
            .width(36.dp)
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
                    color = if (isSelected) Cyan else MutedLavender.copy(alpha = 0.5f),
                    fontSize = 10.sp,
                    fontWeight = if (isSelected) FontWeight.Black else FontWeight.Normal
                )
            }
        }

        // Bubbly Floating Magnifier Badge
        activeLetter?.let { letter ->
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = (-42).dp)
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(Brush.radialGradient(listOf(Cyan, Purple)))
                    .border(2.dp, Color.White, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = letter.toString(),
                    color = DeepBlack,
                    fontSize = 24.sp,
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
    onAdd: () -> Unit
) {
    Surface(
        color = GlassSurface,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, GlassBorder),
        modifier = Modifier.fillMaxWidth().clickable { onPlay() }
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            AlbumThumb(song.albumArtUri, Modifier.size(50.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(song.title, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${song.artist} • ${song.album}", color = MutedLavender, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(song.folder, color = Cyan.copy(alpha = 0.8f), fontSize = 10.sp)
            }
            IconButton(onClick = onFavorite) {
                Icon(
                    if (favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    null,
                    tint = if (favorite) Color(0xFFFF5286) else MutedLavender
                )
            }
            IconButton(onClick = onAdd) {
                Icon(Icons.Default.PlaylistAdd, null, tint = Cyan)
            }
        }
    }
}

@Composable
private fun AlbumThumb(uri: String?, modifier: Modifier = Modifier) {
    if (uri != null) {
        AsyncImage(model = uri, contentDescription = "Artwork", modifier = modifier.clip(RoundedCornerShape(14.dp)))
    } else {
        Box(
            modifier.background(Brush.linearGradient(listOf(Purple, Cyan)), RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.MusicNote, null, tint = Color.White)
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
    onClose: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
    onShuffle: () -> Unit,
    onRepeat: () -> Unit,
    onQueue: () -> Unit
) {
    val animatedProgress = if (duration > 0) position.toFloat() / duration else 0f

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF1B112D), DeepBlack)))) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.Default.KeyboardArrowDown, null, tint = Color.White) }
                Text("NOW PLAYING", color = MutedLavender, modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                IconButton(onClick = onQueue) { Icon(Icons.Default.QueueMusic, null, tint = Cyan) }
            }

            Spacer(Modifier.height(20.dp))

            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(280.dp)) {
                    drawCircle(Cyan.copy(alpha = 0.12f), style = androidx.compose.ui.graphics.drawscope.Stroke(4.dp.toPx()))
                    drawArc(
                        brush = Brush.sweepGradient(listOf(Purple, Cyan, Purple)),
                        startAngle = -90f,
                        sweepAngle = 360f * animatedProgress,
                        useCenter = false,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(8.dp.toPx(), cap = StrokeCap.Round)
                    )
                }
                AlbumThumb(song.albumArtUri, Modifier.size(240.dp).clip(RoundedCornerShape(32.dp)))
                if (isPlaying) MiniVisualizer(Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp))
            }

            Spacer(Modifier.height(18.dp))
            Text(song.title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(song.artist, color = MutedLavender, fontSize = 14.sp)

            Spacer(Modifier.height(18.dp))
            Slider(
                value = if (duration > 0) position.toFloat().coerceIn(0f, duration.toFloat()) else 0f,
                onValueChange = { onSeek(it.toLong()) },
                valueRange = 0f..duration.coerceAtLeast(1L).toFloat(),
                colors = SliderDefaults.colors(thumbColor = Cyan, activeTrackColor = Purple)
            )

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(timeText(position), color = MutedLavender, fontSize = 12.sp)
                Text(timeText(duration), color = MutedLavender, fontSize = 12.sp)
            }

            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onShuffle) { Icon(Icons.Default.Shuffle, null, tint = if (shuffle) Cyan else MutedLavender) }
                IconButton(onClick = onPrevious) { Icon(Icons.Default.SkipPrevious, null, tint = Color.White, modifier = Modifier.size(36.dp)) }
                Surface(
                    shape = CircleShape,
                    color = VioletGlow,
                    border = BorderStroke(2.dp, Cyan),
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
                        tint = if (repeatMode != Player.REPEAT_MODE_OFF) Cyan else MutedLavender
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun MiniVisualizer(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "visualizer")
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(20) { i ->
            val h by transition.animateFloat(
                initialValue = 4f,
                targetValue = (12 + (i * 9 % 24)).toFloat(),
                animationSpec = infiniteRepeatable(tween(260 + i * 18, easing = FastOutSlowInEasing), RepeatMode.Reverse),
                label = "bar$i"
            )
            Box(Modifier.width(3.dp).height(h.dp).clip(CircleShape).background(Brush.verticalGradient(listOf(Cyan, Purple))))
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
                    Icon(Icons.Default.QueueMusic, null, tint = Cyan)
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

        // Device Output Section
        Text("ACTIVE OUTPUT ROUTING", color = Cyan, fontWeight = FontWeight.Bold, fontSize = 13.sp)
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
                        tint = Cyan
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

        // Sleep Timer Section
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
                Text("Remaining: %02d:%02d".format(timerLeft / 60, timerLeft % 60), color = Cyan, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(10.dp))
                TextButton(onClick = onCancelTimer) { Text("Cancel", color = Color(0xFFFF5286)) }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Library Index
        Text("MEDIA STORE INDEX", color = Cyan, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Spacer(Modifier.height(6.dp))
        Text("$songCount tracks loaded from device storage.", color = MutedLavender, fontSize = 12.sp)
        Spacer(Modifier.height(6.dp))
        Button(onClick = onRescan, colors = ButtonDefaults.buttonColors(containerColor = GlassSurface)) {
            Icon(Icons.Default.Refresh, null, tint = Cyan)
            Spacer(Modifier.width(6.dp))
            Text("Rescan Local Music", color = Cyan)
        }

        Spacer(Modifier.height(20.dp))
        Text("SONIQ v0.2.0 • Premium Glass Edition", color = MutedLavender.copy(alpha = 0.5f), fontSize = 11.sp)
    }
}

private fun timeText(ms: Long): String {
    val s = (ms.coerceAtLeast(0L) / 1000).toInt()
    return "%d:%02d".format(s / 60, s % 60)
}
