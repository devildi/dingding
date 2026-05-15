package com.example.dingding

import android.app.DatePickerDialog
import android.app.PendingIntent
import android.app.TimePickerDialog
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.widget.RemoteViews
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.ripple.rememberRipple
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.rotate
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.graphics.nativeCanvas

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MonthlyCalendar(modifier: Modifier = Modifier) {
    val today = remember { LocalDate.now() }
    val yearMonth = remember { YearMonth.from(today) }
    val configuration = LocalConfiguration.current
    val isSquareishScreen = remember(configuration.screenWidthDp, configuration.screenHeightDp) {
        val height = configuration.screenHeightDp.takeIf { it > 0 } ?: 1
        val ratio = configuration.screenWidthDp.toFloat() / height.toFloat()
        ratio in 0.85f..1.15f
    }
    val isLandscape = remember(configuration.screenWidthDp, configuration.screenHeightDp) {
        configuration.screenWidthDp > configuration.screenHeightDp
    }
    val useWeekView = isSquareishScreen || isLandscape
    val weeks = remember(yearMonth, useWeekView, today) {
        val monthGrid = buildMonthGrid(yearMonth)
        if (useWeekView) {
            monthGrid.firstOrNull { week -> week.contains(today) }?.let { listOf(it) } ?: listOf(monthGrid.first())
        } else {
            monthGrid
        }
    }
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
    var dailyHours by remember(context) { mutableStateOf(loadDailyHours(context)) }
    var plannedDailyHours by remember(context) { mutableStateOf(loadPlannedDailyHours(context)) }
    var plannedSetDate by remember(context) { mutableStateOf(loadPlannedSetDate(context)) }
    val scope = rememberCoroutineScope()
    var dialogDate by remember { mutableStateOf<LocalDate?>(null) }
    var clearPunchDate by remember { mutableStateOf<LocalDate?>(null) }
    var selectedDate by remember { mutableStateOf<LocalDate?>(null) }
    var showAdjustDialog by remember { mutableStateOf(false) }
    var adjustInfo by remember(context) { mutableStateOf(loadAdjustInfo(context)) }
    var adjustFormula by remember { mutableStateOf("") }
    var adjustDate by remember { mutableStateOf(LocalDate.now().minusDays(1)) }
    var adjustTime by remember { mutableStateOf(LocalTime.now()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                punches.clear()
                punches.addAll(loadPunches(context))
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action == PunchWidgetProvider.ACTION_PUNCH) {
                    punches.clear()
                    punches.addAll(loadPunches(context))
                }
            }
        }
        val filter = IntentFilter(PunchWidgetProvider.ACTION_PUNCH)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        onDispose {
            context.unregisterReceiver(receiver)
        }
    }
    LaunchedEffect(Unit) {
        purgePreviousMonthData(
            today = LocalDate.now(),
            punches = punches,
            adjustInfo = { adjustInfo },
            setAdjustInfo = { adjustInfo = it },
            context = context
        )
    }
    val workdayCount by remember {
        derivedStateOf { countWorkdays(yearMonth, overrides) }
    }
    val workdaysUpToToday by remember {
        derivedStateOf { countWorkdaysUpToDate(yearMonth, today, overrides) }
    }
    val totalWorkHours by remember {
        derivedStateOf { workdayCount * dailyHours }
    }
    val locale = LocalContext.current.resources.configuration.locales[0] ?: Locale.getDefault()
    val todayHours by remember {
        derivedStateOf { calculateHoursForDay(punches, today) }
    }
    val targetDateHours by remember {
        derivedStateOf { calculateHoursForDay(punches, selectedDate ?: today) }
    }
    val targetDateLabel by remember {
        derivedStateOf {
            val date = selectedDate ?: today
            if (date == today) "今日已打卡" else "${date.monthValue}月${date.dayOfMonth}日已打卡"
        }
    }
    val monthHours by remember {
        derivedStateOf { calculateMonthHoursWithAdjust(punches, yearMonth, adjustInfo) }
    }
    val dateFormatter = remember(locale) { DateTimeFormatter.ofPattern("yyyy-MM-dd", locale) }
    val timeFormatter = remember(locale) { DateTimeFormatter.ofPattern("HH:mm", locale) }
    var lastPunchMillis by remember { mutableStateOf(0L) }
    var cooldownSeconds by remember { mutableStateOf(0) }
    var showResetAdjust by remember { mutableStateOf(false) }
    var showDailyHoursDialog by remember { mutableStateOf(false) }
    var showResetPlannedDialog by remember { mutableStateOf(false) }
    var showTodayTimerDialog by remember { mutableStateOf(false) }
    var showDeletePunchDialog by remember { mutableStateOf(false) }
    var deleteTargetTimestamp by remember { mutableStateOf(0L) }
    var showCalendarSelectDialog by remember { mutableStateOf(false) }
    var showMonthlyStatsDialog by remember { mutableStateOf(false) }
    var dailyHoursInput by remember { mutableStateOf("") }
    var plannedDailyHoursInput by remember { mutableStateOf("") }
    val selectedCalendarDates = remember(context) {
        mutableStateListOf<LocalDate>().apply {
            addAll(loadSelectedCalendarDates(context))
        }
    }
    var isSortDescending by remember { mutableStateOf(true) }

    val latestPunch = punches.firstOrNull() ?: 0L
    LaunchedEffect(latestPunch) {
        val nowSession = System.currentTimeMillis()
        val diff = nowSession - latestPunch
        if (latestPunch > 0 && diff < 10_000L) {
            lastPunchMillis = latestPunch
            var remain = 10 - (diff / 1000).toInt()
            while (remain > 0) {
                cooldownSeconds = remain
                delay(1000)
                remain -= 1
            }
            cooldownSeconds = 0
        } else {
            lastPunchMillis = latestPunch
            cooldownSeconds = 0
        }
    }
    val punchDisplay by remember {
        derivedStateOf {
            val targetDate = selectedDate ?: today
            val filtered = punches.filter { ts -> timestampToLocalDate(ts) == targetDate }
            val sorted = filtered.sorted()
            if (sorted.isEmpty()) {
                "$targetDate 无打卡记录"
            } else {
                val ordered = if (isSortDescending) sorted.reversed() else sorted
                val displayLines = ordered.mapIndexed { index, ts ->
                    val lineNumber = if (isSortDescending) sorted.size - index else index + 1
                    val origIndex = if (isSortDescending) sorted.size - 1 - index else index
                    val label = if (origIndex % 2 == 0) "上班" else "下班"
                    "${lineNumber}. $label：${formatTimestamp(ts, locale)}"
                }.toMutableList()

                // 如果是今天且打卡记录是奇数条（有上班卡但没下班卡）
                if (targetDate == today && sorted.size % 2 == 1) {
                    val difference = if (plannedSetDate != null) {
                        // 从设定计划的那天起，过去数据不参与计算
                        val effectiveSetDate = maxOf(plannedSetDate!!, yearMonth.atDay(1))
                        val overridesMap = overrides.toMap()
                        val workdaysUpToToday = countWorkdaysUpToDate(yearMonth, today, overridesMap)
                        val workdaysBeforeSet = countWorkdaysUpToDate(yearMonth, effectiveSetDate.minusDays(1), overridesMap)
                        val workdaysFromSet = workdaysUpToToday - workdaysBeforeSet
                        val theoreticalHours = workdaysFromSet * plannedDailyHours
                        val zone = ZoneId.systemDefault()
                        val startMillis = effectiveSetDate.atStartOfDay(zone).toInstant().toEpochMilli()
                        val endMillis = today.atStartOfDay(zone).toInstant().toEpochMilli()
                        val actualFromSet = calculateWorkedMillis(punches, startMillis, endMillis) / 3_600_000.0
                        theoreticalHours - (actualFromSet + todayHours)
                    } else {
                        // 默认 7.5：全部工作日 × 7.5 − 本月已打卡
                        val workdaysUpToToday = countWorkdaysUpToDate(yearMonth, today, overrides.toMap())
                        workdaysUpToToday * DEFAULT_DAILY_HOURS - monthHours
                    }

                    // 只有当差值 >= 0 时才显示建议下班时间
                    if (difference >= 0) {
                        val lastClockInTimestamp = sorted.last()
                        val suggestedClockOutMillis = lastClockInTimestamp + (difference * 3_600_000).toLong()
                        val suggestedClockOutDate = Instant.ofEpochMilli(suggestedClockOutMillis)
                            .atZone(ZoneId.systemDefault())
                            .toLocalDate()

                        // 判断建议下班时间是否是今天
                        if (suggestedClockOutDate == today) {
                            // 建议下班时间是今天，显示具体时间
                            val suggestedTime = LocalDateTime.ofInstant(
                                Instant.ofEpochMilli(suggestedClockOutMillis),
                                ZoneId.systemDefault()
                            )
                            val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss", locale)
                            displayLines.add(0, "建议下班时间：${suggestedTime.format(timeFormatter)}")
                        } else {
                            // 建议下班时间不是今天，显示工时差提醒
                            val differenceLabel = String.format(locale, "%.1f", difference)
                            displayLines.add(0, "还差${differenceLabel}小时,请注意打卡时间")
                        }
                    }
                }
                
                displayLines.joinToString("\n")
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
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.combinedClickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {
                                val remainingHoursForPrefill = totalWorkHours - monthHours
                                val totalNeeded = todayHours + kotlin.math.max(0.0, remainingHoursForPrefill)
                                val daysFromPlan = if (plannedDailyHours > 0) {
                                    kotlin.math.ceil(totalNeeded / plannedDailyHours).toInt()
                                } else {
                                    0
                                }
                                dailyHoursInput = daysFromPlan.toString()
                                plannedDailyHoursInput = String.format(locale, "%.1f", plannedDailyHours)
                                showDailyHoursDialog = true
                            },
                            onLongClick = {
                                showResetPlannedDialog = true
                            }
                        )
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
                .padding(2.dp),
                            isSelected = date == selectedDate
                        )
                    }
                }
            }
            val todayPunches = remember(punches) { punches.filter { timestampToLocalDate(it) == today } }
            WorkSummary(
                workdays = workdayCount,
                totalHours = totalWorkHours,
                monthlyHours = monthHours,
                targetDateLabel = targetDateLabel,
                targetDateHours = targetDateHours,
                workdaysUpToToday = workdaysUpToToday,
                dailyHours = dailyHours,
                isTimerClickable = (selectedDate ?: today) == today && todayPunches.size % 2 != 0,
                onClick = { showTodayTimerDialog = true },
                onMonthlyStatsClick = { showMonthlyStatsDialog = true }
            )
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = true)
                    .padding(bottom = gap),
                shape = MaterialTheme.shapes.medium,
                tonalElevation = 1.dp
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    val scrollState = rememberScrollState()
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(12.dp)
                            .verticalScroll(scrollState)
                    ) {
                    punchDisplay?.let { text ->
                        val lines = text.split("\n")
                        val targetDate = selectedDate ?: today
                        val filteredPunches = punches.filter { ts -> timestampToLocalDate(ts) == targetDate }.sorted()

                        Column(modifier = Modifier.fillMaxSize()) {
                            lines.forEachIndexed { index, line ->
                                // 判断是否是打卡记录行（格式：1. 上班：HH:mm:ss 或 2. 下班：HH:mm:ss）
                                val isPunchLine = line.matches(Regex("^\\d+\\. (上班|下班)：.*"))
                                val punchIndex = if (isPunchLine) {
                                    line.substringBefore(".").toIntOrNull()?.minus(1)
                                } else null

                                val annotatedString = androidx.compose.ui.text.buildAnnotatedString {
                                    // 红色显示：建议下班时间 或 工时差提醒
                                    if (line.startsWith("建议下班时间：") || line.contains("小时,请注意打卡时间")) {
                                        withStyle(style = androidx.compose.ui.text.SpanStyle(color = MaterialTheme.colorScheme.error)) {
                                            append(line)
                                        }
                                    } else {
                                        withStyle(style = androidx.compose.ui.text.SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) {
                                            append(line)
                                        }
                                    }
                                }

                                val isClickable = isPunchLine && punchIndex != null && punchIndex < filteredPunches.size && targetDate == today

                                if (isClickable) {
                                    Text(
                                        text = annotatedString,
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.combinedClickable(
                                            onClick = {
                                                val currentTimestamp = filteredPunches[punchIndex!!]
                                                val currentDateTime = LocalDateTime.ofInstant(
                                                    Instant.ofEpochMilli(currentTimestamp),
                                                    ZoneId.systemDefault()
                                                )
                                                val currentTime = currentDateTime.toLocalTime()

                                                fun showPicker(initialHour: Int, initialMinute: Int) {
                                                    TimePickerDialog(
                                                        context,
                                                        { _, hour, minute ->
                                                            val newTime = LocalTime.of(hour, minute)
                                                            val newDateTime = LocalDateTime.of(targetDate, newTime)
                                                            val newTimestamp = newDateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

                                                            val validationError = validatePunchTime(
                                                                targetDate = targetDate,
                                                                editingIndex = punchIndex!!,
                                                                newTimestamp = newTimestamp,
                                                                allPunches = filteredPunches,
                                                                context = context
                                                            )

                                                            if (validationError != null) {
                                                                Toast.makeText(context, validationError, Toast.LENGTH_SHORT).show()
                                                                // 验证失败提示并重新弹出让用户重新选择
                                                                showPicker(hour, minute)
                                                            } else {
                                                                val oldTimestamp = filteredPunches[punchIndex!!]
                                                                val indexInPunches = punches.indexOf(oldTimestamp)
                                                                if (indexInPunches != -1) {
                                                                    punches[indexInPunches] = newTimestamp
                                                                    scope.launch { savePunches(context, punches) }
                                                                }
                                                            }
                                                        },
                                                        initialHour,
                                                        initialMinute,
                                                        true
                                                    ).show()
                                                }

                                                showPicker(currentTime.hour, currentTime.minute)
                                            },
                                            onLongClick = {
                                                deleteTargetTimestamp = filteredPunches[punchIndex!!]
                                                showDeletePunchDialog = true
                                            }
                                        )
                                    )
                                } else {
                                    Text(
                                        text = annotatedString,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                        }
                    }
                }
                if (punches.any { timestampToLocalDate(it) == (selectedDate ?: today) }) {
                    IconButton(
                        onClick = { isSortDescending = !isSortDescending },
                        modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)
                    ) {
                        val rotation by animateFloatAsState(targetValue = if (isSortDescending) 0f else 180f)
                        Icon(
                            imageVector = Icons.Filled.KeyboardArrowDown,
                            contentDescription = "Sort records",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.rotate(rotation)
                        )
                    }
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
                    cooldownSeconds = 10
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
                    val showTimePicker = {
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
                    }

                    Button(
                        onClick = {
                            val initial = adjustDate
                            DatePickerDialog(
                                contextForPicker,
                                { _, y, m, d ->
                                    val newDate = LocalDate.of(y, m + 1, d)
                                    adjustDate = newDate
                                    if (newDate == today) {
                                        showTimePicker()
                                    }
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
                                showTimePicker()
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
                    val endDateTime = if (adjustDate == today) {
                        LocalDateTime.of(adjustDate, adjustTime)
                    } else {
                        // 非当天选择时，覆盖到该日 23:59:59.999999999
                        adjustDate.plusDays(1).atStartOfDay().minusNanos(1)
                    }
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
                    // 如果修正工时的截止时间在设定日期之后，弹出设置每日打卡计划对话框
                    if (plannedSetDate != null) {
                        val setDateStartMillis = plannedSetDate!!.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                        if (info.endMillis > setDateStartMillis) {
                            val remainingHoursForPrefill = totalWorkHours - monthHours
                            val totalNeeded = todayHours + kotlin.math.max(0.0, remainingHoursForPrefill)
                            val daysFromPlan = if (plannedDailyHours > 0) {
                                kotlin.math.ceil(totalNeeded / plannedDailyHours).toInt()
                            } else {
                                0
                            }
                            dailyHoursInput = daysFromPlan.toString()
                            plannedDailyHoursInput = String.format(locale, "%.1f", plannedDailyHours)
                            showDailyHoursDialog = true
                        }
                    }
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

    if (showResetPlannedDialog) {
        AlertDialog(
            onDismissRequest = { showResetPlannedDialog = false },
            title = { Text("重置计划每日工时") },
            text = { Text("是否将计划每日工时重置为${DEFAULT_PLANNED_DAILY_HOURS}小时？") },
            confirmButton = {
                TextButton(onClick = {
                    plannedDailyHours = DEFAULT_PLANNED_DAILY_HOURS
                    plannedSetDate = null
                    savePlannedDailyHours(context, DEFAULT_PLANNED_DAILY_HOURS)
                    clearPlannedSetDate(context)
                    showResetPlannedDialog = false
                }) {
                    Text("确定")
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetPlannedDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    if (showTodayTimerDialog) {
        var currentDurationMillis by remember { mutableStateOf(0L) }
        val targetPunches = remember(punches) { punches.filter { timestampToLocalDate(it) == today }.sorted() }
        
        LaunchedEffect(targetPunches) {
            while (true) {
                var total = 0L
                for (i in 0 until targetPunches.size step 2) {
                    val start = targetPunches[i]
                    val end = targetPunches.getOrNull(i + 1) ?: System.currentTimeMillis()
                    total += (end - start)
                }
                currentDurationMillis = total
                delay(1000)
            }
        }
        
        val totalSeconds = currentDurationMillis / 1000
        val seconds = totalSeconds % 60
        val minutes = (totalSeconds / 60) % 60
        val hours = totalSeconds / 3600

        AlertDialog(
            onDismissRequest = { showTodayTimerDialog = false },
            title = {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("今日已打卡：", style = MaterialTheme.typography.titleMedium)
                }
            },
            text = {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.8f)
                            .aspectRatio(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        val primaryColor = MaterialTheme.colorScheme.primary
                        val onSurfaceColor = MaterialTheme.colorScheme.onSurface
                        val errorColor = MaterialTheme.colorScheme.error

                        val textPaintLarge = remember(onSurfaceColor) {
                            android.graphics.Paint().apply {
                                textAlign = android.graphics.Paint.Align.CENTER
                                textSize = 32f
                                isAntiAlias = true
                                color = android.graphics.Color.argb(
                                    (onSurfaceColor.alpha * 255).toInt(),
                                    (onSurfaceColor.red * 255).toInt(),
                                    (onSurfaceColor.green * 255).toInt(),
                                    (onSurfaceColor.blue * 255).toInt()
                                )
                            }
                        }
                        val textPaintSmall = remember(onSurfaceColor) {
                            android.graphics.Paint().apply {
                                textAlign = android.graphics.Paint.Align.CENTER
                                textSize = 20f
                                isAntiAlias = true
                                color = android.graphics.Color.argb(
                                    (onSurfaceColor.alpha * 255).toInt(),
                                    (onSurfaceColor.red * 255).toInt(),
                                    (onSurfaceColor.green * 255).toInt(),
                                    (onSurfaceColor.blue * 255).toInt()
                                )
                            }
                        }

                        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                            val center = androidx.compose.ui.geometry.Offset(size.width / 2, size.height / 2)
                            val radius = size.width / 2

                            // 1. 大秒表（分钟）
                            drawCircle(
                                color = onSurfaceColor.copy(alpha = 0.1f),
                                radius = radius,
                                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 4.dp.toPx())
                            )

                            // 分钟刻度 (60 ticks)
                            for (i in 0 until 60) {
                                val angle = (i * 6 - 90) * (Math.PI / 180f)
                                val innerRadius = if (i % 5 == 0) radius - 12.dp.toPx() else radius - 6.dp.toPx()
                                val strokeWidth = if (i % 5 == 0) 2.dp.toPx() else 1.dp.toPx()
                                
                                val start = androidx.compose.ui.geometry.Offset(
                                    x = center.x + innerRadius * kotlin.math.cos(angle).toFloat(),
                                    y = center.y + innerRadius * kotlin.math.sin(angle).toFloat()
                                )
                                val end = androidx.compose.ui.geometry.Offset(
                                    x = center.x + radius * kotlin.math.cos(angle).toFloat(),
                                    y = center.y + radius * kotlin.math.sin(angle).toFloat()
                                )
                                drawLine(
                                    color = onSurfaceColor.copy(alpha = 0.5f),
                                    start = start,
                                    end = end,
                                    strokeWidth = strokeWidth
                                )

                                // 刻度数字
                                if (i % 5 == 0) {
                                    val text = if (i == 0) "60" else i.toString()
                                    val textRadius = radius - 14.dp.toPx()
                                    val textX = center.x + textRadius * kotlin.math.cos(angle).toFloat()
                                    val textY = center.y + textRadius * kotlin.math.sin(angle).toFloat() - (textPaintLarge.descent() + textPaintLarge.ascent()) / 2
                                    drawContext.canvas.nativeCanvas.drawText(text, textX, textY, textPaintLarge)
                                }
                            }

                            // 分针
                            val minuteAngle = ((minutes + seconds / 60f) * 6 - 90) * (Math.PI / 180f)
                            val minHandLength = radius - 20.dp.toPx()
                            drawLine(
                                color = primaryColor,
                                start = center,
                                end = androidx.compose.ui.geometry.Offset(
                                    x = center.x + minHandLength * kotlin.math.cos(minuteAngle).toFloat(),
                                    y = center.y + minHandLength * kotlin.math.sin(minuteAngle).toFloat()
                                ),
                                strokeWidth = 4.dp.toPx(),
                                cap = androidx.compose.ui.graphics.StrokeCap.Round
                            )

                            // 2. 小秒表（秒钟），放在下方
                            val smallRadius = radius * 0.35f
                            val smallCenter = androidx.compose.ui.geometry.Offset(center.x, center.y + radius * 0.4f)

                            drawCircle(
                                color = onSurfaceColor.copy(alpha = 0.1f),
                                radius = smallRadius,
                                center = smallCenter,
                                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx())
                            )

                            // 秒钟刻度 (60 ticks)
                            for (i in 0 until 60) {
                                val angle = (i * 6 - 90) * (Math.PI / 180f)
                                val isQuarter = i % 15 == 0
                                val isFive = i % 5 == 0
                                val innerRadius = if (isQuarter) smallRadius - 8.dp.toPx() else if (isFive) smallRadius - 6.dp.toPx() else smallRadius - 3.dp.toPx()
                                val strokeWidth = if (isFive) 1.5f.dp.toPx() else 0.5f.dp.toPx()
                                
                                val start = androidx.compose.ui.geometry.Offset(
                                    x = smallCenter.x + innerRadius * kotlin.math.cos(angle).toFloat(),
                                    y = smallCenter.y + innerRadius * kotlin.math.sin(angle).toFloat()
                                )
                                val end = androidx.compose.ui.geometry.Offset(
                                    x = smallCenter.x + smallRadius * kotlin.math.cos(angle).toFloat(),
                                    y = smallCenter.y + smallRadius * kotlin.math.sin(angle).toFloat()
                                )
                                drawLine(
                                    color = onSurfaceColor.copy(alpha = 0.5f),
                                    start = start,
                                    end = end,
                                    strokeWidth = strokeWidth
                                )

                                // 刻度数字
                                if (isQuarter) {
                                    val text = if (i == 0) "60" else i.toString()
                                    val textRadius = smallRadius - 9.dp.toPx()
                                    val textX = smallCenter.x + textRadius * kotlin.math.cos(angle).toFloat()
                                    val textY = smallCenter.y + textRadius * kotlin.math.sin(angle).toFloat() - (textPaintSmall.descent() + textPaintSmall.ascent()) / 2
                                    drawContext.canvas.nativeCanvas.drawText(text, textX, textY, textPaintSmall)
                                }
                            }

                            // 秒针
                            val secondAngle = (seconds * 6 - 90) * (Math.PI / 180f)
                            val secHandLength = smallRadius - 8.dp.toPx()
                            drawLine(
                                color = errorColor,
                                start = smallCenter,
                                end = androidx.compose.ui.geometry.Offset(
                                    x = smallCenter.x + secHandLength * kotlin.math.cos(secondAngle).toFloat(),
                                    y = smallCenter.y + secHandLength * kotlin.math.sin(secondAngle).toFloat()
                                ),
                                strokeWidth = 2.dp.toPx(),
                                cap = androidx.compose.ui.graphics.StrokeCap.Round
                            )
                            
                            // 绘制中心点
                            drawCircle(
                                color = primaryColor,
                                radius = 4.dp.toPx(),
                                center = center
                            )
                            drawCircle(
                                color = errorColor,
                                radius = 2.dp.toPx(),
                                center = smallCenter
                            )
                        }
                    }
                    
                    Text(
                        text = String.format(locale, "%02d:%02d:%02d", hours, minutes, seconds),
                        style = MaterialTheme.typography.headlineMedium,
                        textAlign = TextAlign.Center
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showTodayTimerDialog = false }) {
                    Text("关闭")
                }
            }
        )
    }

    if (showMonthlyStatsDialog) {
        MonthlyStatsDialog(
            yearMonth = yearMonth,
            punches = punches,
            overrides = overrides,
            today = today,
            adjustInfo = adjustInfo,
            onDismissRequest = { showMonthlyStatsDialog = false }
        )
    }

    if (showDeletePunchDialog) {
        val deleteTimeString = remember(deleteTargetTimestamp) {
            val dateTime = LocalDateTime.ofInstant(
                Instant.ofEpochMilli(deleteTargetTimestamp),
                ZoneId.systemDefault()
            )
            val formatter = DateTimeFormatter.ofPattern("HH:mm:ss", locale)
            dateTime.format(formatter)
        }
        AlertDialog(
            onDismissRequest = { showDeletePunchDialog = false },
            title = { Text("删除打卡记录") },
            text = { Text("是否删除这条打卡记录（$deleteTimeString）？") },
            confirmButton = {
                TextButton(onClick = {
                    punches.remove(deleteTargetTimestamp)
                    scope.launch { savePunches(context, punches) }
                    showDeletePunchDialog = false
                }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeletePunchDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    if (showCalendarSelectDialog) {
        val calendarWeeks = buildMonthGrid(yearMonth)
        AlertDialog(
            onDismissRequest = { showCalendarSelectDialog = false },
            title = { Text("选择打卡日期") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        listOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY).forEach { day ->
                            Text(
                                modifier = Modifier.weight(1f),
                                text = day.getDisplayName(TextStyle.SHORT, locale),
                                style = MaterialTheme.typography.bodySmall,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                    calendarWeeks.forEach { week ->
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            week.forEach { date ->
                                val isPast = date.isBefore(today)
                                val isSelected = date in selectedCalendarDates
                                val isWorkday = date.month == yearMonth.month && (overrides[date] ?: isDefaultWorkday(date))
                                val restricted = date.month != yearMonth.month || isPast
                                val bgColor = when {
                                    isSelected -> MaterialTheme.colorScheme.primary
                                    restricted -> MaterialTheme.colorScheme.surfaceVariant
                                    isWorkday -> MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                                    else -> Color.Transparent
                                }
                                val textColor = when {
                                    restricted -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                                    else -> MaterialTheme.colorScheme.onSurface
                                }
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .aspectRatio(1f)
                                        .padding(1.dp)
                                        .background(bgColor, RoundedCornerShape(4.dp))
                                        .then(if (!restricted) {
                                            Modifier.clickable {
                                                if (isSelected) {
                                                    selectedCalendarDates.remove(date)
                                                } else {
                                                    selectedCalendarDates.add(date)
                                                }
                                            }
                                        } else Modifier),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = date.dayOfMonth.toString(),
                                        color = if (isSelected) MaterialTheme.colorScheme.onPrimary else textColor,
                                        style = MaterialTheme.typography.bodySmall,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val days = selectedCalendarDates.size
                    dailyHoursInput = days.toString()
                    if (days > 0) {
                        val remainingH = totalWorkHours - monthHours
                        val raw = (todayHours + kotlin.math.max(0.0, remainingH)) / days
                        val ceiled = kotlin.math.ceil(raw * 10) / 10
                        plannedDailyHoursInput = String.format(locale, "%.1f", ceiled)
                    }
                    scope.launch {
                        saveSelectedCalendarDates(context, selectedCalendarDates.toList())
                    }
                    showCalendarSelectDialog = false
                }) {
                    Text("确定(${selectedCalendarDates.size}天)")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCalendarSelectDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    if (showDailyHoursDialog) {
        val remainingHours = totalWorkHours - monthHours
        val daysUntilEndOfMonth = yearMonth.atEndOfMonth().dayOfMonth - today.dayOfMonth + 1
        val todayIsWorkday = today.month == yearMonth.month && (overrides[today] ?: isDefaultWorkday(today))
        val remainingWorkdays = workdayCount - workdaysUpToToday + if (todayIsWorkday) 1 else 0
        val remainingHoursLabel = remember(locale, remainingHours) { String.format(locale, "%.1f", kotlin.math.max(0.0, remainingHours)) }

        val daysParsed = dailyHoursInput.toDoubleOrNull()
        val daysError = daysParsed == null || daysParsed <= 0 || daysParsed > maxOf(1, daysUntilEndOfMonth)
        val hoursParsed = plannedDailyHoursInput.toDoubleOrNull()
        val hoursError = hoursParsed == null || hoursParsed <= 0 || hoursParsed >= 24
        val isValid = !daysError && !hoursError

        AlertDialog(
            onDismissRequest = { showDailyHoursDialog = false },
            title = { Text("设置每日打卡计划") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "还需打卡${remainingHoursLabel}小时 | 还剩${maxOf(0, remainingWorkdays)}个工作日",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = dailyHoursInput,
                        onValueChange = { input ->
                            dailyHoursInput = input
                            val parsed = input.toDoubleOrNull()
                            if (parsed != null && parsed > 0) {
                                val raw = (todayHours + kotlin.math.max(0.0, remainingHours)) / parsed
                                val ceiled = kotlin.math.ceil(raw * 10) / 10
                                plannedDailyHoursInput = String.format(locale, "%.1f", ceiled)
                            }
                        },
                        isError = daysError,
                        label = { Text("还需打卡(天)") },
                        placeholder = { Text("还需打卡的剩余天数") },
                        trailingIcon = {
                            IconButton(onClick = {
                                if (selectedCalendarDates.isEmpty()) {
                                    val targetDays = dailyHoursInput.toIntOrNull() ?: 0
                                    if (targetDays > 0) {
                                        var remaining = targetDays
                                        var cursor = today
                                        // 优先选工作日（限当月）
                                        while (remaining > 0 && cursor.month == yearMonth.month) {
                                            if (overrides[cursor] ?: isDefaultWorkday(cursor)) {
                                                selectedCalendarDates.add(cursor)
                                                remaining--
                                            }
                                            cursor = cursor.plusDays(1)
                                        }
                                        // 如果不够，补休息日（限当月）
                                        cursor = today
                                        while (remaining > 0 && cursor.month == yearMonth.month) {
                                            if (cursor !in selectedCalendarDates) {
                                                selectedCalendarDates.add(cursor)
                                                remaining--
                                            }
                                            cursor = cursor.plusDays(1)
                                        }
                                    }
                                }
                                showCalendarSelectDialog = true
                            }) {
                                Icon(Icons.Filled.DateRange, contentDescription = "选择日期")
                            }
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )
                    OutlinedTextField(
                        value = plannedDailyHoursInput,
                        onValueChange = { input ->
                            plannedDailyHoursInput = input
                            val parsed = input.toDoubleOrNull()
                            if (parsed != null && parsed > 0) {
                                val totalNeeded = todayHours + kotlin.math.max(0.0, remainingHours)
                                val days = kotlin.math.ceil(totalNeeded / parsed).toInt()
                                dailyHoursInput = days.toString()
                            }
                        },
                        isError = hoursError,
                        label = { Text("计划每日工时(小时)") },
                        placeholder = { Text("用于计算建议下班时间") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val plannedValue = plannedDailyHoursInput.toDoubleOrNull()
                        if (plannedValue == null || plannedValue <= 0 || plannedValue >= 24) {
                            return@TextButton
                        }
                        if (plannedValue != plannedDailyHours) {
                            plannedDailyHours = plannedValue
                            savePlannedDailyHours(context, plannedValue)
                            if (plannedValue != DEFAULT_PLANNED_DAILY_HOURS) {
                                plannedSetDate = today
                                savePlannedSetDate(context, today)
                            }
                        }
                        showDailyHoursDialog = false
                    },
                    enabled = isValid
                ) {
                    Text("确定")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDailyHoursDialog = false }) {
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
    targetDateLabel: String,
    targetDateHours: Double,
    workdaysUpToToday: Int,
    dailyHours: Double,
    modifier: Modifier = Modifier,
    isTimerClickable: Boolean = false,
    onClick: () -> Unit = {},
    onMonthlyStatsClick: () -> Unit = {}
) {
    val locale = LocalContext.current.resources.configuration.locales[0] ?: Locale.getDefault()
    val totalHoursLabel = remember(locale, totalHours) { String.format(locale, "%.1f", totalHours) }
    val monthlyHoursLabel = remember(locale, monthlyHours) { String.format(locale, "%.1f", monthlyHours) }
    val remaining = totalHours - monthlyHours
    val isOvertime = remaining <= 0
    val displayHours = if (isOvertime) -remaining else remaining
    val displayHoursLabel = remember(locale, displayHours) {
        String.format(locale, "%.1f", displayHours)
    }
    val targetDateHoursLabel = remember(locale, targetDateHours) { String.format(locale, "%.1f", targetDateHours) }
    val dailyHoursLabel = remember(locale, dailyHours) { String.format(locale, "%.1f", dailyHours) }
    
    // 计算截止今天的理论工时
    val theoreticalHours = workdaysUpToToday * dailyHours
    val theoreticalHoursLabel = remember(locale, theoreticalHours) { String.format(locale, "%.1f", theoreticalHours) }
    val difference = theoreticalHours - monthlyHours
    val differenceLabel = remember(locale, difference) { String.format(locale, "%.1f", kotlin.math.abs(difference)) }
    val isShortage = difference > 0 // 少打了
    
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
            
            // 新格式：本月已打卡：X +/- Y = XX小时
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable { onMonthlyStatsClick() }
            ) {
                Text(
                    text = "本月已打卡：${workdaysUpToToday} X ${dailyHoursLabel} ",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = if (isShortage) "- " else "+ ",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = differenceLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isShortage) MaterialTheme.colorScheme.error else Color(0xFF4CAF50)
                )
                Text(
                    text = " = ${monthlyHoursLabel}小时",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            Text(
                text = if (isOvertime) "本月已多打：${displayHoursLabel}小时" else "还需打卡：${displayHoursLabel}小时",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "${targetDateLabel}：${targetDateHoursLabel}小时",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = if (isTimerClickable) Modifier.clickable { onClick() } else Modifier
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
    modifier: Modifier = Modifier,
    isSelected: Boolean = false
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
        tonalElevation = if (isToday) 4.dp else 0.dp,
        border = if (isSelected && !isToday) androidx.compose.foundation.BorderStroke(3.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)) else null,
        shape = RoundedCornerShape(4.dp)
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

private fun countWorkdaysUpToDate(month: YearMonth, today: LocalDate, overrides: Map<LocalDate, Boolean>): Int {
    val start = month.atDay(1)
    val end = if (today.month == month.month && today.year == month.year) {
        today
    } else {
        month.atEndOfMonth()
    }
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

private fun purgePreviousMonthData(
    today: LocalDate,
    punches: SnapshotStateList<Long>,
    adjustInfo: () -> AdjustInfo?,
    setAdjustInfo: (AdjustInfo?) -> Unit,
    context: Context
) {
    if (today.dayOfMonth != 1) return
    val previousMonth = YearMonth.from(today.minusMonths(1))
    val hasPreviousMonthPunch = punches.any { ts ->
        YearMonth.from(timestampToLocalDate(ts)) == previousMonth
    }
    val hasPreviousAdjust = adjustInfo()?.month == previousMonth
    if (!hasPreviousMonthPunch && !hasPreviousAdjust) return
    val removedPunches = punches.removeAll { ts ->
        YearMonth.from(timestampToLocalDate(ts)) == previousMonth
    }
    val clearedAdjust = if (hasPreviousAdjust) {
        setAdjustInfo(null)
        true
    } else {
        false
    }
    if (removedPunches) {
        savePunches(context, punches)
    }
    if (clearedAdjust) {
        saveAdjustInfo(context, null)
    }
}

fun addPunchTimestamp(context: Context, timestamp: Long = System.currentTimeMillis()) {
    val updated = loadPunches(context).toMutableList().apply {
        add(0, timestamp)
    }
    savePunches(context, updated)
}

class PunchWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        appWidgetIds.forEach { id ->
            appWidgetManager.updateAppWidget(id, createRemoteViews(context))
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_PUNCH) {
            val punches = loadPunches(context)
            val lastPunch = punches.firstOrNull() ?: 0L
            val now = System.currentTimeMillis()
            if (now - lastPunch < 10_000L) {
                val remain = 10 - (now - lastPunch) / 1000
                Toast.makeText(context, "请等待 ${remain} 秒后再打卡", Toast.LENGTH_SHORT).show()
                return
            }

            addPunchTimestamp(context)
            Toast.makeText(context, "打卡成功", Toast.LENGTH_SHORT).show()
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, PunchWidgetProvider::class.java)
            val ids = appWidgetManager.getAppWidgetIds(component)
            onUpdate(context, appWidgetManager, ids)
        }
    }

    private fun createRemoteViews(context: Context): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_punch)
        val isClockInNext = loadPunches(context).size % 2 == 0
        if (isClockInNext) {
            views.setTextViewText(R.id.widgetButton, "上班打卡")
            views.setInt(R.id.widgetButton, "setBackgroundResource", R.drawable.bg_widget_clock_in)
        } else {
            views.setTextViewText(R.id.widgetButton, "下班打卡")
            views.setInt(R.id.widgetButton, "setBackgroundResource", R.drawable.bg_widget_clock_out)
        }
        val intent = Intent(context, PunchWidgetProvider::class.java).apply {
            action = ACTION_PUNCH
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        views.setOnClickPendingIntent(R.id.widgetButton, pendingIntent)

        val appIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val appPendingIntent = PendingIntent.getActivity(
            context,
            1, // Request code 1 for launching app to differentiate from broadcast
            appIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        views.setOnClickPendingIntent(R.id.widgetIcon, appPendingIntent)

        return views
    }

    companion object {
        const val ACTION_PUNCH = "com.example.dingding.action.PUNCH"
    }
}

private const val PREF_NAME = "dingding_calendar"
private const val PREF_OVERRIDES = "overrides"
private const val PREF_PUNCHES = "punches"
private const val PREF_DAILY_HOURS = "daily_hours"
private const val PREF_PLANNED_DAILY_HOURS = "planned_daily_hours"
private const val PREF_PLANNED_SET_DATE = "planned_set_date"
private const val PREF_SELECTED_CALENDAR_DATES = "selected_calendar_dates"

/** 默认每日标准工时（小时） */
const val DEFAULT_DAILY_HOURS = 7.5

/** 默认计划每日工时（小时）——用于计算建议下班时间 */
const val DEFAULT_PLANNED_DAILY_HOURS = 7.5

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

    // 发送广播触发桌面 Widget 更新状态
    val appWidgetManager = android.appwidget.AppWidgetManager.getInstance(context)
    val component = android.content.ComponentName(context, PunchWidgetProvider::class.java)
    val ids = appWidgetManager.getAppWidgetIds(component)
    val updateIntent = android.content.Intent(context, PunchWidgetProvider::class.java).apply {
        action = android.appwidget.AppWidgetManager.ACTION_APPWIDGET_UPDATE
        putExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
    }
    context.sendBroadcast(updateIntent)
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

private fun loadDailyHours(context: Context): Double {
    val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    return prefs.getFloat(PREF_DAILY_HOURS, DEFAULT_DAILY_HOURS.toFloat()).toDouble()
}

private fun saveDailyHours(context: Context, hours: Double) {
    context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        .edit()
        .putFloat(PREF_DAILY_HOURS, hours.toFloat())
        .apply()
}

private fun loadPlannedDailyHours(context: Context): Double {
    val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    return prefs.getFloat(PREF_PLANNED_DAILY_HOURS, DEFAULT_PLANNED_DAILY_HOURS.toFloat()).toDouble()
}

private fun savePlannedDailyHours(context: Context, hours: Double) {
    context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        .edit()
        .putFloat(PREF_PLANNED_DAILY_HOURS, hours.toFloat())
        .apply()
}

private fun loadPlannedSetDate(context: Context): LocalDate? {
    val raw = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        .getString(PREF_PLANNED_SET_DATE, null) ?: return null
    return runCatching { LocalDate.parse(raw) }.getOrNull()
}

private fun savePlannedSetDate(context: Context, date: LocalDate) {
    context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        .edit()
        .putString(PREF_PLANNED_SET_DATE, date.toString())
        .apply()
}

private fun clearPlannedSetDate(context: Context) {
    context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        .edit()
        .remove(PREF_PLANNED_SET_DATE)
        .apply()
}


private fun loadSelectedCalendarDates(context: Context): List<LocalDate> {
    val raw = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        .getString(PREF_SELECTED_CALENDAR_DATES, "") ?: ""
    if (raw.isEmpty()) return emptyList()
    return raw.split(",").mapNotNull {
        runCatching { LocalDate.parse(it) }.getOrNull()
    }
}

private fun saveSelectedCalendarDates(context: Context, dates: List<LocalDate>) {
    val serialized = dates.joinToString(",") { it.toString() }
    context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        .edit()
        .putString(PREF_SELECTED_CALENDAR_DATES, serialized)
        .apply()
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

@Composable
private fun MonthlyStatsDialog(
    yearMonth: YearMonth,
    punches: List<Long>,
    overrides: Map<LocalDate, Boolean>,
    today: LocalDate,
    adjustInfo: AdjustInfo?,
    onDismissRequest: () -> Unit
) {
    val locale = LocalContext.current.resources.configuration.locales[0] ?: Locale.getDefault()
    
    val stats = remember(yearMonth, punches, overrides, today, adjustInfo) {
        val adjustedEndDate = if (adjustInfo != null && adjustInfo.month == yearMonth) {
            timestampToLocalDate(adjustInfo.endMillis)
        } else null

        val daysInMonth = (1..yearMonth.lengthOfMonth()).map { yearMonth.atDay(it) }
        daysInMonth.filter { date ->
            val isAdjusted = adjustedEndDate != null && (date < adjustedEndDate || date == adjustedEndDate)
            date < today && !isAdjusted && (overrides[date] ?: isDefaultWorkday(date))
        }.map { date ->
            val dailyPunches = punches.filter { timestampToLocalDate(it) == date }.sorted()
            var dailyTotalMillis = 0L
            for (i in 0 until dailyPunches.size step 2) {
                val start = dailyPunches[i]
                val end = dailyPunches.getOrNull(i + 1) ?: start
                if (end >= start) {
                    dailyTotalMillis += (end - start)
                }
            }
            date to dailyTotalMillis / (1000.0 * 3600.0)
        }.reversed()
    }

    val maxHours = remember(stats) {
        val maxInStats = stats.maxOfOrNull { it.second } ?: 0.0
        maxOf(8.0, maxInStats)
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { 
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("本月打卡统计", style = MaterialTheme.typography.titleMedium) 
            }
        },
        text = {
            Box(modifier = Modifier.heightIn(max = 400.dp)) {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(stats) { (date, hours) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "${date.monthValue}/${date.dayOfMonth}",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.width(45.dp)
                            )
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 8.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(16.dp)
                                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))
                                )
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth((hours / maxHours).toFloat().coerceIn(0f, 1f))
                                        .height(16.dp)
                                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp))
                                )
                            }
                            Text(
                                text = String.format(locale, "%.1fh", hours),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.width(45.dp),
                                textAlign = TextAlign.End
                            )
                        }
                    }
                    
                    if (adjustInfo != null && adjustInfo.month == yearMonth) {
                        item {
                            val adjustedEndDate = timestampToLocalDate(adjustInfo.endMillis)
                            val startLabel = "${yearMonth.monthValue}/1"
                            val endLabel = "${adjustedEndDate.monthValue}/${adjustedEndDate.dayOfMonth}"
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 16.dp)
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                                    .padding(12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "基准修正：${startLabel} - ${endLabel}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "共计打卡：${String.format(locale, "%.1f", adjustInfo.hours)}h",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                TextButton(onClick = onDismissRequest) {
                    Text("关闭")
                }
            }
        }
    )
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

/**
 * 验证打卡时间是否合理
 * @param targetDate 目标日期
 * @param editingIndex 正在编辑的打卡记录索引
 * @param newTimestamp 新的时间戳
 * @param allPunches 当天的所有打卡记录
 * @param context 上下文
 * @return 错误信息，如果时间合理则返回null
 */
private fun validatePunchTime(
    targetDate: LocalDate,
    editingIndex: Int,
    newTimestamp: Long,
    allPunches: List<Long>,
    context: Context
): String? {
    // 1. 验证时间不能是未来时间
    val now = System.currentTimeMillis()
    if (newTimestamp > now) {
        return "不能选择未来时间"
    }

    // 2. 创建临时列表来验证时间顺序
    val tempList = allPunches.toMutableList()
    tempList[editingIndex] = newTimestamp
    val sorted = tempList.sorted()

    // 3. 验证是否改变了原有的打卡先后顺序（防止时间错位导致身份互换）
    if (sorted.indexOf(newTimestamp) != editingIndex) {
        return if (editingIndex % 2 == 0) {
            "修改后的上班时间不能晚于原本关联的下班时间"
        } else {
            "修改后的下班时间不能早于原本关联的上班时间"
        }
    }

    // 4. 验证相邻打卡时间不能太近（至少间隔1分钟）
    for (i in 0 until sorted.size - 1) {
        val diff = sorted[i + 1] - sorted[i]
        if (diff < 60_000) { // 少于1分钟
            return "打卡时间间隔不能少于1分钟"
        }
    }

    // 5. 验证单次工时不能超过12小时
    for (i in sorted.indices step 2) {
        if (i + 1 < sorted.size) {
            val workMillis = sorted[i + 1] - sorted[i]
            if (workMillis > 12 * 3_600_000L) {
                return "单次工时不能超过12小时"
            }
        }
    }

    // 6. 验证每日总工时不能超过24小时
    var totalMillis = 0L
    for (i in sorted.indices step 2) {
        if (i + 1 < sorted.size) {
            totalMillis += (sorted[i + 1] - sorted[i])
        }
    }
    if (totalMillis > 24 * 3_600_000L) {
        return "每日总工时不能超过24小时"
    }

    return null // 时间合理，返回null
}

private data class AdjustInfo(
    val month: YearMonth,
    val hours: Double,
    val endMillis: Long
)

private const val PREF_ADJUST_MONTH = "adjust_month"
private const val PREF_ADJUST_HOURS = "adjust_hours"
private const val PREF_ADJUST_END = "adjust_end"
