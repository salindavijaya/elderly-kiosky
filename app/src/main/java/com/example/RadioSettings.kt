package com.example

import android.content.Context
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

data class RadioStationPreset(
  val id: String,
  val stationName: String,
  val streamUrl: String,
  val sinhalaTitle: String
) {
  companion object {
    const val SHRADDHA_ID = "shraddha_fm"
    const val LAKVIRU_ID = "lakviru_fm"

    val SHRADDHA_FM = RadioStationPreset(
      id = SHRADDHA_ID,
      stationName = "Shraddha FM",
      streamUrl = "http://sh.shraddha.net:8000/stream",
      sinhalaTitle = "ශ්‍රද්ධා ගුවන්විදුලිය"
    )

    val LAKVIRU_FM = RadioStationPreset(
      id = LAKVIRU_ID,
      stationName = "Lakviru FM",
      streamUrl = "http://lakviru.com:8000/stream",
      sinhalaTitle = "ලක්විරු ගුවන්විදුලිය"
    )

    val defaults = listOf(SHRADDHA_FM, LAKVIRU_FM)
  }
}

private val Context.radioSettingsDataStore: DataStore<Preferences> by preferencesDataStore(
  name = "radio_settings"
)

class RadioSettingsRepository(private val context: Context) {
  val stations: Flow<List<RadioStationPreset>> = context.radioSettingsDataStore.data
    .catch { exception ->
      if (exception is IOException) emit(androidx.datastore.preferences.core.emptyPreferences())
      else throw exception
    }
    .map { preferences ->
      RadioStationPreset.defaults.map { default ->
        default.copy(
          stationName = preferences[stringPreferencesKey("${default.id}_name")]
            .orDefault(default.stationName),
          streamUrl = preferences[stringPreferencesKey("${default.id}_url")]
            .orDefault(default.streamUrl),
          sinhalaTitle = preferences[stringPreferencesKey("${default.id}_title")]
            .orDefault(default.sinhalaTitle)
        )
      }
    }

  suspend fun save(stations: List<RadioStationPreset>) {
    require(stations.size == RadioStationPreset.defaults.size) { "Exactly two radio stations are required" }
    require(stations.all { it.isValid() }) { "Radio station settings are invalid" }

    context.radioSettingsDataStore.edit { preferences ->
      stations.forEach { station ->
        preferences[stringPreferencesKey("${station.id}_name")] = station.stationName.trim()
        preferences[stringPreferencesKey("${station.id}_url")] = station.streamUrl.trim()
        preferences[stringPreferencesKey("${station.id}_title")] = station.sinhalaTitle.trim()
      }
    }
  }

  suspend fun reset() {
    context.radioSettingsDataStore.edit { it.clear() }
  }
}

private fun String?.orDefault(default: String): String =
  if (isNullOrBlank()) default else this

private fun RadioStationPreset.isValid(): Boolean =
  stationName.isNotBlank() && sinhalaTitle.isNotBlank() &&
    Uri.parse(streamUrl).let { it.scheme == "http" || it.scheme == "https" }