package com.soniq.player.playback

import android.os.Handler
import android.os.Looper
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.soniq.player.audio.AudioEffectsBridge

class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private val timerHandler = Handler(Looper.getMainLooper())
    private var timerRunnable: Runnable? = null

    private val listener = object : Player.Listener {
        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            AudioEffectsBridge.attach(audioSessionId)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this).build()
        player.addListener(listener)
        session = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    fun setSleepTimer(minutes: Int) {
        timerRunnable?.let(timerHandler::removeCallbacks)
        timerRunnable = null
        if (minutes <= 0) return
        val task = Runnable { session?.player?.pause(); timerRunnable = null }
        timerRunnable = task
        timerHandler.postDelayed(task, minutes * 60_000L)
    }

    override fun onDestroy() {
        timerRunnable?.let(timerHandler::removeCallbacks)
        session?.let {
            it.player.removeListener(listener)
            it.player.release()
            it.release()
        }
        AudioEffectsBridge.release()
        session = null
        super.onDestroy()
    }
}
