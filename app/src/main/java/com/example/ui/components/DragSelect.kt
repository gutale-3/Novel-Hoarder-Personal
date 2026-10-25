package com.example.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Press and hold a row, then slide: every row between it and your finger is selected (or
 * deselected, if the first row was already selected). Near the top or bottom edge the list keeps
 * scrolling by itself, so hundreds of chapters can be marked in one swipe.
 *
 * @param orderedKeys the selectable rows' keys in list order (other rows are ignored).
 */
@Composable
fun Modifier.dragToSelect(
    enabled: Boolean,
    listState: LazyListState,
    orderedKeys: List<String>,
    selected: Set<String>,
    onSelectionChange: (Set<String>) -> Unit
): Modifier {
    val haptics = LocalHapticFeedback.current
    val edgePx = with(LocalDensity.current) { 72.dp.toPx() }
    val maxSpeed = with(LocalDensity.current) { 18.dp.toPx() }
    val keys by rememberUpdatedState(orderedKeys)
    val current by rememberUpdatedState(selected)
    val onChange by rememberUpdatedState(onSelectionChange)
    var autoScroll by remember { mutableFloatStateOf(0f) }
    var lastY by remember { mutableFloatStateOf(-1f) }
    var dragging by remember { mutableStateOf(false) }

    var anchor by remember { mutableIntStateOf(-1) }
    var adding by remember { mutableStateOf(true) }
    var base by remember { mutableStateOf(emptySet<String>()) }

    fun indexAt(y: Float): Int {
        val item = listState.layoutInfo.visibleItemsInfo.firstOrNull { y >= it.offset && y < it.offset + it.size }
            ?: return -1
        return keys.indexOf(item.key as? String ?: return -1)
    }
    fun update(y: Float) {
        val i = indexAt(y)
        if (i < 0 || anchor < 0) return
        val range = keys.subList(minOf(anchor, i), maxOf(anchor, i) + 1).toSet()
        onChange(if (adding) base + range else base - range)
    }

    LaunchedEffect(dragging) {
        while (dragging && isActive) {
            if (autoScroll != 0f) {
                listState.scrollBy(autoScroll)
                if (lastY >= 0f) update(lastY)
            }
            delay(16)
        }
    }

    if (!enabled) return this
    return this.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val start = indexAt(down.position.y)
            if (start < 0) return@awaitEachGesture
            // Long press first, so ordinary swipes still scroll the list.
            // null = the timeout passed with the finger still down and still: a long press.
            val held = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                var released = false
                while (!released) {
                    val c = awaitPointerEvent(PointerEventPass.Initial).changes.first()
                    released = !c.pressed || (c.position - down.position).getDistance() > viewConfiguration.touchSlop
                }
                released
            } == null
            if (!held) return@awaitEachGesture

            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            anchor = start
            adding = keys[start] !in current
            base = current
            dragging = true
            update(down.position.y)
            try {
                while (true) {
                    val c = awaitPointerEvent(PointerEventPass.Initial).changes.first()
                    if (!c.pressed) break
                    c.consume()
                    lastY = c.position.y
                    val h = size.height.toFloat()
                    autoScroll = when {
                        lastY > h - edgePx -> maxSpeed * ((lastY - (h - edgePx)) / edgePx).coerceIn(0.2f, 1f)
                        lastY < edgePx -> -maxSpeed * ((edgePx - lastY) / edgePx).coerceIn(0.2f, 1f)
                        else -> 0f
                    }
                    update(lastY)
                }
            } finally {
                dragging = false
                autoScroll = 0f
                lastY = -1f
                anchor = -1
            }
        }
    }
}
