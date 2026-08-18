package com.example

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Unit tests for Sinhala Voice Intent Classification.
 */
class ExampleUnitTest {

  @Test
  fun testSinhalaIntentParser_CallIntent() {
    val command1 = parseSinhalaVoiceIntent("කෝල් කරන්න පුතාට")
    assertEquals(VoiceIntent.CALL, command1.intent)

    val command2 = parseSinhalaVoiceIntent("ඇමතුමක් ගන්න ඩොක්ටර්ට")
    assertEquals(VoiceIntent.CALL, command2.intent)

    val command3 = parseSinhalaVoiceIntent("call Sarah")
    assertEquals(VoiceIntent.CALL, command3.intent)
  }

  @Test
  fun testSinhalaIntentParser_RadioIntent() {
    val command1 = parseSinhalaVoiceIntent("රේඩියෝ එක දාන්න")
    assertEquals(VoiceIntent.RADIO, command1.intent)

    val command2 = parseSinhalaVoiceIntent("ගුවන්විදුලි පුවත්")
    assertEquals(VoiceIntent.RADIO, command2.intent)

    val command3 = parseSinhalaVoiceIntent("radio fm")
    assertEquals(VoiceIntent.RADIO, command3.intent)
  }

  @Test
  fun testSinhalaIntentParser_YouTubeIntent() {
    val command1 = parseSinhalaVoiceIntent("බණ දේශනාවක් අහන්න")
    assertEquals(VoiceIntent.YOUTUBE, command1.intent)

    val command2 = parseSinhalaVoiceIntent("පිරිත් දාන්න")
    assertEquals(VoiceIntent.YOUTUBE, command2.intent)

    val command3 = parseSinhalaVoiceIntent("සින්දු අහන්න youtube")
    assertEquals(VoiceIntent.YOUTUBE, command3.intent)
  }

  @Test
  fun testSinhalaIntentParser_StopIntent() {
    val command1 = parseSinhalaVoiceIntent("දැන් නවත්තන්න")
    assertEquals(VoiceIntent.STOP, command1.intent)

    val command2 = parseSinhalaVoiceIntent("ඕෆ් කරන්න")
    assertEquals(VoiceIntent.STOP, command2.intent)

    val command3 = parseSinhalaVoiceIntent("stop")
    assertEquals(VoiceIntent.STOP, command3.intent)
  }

  @Test
  fun testSinhalaIntentParser_UnknownIntent() {
    val command = parseSinhalaVoiceIntent("අද කාලගුණය කොහොමද")
    assertEquals(VoiceIntent.UNKNOWN, command.intent)
  }

  @Test
  fun testRadioStationPresets() {
    assertEquals("http://sh.shraddha.net:8000/stream", RadioManager.URL_SHRADDHA_FM)
    assertEquals("http://lakviru.com:8000/stream", RadioManager.URL_LAKVIRU_FM)
    assertEquals("ශ්‍රද්ධා ගුවන්විදුලිය", RadioStationPreset.SHRADDHA_FM.sinhalaTitle)
    assertEquals("ලක්විරු ගුවන්විදුලිය", RadioStationPreset.LAKVIRU_FM.sinhalaTitle)
  }

  @Test
  fun testYouTubePresets() {
    assertNotNull(YouTubePlayerManager.PRESET_MAHA_PIRITHA_ID)
    assertEquals(11, YouTubePlayerManager.PRESET_MAHA_PIRITHA_ID.length)
  }

  @Test
  fun testContactManager_LevenshteinAndFuzzyMatching() {
    // Test Levenshtein algorithm
    val contactManager = ContactManager()
    assertEquals(0, contactManager.levenshteinDistance("Kamal", "Kamal"))
    assertEquals(1, contactManager.levenshteinDistance("Kamal", "Kamel"))
    assertEquals(3, contactManager.levenshteinDistance("kitten", "sitting"))

    val testContacts = listOf(
      PhoneContact("1", "Sarah", "+15550192834"),
      PhoneContact("2", "Doctor Silva", "+18005550144"),
      PhoneContact("3", "Kamal Perera", "+94771234567"),
      PhoneContact("4", "Amma", "+94719876543"),
      PhoneContact("5", "පුතා", "+94775556677")
    )

    // Test name extraction
    assertEquals("පුතා", contactManager.extractTargetNameFromCallQuery("පුතාට කෝල් කරන්න"))
    assertEquals("Sarah", contactManager.extractTargetNameFromCallQuery("call to Sarah"))

    // Test matching
    val matchAmma = contactManager.findBestMatch("අම්මාට කෝල් එකක් ගන්න", testContacts)
    assertEquals("Amma", matchAmma?.name)

    val matchDoctor = contactManager.findBestMatch("ඩොක්ටර්ට කතා කරන්න", testContacts)
    assertEquals("Doctor Silva", matchDoctor?.name)

    val matchKamal = contactManager.findBestMatch("කමල්ට කෝල් කරන්න", testContacts)
    assertEquals("Kamal Perera", matchKamal?.name)
  }
}
