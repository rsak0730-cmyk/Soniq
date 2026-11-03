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
private fun FloatingMiniPlayer(
    song: Song,
    isPlaying: Boolean,
    progress: Float,
    primaryColor: Color,
    secondaryColor: Color,
    onOpen: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit
) {
    Surface(
        modifier = Modifier
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
            .aspectRatio(1f)
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
            val reactiveGlow = if (isPlaying) (fftBass * 2.5.dp.toPx()) else 0f
            drawCircle(
                brush = Brush.sweepGradient(
                    listOf(secondaryColor.copy(alpha = 0.85f), primaryColor.copy(alpha = 0.85f), secondaryColor.copy(alpha = 0.85f))
                ),
                radius = r - 2.dp.toPx(),
                center = c,
                style = Stroke((2.dp.toPx() + reactiveGlow).coerceAtMost(6.dp.toPx()))
            )
            val wavePath = Path()
            val amp = if (isPlaying) (12.dp.toPx() + fftBass * 16.dp.toPx()) else 5.dp.toPx()
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
        SongThumb(
            song = song,
            modifier = Modifier
                .fillMaxSize(0.96f)
                .aspectRatio(1f)
                .clip(CircleShape)
        )
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
    val view = LocalView.current
    var showLyrics by remember { mutableStateOf(false) }
    var lyrics by remember { mutableStateOf<List<LyricLine>>(emptyList()) }
    var isLoadingLyrics by remember { mutableStateOf(false) }
    val lyricsListState = rememberLazyListState()

    LaunchedEffect(song.id, song.title) {
        isLoadingLyrics = true
        lyrics = LyricsEngine.loadLyricsForSong(context, song)
        isLoadingLyrics = false
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
    val density = LocalDensity.current

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
                                "No synchronized lyrics found for '${song.title}'.",
                                color = MutedLavender,
                                fontSize = 13.sp,
                                textAlign = TextAlign.Center
                            )
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
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    var canvasCenter by remember { mutableStateOf(Offset.Zero) }
                    var baseRadiusPx by remember { mutableFloatStateOf(0f) }
                    var isDraggingSeeker by remember { mutableStateOf(false) }
                    val touchZoneTolerancePx = with(density) { 26.dp.toPx() }

                    Canvas(
                        Modifier
                            .size(265.dp)
                            .aspectRatio(1f)
                            .pointerInput(duration) {
                                detectTapGestures { tapOffset ->
                                    val dx = tapOffset.x - canvasCenter.x
                                    val dy = tapOffset.y - canvasCenter.y
                                    val dist = hypot(dx, dy)
                                    if (abs(dist - baseRadiusPx) <= touchZoneTolerancePx && duration > 0) {
                                        var angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat() + 90f
                                        if (angle < 0) angle += 360f
                                        val seekPercent = (angle / 360f).coerceIn(0f, 1f)
                                        onSeek((seekPercent * duration).toLong())
                                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                    }
                                }
                            }
                            .pointerInput(duration) {
                                detectDragGestures(
                                    onDragStart = { startOffset ->
                                        val dx = startOffset.x - canvasCenter.x
                                        val dy = startOffset.y - canvasCenter.y
                                        val dist = hypot(dx, dy)
                                        isDraggingSeeker = abs(dist - baseRadiusPx) <= touchZoneTolerancePx
                                    },
                                    onDragEnd = { isDraggingSeeker = false },
                                    onDragCancel = { isDraggingSeeker = false },
                                    onDrag = { change: PointerInputChange, _ ->
                                        if (isDraggingSeeker && duration > 0) {
                                            change.consume()
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
                                )
                            }
                    ) {
                        canvasCenter = center
                        val strokeWidth = 3.2.dp.toPx()
                        val outerGlowWidth = 7.dp.toPx()
                        val baseRadius = (size.minDimension - 24.dp.toPx()) / 2f
                        baseRadiusPx = baseRadius
                        drawCircle(
                            color = Color(0x352B194E),
                            radius = baseRadius,
                            center = center,
                            style = Stroke(2.dp.toPx(), cap = StrokeCap.Round)
                        )
                        val sweepAngle = 360f * progress
                        if (sweepAngle > 0f) {
                            val wavePath = Path()
                            val steps = (sweepAngle * 1.5f).toInt().coerceAtLeast(8)
                            val waveAmplitude = if (isPlaying) (1.1.dp.toPx() + fftBass * 1.6.dp.toPx()) else 0.7.dp.toPx()
                            val waveFreq = 10.0
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
                                        primaryAccent.copy(alpha = 0.25f * pulseGlow),
                                        secondaryAccent.copy(alpha = 0.35f * pulseGlow),
                                        primaryAccent.copy(alpha = 0.25f * pulseGlow)
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
                            val dropletRadius = (3.5.dp.toPx() + fftBass * 1.4.dp.toPx())
                            drawCircle(color = secondaryAccent.copy(alpha = 0.4f * pulseGlow), radius = dropletRadius + 3.dp.toPx(), center = Offset(tipX, tipY))
                            drawCircle(color = Color.White, radius = dropletRadius, center = Offset(tipX, tipY))
                            drawCircle(color = secondaryAccent, radius = (dropletRadius * 0.5f), center = Offset(tipX, tipY))
                        }
                    }

                    LiquidOrbWaveformCenterpiece(
                        song = song,
                        isPlaying = isPlaying,
                        primaryColor = primaryAccent,
                        secondaryColor = secondaryAccent,
                        gyroPitch = gyroPitch,
                        gyroRoll = gyroRoll,
                        modifier = Modifier.size(195.dp)
                    )

                    if (isPlaying) {
                        MiniVisualizer(
                            accent1 = secondaryAccent,
                            accent2 = primaryAccent,
                            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp)
                        )
                    }
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
