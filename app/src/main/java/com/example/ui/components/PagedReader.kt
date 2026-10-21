package com.example.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.example.data.local.ChapterEntity
import com.example.viewmodel.MainViewModel
import kotlinx.coroutines.launch

/** One screenful of text and the paragraph it starts in (for saving the reading position). */
data class ReaderPage(val text: String, val firstParagraph: Int)

/**
 * Splits paragraphs into pages that exactly fill [maxHeightPx] when drawn with [style] at
 * [maxWidthPx]. Paragraphs flow across page breaks line by line, like a printed book.
 */
fun paginate(
    paragraphs: List<String>,
    measurer: TextMeasurer,
    style: TextStyle,
    maxWidthPx: Int,
    maxHeightPx: Int,
    paragraphGap: String = "\n\n"
): List<ReaderPage> {
    if (paragraphs.isEmpty() || maxWidthPx <= 0 || maxHeightPx <= 0) return emptyList()
    val pages = mutableListOf<ReaderPage>()
    val constraints = Constraints(maxWidth = maxWidthPx)
    var current = StringBuilder()
    var currentFirst = 0

    fun fits(text: String) = measurer.measure(text, style, constraints = constraints).size.height <= maxHeightPx

    for ((index, para) in paragraphs.withIndex()) {
        var remaining = para.trim()
        while (remaining.isNotEmpty()) {
            val prefix = if (current.isEmpty()) "" else paragraphGap
            if (current.isEmpty()) currentFirst = index
            val candidate = current.toString() + prefix + remaining
            if (fits(candidate)) {
                current.append(prefix).append(remaining)
                remaining = ""
                break
            }
            // Take as many whole lines of this paragraph as still fit on the page.
            val layout = measurer.measure(candidate, style, constraints = constraints)
            var lastFitting = -1
            for (line in 0 until layout.lineCount) {
                if (layout.getLineBottom(line) <= maxHeightPx) lastFitting = line else break
            }
            val cut = if (lastFitting >= 0) layout.getLineEnd(lastFitting, visibleEnd = true) else 0
            val takenFromPara = (cut - (current.length + prefix.length)).coerceIn(0, remaining.length)
            if (takenFromPara <= 0) {
                if (current.isEmpty()) { // a single line taller than the page: take it anyway
                    current.append(remaining); remaining = ""
                }
                pages += ReaderPage(current.toString(), currentFirst)
                current = StringBuilder()
                continue
            }
            current.append(prefix).append(remaining.substring(0, takenFromPara).trimEnd())
            remaining = remaining.substring(takenFromPara).trimStart()
            pages += ReaderPage(current.toString(), currentFirst)
            current = StringBuilder()
            if (remaining.isNotEmpty()) currentFirst = index
        }
    }
    if (current.isNotEmpty()) pages += ReaderPage(current.toString(), currentFirst)
    return pages
}

/**
 * Book-style reading: swipe or tap the left/right edge to turn the page, tap the middle for the
 * controls. Going past the last page opens the next chapter; volume keys turn pages too.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PagedReader(
    activeChapter: ChapterEntity,
    chapters: List<ChapterEntity>,
    paragraphs: List<String>,
    viewModel: MainViewModel,
    bookId: String,
    fontFamily: FontFamily,
    textColor: Color,
    onToggleControls: () -> Unit,
    onSelectChapter: (String) -> Unit
) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val s = viewModel.settings
    val style = TextStyle(
        fontSize = viewModel.readerFontSize.sp,
        lineHeight = (viewModel.readerFontSize * viewModel.readerLineHeight).sp,
        fontFamily = fontFamily,
        letterSpacing = viewModel.readerLetterSpacing.em,
        color = textColor,
        textAlign = if (viewModel.readerJustificationEnabled) TextAlign.Justify else TextAlign.Start
    )
    val margin = s.readerMargin.dp
    val chapterIndex = chapters.indexOfFirst { it.id == activeChapter.id }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val widthPx = with(density) { (maxWidth - margin * 2).roundToPx() }
        // Room for the chapter title line and the page number.
        val heightPx = with(density) { (maxHeight - 72.dp).roundToPx() }
        val pages = remember(activeChapter.id, paragraphs, widthPx, heightPx, style) {
            paginate(paragraphs, measurer, style, widthPx, heightPx)
        }
        if (pages.isEmpty()) return@BoxWithConstraints

        val savedPara = remember(activeChapter.id) { viewModel.progress.getSavedParagraphIndex(bookId, activeChapter.id) }
        val startPage = remember(pages) { pages.indexOfLast { it.firstParagraph <= savedPara }.coerceAtLeast(0) }
        val pagerState = rememberPagerState(initialPage = startPage) { pages.size + 1 } // +1: "next chapter" page

        // Save position as the page changes; the extra last page opens the next chapter.
        LaunchedEffect(pagerState.currentPage, pages) {
            val p = pagerState.currentPage
            if (p < pages.size) {
                viewModel.progress.saveReadingProgress(bookId, activeChapter.id, pages[p].firstParagraph)
            } else {
                chapters.getOrNull(chapterIndex + 1)?.let { onSelectChapter(it.id) }
                    ?: pagerState.animateScrollToPage(pages.size - 1)
            }
        }
        LaunchedEffect(pagerState) {
            ReaderPaging.turns.collect { dir ->
                val target = (pagerState.currentPage + dir).coerceIn(0, pages.size)
                if (dir < 0 && pagerState.currentPage == 0) {
                    chapters.getOrNull(chapterIndex - 1)?.let { onSelectChapter(it.id) }
                } else pagerState.animateScrollToPage(target)
            }
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(pages) {
                    detectTapGestures { offset ->
                        val third = size.width / 3f
                        scope.launch {
                            when {
                                offset.x < third -> if (pagerState.currentPage > 0) pagerState.animateScrollToPage(pagerState.currentPage - 1)
                                    else chapters.getOrNull(chapterIndex - 1)?.let { onSelectChapter(it.id) }
                                offset.x > third * 2 -> pagerState.animateScrollToPage((pagerState.currentPage + 1).coerceAtMost(pages.size))
                                else -> onToggleControls()
                            }
                        }
                    }
                }
        ) { index ->
            if (index >= pages.size) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (chapterIndex + 1 < chapters.size) "Next chapter…" else "You're all caught up",
                        color = textColor.copy(alpha = 0.7f)
                    )
                }
            } else {
                Column(Modifier.fillMaxSize().padding(horizontal = margin)) {
                    Text(
                        activeChapter.title,
                        style = MaterialTheme.typography.labelMedium,
                        color = textColor.copy(alpha = 0.55f),
                        maxLines = 1,
                        modifier = Modifier.padding(top = 12.dp, bottom = 8.dp)
                    )
                    Text(pages[index].text, style = style, modifier = Modifier.weight(1f))
                    Text(
                        "${index + 1} / ${pages.size}",
                        style = MaterialTheme.typography.labelSmall,
                        color = textColor.copy(alpha = 0.55f),
                        modifier = Modifier.align(Alignment.CenterHorizontally).padding(vertical = 8.dp)
                    )
                }
            }
        }
    }
}
