package com.example

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.ui.viewinterop.AndroidView
import com.example.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

data class QuickContactItem(
  val name: String,
  val phone: String,
  val bgColor: Color,
  val borderColor: Color
)

class MainActivity : ComponentActivity() {

  companion object {
    private const val TAG = "KioskMainActivity"
    const val EXTRA_FROM_CALL_STATE = "extra_from_call_state"
  }

  // Voice, Radio, Video & Contact Orchestrators
  private lateinit var voiceManager: VoiceManager
  private lateinit var radioManager: RadioManager
  private lateinit var youtubePlayerManager: YouTubePlayerManager
  private lateinit var contactManager: ContactManager
  private lateinit var radioSettingsRepository: RadioSettingsRepository
  private lateinit var youtubeSettingsRepository: YouTubeSettingsRepository
  private var configuredStationsForVoice by mutableStateOf(RadioStationPreset.defaults)

  // Reactive State for UI
  private val kioskEventLogs = mutableStateListOf<String>()
  private var homePressCount by mutableIntStateOf(0)
  private var isActivelyListening by mutableStateOf(false)
  private var recognizedSinhalaText by mutableStateOf("")
  private var lastParsedIntent by mutableStateOf<ParsedVoiceCommand?>(null)
  private var audioRmsLevel by mutableFloatStateOf(0f)

  // Pending call countdown state
  private var pendingCallTargetName by mutableStateOf<String?>(null)
  private var pendingCallNumber by mutableStateOf<String?>(null)
  private var pendingCallCountdownSeconds by mutableIntStateOf(0)
  private var pendingCallRunnable: Runnable? = null
  private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    // 1. Initialize Voice Orchestration, Radio, YouTube and Contact Managers
    voiceManager = VoiceManager(this)
    radioManager = RadioManager(this)
    radioSettingsRepository = RadioSettingsRepository(this)
    youtubeSettingsRepository = YouTubeSettingsRepository(this)
    youtubePlayerManager = YouTubePlayerManager(this)
    contactManager = ContactManager(this)

    // 2. Sticky immersive fullscreen mode
    applyStickyImmersiveMode()

    // 3. Override back press behavior to guarantee kiosk lockdown
    onBackPressedDispatcher.addCallback(
      this,
      object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
          if (youtubePlayerManager.playerState.value.currentVideoId != null) {
            youtubePlayerManager.stop()
            logKioskEvent("YouTube video closed via back press.")
            return
          }
          if (radioManager.playbackState.value.isPlaying) {
            radioManager.stop()
            logKioskEvent("Radio playback stopped via back press.")
            return
          }
          logKioskEvent("Back gesture/button intercepted -> Blocked by Kiosk lockdown.")
          Log.d(TAG, "Back button press intercepted and blocked.")
        }
      }
    )

    logKioskEvent("Kiosk Launcher active with Sinhala Voice, Radio & YouTube Managers.")

    setContent {
      val radioState by radioManager.playbackState.collectAsState()
      val configuredStations by radioSettingsRepository.stations.collectAsState(initial = RadioStationPreset.defaults)
      val youtubeSettings by youtubeSettingsRepository.settings.collectAsState(initial = YouTubeSettings())
      val youtubeState by youtubePlayerManager.playerState.collectAsState()
      val settingsScope = rememberCoroutineScope()

      LaunchedEffect(configuredStations) {
        configuredStationsForVoice = configuredStations
      }

      LaunchedEffect(youtubeSettings) {
        youtubePlayerManager.updateSettings(youtubeSettings)
      }

      // Request runtime permissions on startup for smooth physical device testing
      val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
      ) { results ->
        val recordAudioGranted = results[Manifest.permission.RECORD_AUDIO] == true
        val readContactsGranted = results[Manifest.permission.READ_CONTACTS] == true
        val callPhoneGranted = results[Manifest.permission.CALL_PHONE] == true
        val readPhoneStateGranted = results[Manifest.permission.READ_PHONE_STATE] == true

        logKioskEvent(
          "Permissions: Mic=${if (recordAudioGranted) "OK" else "Deny"}, " +
          "Contacts=${if (readContactsGranted) "OK" else "Deny"}, " +
          "Call=${if (callPhoneGranted) "OK" else "Deny"}, " +
          "PhoneState=${if (readPhoneStateGranted) "OK" else "Deny"}"
        )
      }

      LaunchedEffect(Unit) {
        val requiredPermissions = arrayOf(
          Manifest.permission.RECORD_AUDIO,
          Manifest.permission.READ_CONTACTS,
          Manifest.permission.CALL_PHONE,
          Manifest.permission.READ_PHONE_STATE
        )
        val missingPermissions = requiredPermissions.filter {
          ContextCompat.checkSelfPermission(this@MainActivity, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missingPermissions.isNotEmpty()) {
          permissionLauncher.launch(missingPermissions.toTypedArray())
        }
      }

      MyApplicationTheme {
        Box(modifier = Modifier.fillMaxSize()) {
          // Primary Kiosk Home UI
          KioskHomeScreen(
            eventLogs = kioskEventLogs,
            homePressCount = homePressCount,
            radioState = radioState,
            radioStations = configuredStations,
            onSaveRadioSettings = { stations ->
              settingsScope.launch {
                val activeStationId = configuredStations.firstOrNull {
                  it.streamUrl == radioState.currentStationUrl || it.stationName == radioState.currentStationName
                }?.id
                radioSettingsRepository.save(stations)
                val activeStation = stations.firstOrNull { it.id == activeStationId }
                if (activeStation != null && (radioState.isPlaying || radioState.isBuffering)) {
                  radioManager.stop()
                  radioManager.playStation(activeStation)
                }
              }
            },
            onResetRadioSettings = {
              settingsScope.launch { radioSettingsRepository.reset() }
            },
            youtubeSettings = youtubeSettings,
            onSaveYouTubeSettings = { settings ->
              settingsScope.launch {
                youtubeSettingsRepository.save(settings)
              }
            },
            onResetYouTubeSettings = {
              settingsScope.launch { youtubeSettingsRepository.reset() }
            },
            youtubeState = youtubeState,
            pendingCallTargetName = pendingCallTargetName,
            pendingCallCountdownSeconds = pendingCallCountdownSeconds,
            onCancelPendingCall = { cancelPendingCall("On-screen Cancel Button") },
            onLogEvent = { logKioskEvent(it) },
            onTriggerVoice = { triggerVoiceListening() },
            onPlayRadio = { station ->
              cancelPendingCall()
              youtubePlayerManager.stop()
              configuredStations.firstOrNull { it.id == station.id }?.let { configuredStation ->
                radioManager.playStation(configuredStation)
                logKioskEvent("Playing ${configuredStation.stationName} (${configuredStation.sinhalaTitle})")
              }
            },
            onStopRadio = {
              radioManager.stop()
              logKioskEvent("Radio stopped by user.")
            },
            onPlayYouTube = { videoId, title ->
              cancelPendingCall()
              radioManager.stop()
              youtubePlayerManager.loadVideo(videoId, title)
              logKioskEvent("Playing YouTube: $title")
            },
            onStopYouTube = {
              youtubePlayerManager.stop()
              logKioskEvent("YouTube stopped by user.")
            }
          )

          // Embedded YouTube Fullscreen Video Container Overlay
          if (youtubeState.currentVideoId != null) {
            YouTubeVideoOverlay(
              playerManager = youtubePlayerManager,
              state = youtubeState,
              onClose = {
                youtubePlayerManager.stop()
                logKioskEvent("Closed YouTube video viewer.")
              }
            )
          }

          // Massive Screen-Filling Glowing Sinhala Listening Overlay
          AnimatedVisibility(
            visible = isActivelyListening,
            enter = fadeIn(animationSpec = tween(300)) + scaleIn(initialScale = 0.9f),
            exit = fadeOut(animationSpec = tween(250)) + scaleOut(targetScale = 0.95f)
          ) {
            ScreenFillingListeningIndicator(
              rmsLevel = audioRmsLevel,
              recognizedText = recognizedSinhalaText,
              parsedCommand = lastParsedIntent,
              onCancel = {
                voiceManager.stopListening()
                isActivelyListening = false
                logKioskEvent("Voice listening cancelled by user.")
              },
              onSpeakPromptAgain = {
                triggerVoiceListening()
              }
            )
          }
        }
      }
    }
  }

  override fun onResume() {
    super.onResume()
    applyStickyImmersiveMode()
  }

  override fun onWindowFocusChanged(hasFocus: Boolean) {
    super.onWindowFocusChanged(hasFocus)
    if (hasFocus) {
      applyStickyImmersiveMode()
    }
  }

  /**
   * onNewIntent: Physical Home button trigger when MainActivity is set as HOME launcher.
   */
  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)

    homePressCount++
    val isFromCall = intent.getBooleanExtra(EXTRA_FROM_CALL_STATE, false)
    if (isFromCall) {
      logKioskEvent("onNewIntent: Returned from call via CallStateReceiver.")
      Log.d(TAG, "onNewIntent() triggered from CallStateReceiver")
    } else {
      // If a countdown call is pending, pressing Home cancels it as requested
      if (pendingCallCountdownSeconds > 0) {
        cancelPendingCall("Physical Home Button")
      } else {
        logKioskEvent("onNewIntent: Physical Home button triggered (#$homePressCount) -> Activating Sinhala STT listening.")
        Log.d(TAG, "onNewIntent() Home button triggered (#$homePressCount). Starting voice listener.")
        triggerVoiceListening()
      }
    }

    applyStickyImmersiveMode()
  }

  /**
   * Starts the Sinhala Speech Recognition flow with audio levels and intent classification.
   */
  private fun triggerVoiceListening() {
    val hasAudioPermission = ContextCompat.checkSelfPermission(
      this,
      Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    if (!hasAudioPermission) {
      logKioskEvent("Voice listening failed: RECORD_AUDIO permission missing.")
      Toast.makeText(this, "Please grant microphone permission for Voice input", Toast.LENGTH_SHORT).show()
      return
    }

    // Cancel any pending call countdown before starting new voice action
    cancelPendingCall()

    // Duck active radio / video while user speaks
    radioManager.duckVolume()

    isActivelyListening = true
    recognizedSinhalaText = ""
    lastParsedIntent = null
    audioRmsLevel = 0f

    logKioskEvent("VoiceManager listening started for Sinhala (si-LK)...")

    voiceManager.startListening(
      onResult = { resultText ->
        recognizedSinhalaText = resultText
        val parsed = parseSinhalaVoiceIntent(resultText)
        lastParsedIntent = parsed
        logKioskEvent("Voice recognized: '$resultText' -> Intent: ${parsed.intent}")

        // Execute action & speak Sinhala confirmation
        handleParsedVoiceIntent(parsed)
      },
      onError = { error ->
        logKioskEvent("Voice Recognition error: $error")
        radioManager.restoreVolume()
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
          if (recognizedSinhalaText.isEmpty()) {
            isActivelyListening = false
          }
        }, 1500)
      },
      onRmsChanged = { rms ->
        audioRmsLevel = rms
      }
    )
  }

  /**
   * Executes appropriate action according to parsed voice intent.
   * Requirement 2:
   * - CALL: Match contact via ContactManager, TTS speak "[Name] ට කෝල් එකක් ගන්නවා", wait 3 seconds (Home to cancel), then ACTION_CALL.
   * - RADIO: Extract station name (Shraddha/Lakviru), TTS confirm, start RadioManager.
   * - YOUTUBE: Extract search query, fetch video ID via fetchYouTubeId(query), TTS confirm, open WebView.
   * - STOP: Stop ExoPlayer & WebView media, cancel pending call timers.
   */
  private fun handleParsedVoiceIntent(command: ParsedVoiceCommand) {
    when (command.intent) {
      VoiceIntent.CALL -> {
        radioManager.stop()
        youtubePlayerManager.pauseVideo()

        // 1. Fetch contacts and perform fuzzy matching
        val allContacts = contactManager.getAllContacts()
        val bestMatch = contactManager.findBestMatch(command.rawText, allContacts)

        val targetDisplayName = bestMatch?.name ?: contactManager.extractTargetNameFromCallQuery(command.rawText)
        val targetPhoneNumber = bestMatch?.phoneNumber ?: "18005550199"

        logKioskEvent("Call Intent: Matched contact '$targetDisplayName' -> $targetPhoneNumber")

        // 2. Start 3-second call countdown with TTS confirmation
        startCallCountdown(targetDisplayName, targetPhoneNumber)
      }

      VoiceIntent.RADIO -> {
        cancelPendingCall()
        youtubePlayerManager.stop()

        val rawLower = command.rawText.lowercase()
        val isLakviru = rawLower.contains("ලක්විරු") || rawLower.contains("lakviru")

        if (isLakviru) {
          val station = configuredStationsForVoice.firstOrNull { it.id == RadioStationPreset.LAKVIRU_ID }
          station?.let {
            radioManager.playStation(it)
            logKioskEvent("Radio Voice Trigger: ${it.stationName} (${it.sinhalaTitle})")
            voiceManager.speak("${it.sinhalaTitle} ක්‍රියාත්මක කරමින් පවතී") {
              logKioskEvent("TTS: ${it.sinhalaTitle} ක්‍රියාත්මක කරමින් පවතී")
            }
          }
        } else {
          val station = configuredStationsForVoice.firstOrNull { it.id == RadioStationPreset.SHRADDHA_ID }
          station?.let {
            radioManager.playStation(it)
            logKioskEvent("Radio Voice Trigger: ${it.stationName} (${it.sinhalaTitle})")
            voiceManager.speak("${it.sinhalaTitle} ක්‍රියාත්මක කරමින් පවතී") {
              logKioskEvent("TTS: ${it.sinhalaTitle} ක්‍රියාත්මක කරමින් පවතී")
            }
          }
        }
      }

      VoiceIntent.YOUTUBE -> {
        cancelPendingCall()
        radioManager.stop()

        val searchQuery = extractYouTubeQuery(command.rawText)
        val (videoId, videoTitle) = fetchYouTubeId(searchQuery)

        logKioskEvent("YouTube Voice Trigger: '$searchQuery' -> Video ID: $videoId ($videoTitle)")
        youtubePlayerManager.loadVideo(videoId, videoTitle)

        voiceManager.speak("$videoTitle වාදනය කරමින් පවතී") {
          logKioskEvent("TTS: $videoTitle වාදනය කරමින් පවතී")
        }
      }

      VoiceIntent.STOP -> {
        cancelPendingCall("Voice Stop Command")
        radioManager.stop()
        youtubePlayerManager.stop()
        voiceManager.speak("නතර කර ඇත") {
          logKioskEvent("TTS: නතර කර ඇත (Stopped)")
        }
      }

      VoiceIntent.UNKNOWN -> {
        radioManager.restoreVolume()
        if (command.rawText.isNotEmpty()) {
          voiceManager.speak("තේරුම් ගැනීමට නොහැකි විය") {
            logKioskEvent("TTS: නොදන්නා විධානයකි (Unknown command)")
          }
        }
      }
    }
  }

  /**
   * Starts a 3-second countdown before firing the phone call.
   * TTS confirms: "[Name] ට කෝල් එකක් ගන්නවා".
   * Pressing the physical Home button or on-screen cancel button cancels the call.
   */
  private fun startCallCountdown(targetName: String, phoneNumber: String) {
    cancelPendingCall()

    pendingCallTargetName = targetName
    pendingCallNumber = phoneNumber
    pendingCallCountdownSeconds = 3

    // TTS Confirmation in Sinhala
    val ttsText = "$targetName ට කෝල් එකක් ගන්නවා"
    voiceManager.speak(ttsText) {
      logKioskEvent("TTS: $ttsText")
    }

    // Schedule 1-second interval countdown
    val countdownRunnable = object : Runnable {
      override fun run() {
        if (pendingCallCountdownSeconds > 1) {
          pendingCallCountdownSeconds--
          mainHandler.postDelayed(this, 1000)
        } else if (pendingCallCountdownSeconds == 1) {
          pendingCallCountdownSeconds = 0
          val number = pendingCallNumber ?: phoneNumber
          val name = pendingCallTargetName ?: targetName
          pendingCallTargetName = null
          pendingCallNumber = null
          logKioskEvent("Countdown complete. Firing ACTION_CALL for $name ($number)")
          executeDirectCall(number)
        }
      }
    }

    pendingCallRunnable = countdownRunnable
    mainHandler.postDelayed(countdownRunnable, 1000)
  }

  /**
   * Cancels any pending call countdown and stops timer.
   */
  private fun cancelPendingCall(reason: String = "User Action") {
    if (pendingCallCountdownSeconds > 0 || pendingCallRunnable != null) {
      pendingCallRunnable?.let { mainHandler.removeCallbacks(it) }
      pendingCallRunnable = null
      val target = pendingCallTargetName
      pendingCallTargetName = null
      pendingCallNumber = null
      pendingCallCountdownSeconds = 0
      logKioskEvent("Pending call to '$target' was CANCELLED ($reason).")
      voiceManager.speak("ඇමතුම අවලංගු කරන ලදී") {
        logKioskEvent("TTS: ඇමතුම අවලංගු කරන ලදී (Call cancelled)")
      }
    }
  }

  /**
   * Fires ACTION_CALL intent with CALL_PHONE permission, falling back to ACTION_DIAL if not granted.
   */
  private fun executeDirectCall(phoneNumber: String) {
    try {
      val hasCallPermission = ContextCompat.checkSelfPermission(
        this,
        Manifest.permission.CALL_PHONE
      ) == PackageManager.PERMISSION_GRANTED

      val intent = if (hasCallPermission) {
        Intent(Intent.ACTION_CALL, Uri.parse("tel:$phoneNumber")).apply {
          flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
      } else {
        Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phoneNumber")).apply {
          flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
      }
      startActivity(intent)
      logKioskEvent("ACTION_CALL launched successfully for $phoneNumber")
    } catch (e: Exception) {
      Log.e(TAG, "Error executing phone call", e)
      logKioskEvent("Error initiating call: ${e.message}")
    }
  }

  /**
   * Extracts query string from Sinhala YouTube voice commands.
   */
  private fun extractYouTubeQuery(rawQuery: String): String {
    var cleaned = rawQuery.trim()
    val prefixes = listOf("යූටියුබ් එකෙන්", "යූටියුබ්", "youtube", "play", "දාන්න", "දමන්න", "වාදනය කරන්න")
    for (prefix in prefixes) {
      if (cleaned.startsWith(prefix, ignoreCase = true)) {
        cleaned = cleaned.substring(prefix.length).trim()
      }
    }
    return cleaned.ifBlank { rawQuery.trim() }
  }

  /**
   * Placeholder function to map Sinhala topic/search queries to YouTube Video IDs & titles.
   */
  fun fetchYouTubeId(query: String): Pair<String, String> {
    val q = query.lowercase()
    return when {
      q.contains("පිරිත්") || q.contains("pirith") || q.contains("මහා පිරිත්") -> {
        Pair("jNQXAC9IVRw", "මහා පිරිත් දේශනාව")
      }
      q.contains("බණ") || q.contains("bana") || q.contains("ධර්ම") -> {
        Pair("dQw4w9WgXcQ", "සද්ධර්ම දේශනාව")
      }
      q.contains("සින්දු") || q.contains("ගීත") || q.contains("song") -> {
        Pair("2Vv-BfVoq4g", "ශ්‍රී ලංකා සම්භාව්‍ය ගීත")
      }
      else -> {
        Pair(
          YouTubePlayerManager.PRESET_MAHA_PIRITHA_ID,
          if (query.isNotBlank()) query else "මහා පිරිත් දේශනාව"
        )
      }
    }
  }

  @Suppress("DEPRECATION", "MissingSuperCall")
  override fun onBackPressed() {
    if (pendingCallCountdownSeconds > 0) {
      cancelPendingCall("Back Button")
      return
    }
    if (isActivelyListening) {
      voiceManager.stopListening()
      isActivelyListening = false
      radioManager.restoreVolume()
      logKioskEvent("Voice listening cancelled via back button.")
      return
    }
    if (youtubePlayerManager.playerState.value.currentVideoId != null) {
      youtubePlayerManager.stop()
      logKioskEvent("YouTube dismissed via back button.")
      return
    }
    if (radioManager.playbackState.value.isPlaying) {
      radioManager.stop()
      logKioskEvent("Radio stopped via back button.")
      return
    }
    logKioskEvent("onBackPressed() invoked -> Blocked.")
    Log.d(TAG, "onBackPressed() called directly -> Ignored by Kiosk.")
  }

  @Suppress("DEPRECATION")
  private fun applyStickyImmersiveMode() {
    try {
      WindowCompat.setDecorFitsSystemWindows(window, false)
      val insetsController = WindowCompat.getInsetsController(window, window.decorView)
      insetsController.systemBarsBehavior =
        WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
      insetsController.hide(WindowInsetsCompat.Type.systemBars())

      window.decorView.systemUiVisibility = (
        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
          or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
          or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
          or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
          or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
          or View.SYSTEM_UI_FLAG_FULLSCREEN
      )
    } catch (e: Exception) {
      Log.e(TAG, "Error applying immersive mode", e)
    }
  }

  private fun logKioskEvent(message: String) {
    val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
    val entry = "[$time] $message"
    kioskEventLogs.add(0, entry)
    if (kioskEventLogs.size > 50) {
      kioskEventLogs.removeAt(kioskEventLogs.lastIndex)
    }
  }

  override fun onDestroy() {
    super.onDestroy()
    voiceManager.destroy()
    radioManager.release()
    youtubePlayerManager.destroy()
  }
}

/**
 * Massive, Screen-Filling Glowing Sinhala Voice Listening UI
 */
@Composable
fun ScreenFillingListeningIndicator(
  rmsLevel: Float,
  recognizedText: String,
  parsedCommand: ParsedVoiceCommand?,
  onCancel: () -> Unit,
  onSpeakPromptAgain: () -> Unit
) {
  // Glowing pulse animation
  val infiniteTransition = rememberInfiniteTransition(label = "pulse_transition")
  val pulseScale by infiniteTransition.animateFloat(
    initialValue = 1f,
    targetValue = 1.35f,
    animationSpec = infiniteRepeatable(
      animation = tween(1200, easing = FastOutSlowInEasing),
      repeatMode = RepeatMode.Reverse
    ),
    label = "pulse_scale"
  )

  val glowAlpha by infiniteTransition.animateFloat(
    initialValue = 0.25f,
    targetValue = 0.7f,
    animationSpec = infiniteRepeatable(
      animation = tween(1200, easing = LinearEasing),
      repeatMode = RepeatMode.Reverse
    ),
    label = "glow_alpha"
  )

  // Reactive scaling based on speech RMS volume
  val dynamicScale = (1f + (rmsLevel.coerceIn(0f, 10f) / 25f)).coerceIn(1f, 1.4f)

  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(Color(0xF0001E2F)) // High-contrast translucent dark backdrop
      .clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        onClick = onCancel
      )
      .testTag("massive_listening_overlay"),
    contentAlignment = Alignment.Center
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 24.dp, vertical = 32.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center
    ) {
      // 1. Massive Glowing Microphone Pulse Rings
      Box(
        modifier = Modifier
          .size(240.dp)
          .testTag("glowing_mic_container"),
        contentAlignment = Alignment.Center
      ) {
        // Outer glowing pulse ring
        Box(
          modifier = Modifier
            .size(240.dp)
            .scale(pulseScale * dynamicScale)
            .clip(CircleShape)
            .background(
              Brush.radialGradient(
                colors = listOf(
                  Color(0xFF38BDF8).copy(alpha = glowAlpha),
                  Color(0xFF0284C7).copy(alpha = glowAlpha * 0.4f),
                  Color.Transparent
                )
              )
            )
        )

        // Middle pulse ring
        Box(
          modifier = Modifier
            .size(170.dp)
            .scale(pulseScale * 0.9f)
            .clip(CircleShape)
            .background(Color(0xFF004977).copy(alpha = 0.6f))
            .border(2.dp, Color(0xFF80D5FF).copy(alpha = glowAlpha), CircleShape)
        )

        // Center Mic Button (Click to restart listening if needed)
        Surface(
          modifier = Modifier
            .size(120.dp)
            .clickable { onSpeakPromptAgain() }
            .testTag("center_mic_button"),
          shape = CircleShape,
          color = Color(0xFF004977),
          shadowElevation = 16.dp,
          border = androidx.compose.foundation.BorderStroke(4.dp, Color(0xFF80D5FF))
        ) {
          Box(contentAlignment = Alignment.Center) {
            Icon(
              imageVector = Icons.Default.Mic,
              contentDescription = "Active Sinhala Voice Listening",
              tint = Color.White,
              modifier = Modifier.size(64.dp)
            )
          }
        }
      }

      Spacer(modifier = Modifier.height(28.dp))

      // 2. Primary Sinhala Text Status
      Text(
        text = "සවන් දෙමින් පවතී...",
        color = Color.White,
        fontSize = 32.sp,
        fontWeight = FontWeight.Black,
        textAlign = TextAlign.Center,
        letterSpacing = 0.5.sp
      )

      Text(
        text = "කරුණාකර දැන් කතා කරන්න (Listening in Sinhala)",
        color = Color(0xFFD1E4FF),
        fontSize = 16.sp,
        fontWeight = FontWeight.Medium,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 8.dp)
      )

      Spacer(modifier = Modifier.height(24.dp))

      // 3. Live Recognized Sinhala Speech Display & Intent Badge
      if (recognizedText.isNotEmpty()) {
        Surface(
          shape = RoundedCornerShape(20.dp),
          color = Color(0xFF0F2E47),
          border = androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFF38BDF8)),
          modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .testTag("recognized_sinhala_speech_card")
        ) {
          Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
          ) {
            Text(
              text = "\"$recognizedText\"",
              color = Color(0xFFE0F2FE),
              fontSize = 22.sp,
              fontWeight = FontWeight.Bold,
              textAlign = TextAlign.Center
            )

            if (parsedCommand != null) {
              Spacer(modifier = Modifier.height(10.dp))
              Surface(
                shape = RoundedCornerShape(12.dp),
                color = when (parsedCommand.intent) {
                  VoiceIntent.CALL -> Color(0xFF2563EB)
                  VoiceIntent.RADIO -> Color(0xFF059669)
                  VoiceIntent.YOUTUBE -> Color(0xFFDC2626)
                  VoiceIntent.STOP -> Color(0xFF7C3AED)
                  VoiceIntent.UNKNOWN -> Color(0xFF475569)
                }
              ) {
                Row(
                  modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                  Icon(
                    imageVector = when (parsedCommand.intent) {
                      VoiceIntent.CALL -> Icons.Default.Call
                      VoiceIntent.RADIO -> Icons.Default.Radio
                      VoiceIntent.YOUTUBE -> Icons.Default.PlayCircle
                      VoiceIntent.STOP -> Icons.Default.Stop
                      VoiceIntent.UNKNOWN -> Icons.Default.HelpOutline
                    },
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                  )
                  Text(
                    text = "INTENT: ${parsedCommand.intent.name}",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.sp
                  )
                }
              }
            }
          }
        }
      }

      Spacer(modifier = Modifier.height(28.dp))

      // 4. Tap Anywhere to Dismiss / Close Button
      OutlinedButton(
        onClick = onCancel,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF94A3B8)),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF475569)),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.testTag("dismiss_listening_button")
      ) {
        Icon(Icons.Default.Close, contentDescription = "Close", modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(6.dp))
        Text("නතර කරන්න (Dismiss)", fontSize = 14.sp, color = Color(0xFFE2E8F0))
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GeneralSettingsDialog(
  stations: List<RadioStationPreset>,
  onSave: (List<RadioStationPreset>) -> Unit,
  onReset: () -> Unit,
  youtubeSettings: YouTubeSettings,
  onSaveYouTubeSettings: (YouTubeSettings) -> Unit,
  onResetYouTubeSettings: () -> Unit,
  onDismiss: () -> Unit
) {
  var draftStations by remember(stations) { mutableStateOf(stations) }
  var draftYouTubeSettings by remember(youtubeSettings) { mutableStateOf(youtubeSettings) }
  var validationError by remember { mutableStateOf<String?>(null) }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("General Settings") },
    text = {
      Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        Text("Radio", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        draftStations.forEachIndexed { index, station ->
          Text(station.id.replace('_', ' ').uppercase(), fontWeight = FontWeight.Bold)
          OutlinedTextField(
            value = station.stationName,
            onValueChange = { value ->
              draftStations = draftStations.toMutableList().also {
                it[index] = station.copy(stationName = value)
              }
            },
            label = { Text("English name") },
            singleLine = true
          )
          OutlinedTextField(
            value = station.sinhalaTitle,
            onValueChange = { value ->
              draftStations = draftStations.toMutableList().also {
                it[index] = station.copy(sinhalaTitle = value)
              }
            },
            label = { Text("Sinhala title") },
            singleLine = true
          )
          OutlinedTextField(
            value = station.streamUrl,
            onValueChange = { value ->
              draftStations = draftStations.toMutableList().also {
                it[index] = station.copy(streamUrl = value)
              }
            },
            label = { Text("Stream URL") },
            singleLine = true
          )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text("YouTube", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Text("Play videos automatically")
          Switch(
            checked = draftYouTubeSettings.autoPlay,
            onCheckedChange = { enabled ->
              draftYouTubeSettings = draftYouTubeSettings.copy(autoPlay = enabled)
            }
          )
        }
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Text("Show player controls")
          Switch(
            checked = draftYouTubeSettings.showControls,
            onCheckedChange = { enabled ->
              draftYouTubeSettings = draftYouTubeSettings.copy(showControls = enabled)
            }
          )
        }
        Text("Default volume: ${draftYouTubeSettings.defaultVolume}%")
        Slider(
          value = draftYouTubeSettings.defaultVolume.toFloat(),
          onValueChange = { volume ->
            draftYouTubeSettings = draftYouTubeSettings.copy(defaultVolume = volume.toInt())
          },
          valueRange = 0f..100f,
          steps = 9
        )
        validationError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
      }
    },
    confirmButton = {
      TextButton(onClick = {
        val invalid = draftStations.firstOrNull {
          it.stationName.isBlank() || it.sinhalaTitle.isBlank() ||
            Uri.parse(it.streamUrl).scheme !in listOf("http", "https")
        }
        if (invalid != null) {
          validationError = "Enter a name, Sinhala title, and valid HTTP URL for every station."
        } else {
          onSave(draftStations)
          onSaveYouTubeSettings(draftYouTubeSettings)
          onDismiss()
        }
      }) { Text("Save") }
    },
    dismissButton = {
      Row {
        TextButton(onClick = {
          onReset()
          onResetYouTubeSettings()
          onDismiss()
        }) { Text("Reset") }
        TextButton(onClick = onDismiss) { Text("Cancel") }
      }
    }
  )
}

/**
 * Main Kiosk UI Composable styled with the "Vibrant Palette" design.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KioskHomeScreen(
  eventLogs: List<String>,
  homePressCount: Int,
  radioState: RadioPlaybackState,
  radioStations: List<RadioStationPreset> = RadioStationPreset.defaults,
  youtubeState: YouTubePlayerState,
  pendingCallTargetName: String? = null,
  pendingCallCountdownSeconds: Int = 0,
  onCancelPendingCall: () -> Unit = {},
  onLogEvent: (String) -> Unit,
  onTriggerVoice: () -> Unit,
  onPlayRadio: (RadioStationPreset) -> Unit,
  onSaveRadioSettings: (List<RadioStationPreset>) -> Unit = {},
  onResetRadioSettings: () -> Unit = {},
  youtubeSettings: YouTubeSettings = YouTubeSettings(),
  onSaveYouTubeSettings: (YouTubeSettings) -> Unit = {},
  onResetYouTubeSettings: () -> Unit = {},
  onStopRadio: () -> Unit,
  onPlayYouTube: (String, String) -> Unit,
  onStopYouTube: () -> Unit
) {
  val context = LocalContext.current
  var currentTime by remember { mutableStateOf(getFormattedTime()) }
  var currentDate by remember { mutableStateOf(getFormattedDate()) }
  var showDialerDialog by remember { mutableStateOf(false) }
  var showSosConfirmDialog by remember { mutableStateOf(false) }
  var showLogsDialog by remember { mutableStateOf(false) }
  var showAddContactDialog by remember { mutableStateOf(false) }
  var showMediaDialog by remember { mutableStateOf(false) }
  var showGeneralSettingsDialog by remember { mutableStateOf(false) }

  val quickContacts = remember {
    mutableStateListOf(
      QuickContactItem("Sarah", "15550192834", ContactAvatarBlueBg, ContactAvatarBlueBorder),
      QuickContactItem("Doctor", "18005550144", ContactAvatarGreyBg, ContactAvatarGreyBorder)
    )
  }

  LaunchedEffect(Unit) {
    while (true) {
      currentTime = getFormattedTime()
      currentDate = getFormattedDate()
      delay(1000)
    }
  }

  val requiredPermissions = remember {
    arrayOf(
      Manifest.permission.READ_PHONE_STATE,
      Manifest.permission.CALL_PHONE,
      Manifest.permission.READ_CONTACTS,
      Manifest.permission.RECORD_AUDIO
    )
  }

  var permissionsGranted by remember {
    mutableStateOf(
      requiredPermissions.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
      }
    )
  }

  val permissionLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.RequestMultiplePermissions()
  ) { results ->
    val allGranted = results.values.all { it }
    permissionsGranted = allGranted
    if (allGranted) {
      onLogEvent("All required Kiosk permissions granted.")
      Toast.makeText(context, "Permissions granted successfully", Toast.LENGTH_SHORT).show()
    } else {
      onLogEvent("Some permissions were denied.")
    }
  }

  Scaffold(
    modifier = Modifier
      .fillMaxSize()
      .testTag("kiosk_root_scaffold"),
    containerColor = VibrantBackground,
    bottomBar = {
      Surface(
        modifier = Modifier
          .fillMaxWidth()
          .testTag("bottom_safe_mode_bar"),
        color = BottomBarBg,
        border = androidx.compose.foundation.BorderStroke(1.dp, BottomBarBorder)
      ) {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp, horizontal = 16.dp),
          horizontalArrangement = Arrangement.Center,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Surface(
            shape = RoundedCornerShape(50),
            color = Color.White,
            shadowElevation = 2.dp,
            border = androidx.compose.foundation.BorderStroke(1.dp, BottomBarBorder),
            modifier = Modifier
              .clickable { showLogsDialog = true }
              .testTag("safe_mode_pill_button")
          ) {
            Row(
              modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
              Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = "Safe Mode Active",
                tint = SafeModeBadgeText,
                modifier = Modifier.size(18.dp)
              )
              Text(
                text = "SAFE MODE ACTIVE",
                color = SafeModeBadgeText,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp
              )
            }
          }
        }
      }
    }
  ) { padding ->
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(padding)
        .padding(horizontal = 16.dp, vertical = 8.dp),
      horizontalAlignment = Alignment.CenterHorizontally
    ) {
      // 1. Top Status Header
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text(
          text = currentTime,
          fontSize = 14.sp,
          fontWeight = FontWeight.Medium,
          color = VibrantTextSecondary
        )

        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          Icon(
            imageVector = Icons.Default.SignalCellular4Bar,
            contentDescription = "Cellular",
            tint = VibrantTextSecondary,
            modifier = Modifier.size(18.dp)
          )
          Icon(
            imageVector = Icons.Default.Wifi,
            contentDescription = "WiFi",
            tint = VibrantTextSecondary,
            modifier = Modifier.size(18.dp)
          )
          Icon(
            imageVector = Icons.Default.BatteryFull,
            contentDescription = "Battery",
            tint = VibrantTextSecondary,
            modifier = Modifier.size(18.dp)
          )
          IconButton(
            onClick = { showLogsDialog = true },
            modifier = Modifier
              .size(24.dp)
              .testTag("diagnostics_icon_button")
          ) {
            Icon(
              imageVector = Icons.Default.Terminal,
              contentDescription = "Logs",
              tint = SafeModeBadgeText,
              modifier = Modifier.size(18.dp)
            )
          }
        }
      }

      // 1.5 Pending Call Countdown Warning Banner (3-second cancellation window)
      if (pendingCallCountdownSeconds > 0 && !pendingCallTargetName.isNullOrEmpty()) {
        Card(
          modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
            .testTag("pending_call_countdown_banner"),
          colors = CardDefaults.cardColors(containerColor = Color(0xFFEFF6FF)),
          shape = RoundedCornerShape(20.dp),
          border = androidx.compose.foundation.BorderStroke(2.dp, Color(0xFF3B82F6))
        ) {
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
          ) {
            Row(
              modifier = Modifier.weight(1f),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
              Surface(
                shape = CircleShape,
                color = Color(0xFF2563EB),
                modifier = Modifier.size(44.dp)
              ) {
                Box(contentAlignment = Alignment.Center) {
                  Icon(
                    Icons.Default.Call,
                    contentDescription = "Calling",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                  )
                }
              }

              Column {
                Text(
                  text = "$pendingCallTargetName ට කෝල් එකක් ගන්නවා...",
                  fontSize = 17.sp,
                  fontWeight = FontWeight.Bold,
                  color = Color(0xFF1E3A8A)
                )
                Text(
                  text = "තත්පර $pendingCallCountdownSeconds කින් අමතනු ලැබේ (Press Home to Cancel)",
                  fontSize = 13.sp,
                  color = Color(0xFF3B82F6),
                  fontWeight = FontWeight.Medium
                )
              }
            }

            Button(
              onClick = onCancelPendingCall,
              colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)),
              shape = RoundedCornerShape(12.dp),
              modifier = Modifier.testTag("cancel_pending_call_button")
            ) {
              Icon(Icons.Default.Close, contentDescription = "Cancel", modifier = Modifier.size(16.dp))
              Spacer(modifier = Modifier.width(4.dp))
              Text("අවලංගු කරන්න", fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
          }
        }
      }

      // 2. Large Time and Date Display
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
      ) {
        Text(
          text = currentTime,
          color = VibrantTimeDisplay,
          fontSize = 54.sp,
          fontWeight = FontWeight.Light,
          letterSpacing = (-1).sp
        )
        Text(
          text = currentDate,
          color = VibrantTextSecondary,
          fontSize = 16.sp,
          fontWeight = FontWeight.Normal
        )
      }

      // 3. Persistent Radio Mini-Player Bar (When playing or buffering)
      if (radioState.isPlaying || radioState.isBuffering) {
        RadioMiniPlayerBar(
          state = radioState,
          onStop = onStopRadio,
          onSwitchPreset = {
            val currentIndex = radioStations.indexOfFirst {
              it.stationName == radioState.currentStationName
            }
            if (currentIndex >= 0 && radioStations.isNotEmpty()) {
              onPlayRadio(radioStations[(currentIndex + 1) % radioStations.size])
            } else {
              onPlayRadio(radioStations.firstOrNull() ?: RadioStationPreset.SHRADDHA_FM)
            }
          }
        )
        Spacer(modifier = Modifier.height(10.dp))
      }

      // Permission Warning Banner
      if (!permissionsGranted) {
        Card(
          modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp)
            .testTag("permission_banner_card"),
          colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3CD)),
          shape = RoundedCornerShape(16.dp),
          border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFFEBAA))
        ) {
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
          ) {
            Row(
              modifier = Modifier.weight(1f),
              verticalAlignment = Alignment.CenterVertically
            ) {
              Icon(
                Icons.Default.Warning,
                contentDescription = null,
                tint = Color(0xFFB45309),
                modifier = Modifier.size(22.dp)
              )
              Spacer(modifier = Modifier.width(8.dp))
              Text(
                text = "Grant permissions for phone & audio",
                color = Color(0xFF92400E),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
              )
            }
            Button(
              onClick = { permissionLauncher.launch(requiredPermissions) },
              colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF59E0B)),
              contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
              modifier = Modifier.testTag("grant_permissions_button")
            ) {
              Text(text = "Grant", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
          }
        }
      }

      // 4. 2x2 Vibrant Action Cards Grid
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .weight(1f),
        verticalArrangement = Arrangement.spacedBy(12.dp)
      ) {
        OutlinedButton(
          onClick = { showGeneralSettingsDialog = true },
          modifier = Modifier.fillMaxWidth().testTag("radio_settings_button")
        ) {
          Icon(Icons.Default.Settings, contentDescription = "Settings")
          Spacer(modifier = Modifier.width(8.dp))
          Text("General Settings")
        }

        Row(
          modifier = Modifier
            .fillMaxWidth()
            .weight(1f),
          horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
          VibrantActionCard(
            title = "Phone",
            subtitle = "ඇමතුම් ගන්න",
            icon = Icons.Default.Call,
            backgroundColor = PhoneCardBg,
            textColor = PhoneCardText,
            iconBoxColor = PhoneIconBox,
            modifier = Modifier
              .weight(1f)
              .fillMaxHeight()
              .testTag("phone_dialer_card"),
            onClick = { showDialerDialog = true }
          )

          VibrantActionCard(
            title = "Radio & TV",
            subtitle = "ශ්‍රද්ධා / ලක්විරු / පිරිත්",
            icon = Icons.Default.Radio,
            backgroundColor = MessagesCardBg,
            textColor = MessagesCardText,
            iconBoxColor = MessagesIconBox,
            modifier = Modifier
              .weight(1f)
              .fillMaxHeight()
              .testTag("radio_media_card"),
            onClick = {
              showMediaDialog = true
            }
          )
        }

        Row(
          modifier = Modifier
            .fillMaxWidth()
            .weight(1f),
          horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
          // Photos / Sinhala Voice trigger card
          VibrantActionCard(
            title = "Voice (සිංහල)",
            subtitle = "Home Button / Tap to Speak",
            icon = Icons.Default.Mic,
            backgroundColor = VoiceCardBg,
            textColor = VoiceCardText,
            iconBoxColor = VoiceIconBox,
            modifier = Modifier
              .weight(1f)
              .fillMaxHeight()
              .testTag("photos_voice_card"),
            onClick = {
              onTriggerVoice()
            }
          )

          VibrantActionCard(
            title = "HELP",
            subtitle = "Emergency (911)",
            icon = Icons.Default.MedicalServices,
            backgroundColor = HelpCardBg,
            textColor = HelpIconBox,
            iconBoxColor = HelpIconBox,
            isUppercase = true,
            modifier = Modifier
              .weight(1f)
              .fillMaxHeight()
              .testTag("sos_emergency_card"),
            onClick = { showSosConfirmDialog = true }
          )
        }
      }

      Spacer(modifier = Modifier.height(10.dp))

      // 5. Quick Contacts Section
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .padding(bottom = 4.dp)
      ) {
        Text(
          text = "QUICK CONTACTS",
          color = VibrantTextSecondary,
          fontSize = 12.sp,
          fontWeight = FontWeight.Bold,
          letterSpacing = 1.5.sp,
          modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
        )

        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
          horizontalArrangement = Arrangement.spacedBy(16.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          quickContacts.forEach { contact ->
            QuickContactAvatar(
              name = contact.name,
              bgColor = contact.bgColor,
              borderColor = contact.borderColor,
              onClick = {
                onLogEvent("Calling quick contact: ${contact.name} (${contact.phone})")
                launchDialer(context, contact.phone)
              }
            )
          }

          AddContactButton(
            onClick = { showAddContactDialog = true }
          )
        }
      }
    }
  }

  // --- Dialogs ---

  // Media (Radio & YouTube Bana / Pirith) Dialog
  if (showMediaDialog) {
    AlertDialog(
      onDismissRequest = { showMediaDialog = false },
      containerColor = VibrantSurface,
      shape = RoundedCornerShape(28.dp),
      icon = {
        Surface(
          shape = CircleShape,
          color = Color(0xFFE8F5E9),
          modifier = Modifier.size(56.dp)
        ) {
          Box(contentAlignment = Alignment.Center) {
            Icon(
              Icons.Default.Radio,
              contentDescription = null,
              tint = Color(0xFF2E7D32),
              modifier = Modifier.size(32.dp)
            )
          }
        }
      },
      title = {
        Text(
          "ගුවන්විදුලි සහ වීඩියෝ",
          fontWeight = FontWeight.Bold,
          fontSize = 22.sp,
          color = VibrantTimeDisplay,
          textAlign = TextAlign.Center
        )
      },
      text = {
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 320.dp)
            .verticalScroll(rememberScrollState()),
          verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          // Shraddha FM button
          Button(
            onClick = {
              showMediaDialog = false
              onPlayRadio(radioStations.firstOrNull { it.id == RadioStationPreset.SHRADDHA_ID } ?: RadioStationPreset.SHRADDHA_FM)
            },
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
          ) {
            Icon(Icons.Default.Radio, contentDescription = null, tint = Color.White)
            Spacer(modifier = Modifier.width(10.dp))
            Text(radioStations.firstOrNull { it.id == RadioStationPreset.SHRADDHA_ID }?.stationName ?: "Shraddha FM", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
          }

          // Lakviru FM button
          Button(
            onClick = {
              showMediaDialog = false
              onPlayRadio(radioStations.firstOrNull { it.id == RadioStationPreset.LAKVIRU_ID } ?: RadioStationPreset.LAKVIRU_FM)
            },
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00796B)),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
          ) {
            Icon(Icons.Default.Radio, contentDescription = null, tint = Color.White)
            Spacer(modifier = Modifier.width(10.dp))
            Text(radioStations.firstOrNull { it.id == RadioStationPreset.LAKVIRU_ID }?.stationName ?: "Lakviru FM", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
          }

          // YouTube Pirith / Bana Video button
          Button(
            onClick = {
              showMediaDialog = false
              onPlayYouTube(YouTubePlayerManager.PRESET_MAHA_PIRITHA_ID, "මහා පිරිත් දේශනාව")
            },
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828)),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
          ) {
            Icon(Icons.Default.PlayCircle, contentDescription = null, tint = Color.White)
            Spacer(modifier = Modifier.width(10.dp))
            Text("මහා පිරිත් දේශනාව", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
          }
        }
      },
      confirmButton = {},
      dismissButton = {
        OutlinedButton(
          onClick = { showMediaDialog = false },
          shape = RoundedCornerShape(16.dp)
        ) {
          Text("වසන්න (Close)", fontSize = 15.sp, color = VibrantTextSecondary)
        }
      }
    )
  }

  if (showSosConfirmDialog) {
    AlertDialog(
      onDismissRequest = { showSosConfirmDialog = false },
      containerColor = VibrantSurface,
      shape = RoundedCornerShape(28.dp),
      icon = {
        Surface(
          shape = CircleShape,
          color = HelpCardBg,
          modifier = Modifier.size(56.dp)
        ) {
          Box(contentAlignment = Alignment.Center) {
            Icon(
              Icons.Default.MedicalServices,
              contentDescription = null,
              tint = HelpIconBox,
              modifier = Modifier.size(32.dp)
            )
          }
        }
      },
      title = {
        Text(
          "Call Emergency Help?",
          fontWeight = FontWeight.Bold,
          fontSize = 22.sp,
          color = HelpCardText,
          textAlign = TextAlign.Center
        )
      },
      text = {
        Text(
          "This will immediately place a call to Emergency Services (911). When the call ends, you will automatically return to the Kiosk Launcher.",
          fontSize = 16.sp,
          color = VibrantTextSecondary,
          textAlign = TextAlign.Center
        )
      },
      confirmButton = {
        Button(
          onClick = {
            showSosConfirmDialog = false
            onLogEvent("Emergency SOS call placed (911).")
            launchDialer(context, "911")
          },
          colors = ButtonDefaults.buttonColors(containerColor = HelpIconBox),
          shape = RoundedCornerShape(16.dp),
          modifier = Modifier.testTag("confirm_sos_button")
        ) {
          Text("CALL 911 NOW", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Color.White)
        }
      },
      dismissButton = {
        OutlinedButton(
          onClick = { showSosConfirmDialog = false },
          shape = RoundedCornerShape(16.dp),
          modifier = Modifier.testTag("cancel_sos_button")
        ) {
          Text("Cancel", fontSize = 15.sp, color = VibrantTextSecondary)
        }
      }
    )
  }

  if (showGeneralSettingsDialog) {
    GeneralSettingsDialog(
      stations = radioStations,
      onSave = onSaveRadioSettings,
      onReset = onResetRadioSettings,
      youtubeSettings = youtubeSettings,
      onSaveYouTubeSettings = onSaveYouTubeSettings,
      onResetYouTubeSettings = onResetYouTubeSettings,
      onDismiss = { showGeneralSettingsDialog = false }
    )
  }

  if (showDialerDialog) {
    var dialNumber by remember { mutableStateOf("") }
    AlertDialog(
      onDismissRequest = { showDialerDialog = false },
      containerColor = VibrantSurface,
      shape = RoundedCornerShape(28.dp),
      title = {
        Text(
          "Phone Dialer",
          fontWeight = FontWeight.Bold,
          fontSize = 22.sp,
          color = VibrantTimeDisplay,
          textAlign = TextAlign.Center
        )
      },
      text = {
        Column(
          modifier = Modifier.fillMaxWidth(),
          horizontalAlignment = Alignment.CenterHorizontally
        ) {
          Surface(
            shape = RoundedCornerShape(16.dp),
            color = PhoneCardBg,
            modifier = Modifier
              .fillMaxWidth()
              .padding(bottom = 12.dp)
          ) {
            Text(
              text = if (dialNumber.isEmpty()) "Enter Number" else dialNumber,
              fontSize = 24.sp,
              fontWeight = FontWeight.Bold,
              textAlign = TextAlign.Center,
              modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
              color = if (dialNumber.isEmpty()) VibrantTextSecondary else PhoneCardText
            )
          }

          val keys = listOf(
            listOf("1", "2", "3"),
            listOf("4", "5", "6"),
            listOf("7", "8", "9"),
            listOf("*", "0", "#")
          )

          keys.forEach { row ->
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 3.dp),
              horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
              row.forEach { digit ->
                Button(
                  onClick = { if (dialNumber.length < 15) dialNumber += digit },
                  modifier = Modifier
                    .weight(1f)
                    .height(52.dp)
                    .testTag("dialer_key_$digit"),
                  shape = RoundedCornerShape(14.dp),
                  colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE2E2E6))
                ) {
                  Text(
                    text = digit,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF191C20)
                  )
                }
              }
            }
          }

          Spacer(modifier = Modifier.height(8.dp))

          if (dialNumber.isNotEmpty()) {
            TextButton(
              onClick = { dialNumber = dialNumber.dropLast(1) },
              modifier = Modifier.testTag("dialer_backspace_button")
            ) {
              Icon(Icons.Default.Backspace, contentDescription = "Backspace", tint = VibrantTextSecondary)
              Spacer(modifier = Modifier.width(6.dp))
              Text("Delete", fontSize = 16.sp, color = VibrantTextSecondary)
            }
          }
        }
      },
      confirmButton = {
        Button(
          onClick = {
            if (dialNumber.isNotEmpty()) {
              showDialerDialog = false
              onLogEvent("Dialing number: $dialNumber.")
              launchDialer(context, dialNumber)
            }
          },
          enabled = dialNumber.isNotEmpty(),
          colors = ButtonDefaults.buttonColors(containerColor = PhoneIconBox),
          shape = RoundedCornerShape(16.dp),
          modifier = Modifier.testTag("dialer_call_action_button")
        ) {
          Icon(Icons.Default.Call, contentDescription = null, tint = Color.White)
          Spacer(modifier = Modifier.width(6.dp))
          Text("Call", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color.White)
        }
      },
      dismissButton = {
        OutlinedButton(
          onClick = { showDialerDialog = false },
          shape = RoundedCornerShape(16.dp)
        ) {
          Text("Close", fontSize = 16.sp, color = VibrantTextSecondary)
        }
      }
    )
  }

  if (showAddContactDialog) {
    var newName by remember { mutableStateOf("") }
    var newPhone by remember { mutableStateOf("") }

    AlertDialog(
      onDismissRequest = { showAddContactDialog = false },
      containerColor = VibrantSurface,
      shape = RoundedCornerShape(28.dp),
      title = {
        Text("Add Quick Contact", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = VibrantTimeDisplay)
      },
      text = {
        Column(
          modifier = Modifier.fillMaxWidth(),
          verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
          OutlinedTextField(
            value = newName,
            onValueChange = { newName = it },
            label = { Text("Name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
          )
          OutlinedTextField(
            value = newPhone,
            onValueChange = { newPhone = it },
            label = { Text("Phone Number") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
          )
        }
      },
      confirmButton = {
        Button(
          onClick = {
            if (newName.isNotBlank() && newPhone.isNotBlank()) {
              quickContacts.add(
                QuickContactItem(
                  newName,
                  newPhone,
                  ContactAvatarBlueBg,
                  ContactAvatarBlueBorder
                )
              )
              onLogEvent("Added quick contact: $newName ($newPhone)")
              showAddContactDialog = false
            }
          },
          colors = ButtonDefaults.buttonColors(containerColor = PhoneIconBox),
          shape = RoundedCornerShape(16.dp)
        ) {
          Text("Save", color = Color.White)
        }
      },
      dismissButton = {
        OutlinedButton(
          onClick = { showAddContactDialog = false },
          shape = RoundedCornerShape(16.dp)
        ) {
          Text("Cancel", color = VibrantTextSecondary)
        }
      }
    )
  }

  if (showLogsDialog) {
    AlertDialog(
      onDismissRequest = { showLogsDialog = false },
      containerColor = VibrantSurface,
      shape = RoundedCornerShape(28.dp),
      title = {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Text("Kiosk Event Log", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = VibrantTimeDisplay)
          Surface(
            shape = RoundedCornerShape(12.dp),
            color = Color(0xFFD1E4FF)
          ) {
            Text(
              "$homePressCount Home Triggers",
              color = PhoneIconBox,
              fontSize = 12.sp,
              fontWeight = FontWeight.Bold,
              modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
            )
          }
        }
      },
      text = {
        Column(modifier = Modifier.fillMaxWidth()) {
          Text(
            text = "Real-time Telephony & Sinhala Voice events:",
            fontSize = 13.sp,
            color = VibrantTextSecondary
          )
          Spacer(modifier = Modifier.height(8.dp))
          Surface(
            shape = RoundedCornerShape(14.dp),
            color = Color(0xFF1E293B),
            modifier = Modifier
              .fillMaxWidth()
              .height(260.dp)
              .padding(4.dp)
          ) {
            if (eventLogs.isEmpty()) {
              Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No events recorded yet.", color = Color.Gray, fontSize = 14.sp)
              }
            } else {
              LazyColumn(
                modifier = Modifier
                  .fillMaxSize()
                  .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
              ) {
                items(eventLogs) { logEntry ->
                  Text(
                    text = logEntry,
                    fontSize = 12.sp,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    color = when {
                      logEntry.contains("Voice") || logEntry.contains("TTS") -> Color(0xFF38BDF8)
                      logEntry.contains("Home") -> Color(0xFF93C5FD)
                      logEntry.contains("Call") -> Color(0xFF86EFAC)
                      logEntry.contains("Blocked") -> Color(0xFFFCA5A5)
                      else -> Color(0xFFE2E8F0)
                    }
                  )
                }
              }
            }
          }
        }
      },
      confirmButton = {
        Button(
          onClick = { showLogsDialog = false },
          colors = ButtonDefaults.buttonColors(containerColor = SafeModeBadgeText),
          shape = RoundedCornerShape(16.dp)
        ) {
          Text("Close", color = Color.White)
        }
      }
    )
  }
}

/**
 * Large action card styled with 32dp corner radii and vibrant palette tokens.
 */
@Composable
fun VibrantActionCard(
  title: String,
  subtitle: String? = null,
  icon: ImageVector,
  backgroundColor: Color,
  textColor: Color,
  iconBoxColor: Color,
  isUppercase: Boolean = false,
  modifier: Modifier = Modifier,
  onClick: () -> Unit
) {
  Card(
    modifier = modifier
      .clickable { onClick() },
    colors = CardDefaults.cardColors(containerColor = backgroundColor),
    shape = RoundedCornerShape(32.dp),
    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
  ) {
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(16.dp),
      verticalArrangement = Arrangement.Center,
      horizontalAlignment = Alignment.CenterHorizontally
    ) {
      Surface(
        modifier = Modifier.size(64.dp),
        shape = RoundedCornerShape(18.dp),
        color = iconBoxColor
      ) {
        Box(contentAlignment = Alignment.Center) {
          Icon(
            imageVector = icon,
            contentDescription = title,
            tint = Color.White,
            modifier = Modifier.size(36.dp)
          )
        }
      }

      Spacer(modifier = Modifier.height(10.dp))

      Text(
        text = if (isUppercase) title.uppercase() else title,
        color = textColor,
        fontSize = 20.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = if (isUppercase) 1.sp else 0.sp,
        textAlign = TextAlign.Center
      )

      if (subtitle != null) {
        Text(
          text = subtitle,
          color = textColor.copy(alpha = 0.8f),
          fontSize = 12.sp,
          fontWeight = FontWeight.Medium,
          textAlign = TextAlign.Center
        )
      }
    }
  }
}

@Composable
fun QuickContactAvatar(
  name: String,
  bgColor: Color,
  borderColor: Color,
  onClick: () -> Unit
) {
  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(4.dp),
    modifier = Modifier.clickable { onClick() }
  ) {
    Surface(
      modifier = Modifier.size(56.dp),
      shape = CircleShape,
      color = bgColor,
      border = androidx.compose.foundation.BorderStroke(2.dp, borderColor)
    ) {
      Box(contentAlignment = Alignment.Center) {
        Icon(
          imageVector = Icons.Default.Person,
          contentDescription = name,
          tint = borderColor,
          modifier = Modifier.size(28.dp)
        )
      }
    }
    Text(
      text = name,
      fontSize = 12.sp,
      fontWeight = FontWeight.SemiBold,
      color = VibrantTextSecondary
    )
  }
}

@Composable
fun AddContactButton(
  onClick: () -> Unit
) {
  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(4.dp),
    modifier = Modifier.clickable { onClick() }
  ) {
    Surface(
      modifier = Modifier.size(56.dp),
      shape = CircleShape,
      color = Color.Transparent,
      border = androidx.compose.foundation.BorderStroke(2.dp, ContactAddBorder)
    ) {
      Box(contentAlignment = Alignment.Center) {
        Icon(
          imageVector = Icons.Default.Add,
          contentDescription = "Add Contact",
          tint = VibrantTextSecondary,
          modifier = Modifier.size(24.dp)
        )
      }
    }
    Text(
      text = "Add",
      fontSize = 12.sp,
      fontWeight = FontWeight.SemiBold,
      color = VibrantTextSecondary
    )
  }
}

private fun launchDialer(context: Context, phoneNumber: String) {
  try {
    val callIntent = if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
      Intent(Intent.ACTION_CALL, Uri.parse("tel:$phoneNumber")).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
    } else {
      Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phoneNumber")).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
    }
    context.startActivity(callIntent)
  } catch (e: Exception) {
    Toast.makeText(context, "Could not open dialer: ${e.message}", Toast.LENGTH_SHORT).show()
  }
}

private fun getFormattedTime(): String {
  return SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date())
}

private fun getFormattedDate(): String {
  return SimpleDateFormat("EEEE, MMM d", Locale.getDefault()).format(Date())
}

/**
 * Compact, accessible Radio Mini-Player Bar shown on home screen during audio playback.
 */
@Composable
fun RadioMiniPlayerBar(
  state: RadioPlaybackState,
  onStop: () -> Unit,
  onSwitchPreset: () -> Unit
) {
  Surface(
    modifier = Modifier
      .fillMaxWidth()
      .testTag("radio_mini_player_bar"),
    shape = RoundedCornerShape(20.dp),
    color = Color(0xFF1B4332), // Forest Green
    shadowElevation = 6.dp,
    border = androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFF52B788))
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 14.dp, vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween
    ) {
      Row(
        modifier = Modifier.weight(1f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
      ) {
        Surface(
          shape = CircleShape,
          color = Color(0xFF2D6A4F),
          modifier = Modifier.size(40.dp)
        ) {
          Box(contentAlignment = Alignment.Center) {
            if (state.isBuffering) {
              CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                color = Color(0xFFD8F3DC),
                strokeWidth = 2.5.dp
              )
            } else {
              Icon(
                imageVector = Icons.Default.Radio,
                contentDescription = null,
                tint = Color(0xFFD8F3DC),
                modifier = Modifier.size(22.dp)
              )
            }
          }
        }

        Column {
          Text(
            text = state.currentStationName ?: "Online Radio",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp
          )
          Text(
            text = if (state.isBuffering) "සම්බන්ධ වෙමින් පවතී (Buffering...)" else "වාදනය වෙමින් පවතී (Playing live)",
            color = Color(0xFFB7E4C7),
            fontSize = 12.sp
          )
        }
      }

      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        IconButton(
          onClick = onSwitchPreset,
          modifier = Modifier.size(38.dp)
        ) {
          Icon(
            imageVector = Icons.Default.SwapHoriz,
            contentDescription = "Switch Station",
            tint = Color(0xFFD8F3DC)
          )
        }

        Button(
          onClick = onStop,
          colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE63946)),
          shape = RoundedCornerShape(12.dp),
          contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
          modifier = Modifier.testTag("radio_stop_button")
        ) {
          Icon(Icons.Default.Stop, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
          Spacer(modifier = Modifier.width(4.dp))
          Text("STOP", fontSize = 12.sp, fontWeight = FontWeight.Black, color = Color.White)
        }
      }
    }
  }
}

/**
 * Embedded hardware-accelerated YouTube IFrame video container overlay.
 * Optimized for elderly accessibility and 1.5GB RAM constraints.
 */
@Composable
fun YouTubeVideoOverlay(
  playerManager: YouTubePlayerManager,
  state: YouTubePlayerState,
  onClose: () -> Unit
) {
  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(Color.Black)
      .testTag("youtube_video_overlay")
  ) {
    // Hardware-accelerated WebView rendering YouTube IFrame API
    AndroidView(
      factory = {
        playerManager.createPlayerWebView().apply {
          layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
          )
        }
      },
      modifier = Modifier.fillMaxSize()
    )

    // Top Control Bar
    Surface(
      modifier = Modifier
        .fillMaxWidth()
        .align(Alignment.TopCenter),
      color = Color(0xD9000000)
    ) {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 6.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween
        ) {
          Icon(
            imageVector = Icons.Default.PlayCircle,
            contentDescription = state.videoTitle ?: "Playing YouTube video",
            tint = Color(0xFFFF5252),
            modifier = Modifier.size(24.dp)
          )

          IconButton(
            onClick = onClose,
            modifier = Modifier
              .size(44.dp)
              .testTag("close_youtube_button")
          ) {
            Icon(
              Icons.Default.Close,
              contentDescription = "Close YouTube video",
              tint = Color.White,
              modifier = Modifier.size(24.dp)
            )
          }
      }
    }

    // Loading indicator while buffering
    if (state.isLoading) {
      Box(
        modifier = Modifier
          .fillMaxSize()
          .background(Color(0x80000000)),
        contentAlignment = Alignment.Center
      ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
          CircularProgressIndicator(color = Color(0xFFFF5252), strokeWidth = 4.dp)
        }
      }
    }
  }
}

