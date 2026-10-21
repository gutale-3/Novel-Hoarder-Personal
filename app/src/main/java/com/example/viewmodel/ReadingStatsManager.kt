package com.example.viewmodel

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.data.local.ReadingSessionEntity
import com.example.data.repository.NovelRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

class ReadingStatsManager(
    private val application: Application,
    private val repository: NovelRepository,
    private val coroutineScope: CoroutineScope
) {
    var totalReadingSeconds by mutableLongStateOf(0L)
    var totalWordsRead by mutableLongStateOf(0L)
    var currentStreakDays by mutableIntStateOf(0)
    var todayReadingSeconds by mutableLongStateOf(0L)
    var todayWordsRead by mutableIntStateOf(0)
    var weeklyMinutes by mutableStateOf<List<Pair<String, Int>>>(emptyList())

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val dayNameFormat = SimpleDateFormat("EEE", Locale.US)

    init {
        observeStats()
    }

    fun observeStats() {
        coroutineScope.launch(Dispatchers.IO) {
            repository.getAllReadingSessionsFlow().collectLatest { sessions ->
                refreshStatsFromSessions(sessions)
            }
        }
    }

    private suspend fun refreshStatsFromSessions(sessions: List<ReadingSessionEntity>) = withContext(Dispatchers.Default) {
        val todayStr = dateFormat.format(Date())

        var totalSec = 0L
        var totalWords = 0L
        var todaySec = 0L
        var todayWords = 0

        val dateToSecondsMap = mutableMapOf<String, Long>()

        for (s in sessions) {
            totalSec += s.durationSeconds
            totalWords += s.wordsRead
            dateToSecondsMap[s.date] = (dateToSecondsMap[s.date] ?: 0L) + s.durationSeconds

            if (s.date == todayStr) {
                todaySec += s.durationSeconds
                todayWords += s.wordsRead
            }
        }

        // Calculate Daily Streak
        val dateSet = sessions.map { it.date }.toSet()
        var streak = 0
        val cal = Calendar.getInstance()
        val today = dateFormat.format(cal.time)
        cal.add(Calendar.DAY_OF_YEAR, -1)
        val yesterday = dateFormat.format(cal.time)

        if (dateSet.contains(today)) {
            streak = 1
            val checkCal = Calendar.getInstance()
            checkCal.add(Calendar.DAY_OF_YEAR, -1)
            while (dateSet.contains(dateFormat.format(checkCal.time))) {
                streak++
                checkCal.add(Calendar.DAY_OF_YEAR, -1)
            }
        } else if (dateSet.contains(yesterday)) {
            val checkCal = Calendar.getInstance()
            checkCal.add(Calendar.DAY_OF_YEAR, -1)
            while (dateSet.contains(dateFormat.format(checkCal.time))) {
                streak++
                checkCal.add(Calendar.DAY_OF_YEAR, -1)
            }
        }

        // Calculate past 7 days breakdown
        val weekList = mutableListOf<Pair<String, Int>>()
        val dayCal = Calendar.getInstance()
        dayCal.add(Calendar.DAY_OF_YEAR, -6)
        for (i in 0..6) {
            val dStr = dateFormat.format(dayCal.time)
            val dayName = dayNameFormat.format(dayCal.time)
            val sec = dateToSecondsMap[dStr] ?: 0L
            weekList.add(Pair(dayName, (sec / 60).toInt()))
            dayCal.add(Calendar.DAY_OF_YEAR, 1)
        }

        withContext(Dispatchers.Main) {
            totalReadingSeconds = totalSec
            totalWordsRead = totalWords
            todayReadingSeconds = todaySec
            todayWordsRead = todayWords
            currentStreakDays = streak
            weeklyMinutes = weekList
        }
    }

    fun recordReadingSession(bookId: String, chapterId: String, durationSeconds: Long, wordsRead: Int) {
        if (durationSeconds <= 0 && wordsRead <= 0) return
        // Sanitize to prevent runaway values if app was left backgrounded/idle
        val safeDuration = durationSeconds.coerceIn(1L, 3600L)
        val safeWords = wordsRead.coerceIn(0, 50000)
        coroutineScope.launch(Dispatchers.IO) {
            repository.recordReadingSession(bookId, chapterId, safeDuration, safeWords)
        }
    }

    fun resetAllStats() {
        coroutineScope.launch(Dispatchers.IO) {
            repository.clearAllReadingSessions()
            withContext(Dispatchers.Main) {
                totalReadingSeconds = 0L
                totalWordsRead = 0L
                todayReadingSeconds = 0L
                todayWordsRead = 0
                currentStreakDays = 0
                weeklyMinutes = emptyList()
            }
        }
    }

    fun formatDuration(seconds: Long): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        return when {
            hours > 0 -> "${hours}h ${minutes}m"
            minutes > 0 -> "${minutes}m"
            else -> "${seconds}s"
        }
    }

    fun estimateMinutesLeft(words: Int, wpm: Int = 240): Int {
        val effectiveWpm = if (wpm > 50) wpm else 240
        return ((words.toDouble() / effectiveWpm).coerceAtLeast(1.0)).toInt()
    }
}
