package com.example.zebralocalai.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
  primary = Color(0xFFF0BC46), onPrimary = Color(0xFF222B32),
  secondary = Color(0xFFB3C9D8), tertiary = Color(0xFFB3C9D8),
  primaryContainer = Color(0xFF44391E), onPrimaryContainer = Color(0xFFFFEDC3),
  secondaryContainer = Color(0xFF2C3B46), onSecondaryContainer = Color(0xFFEDF3F7),
  background = Color(0xFF161B20), surface = Color(0xFF232C33),
  surfaceVariant = Color(0xFF2C3B46), onBackground = Color(0xFFEDF3F7),
  onSurface = Color(0xFFEDF3F7), onSurfaceVariant = Color(0xFFAFBEC9),
  outline = Color(0xFF82949F), outlineVariant = Color(0xFF42545F),
)

private val LightColorScheme =
  lightColorScheme(
    primary = Color(0xFFF0BC46),
    onPrimary = Color(0xFF222B32),
    secondary = Color(0xFF435C6D),
    tertiary = Color(0xFF435C6D),
    primaryContainer = Color(0xFFFFEDC3),
    onPrimaryContainer = Color(0xFF322A19),
    secondaryContainer = Color(0xFFE3EAF0),
    onSecondaryContainer = Color(0xFF202C35),
    background = Color(0xFFF0F2F2),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE3EAF0),
    onBackground = Color(0xFF202C35),
    onSurface = Color(0xFF202C35),
    onSurfaceVariant = Color(0xFF596977),
    outline = Color(0xFF82949F),
    outlineVariant = Color(0xFFBECBD2),
  )

@Composable
fun ZebraLocalAITheme(
  darkTheme: Boolean = false,
  dynamicColor: Boolean = false,
  content: @Composable () -> Unit,
) {
  val colorScheme =
    when {
      dynamicColor -> {
        val context = LocalContext.current
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
      }
      darkTheme -> DarkColorScheme
      else -> LightColorScheme
    }

  MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
