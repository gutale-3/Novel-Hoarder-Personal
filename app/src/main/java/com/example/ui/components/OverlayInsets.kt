package com.example.ui.components

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Height of the floating player at the bottom of the screen. Lists add it to their bottom
 * padding so their last items can scroll up above the player, while the text above still
 * scrolls underneath it instead of stopping at a solid block.
 */
val LocalPlayerOverlayPadding = compositionLocalOf<Dp> { 0.dp }
