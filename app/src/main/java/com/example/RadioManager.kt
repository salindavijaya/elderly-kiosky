package com.example

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class RadioPlaybackState(
  val isPlaying: Boolean = false,
  val isBuffering: Boolean = false,
  val currentStationUrl: String? = null,
  val currentStationName: String? = null,
  val errorMessage: String? = null
)

/**
 * Radio Player Manager utilizing ExoPlayer for lightweight, reliable HTTP/HTTPS
 * streaming on Android 7.1.1+ (API 24/25+) devices with 1.5GB RAM constraints.
 * Includes complete Android Audio Focus handling (ducking, pause, and resume).
 */
class RadioManager(private val context: Context) : Player.Listener {

  companion object {
    private const val TAG = "RadioManager"
    const val DEFAULT_DUCK_VOLUME = 0.2f
    const val NORMAL_VOLUME = 1.0f

  }

  private var exoPlayer: ExoPlayer? = null
  private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

  private val _playbackState = MutableStateFlow(RadioPlaybackState())
  val playbackState: StateFlow<RadioPlaybackState> = _playbackState.asStateFlow()

  private var hasAudioFocus = false
  private var isPlaybackPausedDueToFocus = false
  private var audioFocusRequest: AudioFocusRequest? = null

  // Audio Focus Listener for API 25 and below & API 26+
  private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
    when (focusChange) {
      AudioManager.AUDIOFOCUS_LOSS -> {
        Log.d(TAG, "Audio focus lost permanently. Stopping radio.")
        stop()
      }
      AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
        Log.d(TAG, "Audio focus lost transiently (e.g. Phone Call). Pausing radio.")
        if (exoPlayer?.isPlaying == true) {
          isPlaybackPausedDueToFocus = true
          exoPlayer?.pause()
          _playbackState.value = _playbackState.value.copy(isPlaying = false)
        }
      }
      AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
        Log.d(TAG, "Audio focus ducking (e.g. VoiceManager / TTS Speaking). Lowering volume.")
        exoPlayer?.volume = DEFAULT_DUCK_VOLUME
      }
      AudioManager.AUDIOFOCUS_GAIN -> {
        Log.d(TAG, "Audio focus regained. Restoring volume / resuming radio.")
        exoPlayer?.volume = NORMAL_VOLUME
        if (isPlaybackPausedDueToFocus) {
          isPlaybackPausedDueToFocus = false
          exoPlayer?.play()
          _playbackState.value = _playbackState.value.copy(isPlaying = true)
        }
      }
    }
  }

  @OptIn(UnstableApi::class)
  private fun getOrCreatePlayer(): ExoPlayer {
    if (exoPlayer == null) {
      exoPlayer = ExoPlayer.Builder(context)
        .build()
        .apply {
          addListener(this@RadioManager)
          playWhenReady = true
        }
    }
    return exoPlayer!!
  }

  /**
   * Requests audio focus before starting media playback.
   */
  private fun requestAudioFocus(): Boolean {
    if (hasAudioFocus) return true

    val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val playbackAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

      val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(playbackAttributes)
        .setAcceptsDelayedFocusGain(false)
        .setOnAudioFocusChangeListener(audioFocusChangeListener)
        .build()

      this.audioFocusRequest = focusRequest
      audioManager.requestAudioFocus(focusRequest)
    } else {
      @Suppress("DEPRECATION")
      audioManager.requestAudioFocus(
        audioFocusChangeListener,
        AudioManager.STREAM_MUSIC,
        AudioManager.AUDIOFOCUS_GAIN
      )
    }

    hasAudioFocus = (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
    return hasAudioFocus
  }

  /**
   * Abandons audio focus when playback stops.
   */
  private fun abandonAudioFocus() {
    if (!hasAudioFocus) return

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
      audioFocusRequest = null
    } else {
      @Suppress("DEPRECATION")
      audioManager.abandonAudioFocus(audioFocusChangeListener)
    }
    hasAudioFocus = false
  }

  /**
   * Plays a radio station by stream URL.
   * Handles ExoPlayer preparation, MediaItem creation, and audio focus request.
   */
  fun playStation(station: RadioStationPreset) {
    val url = station.streamUrl
    val resolvedName = station.stationName

    Log.d(TAG, "playStation() requested: $resolvedName ($url)")

    if (!requestAudioFocus()) {
      Log.w(TAG, "Could not acquire audio focus to play station: $url")
      _playbackState.value = _playbackState.value.copy(
        errorMessage = "Could not acquire audio focus"
      )
      return
    }

    try {
      val player = getOrCreatePlayer()
      player.volume = NORMAL_VOLUME
      val mediaItem = MediaItem.fromUri(Uri.parse(url))

      player.setMediaItem(mediaItem)
      player.prepare()
      player.play()

      _playbackState.value = RadioPlaybackState(
        isPlaying = true,
        isBuffering = true,
        currentStationUrl = url,
        currentStationName = resolvedName,
        errorMessage = null
      )
    } catch (e: Exception) {
      Log.e(TAG, "Error playing station: $url", e)
      _playbackState.value = RadioPlaybackState(
        isPlaying = false,
        errorMessage = "Error playing radio: ${e.message}"
      )
    }
  }

  /**
   * Stops playback, releases audio focus, and conserves memory for 1.5GB RAM devices.
   */
  fun stop() {
    Log.d(TAG, "stop() called. Halting radio playback.")
    try {
      exoPlayer?.stop()
      exoPlayer?.clearMediaItems()
    } catch (e: Exception) {
      Log.e(TAG, "Error stopping ExoPlayer", e)
    }
    abandonAudioFocus()
    _playbackState.value = RadioPlaybackState(
      isPlaying = false,
      isBuffering = false,
      currentStationUrl = null,
      currentStationName = null,
      errorMessage = null
    )
  }

  /**
   * Temporarily ducks volume (called during TTS voice feedback).
   */
  fun duckVolume() {
    exoPlayer?.volume = DEFAULT_DUCK_VOLUME
  }

  /**
   * Restores normal volume.
   */
  fun restoreVolume() {
    exoPlayer?.volume = NORMAL_VOLUME
  }

  /**
   * Releases player and audio focus on lifecycle shutdown.
   */
  fun release() {
    stop()
    exoPlayer?.removeListener(this)
    exoPlayer?.release()
    exoPlayer = null
  }

  // --- Player.Listener Callbacks ---

  override fun onPlaybackStateChanged(playbackState: Int) {
    when (playbackState) {
      Player.STATE_BUFFERING -> {
        Log.d(TAG, "Radio buffering...")
        _playbackState.value = _playbackState.value.copy(isBuffering = true)
      }
      Player.STATE_READY -> {
        Log.d(TAG, "Radio ready & playing.")
        _playbackState.value = _playbackState.value.copy(
          isBuffering = false,
          isPlaying = exoPlayer?.isPlaying == true
        )
      }
      Player.STATE_ENDED -> {
        Log.d(TAG, "Radio playback ended.")
        stop()
      }
      Player.STATE_IDLE -> {
        _playbackState.value = _playbackState.value.copy(isBuffering = false)
      }
    }
  }

  override fun onIsPlayingChanged(isPlaying: Boolean) {
    _playbackState.value = _playbackState.value.copy(isPlaying = isPlaying)
  }

  override fun onPlayerError(error: PlaybackException) {
    Log.e(TAG, "ExoPlayer playback error: ${error.message}", error)
    _playbackState.value = _playbackState.value.copy(
      isPlaying = false,
      isBuffering = false,
      errorMessage = "Playback failed: ${error.errorCodeName}"
    )
  }
}
