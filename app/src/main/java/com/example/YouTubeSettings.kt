package com.example

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

data class YouTubeSettings(
  val autoPlay: Boolean = true,
  val showControls: Boolean = true,
  val defaultVolume: Int = 100
)

private val Context.youtubeSettingsDataStore: DataStore<Preferences> by preferencesDataStore(
  name = "youtube_settings"
)

class YouTubeSettingsRepository(private val context: Context) {
  val settings: Flow<YouTubeSettings> = context.youtubeSettingsDataStore.data
    .catch { exception ->
      if (exception is IOException) emit(androidx.datastore.preferences.core.emptyPreferences())
      else throw exception
    }
    .map { preferences ->
      YouTubeSettings(
        autoPlay = preferences[booleanPreferencesKey("auto_play")] ?: true,
        showControls = preferences[booleanPreferencesKey("show_controls")] ?: true,
        defaultVolume = (preferences[intPreferencesKey("default_volume")] ?: 100).coerceIn(0, 100)
      )
    }

  suspend fun save(settings: YouTubeSettings) {
    context.youtubeSettingsDataStore.edit { preferences ->
      preferences[booleanPreferencesKey("auto_play")] = settings.autoPlay
      preferences[booleanPreferencesKey("show_controls")] = settings.showControls
      preferences[intPreferencesKey("default_volume")] = settings.defaultVolume.coerceIn(0, 100)
    }
  }

  suspend fun reset() {
    context.youtubeSettingsDataStore.edit { it.clear() }
  }
}
