package com.soniq.player.playback

import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes as AndroidAudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
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
import com.soniq.player.data.SonicStatsEngine

class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null
    private var noisyReceiver: AudioNoisyReceiver? = null
    private var notificationReceiver: NotificationFadeReceiver? = null
    private val crossfadeHandler = Handler(Looper.getMainLooper())

    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null
    private var isDucked = false

    private val listener = object : Player.Listener {
        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            AudioEffectsBridge.attach(audioSessionId)
            RealtimeFftMonitor.attach(audioSessionId)
            SoftwareDspEngine.attachLoudness(audioSessionId)
        }

        override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
            // Volume reset upon track switch
            session?.player?.let { p ->
                if (!isDucked) p.volume = 1.0f
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        SonicStatsEngine.init(applicationContext)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                false // Custom smart notification auto-fade focus handling
            )
            .build()

        player.addListener(listener)
        session = MediaSession.Builder(this, player).build()
        noisyReceiver = AudioNoisyReceiver(this, player).also { it.register() }

        setupSmartAudioFocus(player)
        setupNotificationFadeReceiver(player)
        setupCrossfadeMonitor(player)
    }

    /**
     * Smart Audio Auto-Fade on Incoming Notifications.
     * Smoothly ducks volume down to 18% when notification rings, then glides back up to 100%.
     */
    private fun setupSmartAudioFocus(player: ExoPlayer) {
        val focusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
            when (focusChange) {
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                    // Incoming Notification: Smooth Auto-Fade Out to 0.18f
                    fadeVolume(player, player.volume, 0.18f, 350L)
                    isDucked = true
                }
                AudioManager.AUDIOFOCUS_GAIN -> {
                    // Notification finished: Smooth Auto-Fade Back to 1.0f
                    if (isDucked) {
                        fadeVolume(player, player.volume, 1.0f, 600L)
                        isDucked = false
                    }
                    if (!player.isPlaying && player.playbackState == Player.STATE_READY) {
                        player.play()
                    }
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                    if (player.isPlaying) player.pause()
                }
                AudioManager.AUDIOFOCUS_LOSS -> {
                    if (player.isPlaying) player.pause()
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AndroidAudioAttributes.Builder()
                        .setUsage(AndroidAudioAttributes.USAGE_MEDIA)
                        .setContentType(AndroidAudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener(focusChangeListener)
                .build()

            audioManager?.requestAudioFocus(focusRequest!!)
        } else {
            @Suppress("DEPRECATION")
            audioManager?.requestAudioFocus(
                focusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
        }
    }

    private fun setupNotificationFadeReceiver(player: ExoPlayer) {
        notificationReceiver = NotificationFadeReceiver { isIncomingAlert ->
            if (isIncomingAlert) {
                fadeVolume(player, player.volume, 0.18f, 300L)
            } else {
                fadeVolume(player, player.volume, 1.0f, 500L)
            }
        }
        val filter = IntentFilter().apply {
            addAction("android.media.RINGER_MODE_CHANGED")
            addAction("android.intent.action.PHONE_STATE")
        }
        registerReceiver(notificationReceiver, filter)
    }

    private fun fadeVolume(player: ExoPlayer, from: Float, to: Float, durationMs: Long) {
        val animator = ValueAnimator.ofFloat(from, to).apply {
            duration = durationMs
            addUpdateListener { anim ->
                player.volume = anim.animatedValue as Float
            }
        }
        animator.start()
    }

    private fun setupCrossfadeMonitor(player: ExoPlayer) {
        val checkInterval = 500L
        val monitorRunnable = object : Runnable {
            override fun run() {
                val duration = player.duration
                val pos = player.currentPosition
                if (!isDucked && player.isPlaying && duration > 0 && (duration - pos) in 1..4000) {
                    val remainingMs = duration - pos
                    val factor = (remainingMs / 4000f).coerceIn(0.1f, 1f)
                    player.volume = factor
                } else if (!isDucked && player.volume < 1.0f && player.currentPosition < 3000) {
                    val factor = (player.currentPosition / 3000f).coerceIn(0.1f, 1f)
                    player.volume = factor
                } else if (!isDucked && player.volume < 1.0f && duration - pos > 4000) {
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
        notificationReceiver?.let { unregisterReceiver(it) }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
        }

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

class NotificationFadeReceiver(private val onAlertChanged: (Boolean) -> Unit) : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == "android.intent.action.PHONE_STATE") {
            val state = intent.getStringExtra("state")
            onAlertChanged(state == "RINGING")
        }
    }
}
