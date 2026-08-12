package com.example.ui.theme

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Screen size buckets, matching the Material 3 window size classes.
 *
 * Implemented with LocalConfiguration rather than the material3-window-size-class artifact so no
 * new dependency is needed.
 */
enum class ScreenSize { Compact, Medium, Expanded }

@Composable
@ReadOnlyComposable
fun rememberScreenSize(): ScreenSize {
    val widthDp = LocalConfiguration.current.screenWidthDp
    return when {
        widthDp < 600 -> ScreenSize.Compact   // phones in portrait
        widthDp < 840 -> ScreenSize.Medium    // large phones landscape, small tablets
        else -> ScreenSize.Expanded           // tablets, foldables open
    }
}

/** True on a very narrow device, where labels must be shortened rather than wrapped. */
@Composable
@ReadOnlyComposable
fun isNarrowScreen(): Boolean = LocalConfiguration.current.screenWidthDp < 360

/** Horizontal padding that grows with the screen instead of stretching content edge to edge. */
@Composable
@ReadOnlyComposable
fun screenHorizontalPadding(): Dp = when (rememberScreenSize()) {
    ScreenSize.Compact -> 16.dp
    ScreenSize.Medium -> 24.dp
    ScreenSize.Expanded -> 32.dp
}

@Composable
@ReadOnlyComposable
fun screenContentPadding(): PaddingValues =
    PaddingValues(horizontal = screenHorizontalPadding(), vertical = 12.dp)

/**
 * Maximum width for a column of content. Without this, cards and text stretch the full width of a
 * tablet and become unreadable.
 */
@Composable
@ReadOnlyComposable
fun maxContentWidth(): Dp = when (rememberScreenSize()) {
    ScreenSize.Compact -> Dp.Unspecified
    ScreenSize.Medium -> 720.dp
    ScreenSize.Expanded -> 840.dp
}

/** Minimum touch target height. Buttons should use this instead of a fixed height. */
val MinTouchTarget: Dp = 48.dp
