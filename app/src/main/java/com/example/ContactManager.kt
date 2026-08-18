package com.example

import android.content.Context
import android.database.Cursor
import android.provider.ContactsContract
import android.util.Log
import kotlin.math.max
import kotlin.math.min

/**
 * Data model for a phone contact.
 */
data class PhoneContact(
  val id: String,
  val name: String,
  val phoneNumber: String,
  val normalizedName: String = name.lowercase().trim()
)

/**
 * ContactManager responsible for:
 * 1. Fetching contacts from device ContactsContract provider.
 * 2. Extracting contact target names from Sinhala speech phrases.
 * 3. Matching spoken names with contacts using fuzzy string matching (Levenshtein distance).
 */
class ContactManager(private val context: Context? = null) {

  companion object {
    private const val TAG = "ContactManager"

    // Common Sinhala / English kinship & name transliteration dictionary
    val SINHALA_ENGLISH_NAME_MAP = mapOf(
      "අම්මා" to listOf("amma", "mom", "mother", "ammaa"),
      "තාත්තා" to listOf("thaththa", "thaththi", "dad", "father", "appachchi"),
      "පුතා" to listOf("putha", "son"),
      "දුව" to listOf("duwa", "daughter"),
      "අයියා" to listOf("aiya", "aiyya", "brother"),
      "මල්ලි" to listOf("malli", "brother"),
      "අක්කා" to listOf("akka", "sister"),
      "නංගි" to listOf("nangi", "sister"),
      "ඩොක්ටර්" to listOf("doctor", "doc", "hospital"),
      "ගෙදර" to listOf("home", "gedara")
    )
  }

  private fun safeLog(msg: String) {
    try {
      Log.d(TAG, msg)
    } catch (_: Throwable) {
      // Fallback for standalone JVM unit test environments
    }
  }

  private fun safeLogE(msg: String, tr: Throwable? = null) {
    try {
      if (tr != null) Log.e(TAG, msg, tr) else Log.e(TAG, msg)
    } catch (_: Throwable) {
      // Fallback for standalone JVM unit test environments
    }
  }

  /**
   * Queries contacts from the Android ContactsContract.
   */
  fun getAllContacts(): List<PhoneContact> {
    val contactsList = mutableListOf<PhoneContact>()
    val seenNumbers = mutableSetOf<String>()

    try {
      val ctx = context ?: return emptyList()
      val projection = arrayOf(
        ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
        ContactsContract.CommonDataKinds.Phone.NUMBER
      )

      val cursor: Cursor? = ctx.contentResolver.query(
        ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
        projection,
        null,
        null,
        "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
      )

      cursor?.use { c ->
        val idIndex = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
        val nameIndex = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
        val numberIndex = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)

        while (c.moveToNext()) {
          val id = if (idIndex >= 0) c.getString(idIndex) ?: "" else ""
          val name = if (nameIndex >= 0) c.getString(nameIndex) ?: "" else ""
          val rawNumber = if (numberIndex >= 0) c.getString(numberIndex) ?: "" else ""

          val cleanNumber = rawNumber.replace("[^0-9+]".toRegex(), "")

          if (name.isNotBlank() && cleanNumber.isNotBlank() && seenNumbers.add(cleanNumber)) {
            contactsList.add(
              PhoneContact(
                id = id,
                name = name.trim(),
                phoneNumber = cleanNumber
              )
            )
          }
        }
      }
      safeLog("Successfully loaded ${contactsList.size} contacts from device.")
    } catch (e: SecurityException) {
      safeLogE("Missing READ_CONTACTS permission", e)
    } catch (e: Exception) {
      safeLogE("Error fetching contacts from device", e)
    }

    return contactsList
  }

  /**
   * Extracts the intended target contact name from a Sinhala voice call phrase.
   * e.g., "අම්මාට කෝල් එකක් ගන්න" -> "අම්මා"
   * e.g., "කෝල් කරන්න කසුන්ට" -> "කසුන්"
   * e.g., "call Kamal" -> "Kamal"
   */
  fun extractTargetNameFromCallQuery(rawQuery: String): String {
    var cleaned = rawQuery.trim()

    // Strip leading / trailing common voice verbs
    val verbPrefixes = listOf(
      "කෝල් කරන්න", "කෝල් එකක් ගන්න", "ඇමතුමක් ගන්න", "අමතන්න", "කතා කරන්න",
      "call to", "call", "dial", "please call"
    )
    for (prefix in verbPrefixes) {
      if (cleaned.startsWith(prefix, ignoreCase = true)) {
        cleaned = cleaned.substring(prefix.length).trim()
      }
    }

    val verbSuffixes = listOf(
      "ට කෝල් කරන්න", "ට කෝල් එකක් ගන්න", "ට ඇමතුමක් ගන්න", "ට අමතන්න", "ට කතා කරන්න",
      "ට ගන්න", "ට කෝල් එකක් දාන්න", "ට කෝල්", "ට ඇමතුම", "කතා කරන්න", "ට", "ta", "call", "please"
    )
    for (suffix in verbSuffixes) {
      if (cleaned.endsWith(suffix, ignoreCase = true)) {
        cleaned = cleaned.substring(0, cleaned.length - suffix.length).trim()
      }
    }

    return cleaned.ifBlank { rawQuery.trim() }
  }

  /**
   * Finds the best matching contact from the list using fuzzy string matching.
   * Considers exact match, token containment, transliterated aliases, and Levenshtein similarity.
   */
  fun findBestMatch(spokenQuery: String, contacts: List<PhoneContact>): PhoneContact? {
    if (contacts.isEmpty()) return null

    val targetName = extractTargetNameFromCallQuery(spokenQuery).lowercase()
    if (targetName.isBlank()) return contacts.firstOrNull()

    var bestContact: PhoneContact? = null
    var highestScore = 0.0

    // Check transliterated aliases
    val aliases = SINHALA_ENGLISH_NAME_MAP[targetName] ?: emptyList()

    val targetLatin = transliterateSinhalaToLatin(targetName)

    for (contact in contacts) {
      val contactNorm = contact.normalizedName

      // 1. Exact match (Score 1.0)
      if (contactNorm == targetName || (targetLatin.isNotBlank() && contactNorm == targetLatin)) {
        return contact
      }

      // 2. Direct transliteration alias match (Score 0.95)
      for (alias in aliases) {
        if (contactNorm.contains(alias, ignoreCase = true) || alias.contains(contactNorm, ignoreCase = true)) {
          return contact
        }
      }

      // 3. Transliterated phonetic token match
      if (targetLatin.isNotBlank() && (contactNorm.contains(targetLatin) || targetLatin.contains(contactNorm))) {
        return contact
      }

      // 4. Substring / Word token containment
      var score = 0.0
      if (contactNorm.contains(targetName) || targetName.contains(contactNorm)) {
        score = 0.85
      } else {
        // Check word-by-word containment
        val contactWords = contactNorm.split(" ", "_", "-").filter { it.isNotBlank() }
        val targetWords = targetName.split(" ", "_", "-").filter { it.isNotBlank() }

        var wordMatch = false
        for (tw in targetWords) {
          val twLatin = transliterateSinhalaToLatin(tw)
          for (cw in contactWords) {
            if (cw == tw || cw == twLatin || cw.contains(tw) || (twLatin.isNotBlank() && cw.contains(twLatin))) {
              score = max(score, 0.80)
              wordMatch = true
              break
            }
          }
          if (wordMatch) break
        }

        // 5. Normalized Levenshtein distance similarity
        if (!wordMatch) {
          val levSimSinhala = calculateSimilarity(targetName, contactNorm)
          val levSimLatin = if (targetLatin.isNotBlank()) calculateSimilarity(targetLatin, contactNorm) else 0.0
          score = max(score, max(levSimSinhala, levSimLatin))
        }
      }

      if (score > highestScore) {
        highestScore = score
        bestContact = contact
      }
    }

    safeLog("Best match for '$spokenQuery' (target: '$targetName') -> ${bestContact?.name} (Score: $highestScore)")
    // Accept match if confidence score is >= 0.50
    return if (highestScore >= 0.50) bestContact else contacts.firstOrNull()
  }

  /**
   * Calculates similarity between 0.0 and 1.0 based on Levenshtein distance.
   */
  fun calculateSimilarity(s1: String, s2: String): Double {
    if (s1.isBlank() || s2.isBlank()) return 0.0
    if (s1 == s2) return 1.0

    val distance = levenshteinDistance(s1, s2)
    val maxLength = max(s1.length, s2.length)
    if (maxLength == 0) return 1.0

    return 1.0 - (distance.toDouble() / maxLength.toDouble())
  }

  /**
   * Standard dynamic programming Levenshtein distance implementation.
   */
  fun levenshteinDistance(a: CharSequence, b: CharSequence): Int {
    val lenA = a.length
    val lenB = b.length

    val dp = Array(lenA + 1) { IntArray(lenB + 1) }

    for (i in 0..lenA) dp[i][0] = i
    for (j in 0..lenB) dp[0][j] = j

    for (i in 1..lenA) {
      for (j in 1..lenB) {
        val cost = if (a[i - 1] == b[j - 1]) 0 else 1
        dp[i][j] = min(
          min(dp[i - 1][j] + 1, dp[i][j - 1] + 1),
          dp[i - 1][j - 1] + cost
        )
      }
    }

    return dp[lenA][lenB]
  }

  /**
   * Phonetic transliteration from Sinhala unicode to Latin script for fuzzy contact matching.
   * Accurately handles Sinhala abugida inherent vowels, hal kireema (virama), and vowel modifiers.
   */
  fun transliterateSinhalaToLatin(input: String): String {
    val consonantMap = mapOf(
      'ක' to "k", 'ඛ' to "kh", 'ග' to "g", 'ඝ' to "gh", 'ඟ' to "ng",
      'ච' to "ch", 'ඡ' to "chh", 'ජ' to "j", 'ඣ' to "jh", 'ඤ' to "ny",
      'ට' to "t", 'ඨ' to "th", 'ඩ' to "d", 'ඪ' to "dh", 'ණ' to "n",
      'ත' to "th", 'ථ' to "th", 'ද' to "d", 'ධ' to "dh", 'න' to "n",
      'ප' to "p", 'ඵ' to "ph", 'බ' to "b", 'භ' to "bh", 'ම' to "m",
      'ය' to "y", 'ර' to "r", 'ල' to "l", 'ව' to "v", 'ශ' to "sh",
      'ෂ' to "sh", 'ස' to "s", 'හ' to "h", 'ළ' to "l"
    )

    val independentVowels = mapOf(
      'අ' to "a", 'ආ' to "aa", 'ඇ' to "ae", 'ඈ' to "aae", 'ඉ' to "i",
      'ඊ' to "ee", 'උ' to "u", 'ඌ' to "oo", 'එ' to "e", 'ඒ' to "ee",
      'ඔ' to "o", 'ඕ' to "oo"
    )

    val vowelModifiers = mapOf(
      'ා' to "a", 'ැ' to "a", 'ෑ' to "aa", 'ි' to "i", 'ී' to "ee",
      'ු' to "u", 'ූ' to "oo", 'ෙ' to "e", 'ේ' to "ee", 'ො' to "o",
      'ෝ' to "oo"
    )

    val sb = StringBuilder()
    var i = 0
    while (i < input.length) {
      val ch = input[i]
      if (consonantMap.containsKey(ch)) {
        val base = consonantMap[ch] ?: ""
        sb.append(base)

        // Check next char for virama or vowel modifier
        if (i + 1 < input.length) {
          val nextCh = input[i + 1]
          if (nextCh == '්') {
            // Pure consonant (no inherent vowel)
            i += 2
            continue
          } else if (vowelModifiers.containsKey(nextCh)) {
            sb.append(vowelModifiers[nextCh])
            i += 2
            continue
          } else {
            // Default inherent vowel 'a'
            sb.append("a")
          }
        } else {
          // Last character consonant default inherent vowel 'a'
          sb.append("a")
        }
      } else if (independentVowels.containsKey(ch)) {
        sb.append(independentVowels[ch])
      } else if (vowelModifiers.containsKey(ch)) {
        sb.append(vowelModifiers[ch])
      } else if (ch != '්' && (ch.isLetterOrDigit() || ch.isWhitespace())) {
        sb.append(ch)
      }
      i++
    }

    return sb.toString().lowercase().trim()
  }
}
