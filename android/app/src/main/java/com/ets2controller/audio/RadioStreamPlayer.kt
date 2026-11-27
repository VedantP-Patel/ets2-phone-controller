package com.ets2controller.audio

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import com.ets2controller.network.RadioStation

/**
 * Direct internet radio stream player backed by Android MediaPlayer.
 * Lets drivers browse and jump straight to any live_streams.sii station
 * while driving without pausing ETS2.
 */
class RadioStreamPlayer(
    private val onStateChanged: (playing: Boolean, buffering: Boolean, station: RadioStation?, error: String?) -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var mediaPlayer: MediaPlayer? = null
    private var currentStation: RadioStation? = null
    private var isBuffering: Boolean = false
    private var isPlaying: Boolean = false
    private var volume: Float = 0.85f

    private fun notifyState(error: String? = null) {
        val playing = isPlaying
        val buffering = isBuffering
        val st = currentStation
        mainHandler.post { onStateChanged(playing, buffering, st, error) }
    }

    fun play(station: RadioStation) {
        releaseInternal()
        currentStation = station
        isBuffering = true
        isPlaying = false
        notifyState()

        val mp = MediaPlayer()
        mediaPlayer = mp
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            mp.setDataSource(station.url)
            mp.setVolume(volume, volume)
            mp.setOnPreparedListener { player ->
                if (mediaPlayer !== player) return@setOnPreparedListener
                isBuffering = false
                isPlaying = true
                player.start()
                notifyState()
            }
            mp.setOnErrorListener { player, _, _ ->
                if (mediaPlayer !== player) return@setOnErrorListener true
                isBuffering = false
                isPlaying = false
                notifyState("Stream offline or unreachable")
                true
            }
            mp.prepareAsync()
        } catch (_: Exception) {
            isBuffering = false
            isPlaying = false
            notifyState("Unable to open stream")
        }
    }

    fun togglePlayPause(fallbackStation: RadioStation? = null): Boolean {
        val mp = mediaPlayer
        if (mp != null && !isBuffering) {
            return try {
                if (mp.isPlaying) {
                    mp.pause()
                    isPlaying = false
                } else {
                    mp.start()
                    isPlaying = true
                }
                notifyState()
                true
            } catch (_: Exception) {
                false
            }
        }
        val target = currentStation ?: fallbackStation ?: return false
        play(target)
        return true
    }

    fun pauseIfPlaying() {
        val mp = mediaPlayer ?: return
        try {
            if (mp.isPlaying) mp.pause()
        } catch (_: Exception) {}
        isBuffering = false
        isPlaying = false
        notifyState()
    }

    fun setVolume(v: Float) {
        volume = v.coerceIn(0f, 1f)
        try {
            mediaPlayer?.setVolume(volume, volume)
        } catch (_: Exception) {}
    }

    private fun releaseInternal() {
        val old = mediaPlayer
        mediaPlayer = null
        if (old != null) {
            try { old.reset() } catch (_: Exception) {}
            try { old.release() } catch (_: Exception) {}
        }
    }

    fun release() {
        releaseInternal()
        isPlaying = false
        isBuffering = false
    }
}
