package com.example.dingding

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.view.Gravity
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.ripple.rememberRipple
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.scale
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale

@Composable
fun MonthlyCalendar(modifier: Modifier = Modifier) {
    val today = remember { LocalDate.now() }
    val yearMonth = remember { YearMonth.from(today) }
    val weeks = remember { buildMonthGrid(yearMonth) }
    val context = LocalContext.current
    val overrides = remember(context) {
        mutableStateMapOf<LocalDate, Boolean>().apply {
            putAll(loadOverrides(context))
        }
    }
    val punches: SnapshotStateList<Long> = remember(context) {
        mutableStateListOf<Long>().apply {
            addAll(loadPunches(context))
        }
    }
    val scope = rememberCoroutineScope()
    var dialogDate by remember { mutableStateOf<LocalDate?>(null) }
    var clearPunchDate by remember { mutableStateOf<LocalDate?>(null) }
    var selectedDate by remember { mutableStateOf<LocalDate?>(null) }
    var showAdjustDialog by remember { mutableStateOf(false) }
    var adjustInfo by remember(context) { mutableStateOf(loadAdjustInfo(context)) }
    var adjustFormula by remember { mutableStateOf("") }
    var adjustDate by remember { mutableStateOf(LocalDate.now().minusDays(1)) }
    var adjustTime by remember { mutableStateOf(LocalTime.now()) }
    val workdayCount by remember {
        derivedStateOf { countWorkdays(yearMonth, overrides) }
    }
    val totalWorkHours by remember {
        derivedStateOf { workdayCount * 7.5 }
    }
    val locale = LocalContext.current.resources.configuration.locales[0] ?: Locale.getDefault()
    val todayHours by remember {
        derivedStateOf { calculateHoursForDay(punches, today) }
    }
    val monthHours by remember {
        derivedStateOf { calculateMonthHoursWithAdjust(punches, yearMonth, adjustInfo) }
    }
    val dateFormatter = remember(locale) { DateTimeFormatter.ofPattern("yyyy-MM-dd", locale) }
    val timeFormatter = remember(locale) { DateTimeFormatter.ofPattern("HH:mm", locale) }
    var lastPunchMillis by remember { mutableStateOf(0L) }
    var cooldownSeconds by remember { mutableStateOf(0) }
    var cooldownJob by remember { mutableStateOf<Job?>(null) }
    var showResetAdjust by remember { mutableStateOf(false) }
    val punchDisplay by remember {
        derivedStateOf {
            val filtered = punches.filter { ts ->
                selectedDate?.let { timestampToLocalDate(ts) == it } ?: true
            }
            if (filtered.isEmpty()) {
                selectedDate?.let { "$it 无打卡记录" }
            } else {
                filtered.mapIndexed { index, ts ->
                    val label = if (index % 2 == 0) "上班" else "下班"
                    "$label：${formatTimestamp(ts, locale)}"
                }.joinToString("\n")
            }
        }
    }
    val isClockInNext by remember { derivedStateOf { punches.size % 2 == 0 } }
    val fabLabel by remember {
        derivedStateOf { if (isClockInNext) "上班打卡" else "下班打卡" }
    }
    val baseFabVisible by remember { derivedStateOf { selectedDate == null || selectedDate == today } }
    val fabEnabled by remember { derivedStateOf { cooldownSeconds == 0 } }
    val dayHeaders = remember {
        listOf(
            DayOfWeek.MONDAY,
            DayOfWeek.TUESDAY,
            DayOfWeek.WEDNESDAY,
            DayOfWeek.THURSDAY,
            DayOfWeek.FRIDAY,
            DayOfWeek.SATURDAY,
            DayOfWeek.SUNDAY
        )
    }

    val gap = 12.dp
    val fabPadding = 16.dp
    val fabHeight = 56.dp

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 16.dp)
                .padding(bottom = fabHeight + fabPadding + gap),
            verticalArrangement = Arrangement.spacedBy(gap)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "${yearMonth.month.getDisplayName(TextStyle.FULL, locale)} ${yearMonth.year}",
                    style = MaterialTheme.typography.titleLarge
                )
                val adjustButtonInteraction = remember { MutableInteractionSource() }
                val ripple = rememberRipple(bounded = true)
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    tonalElevation = 1.dp,
                    modifier = Modifier
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onLongPress = { showResetAdjust = true },
                                onTap = {
                                    adjustFormula = ""
                                    adjustDate = LocalDate.now().minusDays(1)
                                    adjustTime = LocalTime.now()
                                    showAdjustDialog = true
                                }
                            )
                        }
                ) {
                    Text(
                        text = "修正工时",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                dayHeaders.forEach { day ->
                    Text(
                        modifier = Modifier.weight(1f),
                        text = day.getDisplayName(TextStyle.SHORT, locale),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
                    )
                }
            }
            weeks.forEach { week ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    week.forEach { date ->
                        val effectiveWorkday = if (date.month == yearMonth.month) {
                            overrides[date] ?: isDefaultWorkday(date)
                        } else {
                            false
                        }
                        val isRestDay = !effectiveWorkday
                        val hasPunch = punches.any { ts -> timestampToLocalDate(ts) == date }
                        DayCell(
                            date = date,
                            isCurrentMonth = date.month == yearMonth.month,
                            isToday = date == today,
                            isWorkday = effectiveWorkday,
                            isRestDay = isRestDay,
                            hasPunchOnRestDay = hasPunch && isRestDay,
                            onLongPress = {
                                if (date.month == yearMonth.month) {
                                    dialogDate = date
                                }
                            },
            onDoubleClick = {
                if (date.month == yearMonth.month) {
                    clearPunchDate = date
                }
            },
            onClick = {
                if (date.month == yearMonth.month) {
                    selectedDate = date
                }
            },
            modifier = Modifier
                .weight(1f)
                .aspectRatio(1f)
                .padding(2.dp)
                        )
                    }
                }
            }
            WorkSummary(
                workdays = workdayCount,
                totalHours = totalWorkHours,
                monthlyHours = monthHours,
                todayHours = todayHours
            )
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = true)
                    .padding(bottom = gap),
                shape = MaterialTheme.shapes.medium,
                tonalElevation = 1.dp
            ) {
                val scrollState = rememberScrollState()
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp)
                        .verticalScroll(scrollState)
                ) {
                    punchDisplay?.let { text ->
                        Text(
                            text = text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        if (baseFabVisible) {
            ExtendedFloatingActionButton(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
                containerColor = if (fabEnabled) {
                    if (isClockInNext) MaterialTheme.colorScheme.primary else Color.Black
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                contentColor = if (fabEnabled) {
                    if (isClockInNext) MaterialTheme.colorScheme.onPrimary else Color.White
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                shape = RoundedCornerShape(20.dp),
                onClick = {
                    if (!fabEnabled) return@ExtendedFloatingActionButton
                    val nowTs = System.currentTimeMillis()
                    if (nowTs - lastPunchMillis < 10_000L) return@ExtendedFloatingActionButton
                    val now = System.currentTimeMillis()
                    punches.add(0, now)
                    lastPunchMillis = now
                    cooldownJob?.cancel()
                    cooldownSeconds = 10
                    cooldownJob = scope.launch {
                        var remaining = 10
                        while (remaining > 0) {
                            delay(1000)
                            remaining -= 1
                            cooldownSeconds = remaining
                        }
                    }
                    scope.launch {
                        savePunches(context, punches)
                    }
                }
            ) {
                Text(
                    text = if (cooldownSeconds > 0) "${fabLabel}(${cooldownSeconds}s)" else fabLabel,
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }
    }

    dialogDate?.let { target ->
        val currentIsWorkday = overrides[target] ?: isDefaultWorkday(target)
        val targetText = if (currentIsWorkday) "是否改为休息日" else "是否改为工作日"
        AlertDialog(
            onDismissRequest = { dialogDate = null },
            title = { Text(text = targetText) },
            confirmButton = {
                TextButton(
                    onClick = {
                        val newValue = !currentIsWorkday
                        if (newValue == isDefaultWorkday(target)) {
                            overrides.remove(target)
                        } else {
                            overrides[target] = newValue
                        }
                        scope.launch {
                            saveOverrides(context, overrides)
                        }
                        dialogDate = null
                    }
                ) {
                    Text("确定")
                }
            },
            dismissButton = {
                TextButton(onClick = { dialogDate = null }) {
                    Text("取消")
                }
            }
        )
    }

    clearPunchDate?.let { target ->
        val targetText = "是否清除${target}的打卡记录?"
        AlertDialog(
            onDismissRequest = { clearPunchDate = null },
            title = { Text(text = targetText) },
            confirmButton = {
                TextButton(
                    onClick = {
                        val removed = punches.removeAll { ts ->
                            timestampToLocalDate(ts) == target
                        }
                        if (removed) {
                            scope.launch {
                                savePunches(context, punches)
                            }
                        }
                        clearPunchDate = null
                    }
                ) {
                    Text("确定")
                }
            },
            dismissButton = {
                TextButton(onClick = { clearPunchDate = null }) {
                    Text("取消")
                }
            }
        )
    }

    if (showAdjustDialog) {
        val contextForPicker = LocalContext.current
        AlertDialog(
            onDismissRequest = { showAdjustDialog = false },
            title = { Text("修正工时") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = adjustFormula,
                        onValueChange = { adjustFormula = it },
                        placeholder = { Text("请输入精确工时") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true
                    )
                    Button(
                        onClick = {
                            val initial = adjustDate
                            DatePickerDialog(
                                contextForPicker,
                                { _, y, m, d ->
                                    adjustDate = LocalDate.of(y, m + 1, d)
                                },
                                initial.year,
                                initial.monthValue - 1,
                                initial.dayOfMonth
                            ).show()
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                    ) {
                        Text("精确工时截止日：${adjustDate.format(dateFormatter)}")
                    }
                    if (adjustDate == today) {
                        Button(
                            onClick = {
                                val initial = adjustTime
                                TimePickerDialog(
                                    contextForPicker,
                                    { _, hour, minute ->
                                        adjustTime = LocalTime.of(hour, minute)
                                    },
                                    initial.hour,
                                    initial.minute,
                                    true
                                ).show()
                            },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                        ) {
                            Text("精确时间：${adjustTime.format(timeFormatter)}")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val hours = adjustFormula.toDoubleOrNull()
                    if (hours == null) {
                        Toast.makeText(contextForPicker, "请输入精确工时", Toast.LENGTH_SHORT).show()
                        return@TextButton
                    }
                    val endDateTime = LocalDateTime.of(adjustDate, adjustTime)
                    if (YearMonth.from(adjustDate) != yearMonth) {
                        Toast.makeText(contextForPicker, "请选择当前月份的日期", Toast.LENGTH_SHORT).show()
                        return@TextButton
                    }
                    if (endDateTime.isAfter(LocalDateTime.now())) {
                        Toast.makeText(contextForPicker, "截止时间不能晚于当前时间", Toast.LENGTH_SHORT).show()
                        return@TextButton
                    }
                    val info = AdjustInfo(
                        month = yearMonth,
                        hours = hours,
                        endMillis = endDateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                    )
                    adjustInfo = info
                    saveAdjustInfo(contextForPicker, info)
                    showAdjustDialog = false
                }) {
                    Text("确定")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAdjustDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    if (showResetAdjust) {
        AlertDialog(
            onDismissRequest = { showResetAdjust = false },
            title = { Text("是否将精确工时复位?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        adjustInfo = null
                        saveAdjustInfo(context, null)
                        showResetAdjust = false
                    }
                ) {
                    Text("确定")
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetAdjust = false }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
private fun WorkSummary(
    workdays: Int,
    totalHours: Double,
    monthlyHours: Double,
    todayHours: Double,
    modifier: Modifier = Modifier
) {
    val locale = LocalContext.current.resources.configuration.locales[0] ?: Locale.getDefault()
    val totalHoursLabel = remember(locale, totalHours) { String.format(locale, "%.1f", totalHours) }
    val monthlyHoursLabel = remember(locale, monthlyHours) { String.format(locale, "%.1f", monthlyHours) }
    val todayHoursLabel = remember(locale, todayHours) { String.format(locale, "%.1f", todayHours) }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "共有${workdays}个工作日，总工时${totalHoursLabel}小时",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = "本月已打卡：${monthlyHoursLabel}小时",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "今日已打卡：${todayHoursLabel}小时",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DayCell(
    date: LocalDate,
    isCurrentMonth: Boolean,
    isToday: Boolean,
    isWorkday: Boolean,
    isRestDay: Boolean,
    hasPunchOnRestDay: Boolean,
    onLongPress: () -> Unit,
    onDoubleClick: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val textColor = when {
        !isCurrentMonth -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
        isRestDay -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onSurface
    }
    val background = if (isCurrentMonth && isWorkday) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
    } else {
        Color.Transparent
    }

    Surface(
        modifier = modifier.combinedClickable(
            onLongClick = { if (isCurrentMonth) onLongPress() },
            onClick = { if (isCurrentMonth) onClick() },
            onDoubleClick = { if (isCurrentMonth) onDoubleClick() }
        ),
        color = background,
        tonalElevation = if (isToday) 4.dp else 0.dp
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxSize()
        ) {
            Text(
                text = date.dayOfMonth.toString(),
                color = textColor,
                style = MaterialTheme.typography.bodyLarge
            )
            if (hasPunchOnRestDay) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .size(6.dp)
                        .background(MaterialTheme.colorScheme.error, shape = CircleShape)
                )
            }
            if (isToday) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(MaterialTheme.colorScheme.error.copy(alpha = 0.5f))
                )
            }
        }
    }
}

private fun countWorkdays(month: YearMonth, overrides: Map<LocalDate, Boolean>): Int {
    val start = month.atDay(1)
    val end = month.atEndOfMonth()
    return generateSequence(start) { it.plusDays(1) }
        .takeWhile { !it.isAfter(end) }
        .count { date ->
            overrides[date] ?: isDefaultWorkday(date)
        }
}

private fun buildMonthGrid(month: YearMonth): List<List<LocalDate>> {
    val firstDay = month.atDay(1)
    val start = firstDay.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val lastDay = month.atEndOfMonth()
    val end = lastDay.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))

    val days = generateSequence(start) { it.plusDays(1) }
        .takeWhile { !it.isAfter(end) }
        .toList()

    return days.chunked(7)
}

private fun isDefaultWorkday(date: LocalDate): Boolean {
    return date.dayOfWeek.value in DayOfWeek.MONDAY.value..DayOfWeek.FRIDAY.value
}

private const val PREF_NAME = "dingding_calendar"
private const val PREF_OVERRIDES = "overrides"
private const val PREF_PUNCHES = "punches"

private fun loadOverrides(context: Context): Map<LocalDate, Boolean> {
    val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    val raw = prefs.getString(PREF_OVERRIDES, "") ?: ""
    if (raw.isBlank()) return emptyMap()
    return raw.split(",")
        .mapNotNull { entry ->
            val parts = entry.split("=")
            if (parts.size != 2) return@mapNotNull null
            val date = runCatching { LocalDate.parse(parts[0]) }.getOrNull() ?: return@mapNotNull null
            val isWorkday = parts[1] == "1"
            date to isWorkday
        }
        .toMap()
}

private fun saveOverrides(context: Context, overrides: Map<LocalDate, Boolean>) {
    val serialized = overrides.entries.joinToString(",") { (date, isWorkday) ->
        "${date}=${if (isWorkday) "1" else "0"}"
    }
    context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        .edit()
        .putString(PREF_OVERRIDES, serialized)
        .apply()
}

private fun loadPunches(context: Context): List<Long> {
    val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    val raw = prefs.getString(PREF_PUNCHES, "") ?: ""
    if (raw.isBlank()) return emptyList()
    return raw.split(",")
        .mapNotNull { it.toLongOrNull() }
        .filter { it > 0 }
}

private fun savePunches(context: Context, punches: List<Long>) {
    val serialized = punches.joinToString(",")
    context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        .edit()
        .putString(PREF_PUNCHES, serialized)
        .apply()
}

private fun loadAdjustInfo(context: Context): AdjustInfo? {
    val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    val monthStr = prefs.getString(PREF_ADJUST_MONTH, null) ?: return null
    val hours = Double.fromBits(prefs.getLong(PREF_ADJUST_HOURS, Double.NaN.toRawBits()))
    val end = prefs.getLong(PREF_ADJUST_END, -1L)
    if (hours.isNaN() || end <= 0) return null
    val month = runCatching { YearMonth.parse(monthStr) }.getOrNull() ?: return null
    return AdjustInfo(month = month, hours = hours, endMillis = end)
}

private fun saveAdjustInfo(context: Context, info: AdjustInfo?) {
    val edit = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit()
    if (info == null) {
        edit.remove(PREF_ADJUST_MONTH)
        edit.remove(PREF_ADJUST_HOURS)
        edit.remove(PREF_ADJUST_END)
    } else {
        edit.putString(PREF_ADJUST_MONTH, info.month.toString())
        edit.putLong(PREF_ADJUST_HOURS, info.hours.toRawBits())
        edit.putLong(PREF_ADJUST_END, info.endMillis)
    }
    edit.apply()
}

private fun calculateHoursForDay(punches: List<Long>, date: LocalDate): Double {
    val zone = ZoneId.systemDefault()
    val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
    val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val millis = calculateWorkedMillis(punches, start, end)
    return millis / 3_600_000.0
}

private fun calculateHoursForMonth(punches: List<Long>, today: LocalDate): Double {
    val zone = ZoneId.systemDefault()
    val monthStartDate = today.withDayOfMonth(1)
    val start = monthStartDate.atStartOfDay(zone).toInstant().toEpochMilli()
    val end = monthStartDate.plusMonths(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val millis = calculateWorkedMillis(punches, start, end)
    return millis / 3_600_000.0
}

private fun calculateMonthHoursWithAdjust(
    punches: List<Long>,
    month: YearMonth,
    adjustInfo: AdjustInfo?
): Double {
    val zone = ZoneId.systemDefault()
    val start = month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val end = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
    if (adjustInfo == null || adjustInfo.month != month) {
        val millis = calculateWorkedMillis(punches, start, end)
        return millis / 3_600_000.0
    }
    val adjustedEnd = adjustInfo.endMillis.coerceAtMost(end)
    if (adjustedEnd <= start) {
        val millis = calculateWorkedMillis(punches, start, end)
        return millis / 3_600_000.0
    }
    val afterMillis = calculateWorkedMillis(punches, adjustedEnd, end)
    return adjustInfo.hours + afterMillis / 3_600_000.0
}

private fun calculateWorkedMillis(punches: List<Long>, rangeStart: Long, rangeEnd: Long): Long {
    if (punches.isEmpty()) return 0L
    val sorted = punches.sorted()
    var total = 0L
    var i = 0
    while (i < sorted.size) {
        val start = sorted[i]
        val end = sorted.getOrNull(i + 1) ?: break
        val intervalStart = maxOf(start, rangeStart)
        val intervalEnd = minOf(end, rangeEnd)
        if (intervalEnd > intervalStart) {
            total += intervalEnd - intervalStart
        }
        i += 2
    }
    return total
}

private fun formatTimestamp(timestamp: Long, locale: Locale): String {
    val dateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(timestamp), ZoneId.systemDefault())
    val formatter = DateTimeFormatter.ofPattern("HH:mm:ss", locale)
    return dateTime.format(formatter)
}

private fun timestampToLocalDate(timestamp: Long): LocalDate {
    return Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).toLocalDate()
}

private data class AdjustInfo(
    val month: YearMonth,
    val hours: Double,
    val endMillis: Long
)

private const val PREF_ADJUST_MONTH = "adjust_month"
private const val PREF_ADJUST_HOURS = "adjust_hours"
private const val PREF_ADJUST_END = "adjust_end"
