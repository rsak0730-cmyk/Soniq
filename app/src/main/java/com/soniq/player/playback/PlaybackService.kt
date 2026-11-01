package com.soniq.player.playback

import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.soniq.player.audio.AudioEffectsBridge
import com.soniq.player.audio.RealtimeFftMonitor
import com.soniq.player.audio.SoftwareDspEngine

class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private var noisyReceiver: AudioNoisyReceiver? = null
    private val crossfadeHandler = Handler(Looper.getMainLooper())
    private var currentCrossfadeRunnable: Runnable? = null

    private val listener = object : Player.Listener {
        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            AudioEffectsBridge.attach(audioSessionId)
            RealtimeFftMonitor.attach(audioSessionId)
            SoftwareDspEngine.attachLoudness(audioSessionId)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                true // Auto handle audio focus
            )
            .build()

        player.addListener(listener)
        session = MediaSession.Builder(this, player).build()
        noisyReceiver = AudioNoisyReceiver(this, player).also { it.register() }

        // Trigger smooth volume crossfade near track endings
        setupCrossfadeMonitor(player)
    }

    private fun setupCrossfadeMonitor(player: ExoPlayer) {
        val checkInterval = 500L
        val monitorRunnable = object : Runnable {
            override fun run() {
                val duration = player.duration
                val pos = player.currentPosition
                if (player.isPlaying && duration > 0 && (duration - pos) in 1..4000) {
                    val remainingMs = duration - pos
                    val factor = (remainingMs / 4000f).coerceIn(0.1f, 1f)
                    player.volume = factor
                } else if (player.volume < 1.0f && player.currentPosition < 3000) {
                    val factor = (player.currentPosition / 3000f).coerceIn(0.1f, 1f)
                    player.volume = factor
                } else if (player.volume < 1.0f && duration - pos > 4000) {
                    player.volume = 1.0f
                }
                crossfadeHandler.postDelayed(this, checkInterval)
            }
        }
        crossfadeHandler.post(monitorRunnable)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        crossfadeHandler.removeCallbacksAndMessages(null)
        noisyReceiver?.unregister()
        RealtimeFftMonitor.release()
        SoftwareDspEngine.detach()
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
