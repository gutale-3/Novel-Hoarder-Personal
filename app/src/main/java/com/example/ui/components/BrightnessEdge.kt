package com.example.ui.components

import android.app.Activity
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

object ReaderBrightness {
    private fun prefs(c: Context) = c.getSharedPreferences("novel_hoarder_prefs", Context.MODE_PRIVATE)
    fun swipeEnabled(c: Context) = prefs(c).getBoolean("reader_brightness_swipe", true)
    fun setSwipeEnabled(c: Context, on: Boolean) = prefs(c).edit().putBoolean("reader_brightness_swipe", on).apply()
    fun saved(c: Context) = prefs(c).getFloat("reader_brightness", -1f)
    fun save(c: Context, v: Float) = prefs(c).edit().putFloat("reader_brightness", v).apply()
}

/**
 * Slide up or down along the left edge of the reader to change screen brightness (for the
 * reader only; the phone's own setting comes back when you leave).
 */
@Composable
fun BoxScope.BrightnessEdge() {
    val context = LocalContext.current
    val activity = context as? Activity ?: return
    if (!ReaderBrightness.swipeEnabled(context)) return
    var level by remember { mutableFloatStateOf(ReaderBrightness.saved(context)) }
    var showLevel by remember { mutableStateOf(false) }

    fun apply(v: Float) {
        val lp = activity.window.attributes
        lp.screenBrightness = v
        activity.window.attributes = lp
    }
    DisposableEffect(Unit) {
        if (level >= 0f) apply(level)
        onDispose { apply(-1f) } // back to the system brightness outside the reader
    }

    Box(
        Modifier
            .align(Alignment.CenterStart)
            .fillMaxHeight(0.7f)
            .width(22.dp)
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = {
                        if (level < 0f) level = 0.5f
                        showLevel = true
                    },
                    onDragEnd = { showLevel = false; ReaderBrightness.save(context, level) },
                    onDragCancel = { showLevel = false }
                ) { change, dy ->
                    change.consume()
                    level = (level - dy / (size.height * 0.8f)).coerceIn(0.02f, 1f)
                    apply(level)
                }
            }
    )
    if (showLevel) {
        Text(
            "☀ ${(level * 100).toInt()}%",
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
                .align(Alignment.Center)
                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                .padding(horizontal = 16.dp, vertical = 8.dp)
        )
    }
}
