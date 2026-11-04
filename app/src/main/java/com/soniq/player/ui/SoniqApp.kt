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
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.palette.graphics.Palette
import com.google.common.util.concurrent.MoreExecutors
import com.soniq.player.audio.AudioDeviceMonitor
import com.soniq.player.audio.AudioEffectsBridge
import com.soniq.player.audio.AudioFingerprintFixer
import com.soniq.player.audio.AudioOutput
import com.soniq.player.audio.GyroscopeParallaxEngine
import com.soniq.player.data.*
import com.soniq.player.playback.PlaybackService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SoniqApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("soniq_user_data", 0) }
    var songs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var currentTab by remember { mutableStateOf(MainTab.HOME) }
    var controller by remember { mutableStateOf<MediaController?>(null) }
    var currentSong by remember { mutableStateOf<Song?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var shuffle by remember { mutableStateOf(false) }
    var repeatMode by remember { mutableIntStateOf(Player.REPEAT_MODE_OFF) }
    var showPlayer by remember { mutableStateOf(false) }
    var showDeepOceanLockScreen by remember { mutableStateOf(false) }

    var dynamicPrimaryColor by remember { mutableStateOf(NeonPurple) }
    var dynamicSecondaryColor by remember { mutableStateOf(ElectricCyan) }
    var outputs by remember { mutableStateOf<List<AudioOutput>>(emptyList()) }
    var favorites by remember { mutableStateOf<Set<String>>(prefs.getStringSet("favorites", emptySet())?.toSet() ?: emptySet()) }
    var recents by remember { mutableStateOf<List<String>>(prefs.getString("recents_ordered", "")?.split("|")?.filter { it.isNotBlank() } ?: emptyList()) }
    var searchHistory by remember { mutableStateOf<List<String>>(prefs.getString("search_history_ordered", "")?.split("|")?.filter { it.isNotBlank() }?.take(6) ?: emptyList()) }

    var isBatchFixing by remember { mutableStateOf(false) }
    var batchCurrent by remember { mutableIntStateOf(0) }
    var batchTotal by remember { mutableIntStateOf(0) }
    var batchTrackName by remember { mutableStateOf("") }
    var batchSummary by remember { mutableStateOf<String?>(null) }

    var duplicateGroups by remember { mutableStateOf<List<DuplicateGroup>?>(null) }
    var isScanningDuplicates by remember { mutableStateOf(false) }

    var playlists by remember { mutableStateOf<List<String>>(prefs.getStringSet("playlists", setOf("Favorites", "Workout", "Late Night"))?.toList()?.sorted() ?: listOf("Favorites")) }
    var selectedPlaylist by remember { mutableStateOf("Favorites") }
    var playlistSongs by remember { mutableStateOf<Set<String>>(prefs.getString("playlist_Favorites", "")?.split("|")?.filter { it.isNotBlank() }?.toSet() ?: emptySet()) }
    var showCreatePlaylist by remember { mutableStateOf(false) }
    var newPlaylistName by remember { mutableStateOf("") }
    var addToPlaylistSong by remember { mutableStateOf<Song?>(null) }

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
    val gyroEngine = remember { GyroscopeParallaxEngine(context) }

    var currentSpeedMode by remember { mutableStateOf("Normal") }
    var slowedSpeedFactor by remember { mutableFloatStateOf(0.85f) }
    var reverbStrengthFactor by remember { mutableFloatStateOf(0.70f) }

    DisposableEffect(Unit) {
        gyroEngine.start()
        onDispose { gyroEngine.stop() }
    }

    BackHandler(enabled = showDeepOceanLockScreen || showPlayer || selectedFilterKey != null || currentTab != MainTab.HOME) {
        when {
            showDeepOceanLockScreen -> showDeepOceanLockScreen = false
            showPlayer -> showPlayer = false
            selectedFilterKey != null -> selectedFilterKey = null
            currentTab != MainTab.HOME -> currentTab = MainTab.HOME
        }
    }

    fun saveFavorites() { prefs.edit().putStringSet("favorites", favorites).apply() }
    fun saveRecents(updated: List<String>) { prefs.edit().putString("recents_ordered", updated.joinToString("|")).apply() }

    fun playSong(song: Song, source: List<Song> = songs, openFullPlayer: Boolean = true) {
        val c = controller ?: return
        val queue = source.ifEmpty { listOf(song) }
        val index = queue.indexOfFirst { it.id == song.id }.coerceAtLeast(0)
        c.setMediaItems(queue.map { s ->
            MediaItem.Builder()
                .setMediaId(s.id.toString())
                .setUri(s.uri)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(s.title)
                        .setArtist(s.artist)
                        .setAlbumTitle(s.album)
                        .setArtworkUri(s.albumArtUri?.let(Uri::parse))
                        .build()
                ).build()
        }, index, 0L)
        c.prepare()
        c.play()
        currentSong = song
        isPlaying = true
        duration = song.durationMs
        position = 0L
        val updatedRecents = (listOf(song.id.toString()) + recents.filterNot { it == song.id.toString() }).take(30)
        recents = updatedRecents
        saveRecents(updatedRecents)
        if (openFullPlayer) showPlayer = true
    }

    fun triggerBatchFix() {
        if (isBatchFixing) return
        val unknownList = songs.filter { AudioFingerprintFixer.isSongUnknown(it) }
        if (unknownList.isEmpty()) {
            batchSummary = "All tracks in your library are already identified!"
            return
        }

        scope.launch {
            isBatchFixing = true
            batchTotal = unknownList.size
            batchCurrent = 0

            val count = AudioFingerprintFixer.batchFixAllUnknown(
                context = context,
                songs = songs,
                onProgress = { cur, tot, name ->
                    batchCurrent = cur
                    batchTotal = tot
                    batchTrackName = name
                },
                onSongFixed = { song, fix ->
                    val artUri = fix.albumArtUrl ?: song.albumArtUri
                    saveMetadataOverride(prefs, song.id, fix.title, fix.artist, fix.album, artUri)

                    songs = songs.map {
                        if (it.id == song.id) it.copy(
                            title = fix.title,
                            artist = fix.artist,
                            album = fix.album,
                            albumArtUri = artUri
                        ) else it
                    }

                    if (currentSong?.id == song.id) {
                        currentSong = currentSong?.copy(
                            title = fix.title,
                            artist = fix.artist,
                            album = fix.album,
                            albumArtUri = artUri
                        )
                    }
                }
            )

            isBatchFixing = false
            batchSummary = "Auto-fix complete! Successfully matched and updated $count songs."
        }
    }

    fun applyPlaybackSettings(mode: String, speed: Float, reverb: Float) {
        val c = controller ?: return
        currentSpeedMode = mode
        runCatching {
            when (mode) {
                "Nightcore" -> {
                    c.playbackParameters = PlaybackParameters(1.25f, 1.25f)
                    if (AudioEffectsBridge.isAvailable() && AudioEffectsBridge.virtualizerSupported()) {
                        AudioEffectsBridge.setVirtualizerStrength(0)
                    }
                }
                "Slowed" -> {
                    c.playbackParameters = PlaybackParameters(speed.coerceIn(0.5f, 1.5f), speed.coerceIn(0.5f, 1.5f))
                    if (AudioEffectsBridge.isAvailable() && AudioEffectsBridge.virtualizerSupported()) {
                        AudioEffectsBridge.setVirtualizerStrength((reverb * 1000f).roundToInt().coerceIn(0, 1000))
                    }
                }
                else -> {
                    c.playbackParameters = PlaybackParameters(1.0f, 1.0f)
                    if (AudioEffectsBridge.isAvailable() && AudioEffectsBridge.virtualizerSupported()) {
                        AudioEffectsBridge.setVirtualizerStrength(0)
                    }
                }
            }
        }
    }

    fun launchPartyMode() {
        try {
            val intent = Intent("com.samsung.android.settings.BLUETOOTH_CASTING_SETTINGS").apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            runCatching {
                val fallback = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(fallback)
            }
        }
    }

    LaunchedEffect(currentSong?.id, currentSong?.albumArtUri) {
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
        val loaded = runCatching { MusicRepository(context).loadSongs() }.getOrDefault(emptyList())
        songs = applySavedMetadataOverrides(loaded, prefs)
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
                        val idStr = item?.mediaId
                        val title = item?.mediaMetadata?.title?.toString()
                        currentSong = songs.firstOrNull { it.id.toString() == idStr }
                            ?: songs.firstOrNull { it.title == title }
                            ?: currentSong
                    }
                    override fun onPlaybackStateChanged(state: Int) {
                        duration = c.duration.coerceAtLeast(0L)
                    }
                })
            }
        }, MoreExecutors.directExecutor())
        onDispose { MediaController.releaseFuture(future) }
    }

    LaunchedEffect(controller, songs) {
        while (true) {
            controller?.let { c ->
                position = c.currentPosition.coerceAtLeast(0L)
                val playerDur = c.duration
                if (playerDur > 0) duration = playerDur
                isPlaying = c.isPlaying
                if (c.currentMediaItem != null && currentSong == null && songs.isNotEmpty()) {
                    val idStr = c.currentMediaItem?.mediaId
                    val title = c.currentMediaItem?.mediaMetadata?.title?.toString()
                    currentSong = songs.firstOrNull { it.id.toString() == idStr }
                        ?: songs.firstOrNull { it.title == title }
                }
            }
            delay(100)
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

    FluidTouchCanvas(rippleColor = dynamicSecondaryColor) {
        Box(Modifier.fillMaxSize()) {
            LiquidWaterWaveBackground(
                isPlaying = isPlaying,
                primaryColor = dynamicPrimaryColor,
                secondaryColor = dynamicSecondaryColor,
                pitch = gyroEngine.pitch,
                roll = gyroEngine.roll
            )
            val bottomPadding = if (currentSong != null) 154.dp else 86.dp
            Column(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .padding(bottom = bottomPadding)
                    .padding(horizontal = 16.dp)
            ) {
                // Header
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
                            letterSpacing = 1.4.sp
                        )
                        Text("Liquid Gyro Acoustics • Studio Suite", color = MutedLavender, fontSize = 11.sp)
                    }
                    Surface(
                        color = GlassPill,
                        shape = RoundedCornerShape(20.dp),
                        border = BorderStroke(1.dp, GlassBorder),
                        modifier = Modifier.clickable { launchPartyMode() }
                    ) {
                        Row(
                            Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Headphones, contentDescription = "Party Share", tint = dynamicSecondaryColor, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Party", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                // Search Bar
                AnimatedVisibility(visible = currentTab == MainTab.HOME || currentTab == MainTab.LIBRARY) {
                    Column {
                        OutlinedTextField(
                            value = query,
                            onValueChange = {
                                query = it
                                if (it.isNotBlank() && currentTab == MainTab.HOME) {
                                    currentTab = MainTab.LIBRARY
                                }
                                if (it.trim().length >= 3) {
                                    val clean = it.trim()
                                    searchHistory = (listOf(clean) + searchHistory.filterNot { old -> old.equals(clean, true) }).take(6)
                                    prefs.edit().putString("search_history_ordered", searchHistory.joinToString("|")).apply()
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
                                    IconButton(onClick = { query = "" }) {
                                        Icon(Icons.Default.Close, "Clear", tint = MutedLavender)
                                    }
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

                        if (query.isEmpty() && searchHistory.isNotEmpty() && currentTab == MainTab.LIBRARY) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState())
                                    .padding(top = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.History, null, tint = MutedLavender.copy(alpha = 0.6f), modifier = Modifier.size(14.dp))
                                searchHistory.forEach { term ->
                                    SuggestionChip(
                                        onClick = { query = term },
                                        label = { Text(term, fontSize = 11.sp, color = MutedLavender) },
                                        shape = RoundedCornerShape(14.dp),
                                        border = BorderStroke(0.8.dp, GlassBorder)
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }

                AnimatedContent(
                    targetState = currentTab,
                    transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(180)) },
                    label = "screen_transition"
                ) { target ->
                    when (target) {
                        MainTab.HOME -> HomeScreen(
                            songs = songs,
                            recents = recents,
                            favorites = favorites,
                            onPlay = { song, list -> playSong(song, list) },
                            onFavorite = { song ->
                                favorites = if (song.id.toString() in favorites) favorites - song.id.toString()
                                else favorites + song.id.toString()
                                saveFavorites()
                            },
                            onAddToPlaylist = { addToPlaylistSong = it },
                            onAddToAlbum = { addToAlbumSong = it },
                            onEdit = { editTagSong = it },
                            onNavigateLibrary = { mode ->
                                libraryMode = mode
                                selectedFilterKey = null
                                currentTab = MainTab.LIBRARY
                            }
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
                            playlistName = selectedPlaylist,
                            playlistSongs = playlistSongs,
                            customAlbums = customAlbums,
                            getCustomAlbumSongs = { albumName ->
                                val raw = prefs.getString("album_$albumName", "") ?: ""
                                raw.split("|").filter { it.isNotBlank() }.toSet()
                            },
                            onCreateAlbum = { showCreateAlbum = true },
                            onAutoFixAll = { triggerBatchFix() },
                            onPlay = { song, list -> playSong(song, list) },
                            onFavorite = { song ->
                                favorites = if (song.id.toString() in favorites) favorites - song.id.toString()
                                else favorites + song.id.toString()
                                saveFavorites()
                            },
                            onAddToPlaylist = { addToPlaylistSong = it },
                            onAddToAlbum = { addToAlbumSong = it },
                            onEdit = { editTagSong = it },
                            modifier = Modifier.fillMaxSize()
                        )
                        MainTab.EQUALIZER -> EqualizerScreen(Modifier.fillMaxSize())
                        MainTab.PLAYLISTS -> PlaylistScreen(
                            playlists = playlists,
                            selected = selectedPlaylist,
                            allSongs = songs,
                            getPlaylistTrackIds = { name ->
                                if (name == "Favorites") favorites
                                else prefs.getString("playlist_$name", "")?.split("|")?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
                            },
                            onOpen = { name ->
                                selectedPlaylist = name
                                playlistSongs = if (name == "Favorites") favorites
                                else prefs.getString("playlist_$name", "")?.split("|")?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
                                libraryMode = "Playlist"
                                selectedFilterKey = null
                                currentTab = MainTab.LIBRARY
                            },
                            onPlayAll = { name ->
                                val ids = if (name == "Favorites") favorites
                                else prefs.getString("playlist_$name", "")?.split("|")?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
                                val list = songs.filter { it.id.toString() in ids }
                                if (list.isNotEmpty()) playSong(list.first(), list)
                            },
                            onDelete = { name ->
                                if (name != "Favorites") {
                                    playlists = playlists - name
                                    prefs.edit().putStringSet("playlists", playlists.toSet()).remove("playlist_$name").apply()
                                }
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
                            isScanningDuplicates = isScanningDuplicates,
                            onCustomTimerChange = { customTimer = it },
                            onSetTimer = { timerMinutes = it },
                            onCancelTimer = { timerMinutes = 0 },
                            onRescan = {
                                val loaded = MusicRepository(context).loadSongs()
                                songs = applySavedMetadataOverrides(loaded, prefs)
                                Toast.makeText(context, "Rescanned ${songs.size} tracks", Toast.LENGTH_SHORT).show()
                            },
                            onAutoFixAll = { triggerBatchFix() },
                            onScanDuplicates = {
                                if (!isScanningDuplicates) {
                                    scope.launch {
                                        isScanningDuplicates = true
                                        duplicateGroups = DuplicateCleanerEngine.findDuplicates(context, songs)
                                        isScanningDuplicates = false
                                    }
                                }
                            },
                            onLaunchDualAudio = { launchPartyMode() },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }

            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (currentSong != null && !showPlayer && !showDeepOceanLockScreen) {
                    FloatingMiniPlayer(
                        song = currentSong!!,
                        isPlaying = isPlaying,
                        progress = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f,
                        primaryColor = dynamicPrimaryColor,
                        secondaryColor = dynamicSecondaryColor,
                        onOpen = { showPlayer = true },
                        onPlayPause = { controller?.let { if (it.isPlaying) it.pause() else it.play() } },
                        onNext = { controller?.seekToNextMediaItem() }
                    )
                }
                FloatingBottomNavBar(
                    currentTab = currentTab,
                    activeColor = dynamicSecondaryColor,
                    onSelect = { currentTab = it }
                )
            }

            // Main Now Playing Screen
            if (showPlayer && currentSong != null && !showDeepOceanLockScreen) {
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
                                if (playerOffsetY > 340f) {
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
                        speedMode = currentSpeedMode,
                        slowedSpeedFactor = slowedSpeedFactor,
                        reverbStrengthFactor = reverbStrengthFactor,
                        onSlowedSpeedChange = { speed ->
                            slowedSpeedFactor = speed
                            applyPlaybackSettings("Slowed", speed, reverbStrengthFactor)
                        },
                        onReverbStrengthChange = { rev ->
                            reverbStrengthFactor = rev
                            applyPlaybackSettings("Slowed", slowedSpeedFactor, rev)
                        },
                        onSpeedModeChange = { mode ->
                            applyPlaybackSettings(mode, slowedSpeedFactor, reverbStrengthFactor)
                        },
                        isFavorite = currentSong!!.id.toString() in favorites,
                        primaryAccent = dynamicPrimaryColor,
                        secondaryAccent = dynamicSecondaryColor,
                        gyroPitch = gyroEngine.pitch,
                        gyroRoll = gyroEngine.roll,
                        onClose = { showPlayer = false },
                        onOpenLockScreen = { showDeepOceanLockScreen = true },
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
                        onToggleFavorite = {
                            val id = currentSong!!.id.toString()
                            favorites = if (id in favorites) favorites - id else favorites + id
                            saveFavorites()
                        },
                        onLaunchParty = { launchPartyMode() },
                        onOpenEqualizer = {
                            showPlayer = false
                            currentTab = MainTab.EQUALIZER
                        }
                    )
                }
            }

            // Deep Ocean Lock-Screen View (#18)
            if (showDeepOceanLockScreen && currentSong != null) {
                DeepOceanLockScreenView(
                    song = currentSong!!,
                    isPlaying = isPlaying,
                    position = position,
                    duration = duration,
                    shuffle = shuffle,
                    repeatMode = repeatMode,
                    gyroPitch = gyroEngine.pitch,
                    gyroRoll = gyroEngine.roll,
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
                    onCloseLockScreen = { showDeepOceanLockScreen = false }
                )
            }

            duplicateGroups?.let { groups ->
                AlertDialog(
                    onDismissRequest = { duplicateGroups = null },
                    containerColor = Color(0xFF1E1436),
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CleaningServices, null, tint = ElectricCyan)
                            Spacer(Modifier.width(8.dp))
                            Text("Duplicate & Bitrate Cleaner", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }
                    },
                    text = {
                        if (groups.isEmpty()) {
                            Text("No duplicate or low-bitrate copies detected in your library!", color = MutedLavender)
                        } else {
                            LazyColumn(modifier = Modifier.heightIn(max = 350.dp)) {
                                items(groups) { group ->
                                    Column(Modifier.padding(vertical = 6.dp)) {
                                        Text(group.cleanTitle, color = ElectricCyan, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                        group.songs.forEach { item ->
                                            Row(
                                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    "${item.song.folder} • ${item.bitrateKbps} kbps",
                                                    color = if (item.bitrateKbps >= 256) Color(0xFF50FA7B) else MutedLavender,
                                                    fontSize = 11.sp
                                                )
                                                if (item.bitrateKbps < 256) {
                                                    Text(
                                                        "Lower Bitrate",
                                                        color = Color(0xFFFF5286),
                                                        fontSize = 10.sp,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                }
                                            }
                                        }
                                        HorizontalDivider(color = Color(0x3353F6FF), modifier = Modifier.padding(top = 4.dp))
                                    }
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { duplicateGroups = null }) { Text("Close", color = ElectricCyan) }
                    }
                )
            }

            AutoFixBatchDialog(
                isRunning = isBatchFixing,
                current = batchCurrent,
                total = batchTotal,
                currentTrackTitle = batchTrackName,
                summaryMessage = batchSummary,
                onDismissSummary = { batchSummary = null }
            )

            if (showCreatePlaylist) {
                AlertDialog(
                    onDismissRequest = { showCreatePlaylist = false },
                    containerColor = Color(0xFF1E1436),
                    title = { Text("Create New Playlist", color = Color.White) },
                    text = {
                        OutlinedTextField(
                            value = newPlaylistName,
                            onValueChange = { newPlaylistName = it },
                            label = { Text("Playlist Name", color = MutedLavender) },
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
                            val name = newPlaylistName.trim()
                            if (name.isNotEmpty()) {
                                playlists = (playlists + name).distinct().sorted()
                                prefs.edit().putStringSet("playlists", playlists.toSet()).apply()
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
                    containerColor = Color(0xFF1E1436),
                    title = { Text("Add to Playlist", color = Color.White) },
                    text = {
                        Column {
                            playlists.forEach { name ->
                                TextButton(
                                    onClick = {
                                        if (name == "Favorites") {
                                            favorites = favorites + song.id.toString()
                                            saveFavorites()
                                        } else {
                                            val raw = prefs.getString("playlist_$name", "") ?: ""
                                            val ids = raw.split("|").filter { it.isNotBlank() }.toSet() + song.id.toString()
                                            prefs.edit().putString("playlist_$name", ids.joinToString("|")).apply()
                                            if (name == selectedPlaylist) playlistSongs = ids
                                        }
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

            if (showCreateAlbum) {
                AlertDialog(
                    onDismissRequest = { showCreateAlbum = false },
                    containerColor = Color(0xFF1E1436),
                    title = { Text("Create Custom Album", color = Color.White) },
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
                            Text("No custom albums created yet. Tap '+ Album' in the Albums tab to create one.", color = MutedLavender)
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

            editTagSong?.let { song ->
                TagEditorAndFingerprintDialog(
                    song = song,
                    onDismiss = { editTagSong = null },
                    onSave = { updatedTitle, updatedArtist, updatedAlbum, newArtUri ->
                        val finalArt = newArtUri ?: song.albumArtUri
                        saveMetadataOverride(prefs, song.id, updatedTitle, updatedArtist, updatedAlbum, finalArt)
                        songs = songs.map {
                            if (it.id == song.id) it.copy(
                                title = updatedTitle,
                                artist = updatedArtist,
                                album = updatedAlbum,
                                albumArtUri = finalArt
                            ) else it
                        }
                        if (currentSong?.id == song.id) {
                            currentSong = currentSong?.copy(
                                title = updatedTitle,
                                artist = updatedArtist,
                                album = updatedAlbum,
                                albumArtUri = finalArt
                            )
                        }
                        editTagSong = null
                    }
                )
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
    onOpenLockScreen: () -> Unit,
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

            // Navigation Bar
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
                    BouncyIconButton(onClick = onOpenLockScreen, size = 36.dp) {
                        Icon(Icons.Default.Lock, "Lock Screen", tint = BioluminescentCyan, modifier = Modifier.size(20.dp))
                    }
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
                isPlaying = isPlaying,
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
