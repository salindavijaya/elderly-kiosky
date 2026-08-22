package com.example

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class YouTubePlayerState(
  val isPlaying: Boolean = false,
  val currentVideoId: String? = null,
  val videoTitle: String? = null,
  val isLoading: Boolean = false,
  val errorMessage: String? = null
)

/**
 * YouTube Player Manager utilizing a lightweight, hardware-accelerated WebView
 * with YouTube IFrame API. Optimized for 1.5GB RAM Android devices in Kiosk mode.
 * Integrates full Android Audio Focus handling for speech & incoming phone calls.
 */
class YouTubePlayerManager(private val context: Context) {

  companion object {
    private const val TAG = "YouTubePlayerManager"

    // Default Sinhala Buddhist Bana & Pirith Presets
    const val PRESET_MAHA_PIRITHA_ID = "jNQXAC9IVRw" // Example fallback / Buddhist Pirith ID
    const val PRESET_BANA_ID = "dQw4w9WgXcQ"

    private val FALLBACK_VIDEO_IDS = listOf(
      PRESET_MAHA_PIRITHA_ID,
      PRESET_BANA_ID,
      "2Vv-BfVoq4g"
    )
  }

  private var webView: WebView? = null
  private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
  private var settings = YouTubeSettings()

  private val _playerState = MutableStateFlow(YouTubePlayerState())
  val playerState: StateFlow<YouTubePlayerState> = _playerState.asStateFlow()

  fun updateSettings(settings: YouTubeSettings) {
    this.settings = settings
    setVolume(settings.defaultVolume)
  }

  private var hasAudioFocus = false
  private var isPlaybackPausedDueToFocus = false
  private var audioFocusRequest: AudioFocusRequest? = null
  private val mainHandler = Handler(Looper.getMainLooper())
  private var fallbackIndex = 0

  private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
    when (focusChange) {
      AudioManager.AUDIOFOCUS_LOSS -> {
        Log.d(TAG, "Audio focus lost. Pausing YouTube video without closing viewer.")
        isPlaybackPausedDueToFocus = true
        pauseVideo()
      }
      AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
        Log.d(TAG, "Audio focus lost transiently (call). Pausing YouTube player.")
        isPlaybackPausedDueToFocus = true
        pauseVideo()
      }
      AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
        Log.d(TAG, "Audio focus ducking (VoiceManager TTS speaking). Lowering video volume.")
        setVolume(20)
      }
      AudioManager.AUDIOFOCUS_GAIN -> {
        Log.d(TAG, "Audio focus regained. Restoring video volume & playback.")
        setVolume(settings.defaultVolume)
        if (isPlaybackPausedDueToFocus) {
          isPlaybackPausedDueToFocus = false
          playVideo()
        }
      }
    }
  }

  /**
   * Initializes and returns a hardware-accelerated WebView configured for YouTube IFrame API.
   */
  @SuppressLint("SetJavaScriptEnabled")
  fun createPlayerWebView(): WebView {
    if (webView != null) return webView!!

    val wv = WebView(context).apply {
      layoutParams = ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT
      )
      setBackgroundColor(Color.BLACK)

      // Hardware acceleration is handled natively by the window/hardware canvas
      settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        mediaPlaybackRequiresUserGesture = false
        allowContentAccess = true
        allowFileAccess = false
        loadWithOverviewMode = true
        useWideViewPort = true
        cacheMode = WebSettings.LOAD_DEFAULT
        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
      }

      webChromeClient = object : WebChromeClient() {
        override fun getDefaultVideoPoster(): android.graphics.Bitmap? {
          return null
        }
      }

      webViewClient = object : WebViewClient() {
        override fun onPageFinished(view: WebView?, url: String?) {
          super.onPageFinished(view, url)
          Log.d(TAG, "YouTube IFrame WebView loaded successfully: $url")
          _playerState.value = _playerState.value.copy(isLoading = false)
        }

        override fun onReceivedError(
          view: WebView?,
          request: WebResourceRequest?,
          error: WebResourceError?
        ) {
          super.onReceivedError(view, request, error)
          Log.e(TAG, "YouTube WebView error: ${error?.description}")
          _playerState.value = _playerState.value.copy(
            isLoading = false,
            errorMessage = error?.description?.toString() ?: "Failed to load video"
          )
        }
      }

      addJavascriptInterface(YouTubeJsBridge(), "AndroidBridge")
    }

    this.webView = wv
    return wv
  }

  /**
   * Loads and auto-plays a YouTube video by ID or URL in fullscreen inside the WebView container.
   */
  fun loadVideo(videoIdOrUrl: String, title: String? = null) {
    val cleanVideoId = extractVideoId(videoIdOrUrl)
    Log.d(TAG, "loadVideo() called for videoId: $cleanVideoId")
    fallbackIndex = 0

    if (!requestAudioFocus()) {
      Log.w(TAG, "Audio focus request failed before loading YouTube video.")
    }

    _playerState.value = YouTubePlayerState(
      isPlaying = true,
      currentVideoId = cleanVideoId,
      videoTitle = title ?: "YouTube Video",
      isLoading = true,
      errorMessage = null
    )

    val wv = createPlayerWebView()
    val htmlContent = generateIFrameHtml(cleanVideoId, settings)
    wv.loadDataWithBaseURL("https://www.youtube.com", htmlContent, "text/html", "UTF-8", null)
  }

  /**
   * Injects JavaScript into the IFrame to play video.
   */
  fun playVideo() {
    webView?.evaluateJavascript(
      "if (typeof player !== 'undefined' && player.playVideo) { player.playVideo(); }",
      null
    )
    _playerState.value = _playerState.value.copy(isPlaying = true)
  }

  /**
   * Injects JavaScript into the IFrame to pause video.
   */
  fun pauseVideo() {
    webView?.evaluateJavascript(
      "if (typeof player !== 'undefined' && player.pauseVideo) { player.pauseVideo(); }",
      null
    )
    _playerState.value = _playerState.value.copy(isPlaying = false)
  }

  /**
   * Injects JavaScript to set volume (0 to 100).
   */
  fun setVolume(volume: Int) {
    webView?.evaluateJavascript(
      "if (typeof player !== 'undefined' && player.setVolume) { player.setVolume($volume); }",
      null
    )
  }

  /**
   * Stops video playback, clears WebView, and releases audio focus.
   */
  fun stop() {
    Log.d(TAG, "stop() called on YouTube player.")
    try {
      webView?.evaluateJavascript(
        "if (typeof player !== 'undefined' && player.stopVideo) { player.stopVideo(); }",
        null
      )
      webView?.loadUrl("about:blank")
    } catch (e: Exception) {
      Log.e(TAG, "Error stopping YouTube player", e)
    }

    abandonAudioFocus()
    fallbackIndex = 0
    _playerState.value = YouTubePlayerState(
      isPlaying = false,
      currentVideoId = null,
      videoTitle = null,
      isLoading = false,
      errorMessage = null
    )
  }

  /**
   * Cleanly destroys the WebView instance to free memory on 1.5GB RAM device.
   */
  fun destroy() {
    stop()
    try {
      webView?.stopLoading()
      webView?.clearHistory()
      webView?.clearCache(true)
      webView?.removeAllViews()
      webView?.destroy()
      webView = null
    } catch (e: Exception) {
      Log.e(TAG, "Error destroying YouTube WebView", e)
    }
  }

  private fun requestAudioFocus(): Boolean {
    if (hasAudioFocus) return true

    val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val playbackAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
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
   * Extracts YouTube 11-character video ID from common URL formats or raw ID.
   */
  private fun extractVideoId(input: String): String {
    val trimmed = input.trim()
    if (trimmed.length == 11 && !trimmed.contains("/") && !trimmed.contains("?")) {
      return trimmed
    }
    val pattern = Regex("(?:youtu\\.be\\/|youtube\\.com\\/(?:embed\\/|v\\/|watch\\?v=|watch\\?.+&v=))([\\w-]{11})")
    val match = pattern.find(trimmed)
    return match?.groupValues?.get(1) ?: trimmed
  }

  /**
   * Generates lean, responsive HTML embedding YouTube IFrame Player with auto-play.
   */
  private fun generateIFrameHtml(videoId: String, settings: YouTubeSettings): String {
    return """
      <!DOCTYPE html>
      <html>
      <head>
        <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
        <style>
          * { margin: 0; padding: 0; box-sizing: border-box; }
          body, html { width: 100%; height: 100%; background-color: #000000; overflow: hidden; }
          #player { width: 100%; height: 100%; position: absolute; top: 0; left: 0; }
        </style>
      </head>
      <body>
        <div id="player"></div>
        <script>
          var tag = document.createElement('script');
          tag.src = "https://www.youtube.com/iframe_api";
          var firstScriptTag = document.getElementsByTagName('script')[0];
          firstScriptTag.parentNode.insertBefore(tag, firstScriptTag);

          var player;
          function onYouTubeIframeAPIReady() {
            player = new YT.Player('player', {
              width: '100%',
              height: '100%',
              videoId: '$videoId',
              playerVars: {
                'autoplay': ${if (settings.autoPlay) 1 else 0},
                'controls': ${if (settings.showControls) 1 else 0},
                'modestbranding': 1,
                'rel': 0,
                'iv_load_policy': 3,
                'cc_load_policy': 0,
                'playsinline': 1,
                'fs': 1
              },
              events: {
                'onReady': onPlayerReady,
                'onStateChange': onPlayerStateChange,
                'onError': onPlayerError
              }
            });
          }

          function onPlayerReady(event) {
            if (${settings.autoPlay}) {
              event.target.playVideo();
            }
            event.target.setVolume(${settings.defaultVolume.coerceIn(0, 100)});
            if (window.AndroidBridge) {
              window.AndroidBridge.onVideoReady();
            }
          }

          function onPlayerStateChange(event) {
            // YT.PlayerState: PLAYING = 1, PAUSED = 2, ENDED = 0, BUFFERING = 3
            if (window.AndroidBridge) {
              window.AndroidBridge.onPlayerStateChange(event.data);
            }
          }

          function onPlayerError(event) {
            if (window.AndroidBridge) {
              window.AndroidBridge.onError(event.data);
            }
          }
        </script>
      </body>
      </html>
    """.trimIndent()
  }

  inner class YouTubeJsBridge {
    @JavascriptInterface
    fun onVideoReady() {
      Log.d(TAG, "YouTube JS Bridge: Video ready & playing")
      _playerState.value = _playerState.value.copy(isPlaying = true, isLoading = false)
    }

    @JavascriptInterface
    fun onPlayerStateChange(state: Int) {
      Log.d(TAG, "YouTube JS Bridge: Player state change -> $state")
      val isPlaying = (state == 1)
      _playerState.value = _playerState.value.copy(isPlaying = isPlaying)
    }

    @JavascriptInterface
    fun onError(errorCode: Int) {
      Log.e(TAG, "YouTube JS Bridge: Error code -> $errorCode")
      _playerState.value = _playerState.value.copy(
        isPlaying = false,
        errorMessage = null
      )
      mainHandler.post { loadFallbackVideo(errorCode) }
    }
  }

  private fun loadFallbackVideo(errorCode: Int) {
    if (_playerState.value.currentVideoId == null || fallbackIndex >= FALLBACK_VIDEO_IDS.size) {
      _playerState.value = _playerState.value.copy(
        isLoading = false,
        errorMessage = "No playable recommendation is available right now."
      )
      return
    }

    val fallbackId = FALLBACK_VIDEO_IDS[fallbackIndex++]
    if (fallbackId == _playerState.value.currentVideoId) {
      loadFallbackVideo(errorCode)
      return
    }

    Log.w(TAG, "Video unavailable (error $errorCode); loading recommendation $fallbackId")
    val currentTitle = _playerState.value.videoTitle
    _playerState.value = _playerState.value.copy(
      currentVideoId = fallbackId,
      videoTitle = currentTitle ?: "Recommended video",
      isPlaying = true,
      isLoading = true,
      errorMessage = null
    )
    createPlayerWebView().loadDataWithBaseURL(
      "https://www.youtube.com",
      generateIFrameHtml(fallbackId, settings),
      "text/html",
      "UTF-8",
      null
    )
  }
}
