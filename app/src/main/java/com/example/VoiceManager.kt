package com.example

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/**
 * Supported categorized Voice Intents parsed from Sinhala voice queries.
 */
enum class VoiceIntent {
  CALL,
  RADIO,
  YOUTUBE,
  STOP,
  UNKNOWN
}

/**
 * Result model representing the parsed user intention.
 */
data class ParsedVoiceCommand(
  val intent: VoiceIntent,
  val rawText: String,
  val keywordMatched: String? = null,
  val description: String = ""
)

/**
 * Sinhala Voice Orchestrator managing TextToSpeech (TTS) with Locale("si", "LK"),
 * SpeechRecognizer (STT) configured for "si-LK", and intent classification.
 * Handles transient Audio Focus ducking so background radio / video lowers volume when TTS speaks.
 */
class VoiceManager(private val context: Context) {

  companion object {
    private const val TAG = "VoiceManager"
    const val SINHALA_LANGUAGE_TAG = "si-LK"
    val SINHALA_LOCALE = Locale("si", "LK")
  }

  private var textToSpeech: TextToSpeech? = null
  private var isTtsInitialized = false
  private var speechRecognizer: SpeechRecognizer? = null
  private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
  private var ttsAudioFocusRequest: AudioFocusRequest? = null

  // Callbacks for speech events
  private var onTtsStartCallback: (() -> Unit)? = null
  private var onTtsDoneCallback: (() -> Unit)? = null

  init {
    initializeTts()
    initializeSpeechRecognizer()
  }

  /**
   * Initializes Android TextToSpeech with Locale("si", "LK").
   */
  private fun initializeTts() {
    textToSpeech = TextToSpeech(context) { status ->
      if (status == TextToSpeech.SUCCESS) {
        val result = textToSpeech?.setLanguage(SINHALA_LOCALE)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
          Log.w(TAG, "Sinhala TTS language missing or not supported on this engine. Default locale used as fallback.")
        } else {
          Log.d(TAG, "Sinhala TTS initialized successfully with locale si-LK.")
        }
        isTtsInitialized = true

        textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
          override fun onStart(utteranceId: String?) {
            onTtsStartCallback?.invoke()
          }

          override fun onDone(utteranceId: String?) {
            releaseTtsAudioFocus()
            onTtsDoneCallback?.invoke()
          }

          @Suppress("DEPRECATION")
          override fun onError(utteranceId: String?) {
            Log.e(TAG, "TTS Utterance error for id: $utteranceId")
            releaseTtsAudioFocus()
          }
        })
      } else {
        Log.e(TAG, "Failed to initialize TextToSpeech engine. Status code: $status")
      }
    }
  }

  private fun requestTtsAudioFocus() {
    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val playbackAttributes = AudioAttributes.Builder()
          .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
          .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
          .build()

        val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
          .setAudioAttributes(playbackAttributes)
          .setAcceptsDelayedFocusGain(false)
          .build()

        this.ttsAudioFocusRequest = focusRequest
        audioManager.requestAudioFocus(focusRequest)
      } else {
        @Suppress("DEPRECATION")
        audioManager.requestAudioFocus(
          null,
          AudioManager.STREAM_NOTIFICATION,
          AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
        )
      }
    } catch (e: Exception) {
      Log.e(TAG, "Error requesting transient audio focus for TTS", e)
    }
  }

  private fun releaseTtsAudioFocus() {
    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        ttsAudioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        ttsAudioFocusRequest = null
      } else {
        @Suppress("DEPRECATION")
        audioManager.abandonAudioFocus(null)
      }
    } catch (e: Exception) {
      Log.e(TAG, "Error releasing TTS audio focus", e)
    }
  }

  /**
   * Initializes Android SpeechRecognizer.
   */
  private fun initializeSpeechRecognizer() {
    if (SpeechRecognizer.isRecognitionAvailable(context)) {
      speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
      Log.d(TAG, "SpeechRecognizer created successfully.")
    } else {
      Log.w(TAG, "Speech recognition is not available on this device.")
    }
  }

  /**
   * Speaks the provided Sinhala text using TextToSpeech with audio focus ducking.
   */
  fun speak(
    text: String,
    onStart: () -> Unit = {},
    onDone: () -> Unit = {}
  ) {
    if (!isTtsInitialized || textToSpeech == null) {
      Log.w(TAG, "TextToSpeech not yet initialized, cannot speak: $text")
      return
    }
    this.onTtsStartCallback = onStart
    this.onTtsDoneCallback = onDone

    requestTtsAudioFocus()

    val utteranceId = "tts_${System.currentTimeMillis()}"
    val params = Bundle().apply {
      putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
    }

    textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
  }

  /**
   * Starts listening for Sinhala speech input using Android SpeechRecognizer.
   * Configures RecognizerIntent with EXTRA_LANGUAGE = "si-LK" and
   * EXTRA_LANGUAGE_MODEL = LANGUAGE_MODEL_FREE_FORM.
   */
  fun startListening(
    onResult: (String) -> Unit,
    onError: (String) -> Unit,
    onRmsChanged: ((Float) -> Unit)? = null
  ) {
    if (speechRecognizer == null) {
      initializeSpeechRecognizer()
      if (speechRecognizer == null) {
        onError("SpeechRecognizer is not available on this device.")
        return
      }
    }

    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
      putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
      putExtra(RecognizerIntent.EXTRA_LANGUAGE, SINHALA_LANGUAGE_TAG)
      putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, SINHALA_LANGUAGE_TAG)
      putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, SINHALA_LANGUAGE_TAG)
      putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
      putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
    }

    speechRecognizer?.setRecognitionListener(object : RecognitionListener {
      override fun onReadyForSpeech(params: Bundle?) {
        Log.d(TAG, "SpeechRecognizer: Ready for speech (si-LK)")
      }

      override fun onBeginningOfSpeech() {
        Log.d(TAG, "SpeechRecognizer: Beginning of speech detected")
      }

      override fun onRmsChanged(rmsdB: Float) {
        onRmsChanged?.invoke(rmsdB)
      }

      override fun onBufferReceived(buffer: ByteArray?) {}

      override fun onEndOfSpeech() {
        Log.d(TAG, "SpeechRecognizer: End of speech detected")
      }

      override fun onError(error: Int) {
        val errorMessage = getSpeechErrorMessage(error)
        Log.e(TAG, "SpeechRecognizer error: $errorMessage (code $error)")
        onError(errorMessage)
      }

      override fun onResults(results: Bundle?) {
        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        val recognizedText = matches?.firstOrNull() ?: ""
        Log.d(TAG, "SpeechRecognizer recognized text: '$recognizedText'")
        onResult(recognizedText)
      }

      override fun onPartialResults(partialResults: Bundle?) {}

      override fun onEvent(eventType: Int, params: Bundle?) {}
    })

    try {
      speechRecognizer?.startListening(intent)
      Log.d(TAG, "SpeechRecognizer.startListening() initiated with language si-LK")
    } catch (e: Exception) {
      Log.e(TAG, "Exception starting speech listening", e)
      onError(e.message ?: "Failed to start speech recognition")
    }
  }

  /**
   * Stops active speech listening session.
   */
  fun stopListening() {
    try {
      speechRecognizer?.stopListening()
    } catch (e: Exception) {
      Log.e(TAG, "Error stopping SpeechRecognizer", e)
    }
  }

  /**
   * Release resources on activity lifecycle termination.
   */
  fun destroy() {
    try {
      textToSpeech?.stop()
      textToSpeech?.shutdown()
      speechRecognizer?.destroy()
    } catch (e: Exception) {
      Log.e(TAG, "Error destroying VoiceManager", e)
    }
  }

  private fun getSpeechErrorMessage(errorCode: Int): String {
    return when (errorCode) {
      SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
      SpeechRecognizer.ERROR_CLIENT -> "Client side error"
      SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions"
      SpeechRecognizer.ERROR_NETWORK -> "Network communication error"
      SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
      SpeechRecognizer.ERROR_NO_MATCH -> "No speech match found"
      SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognition service busy"
      SpeechRecognizer.ERROR_SERVER -> "Recognition server error"
      SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech input detected"
      else -> "Speech recognition error ($errorCode)"
    }
  }
}

/**
 * Parser that takes the Sinhala STT string result and categorizes the intent
 * using Regex and keyword matching into CALL, RADIO, YOUTUBE, STOP, or UNKNOWN.
 */
fun parseSinhalaVoiceIntent(rawQuery: String): ParsedVoiceCommand {
  val cleanQuery = rawQuery.trim().lowercase()

  if (cleanQuery.isEmpty()) {
    return ParsedVoiceCommand(VoiceIntent.UNKNOWN, rawQuery, null, "No input detected")
  }

  // 1. STOP intent keywords: "නවත්තන්න", "නතර", "stop", "ඕෆ්", "වහන්න", "අවසන්"
  val stopRegex = Regex("(නවත්තන්න|නවතන්න|නතර|stop|ඕෆ්|වහන්න|අවසන්|pause|කපා දමන්න)", RegexOption.IGNORE_CASE)
  if (stopRegex.containsMatchIn(cleanQuery)) {
    val matched = stopRegex.find(cleanQuery)?.value
    return ParsedVoiceCommand(
      intent = VoiceIntent.STOP,
      rawText = rawQuery,
      keywordMatched = matched,
      description = "Stop / Terminate playback or current task"
    )
  }

  // 2. CALL intent keywords: "කෝල්", "ඇමතුම", "අමතන්න", "ගන්න", "call", "dial", "දුරකථන"
  val callRegex = Regex("(කෝල්|ඇමතුම|අමතන්න|ගන්න|call|dial|දුරකථන|phone)", RegexOption.IGNORE_CASE)
  if (callRegex.containsMatchIn(cleanQuery)) {
    val matched = callRegex.find(cleanQuery)?.value
    return ParsedVoiceCommand(
      intent = VoiceIntent.CALL,
      rawText = rawQuery,
      keywordMatched = matched,
      description = "Initiate phone call to contact or number"
    )
  }

  // 3. RADIO intent keywords: "රේඩියෝ", "ගුවන්විදුලි", "radio", "නාලිකාව", "fm", "ප්‍රවෘත්ති"
  val radioRegex = Regex("(රේඩියෝ|ගුවන්විදුලි|radio|නාලිකාව|fm|ප්‍රවෘත්ති|වාහිනී)", RegexOption.IGNORE_CASE)
  if (radioRegex.containsMatchIn(cleanQuery)) {
    val matched = radioRegex.find(cleanQuery)?.value
    return ParsedVoiceCommand(
      intent = VoiceIntent.RADIO,
      rawText = rawQuery,
      keywordMatched = matched,
      description = "Tune into Sinhala Radio / Pirith / News broadcast"
    )
  }

  // 4. YOUTUBE intent keywords: "යූටියුබ්", "youtube", "බණ", "පිරිත්", "සින්දු", "විඩියෝ", "video", "ගීත"
  val youtubeRegex = Regex("(යූටියුබ්|youtube|බණ|පිරිත්|සින්දු|විඩියෝ|video|ගීත|දේශනා)", RegexOption.IGNORE_CASE)
  if (youtubeRegex.containsMatchIn(cleanQuery)) {
    val matched = youtubeRegex.find(cleanQuery)?.value
    return ParsedVoiceCommand(
      intent = VoiceIntent.YOUTUBE,
      rawText = rawQuery,
      keywordMatched = matched,
      description = "Play YouTube / Bana / Pirith / Music video"
    )
  }

  return ParsedVoiceCommand(
    intent = VoiceIntent.UNKNOWN,
    rawText = rawQuery,
    keywordMatched = null,
    description = "Command not recognized as known Kiosk intent"
  )
}
