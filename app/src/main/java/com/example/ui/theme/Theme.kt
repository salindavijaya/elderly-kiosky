package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val VibrantColorScheme =
  lightColorScheme(
    primary = PhoneIconBox,
    onPrimary = Color.White,
    primaryContainer = PhoneCardBg,
    onPrimaryContainer = PhoneCardText,
    secondary = MessagesIconBox,
    onSecondary = Color.White,
    secondaryContainer = MessagesCardBg,
    onSecondaryContainer = MessagesCardText,
    tertiary = VoiceIconBox,
    onTertiary = Color.White,
    tertiaryContainer = VoiceCardBg,
    onTertiaryContainer = VoiceCardText,
    error = HelpIconBox,
    onError = Color.White,
    errorContainer = HelpCardBg,
    onErrorContainer = HelpCardText,
    background = VibrantBackground,
    onBackground = VibrantOnBackground,
    surface = VibrantSurface,
    onSurface = VibrantOnSurface,
    surfaceVariant = Color(0xFFE0E2EC),
    onSurfaceVariant = VibrantTextSecondary,
    outline = Color(0xFF74777F)
  )

private val DarkColorScheme =
  darkColorScheme(
    primary = Color(0xFFA0C9FF),
    onPrimary = Color(0xFF00325A),
    primaryContainer = Color(0xFF004977),
    onPrimaryContainer = Color(0xFFD1E4FF),
    secondary = Color(0xFFC4C6D0),
    onSecondary = Color(0xFF2E3137),
    secondaryContainer = Color(0xFF44474E),
    onSecondaryContainer = Color(0xFFE0E2EC),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF93000A),
    background = Color(0xFF1B1B1F),
    surface = Color(0xFF1B1B1F)
  )

@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = false,
  content: @Composable () -> Unit,
) {
  val colorScheme = if (darkTheme) DarkColorScheme else VibrantColorScheme

  MaterialTheme(
    colorScheme = colorScheme,
    typography = Typography,
    content = content
  )
}
