package com.example.ui.theme

import androidx.compose.ui.unit.Density
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

@Composable
fun NovelHoarderTheme(
  appTheme: AppTheme = AppTheme.IMMERSIVE_UI,
  content: @Composable () -> Unit,
) {
  val colorScheme = ThemeConfig.getColorScheme(appTheme)

  CompositionLocalProvider(LocalDensity provides adaptiveDensity()) {
    MaterialTheme(
      colorScheme = colorScheme,
      typography = Typography,
      content = content
    )
  }
}

/**
 * Makes the layout fit the phone it is on. Very narrow screens (< 360 dp) are scaled down a
 * little (never below 85%), and the system font scale is capped at 115% so huge system fonts
 * cannot wrap button labels ("Sto / p"). Normal phones are untouched.
 */
@Composable
private fun adaptiveDensity(): Density {
  val base = LocalDensity.current
  val widthDp = LocalConfiguration.current.screenWidthDp
  // Only phones narrower than ~360 dp are scaled; normal and large phones keep full size.
  val widthScale = if (widthDp >= NARROW_WIDTH_DP) 1f else (widthDp / NARROW_WIDTH_DP).coerceIn(0.85f, 1f)
  return Density(density = base.density * widthScale, fontScale = base.fontScale.coerceAtMost(1.15f))
}

private const val NARROW_WIDTH_DP = 360f
