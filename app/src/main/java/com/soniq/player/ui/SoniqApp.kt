@file:OptIn(
    androidx.compose.animation.ExperimentalAnimationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class
)

package com.soniq.player.ui

import android.content.ComponentName
import android.media.AudioManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.with
import androidx.compose.foundation.*
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
import androidx.compose.ui.input.pointer.pointerInput
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
import java.util.Locale
import kotlin.math.roundToInt

private val Purple = Color(0xFFB68CFF)
private val Cyan = Color(0xFF67E8F9)
private val Muted = Color(0xFFB9A9D3)
private val Glass = Color(0xAA211832)

@Composable
fun SoniqApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("soniq_user_data", 0) }
    var songs by remember { mutableStateOf(emptyList<Song>()) }
    var query by remember { mutableStateOf("") }
    var tab by remember { mutableStateOf("Library") }
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
    var playlists by remember { mutableStateOf(prefs.getStringSet("playlists", setOf("My playlist"))?.toList()?.sorted() ?: listOf("My playlist")) }
    var selectedPlaylist by remember { mutableStateOf("My playlist") }
    var playlistSongs by remember { mutableStateOf(prefs.getString("playlist_My playlist", "")?.split("|")?.filter { it.isNotBlank() }?.toSet() ?: emptySet()) }
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
        val queue = (source.ifEmpty { songs }).ifEmpty { listOf(song) }
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
            delay(400)
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
    val artists = remember(songs) { songs.map { it.artist }.distinct().sorted() }
    val albums = remember(songs) { songs.map { it.album }.distinct().sorted() }
    val folders = remember(songs) { songs.map { it.folder }.distinct().sorted() }
    val displayedSongs = when (libraryMode) {
        "Artists" -> filtered.filter { it.artist.equals(query, true) || query.isBlank() }
        "Albums" -> filtered.filter { it.album.equals(query, true) || query.isBlank() }
        "Folders" -> filtered.filter { it.folder.contains(query, true) }
        "Favorites" -> filtered.filter { it.id.toString() in favorites }
        "Recent" -> filtered.sortedByDescending { if (it.id.toString() in recents) recents.indexOf(it.id.toString()) else -1 }
        "Playlist" -> filtered.filter { it.id.toString() in playlistSongs }
        else -> filtered
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF090711))) {
        if (isPlaying) {
            val water = rememberInfiniteTransition(label = "water-shimmer")
            val shift by water.animateFloat(0f, 1f, infiniteRepeatable(tween(2800), RepeatMode.Reverse), label = "shift")
            Box(Modifier.fillMaxSize().drawBehind {
                drawRect(Brush.linearGradient(
                    listOf(Color.Transparent, Cyan.copy(alpha = .10f + shift * .12f), Color.Transparent),
                    start = Offset(size.width * shift - 250f, 0f),
                    end = Offset(size.width * shift + 250f, size.height)
                ))
            })
            Box(Modifier.align(Alignment.TopEnd).size(260.dp).blur(95.dp)
                .background(Purple.copy(alpha = .22f + shift * .13f), CircleShape))
            Box(Modifier.align(Alignment.BottomStart).size(240.dp).blur(100.dp)
                .background(Cyan.copy(alpha = .16f + shift * .10f), CircleShape))
        } else {
            // Static, frozen gradient state while playback is paused or stopped.
            Box(Modifier.align(Alignment.TopEnd).size(260.dp).blur(100.dp).background(Purple.copy(alpha = .13f), CircleShape))
            Box(Modifier.align(Alignment.BottomStart).size(240.dp).blur(110.dp).background(Cyan.copy(alpha = .09f), CircleShape))
        }

        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("SONIQ", color = Color.White, fontSize = 29.sp, fontWeight = FontWeight.Black)
                    Text("Your sound. Your space.", color = Muted, fontSize = 12.sp)
                }
                IconButton(onClick = { if (currentSong != null) showPlayer = true else tab = "Library" }) {
                    Icon(Icons.Default.Album, null, tint = Cyan)
                }
                IconButton(onClick = { tab = "Settings" }) {
                    Icon(Icons.Default.Settings, null, tint = Muted)
                }
            }
            Spacer(Modifier.height(12.dp))
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
                placeholder = { Text("Search songs, artists, albums, folders…") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, "Clear") } },
                singleLine = true,
                shape = RoundedCornerShape(24.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Purple, unfocusedBorderColor = Color(0x557D65A8),
                    focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                    cursorColor = Cyan, focusedContainerColor = Glass, unfocusedContainerColor = Glass
                )
            )
            if (query.isBlank() && searchHistory.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    searchHistory.take(4).forEach { old ->
                        SuggestionChip(onClick = { query = old }, label = { Text(old, maxLines = 1) })
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                listOf("Library", "Equalizer", "Devices", "Timer", "Playlists", "Settings").forEach { name ->
                    FilterChip(selected = tab == name, onClick = { tab = name }, label = { Text(name) })
                }
            }
            Spacer(Modifier.height(10.dp))

            AnimatedContent(tab, transitionSpec = { (fadeIn() + scaleIn(initialScale = .98f)) with fadeOut() }, label = "tab") { active ->
                when (active) {
                    "Library" -> LibraryScreen(
                        songs = displayedSongs, allSongs = songs, mode = libraryMode,
                        onMode = { libraryMode = it }, listState = listState,
                        favorites = favorites, onPlay = { playSong(it, displayedSongs) },
                        onFavorite = { song ->
                            favorites = if (song.id.toString() in favorites) favorites - song.id.toString()
                                else favorites + song.id.toString()
                            saveFavorites()
                        }, onAdd = { addToPlaylistSong = it }, modifier = Modifier.fillMaxSize()
                    )
                    "Equalizer" -> EqualizerScreen(Modifier.fillMaxSize())
                    "Devices" -> DeviceScreen(outputs, Modifier.fillMaxSize())
                    "Timer" -> TimerScreen(timerMinutes, timerLeft, customTimer,
                        { customTimer = it }, { timerMinutes = it }, { timerMinutes = 0 }, Modifier.fillMaxSize())
                    "Playlists" -> PlaylistScreen(playlists, selectedPlaylist, songs, playlistSongs,
                        { selectedPlaylist = it
                            playlistSongs = prefs.getString("playlist_$it", "")?.split("|")?.filter(String::isNotBlank)?.toSet() ?: emptySet()
                            libraryMode = "Playlist"; tab = "Library"
                        }, { showCreatePlaylist = true }, Modifier.fillMaxSize())
                    else -> SettingsScreen(songs.size, { songs = MusicRepository(context).loadSongs() }, Modifier.fillMaxSize())
                }
            }
            Spacer(Modifier.height(4.dp))
            if (currentSong != null) {
                TextButton(onClick = { showPlayer = true }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Icon(Icons.Default.GraphicEq, null, tint = Cyan)
                    Spacer(Modifier.width(6.dp))
                    Text("OPEN NOW PLAYING", color = Cyan)
                }
            }
        }
        if (showPlayer && currentSong != null) {
            FullPlayer(
                song = currentSong!!, isPlaying = isPlaying, position = position, duration = duration,
                shuffle = shuffle, repeatMode = repeatMode,
                onClose = { showPlayer = false },
                onPlayPause = { controller?.let { if (it.isPlaying) it.pause() else it.play() } },
                onNext = { controller?.seekToNextMediaItem() },
                onPrevious = { controller?.seekToPreviousMediaItem() },
                onSeek = { controller?.seekTo(it) },
                onShuffle = { shuffle = !shuffle; controller?.shuffleModeEnabled = shuffle },
                onRepeat = { repeatMode = when (repeatMode) {
                    Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                    Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                    else -> Player.REPEAT_MODE_OFF
                }; controller?.repeatMode = repeatMode },
                onQueue = { tab = "Library"; showPlayer = false }
            )
        }
        if (showCreatePlaylist) {
            AlertDialog(
                onDismissRequest = { showCreatePlaylist = false },
                title = { Text("Create playlist") },
                text = { OutlinedTextField(newPlaylistName, { newPlaylistName = it }, label = { Text("Playlist name") }) },
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
                        newPlaylistName = ""; showCreatePlaylist = false
                    }) { Text("Create") }
                },
                dismissButton = { TextButton(onClick = { showCreatePlaylist = false }) { Text("Cancel") } }
            )
        }
        addToPlaylistSong?.let { song ->
            AlertDialog(
                onDismissRequest = { addToPlaylistSong = null },
                title = { Text("Add to playlist") },
                text = {
                    Column {
                        playlists.forEach { name ->
                            TextButton(onClick = {
                                val ids = (prefs.getString("playlist_$name", "") ?: "").split("|").filter(String::isNotBlank).toSet() + song.id.toString()
                                prefs.edit().putString("playlist_$name", ids.joinToString("|")).apply()
                                if (name == selectedPlaylist) playlistSongs = ids
                                addToPlaylistSong = null
                            }) { Text(name) }
                        }
                        TextButton(onClick = { addToPlaylistSong = null }) { Text("Cancel") }
                    }
                },
                confirmButton = {}
            )
        }
    }
}

@Composable
private fun LibraryScreen(
    songs: List<Song>, allSongs: List<Song>, mode: String, onMode: (String) -> Unit,
    listState: LazyListState, favorites: Set<String>, onPlay: (Song) -> Unit,
    onFavorite: (Song) -> Unit, onAdd: (Song) -> Unit, modifier: Modifier = Modifier
) {
    Column(modifier) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Songs", "Artists", "Albums", "Folders", "Favorites", "Recent", "Playlist").forEach {
                FilterChip(selected = mode == it, onClick = { onMode(it) }, label = { Text(it) })
            }
        }
        Text("${songs.size} TRACKS  •  ${allSongs.size} IN LIBRARY", color = Muted, fontSize = 11.sp,
            modifier = Modifier.padding(vertical = 7.dp))
        if (songs.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(if (allSongs.isEmpty()) "No local music found. Grant audio permission and add music to your device."
                    else "No matching tracks in this view.", color = Muted)
            }
        } else {
            Row(Modifier.weight(1f)) {
                LazyColumn(Modifier.weight(1f), state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(songs, key = { it.id }) { song ->
                        SongCard(song, song.id.toString() in favorites, { onPlay(song) },
                            { onFavorite(song) }, { onAdd(song) })
                    }
                }
                AlphabetRail(songs, listState)
            }
        }
    }
}

@Composable
private fun SongCard(song: Song, favorite: Boolean, onPlay: () -> Unit, onFavorite: () -> Unit, onAdd: () -> Unit) {
    Surface(color = Glass, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth().clickable { onPlay() }) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            AlbumThumb(song.albumArtUri, Modifier.size(52.dp))
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(song.title, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${song.artist}  •  ${song.album}", color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(song.folder, color = Cyan.copy(alpha = .75f), fontSize = 10.sp)
            }
            IconButton(onClick = onFavorite) { Icon(if (favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, null, tint = if (favorite) Color(0xFFFF75B5) else Muted) }
            IconButton(onClick = onAdd) { Icon(Icons.Default.PlaylistAdd, null, tint = Cyan) }
        }
    }
}

@Composable
private fun AlbumThumb(uri: String?, modifier: Modifier = Modifier) {
    if (uri != null) {
        AsyncImage(model = uri, contentDescription = "Album artwork", modifier = modifier.clip(RoundedCornerShape(15.dp)))
    } else {
        Box(modifier.background(Brush.linearGradient(listOf(Color(0xFF6E3EC7), Color(0xFF20B9C9))), RoundedCornerShape(15.dp)),
            contentAlignment = Alignment.Center) { Icon(Icons.Default.MusicNote, null, tint = Color.White) }
    }
}

@Composable
private fun AlphabetRail(songs: List<Song>, listState: LazyListState) {
    val scope = rememberCoroutineScope()
    Column(Modifier.width(24.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceEvenly) {
        ('A'..'Z').forEach { letter ->
            var pressed by remember { mutableStateOf(false) }
            val scale by animateFloatAsState(if (pressed) 1.5f else 1f, label = "letter-bubble")
            Text(letter.toString(), color = if (pressed) Cyan else Muted, fontSize = 9.sp,
                modifier = Modifier.graphicsLayer { scaleX = scale; scaleY = scale }
                    .clickable {
                        pressed = true
                        val index = songs.indexOfFirst { it.title.firstOrNull()?.uppercaseChar() == letter }
                        if (index >= 0) scope.launch { listState.animateScrollToItem(index) }
                        scope.launch { delay(180); pressed = false }
                    })
        }
    }
}

@Composable
private fun MiniPlayer(song: Song?, playing: Boolean, position: Long, duration: Long,
                       onExpand: () -> Unit, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    if (song == null) {
        Surface(color = Glass, shape = RoundedCornerShape(22.dp), modifier = modifier) {
            Text("Choose a song to start listening", color = Muted, modifier = Modifier.padding(17.dp))
        }
        return
    }
    Surface(color = Glass, shape = RoundedCornerShape(22.dp), modifier = modifier.clickable(onClick = onExpand)) {
        Column {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                AlbumThumb(song.albumArtUri, Modifier.size(44.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(song.title, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text(song.artist, color = Muted, fontSize = 11.sp)
                }
                IconButton(onClick = onToggle) { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = Color.White) }
                IconButton(onClick = onExpand) { Icon(Icons.Default.OpenInFull, null, tint = Cyan) }
            }
            LinearProgressIndicator(progress = { if (duration > 0) position.toFloat() / duration else 0f },
                modifier = Modifier.fillMaxWidth().height(3.dp), color = Cyan, trackColor = Color.Transparent)
        }
    }
}

@Composable
private fun FullPlayer(
    song: Song, isPlaying: Boolean, position: Long, duration: Long, shuffle: Boolean, repeatMode: Int,
    onClose: () -> Unit, onPlayPause: () -> Unit, onNext: () -> Unit, onPrevious: () -> Unit,
    onSeek: (Long) -> Unit, onShuffle: () -> Unit, onRepeat: () -> Unit, onQueue: () -> Unit
) {
    val animatedProgress = if (duration > 0) position.toFloat() / duration else 0f
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF241538), Color(0xFF090711))))) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.Default.KeyboardArrowDown, null, tint = Color.White) }
                Text("NOW PLAYING", color = Muted, modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
                IconButton(onClick = onQueue) { Icon(Icons.Default.QueueMusic, null, tint = Cyan) }
            }
            Spacer(Modifier.height(22.dp))
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(300.dp)) {
                    drawCircle(Cyan.copy(alpha = .14f), style = androidx.compose.ui.graphics.drawscope.Stroke(3.dp.toPx()))
                    drawArc(Brush.sweepGradient(listOf(Purple, Cyan, Purple)), -90f, 360f * animatedProgress,
                        false, style = androidx.compose.ui.graphics.drawscope.Stroke(7.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round))
                }
                AlbumThumb(song.albumArtUri, Modifier.size(255.dp).clip(RoundedCornerShape(34.dp)))
                if (isPlaying) MiniVisualizer(Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp))
            }
            Spacer(Modifier.height(18.dp))
            Text(song.title, color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(song.artist, color = Muted, fontSize = 14.sp)
            Spacer(Modifier.height(20.dp))
            Slider(value = if (duration > 0) position.toFloat().coerceIn(0f, duration.toFloat()) else 0f,
                onValueChange = { onSeek(it.toLong()) }, valueRange = 0f..duration.coerceAtLeast(1L).toFloat(),
                colors = SliderDefaults.colors(thumbColor = Cyan, activeTrackColor = Purple))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(timeText(position), color = Muted, fontSize = 11.sp)
                Text(timeText(duration), color = Muted, fontSize = 11.sp)
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onShuffle) { Icon(Icons.Default.Shuffle, null, tint = if (shuffle) Cyan else Muted) }
                IconButton(onClick = onPrevious) { Icon(Icons.Default.SkipPrevious, null, tint = Color.White, modifier = Modifier.size(36.dp)) }
                Surface(shape = CircleShape, color = Brush.linearGradient(listOf(Purple, Cyan)).let { Color(0xFF6547A8) },
                    modifier = Modifier.size(70.dp).clickable(onClick = onPlayPause)) {
                    Box(contentAlignment = Alignment.Center) { Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.size(40.dp)) }
                }
                IconButton(onClick = onNext) { Icon(Icons.Default.SkipNext, null, tint = Color.White, modifier = Modifier.size(36.dp)) }
                IconButton(onClick = onRepeat) { Icon(if (repeatMode == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat, null, tint = if (repeatMode != Player.REPEAT_MODE_OFF) Cyan else Muted) }
            }
            Spacer(Modifier.height(18.dp))
        }
    }
}

@Composable
private fun MiniVisualizer(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "visualizer")
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(24) { i ->
            val h by transition.animateFloat(5f, (12 + (i * 7 % 27)).toFloat(),
                infiniteRepeatable(tween(250 + i * 17, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "bar$i")
            Box(Modifier.width(3.dp).height(h.dp).clip(CircleShape).background(Brush.verticalGradient(listOf(Cyan, Purple))))
        }
    }
}

@Composable
private fun DeviceScreen(outputs: List<AudioOutput>, modifier: Modifier = Modifier) {
    Column(modifier.verticalScroll(rememberScrollState()).padding(top = 8.dp)) {
        Text("SMART AUDIO ROUTING", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold)
        Text("SONIQ adapts its effect controls to Android-reported output types. Android/the connected device chooses the active route; this list is not a forced route selector.", color = Muted)
        Spacer(Modifier.height(14.dp))
        outputs.forEach { output ->
            Surface(color = Glass, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(when (output.kind.label) {
                        "Bluetooth" -> Icons.Default.Bluetooth
                        "USB audio" -> Icons.Default.Usb
                        "Wired headphones" -> Icons.Default.Headphones
                        else -> Icons.Default.Speaker
                    }, null, tint = Cyan)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(output.kind.label, color = Color.White, fontWeight = FontWeight.SemiBold)
                        Text(output.name, color = Muted, fontSize = 12.sp)
                    }
                }
            }
        }
        if (outputs.isEmpty()) Text("No output endpoints reported by Android.", color = Muted)
    }
}

@Composable
private fun TimerScreen(minutes: Int, remaining: Int, custom: String, onCustom: (String) -> Unit,
                        onSelect: (Int) -> Unit, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.verticalScroll(rememberScrollState()).padding(top = 8.dp)) {
        Text("SLEEP TIMER", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text("Pause playback after a chosen duration. The countdown runs in the app process; keep SONIQ active for the most reliable timer behavior.", color = Muted)
        Spacer(Modifier.height(18.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            listOf(5, 10, 15, 20, 30, 45, 60, 90).forEach {
                FilterChip(selected = minutes == it, onClick = { onSelect(it) }, label = { Text("${it}m") })
            }
        }
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(custom, onCustom, label = { Text("Custom minutes") }, singleLine = true)
        Button(onClick = { onSelect(custom.toIntOrNull()?.coerceIn(1, 720) ?: 25) }) { Text("Start custom timer") }
        Spacer(Modifier.height(18.dp))
        Text(if (remaining > 0) "Remaining  %02d:%02d".format(remaining / 60, remaining % 60) else "No timer active",
            color = Cyan, fontSize = 23.sp, fontWeight = FontWeight.Bold)
        if (remaining > 0) TextButton(onClick = onCancel) { Text("Cancel timer") }
    }
}

@Composable
private fun PlaylistScreen(playlists: List<String>, selected: String, songs: List<Song>, ids: Set<String>,
                           onOpen: (String) -> Unit, onCreate: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("YOUR PLAYLISTS", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Button(onClick = onCreate) { Icon(Icons.Default.Add, null); Text("New") }
        }
        playlists.forEach { name ->
            Surface(color = Glass, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp).clickable { onOpen(name) }) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.QueueMusic, null, tint = Cyan)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(name, color = Color.White, fontWeight = FontWeight.SemiBold)
                        Text(if (name == selected) "${ids.size} tracks in current playlist" else "Open playlist", color = Muted, fontSize = 11.sp)
                    }
                    Icon(Icons.Default.ChevronRight, null, tint = Muted)
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(songCount: Int, onRescan: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.verticalScroll(rememberScrollState()).padding(top = 8.dp)) {
        Text("SONIQ SETTINGS", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Text("Library", color = Cyan, fontWeight = FontWeight.Bold)
        Text("$songCount tracks indexed from Android MediaStore.", color = Muted)
        Button(onClick = onRescan) { Icon(Icons.Default.Refresh, null); Text("Rescan music library") }
        Spacer(Modifier.height(12.dp))
        Text("Playback", color = Cyan, fontWeight = FontWeight.Bold)
        Text("Background audio uses Android Media3 MediaSessionService. Playback notification and lock-screen controls are supplied by the system media session.", color = Muted)
        Spacer(Modifier.height(12.dp))
        Text("Effects", color = Cyan, fontWeight = FontWeight.Bold)
        Text("Equalizer bands and enhancements depend on the native effects exposed by your phone. SONIQ does not pretend to provide unsupported bands.", color = Muted)
        Spacer(Modifier.height(12.dp))
        Text("SONIQ • build 0.2.0", color = Muted)
    }
}

private fun timeText(ms: Long): String {
    val s = (ms.coerceAtLeast(0L) / 1000).toInt()
    return "%d:%02d".format(s / 60, s % 60)
}
