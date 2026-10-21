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
 * Makes the layout fit the phone it is on. Screens were laid out for a ~400 dp wide phone; on
 * narrower screens, or with a large system "display size", buttons and rows wrapped ("Sto / p").
 * Scaling everything by the width ratio (never below 85%) and capping the system font scale at
 * 115% keeps every screen proportioned the same way on any phone. Reader text keeps its own
 * size setting on top of this.
 */
@Composable
private fun adaptiveDensity(): Density {
  val base = LocalDensity.current
  val widthDp = LocalConfiguration.current.screenWidthDp
  val widthScale = (widthDp / DESIGN_WIDTH_DP).coerceIn(0.85f, 1f)
  return Density(density = base.density * widthScale, fontScale = base.fontScale.coerceAtMost(1.15f))
}

private const val DESIGN_WIDTH_DP = 400f
