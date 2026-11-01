package com.soniq.player.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import androidx.media3.exoplayer.ExoPlayer

class AudioNoisyReceiver(
    private val context: Context,
    private val player: ExoPlayer
) : BroadcastReceiver() {

    private var registered = false

    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
            // Unplugged headphones or disconnected bluetooth
            if (player.isPlaying) {
                player.pause()
            }
        }
    }

    fun register() {
        if (!registered) {
            context.registerReceiver(this, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
            registered = true
        }
    }

    fun unregister() {
        if (registered) {
            context.unregisterReceiver(this)
            registered = false
        }
    }
}
