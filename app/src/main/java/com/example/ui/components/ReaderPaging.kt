package com.example.ui.components

import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.lazy.LazyListState
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Bridges hardware page-turn keys (handled by the Activity) to the reader's scroll state.
 * The Activity only consumes the key while a reader is on screen, so volume keys keep working
 * normally everywhere else.
 */
object ReaderPaging {
    @Volatile
    var readerVisible: Boolean = false

    private val _turns = MutableSharedFlow<Int>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val turns: SharedFlow<Int> = _turns

    /** @param direction +1 for the next page, -1 for the previous one. */
    fun requestTurn(direction: Int) {
        _turns.tryEmit(direction)
    }
}

/**
 * Scrolls one screen of text, keeping a small overlap so the last line read stays visible —
 * the "page turn" feel of an e-reader on top of the continuous list.
 */
suspend fun LazyListState.turnPage(direction: Int) {
    val viewport = layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset
    if (viewport <= 0) return
    animateScrollBy(direction * viewport * 0.92f)
}
