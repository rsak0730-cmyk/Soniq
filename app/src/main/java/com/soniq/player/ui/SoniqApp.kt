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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
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
import org.json.JSONObject
import kotlin.math.roundToInt

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

    BackHandler(enabled = showPlayer || selectedFilterKey != null || currentTab != MainTab.HOME) {
        when {
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
                if (currentSong != null && !showPlayer) {
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
