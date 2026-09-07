package com.example.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.local.ChapterEntity
import com.example.viewmodel.MainViewModel

@Composable
fun ReaderBottomBar(
    visible: Boolean,
    currentChapterId: String,
    chapters: List<ChapterEntity>,
    viewModel: MainViewModel,
    barBgColor: Color,
    barContentColor: Color,
    onSelectChapter: (String) -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically()
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            tonalElevation = if (viewModel.readerTheme == "eink") 0.dp else 8.dp,
            color = barBgColor,
            contentColor = barContentColor,
            border = if (viewModel.readerTheme == "eink") BorderStroke(1.dp, Color.Black) else null
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val currentIdx = chapters.indexOfFirst { it.id == currentChapterId }
                val hasPrev = currentIdx > 0
                val hasNext = currentIdx < chapters.size - 1

                TextButton(
                    onClick = {
                        if (hasPrev) onSelectChapter(chapters[currentIdx - 1].id)
                    },
                    enabled = hasPrev,
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = barContentColor,
                        disabledContentColor = barContentColor.copy(alpha = 0.38f)
                    ),
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    Icon(imageVector = Icons.Default.ChevronLeft, contentDescription = "Previous")
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Previous", fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }

                Text(
                    text = if (chapters.isNotEmpty() && currentIdx != -1) {
                        "${currentIdx + 1} / ${chapters.size}"
                    } else "",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = barContentColor
                    ),
                    modifier = Modifier.padding(horizontal = 8.dp),
                    maxLines = 1
                )

                TextButton(
                    onClick = {
                        if (hasNext) onSelectChapter(chapters[currentIdx + 1].id)
                    },
                    enabled = hasNext,
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = barContentColor,
                        disabledContentColor = barContentColor.copy(alpha = 0.38f)
                    ),
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    Text("Next", fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(imageVector = Icons.Default.ChevronRight, contentDescription = "Next")
                }
            }
        }
    }
}
