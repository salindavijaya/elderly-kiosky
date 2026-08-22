# Project Handover Document: "Siha Kiosk" (Sinhala Elderly Voice Assistant)

**To:** Lead Developer / AI Code Agent (Claude)
**From:** System Architect
**Date:** August 22, 2026
**Target Device:** Samsung Galaxy J2 Pro (SM-J250F)
**Target OS:** Android 7.1.1 (API 25) / 1.5 GB RAM / Snapdragon 425

## 1. Project Overview

"Siha Kiosk" is a unified, voice-controlled Android application explicitly designed for a non-literate elderly user. The core objective is to replace the standard Android launcher and system navigation with a single, highly constrained interface that operates exclusively via Sinhala voice commands.

The application is heavily locked down to prevent accidental navigation into system settings or other apps. All major functions (calling, internet radio, YouTube Dhamma Deshana streaming) occur *within* the app itself to avoid user confusion.

## 2. Core Architectural Decisions

### 2.1 UI & Lockdown Strategy (Kiosk Engine)

*   **Launcher Hijack:** The app is registered as `CATEGORY_HOME` and `CATEGORY_DEFAULT` in the `AndroidManifest.xml`. Pressing the physical Home button will route to this app.
*   **Trigger Mechanism:** The physical Home button acts as a Push-to-Talk (PTT) trigger. Pressing Home fires `onNewIntent()`, which initiates the `SpeechRecognizer`.
*   **Immersive Mode:** The status bar and navigation bar are hidden (sticky immersive mode).
*   **Call Handling:** A `PhoneStateListener` monitors `TelephonyManager`. When a call ends (`EXTRA_STATE_IDLE`), an Intent with `FLAG_ACTIVITY_NEW_TASK` forces the kiosk interface back to the foreground.

### 2.2 Voice Orchestration (si-LK)

*   **STT Engine:** Native `android.speech.SpeechRecognizer` configured for `si-LK`.
*   **TTS Engine:** Native `android.speech.tts.TextToSpeech` configured for `si-LK`. It provides audio feedback *before* executing actions (e.g., confirming a phone contact) and during media playback.
*   **Intent Parsing:** A lightweight regex/keyword matching system determines intents (Call, Radio, YouTube, Stop) based on spoken Sinhala keywords (e.g., "රේඩියෝ", "කෝල්", "බණ", "නවත්තන්න").

### 2.3 Media & Connectivity

*   **Web Radio:** `ExoPlayer` is used for lightweight background streaming of specific URLs (Shraddha FM, Lakviru FM).
*   **YouTube Streaming:** The native YouTube app is bypassed entirely to keep the user inside the kiosk. A hardware-accelerated `WebView` implements the YouTube IFrame Player API.
*   **YouTube Proxy:** To protect API keys and reduce client-side JSON parsing overhead, a Cloudflare Worker proxy accepts the Sinhala search query, queries the YouTube Data API v3, and returns just the top `videoId`.

### 2.4 Contact Resolution

*   **Fuzzy Matching:** A normalized Levenshtein distance algorithm maps spoken Sinhala STT strings against the device's Contacts Provider.
*   **Execution Delay:** Before firing `ACTION_CALL`, the TTS engine reads back the matched name with a 3-second delay, allowing cancellation via the Home button.

## 3. Implementation Status & Component Map

The architectural blueprint is complete. The following modules require implementation and integration:

1.  **`KioskOverlayService.kt` / `MainActivity.kt`:** Base launcher, immersive mode, and `onNewIntent` trigger.
2.  **`CallStateReceiver.kt`:** Broadcast receiver for forcing the app foreground on call end.
3.  **`VoiceManager.kt` / `IntentRouter.kt`:** STT initialization, TTS handling, and keyword parsing.
4.  **`MediaManager.kt`:** `ExoPlayer` implementation for radio streams and WebView configuration for the YouTube IFrame.
5.  **`ContactDialerManager.kt`:** Contacts Provider querying, fuzzy matching algorithm, and call intent execution.
6.  **Cloudflare Worker (`index.ts`):** Deployment and integration of the provided TypeScript proxy for YouTube API requests.

## 4. Specific Considerations for API 25 / J250F Hardware

*   **Memory Pressure:** With 1.5GB RAM, aggressive caching or heavy object allocations during UI updates must be avoided to prevent OOM errors.
*   **WebView Performance:** Hardware acceleration in the `WebView` must be explicitly enabled, and the IFrame HTML should be as lightweight as possible.
*   **WebView Autoplay Constraints:** Ensure `WebSettings.mediaPlaybackRequiresUserGesture = false` is set so the YouTube IFrame API can autoplay via JavaScript injection upon loading the `videoId`.
*   **Audio Focus:** Ensure strict `AudioManager` focus handling. Media (ExoPlayer/WebView) must pause or duck when the Voice Engine starts listening or speaking.

## 5. Next Steps for Lead Developer

1.  Initialize the Android Studio project with the correct API level and Kiosk Launcher Manifest configurations.
2.  Implement the Cloudflare Worker and verify the YouTube API response payload.
3.  Implement the `VoiceManager` and test `si-LK` recognition accuracy on the physical J250F device, tuning the Intent Router keywords based on real-world STT outputs.
4.  Construct the `ContactDialerManager` and refine the fuzzy matching threshold.
5.  Integrate `ExoPlayer` and the `WebView` YouTube player, ensuring smooth transitions and proper Audio Focus management.
