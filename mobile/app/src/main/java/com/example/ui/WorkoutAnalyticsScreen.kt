package com.example.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.InterFontFamily
import com.example.ui.theme.OswaldFontFamily
import com.example.viewmodel.TrainerViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkoutAnalyticsScreen(
    viewModel: TrainerViewModel?,
    isDarkTheme: Boolean,
    currentStreak: Int,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Styling colors
    val backgroundColor = if (isDarkTheme) Color(0xFF121212) else Color(0xFFF8F9FA)
    val cardBgColor = if (isDarkTheme) Color(0xFF1E1E1E) else Color(0xFFFFFFFF)
    val cardBorderColor = if (isDarkTheme) Color(0xFF2D2D2D) else Color(0xFFE9ECEF)
    val onSurfaceColor = if (isDarkTheme) Color(0xFFFFFFFF) else Color(0xFF212529)
    val onSurfaceSecondaryColor = if (isDarkTheme) Color(0xFFA0A0A0) else Color(0xFF6C757D)
    val accentBlue = Color(0xFF2979FF)
    val accentOrange = Color(0xFFFF9100)
    val context = LocalContext.current

    // Local toggles for chart intervals
    var liftingPeriodMonth by remember { mutableStateOf(true) } // true = Month, false = Week
    var activityPeriodMonth by remember { mutableStateOf(false) } // true = Month, false = Week
    var showResetConfirmDialog by remember { mutableStateOf(false) }

    val workoutLogs by viewModel?.workoutLogs?.collectAsState(initial = emptyList()) ?: remember { mutableStateOf(emptyList()) }
    val currentYear = remember { java.util.Calendar.getInstance().get(java.util.Calendar.YEAR) }

    // Calendar instance for dates
    val calendar = remember { java.util.Calendar.getInstance() }

    // Helper functions
    fun getDayOfWeekIndex(timestamp: Long): Int {
        calendar.timeInMillis = timestamp
        return when (calendar.get(java.util.Calendar.DAY_OF_WEEK)) {
            java.util.Calendar.MONDAY -> 0
            java.util.Calendar.TUESDAY -> 1
            java.util.Calendar.WEDNESDAY -> 2
            java.util.Calendar.THURSDAY -> 3
            java.util.Calendar.FRIDAY -> 4
            java.util.Calendar.SATURDAY -> 5
            java.util.Calendar.SUNDAY -> 6
            else -> 0
        }
    }

    fun getMonthIndex(timestamp: Long): Int {
        calendar.timeInMillis = timestamp
        return calendar.get(java.util.Calendar.MONTH)
    }

    fun isInCurrentPeriod(timestamp: Long, monthView: Boolean): Boolean {
        val now = java.util.Calendar.getInstance()
        val logCalendar = java.util.Calendar.getInstance().apply { timeInMillis = timestamp }
        if (monthView) {
            return logCalendar.get(java.util.Calendar.YEAR) == now.get(java.util.Calendar.YEAR)
        }

        val startOfWeek = java.util.Calendar.getInstance().apply {
            firstDayOfWeek = java.util.Calendar.MONDAY
            set(java.util.Calendar.DAY_OF_WEEK, java.util.Calendar.MONDAY)
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        val endOfWeek = (startOfWeek.clone() as java.util.Calendar).apply {
            add(java.util.Calendar.DAY_OF_YEAR, 7)
        }
        return timestamp >= startOfWeek.timeInMillis && timestamp < endOfWeek.timeInMillis
    }

    // --- CALENDAR CONSISTENCY & STREAK STATE ---
    var currentMonthCalendar by remember {
        mutableStateOf(java.util.Calendar.getInstance())
    }

    val daysInMonth = remember(currentMonthCalendar) {
        val tempCal = java.util.Calendar.getInstance().apply {
            timeInMillis = currentMonthCalendar.timeInMillis
        }
        tempCal.getActualMaximum(java.util.Calendar.DAY_OF_MONTH)
    }

    val firstDayOfWeek = remember(currentMonthCalendar) {
        val tempCal = java.util.Calendar.getInstance().apply {
            timeInMillis = currentMonthCalendar.timeInMillis
            set(java.util.Calendar.DAY_OF_MONTH, 1)
        }
        tempCal.get(java.util.Calendar.DAY_OF_WEEK)
    }

    val firstDayOffset = remember(firstDayOfWeek) {
        when (firstDayOfWeek) {
            java.util.Calendar.MONDAY -> 0
            java.util.Calendar.TUESDAY -> 1
            java.util.Calendar.WEDNESDAY -> 2
            java.util.Calendar.THURSDAY -> 3
            java.util.Calendar.FRIDAY -> 4
            java.util.Calendar.SATURDAY -> 5
            java.util.Calendar.SUNDAY -> 6
            else -> 0
        }
    }

    val workoutDaysThisMonth = remember(workoutLogs, currentMonthCalendar) {
        val tempCal = java.util.Calendar.getInstance()
        val displayYear = currentMonthCalendar.get(java.util.Calendar.YEAR)
        val displayMonth = currentMonthCalendar.get(java.util.Calendar.MONTH)
        
        val daysWithWorkout = mutableSetOf<Int>()
        
        workoutLogs.forEach { log ->
            tempCal.timeInMillis = log.timestamp
            val logYear = tempCal.get(java.util.Calendar.YEAR)
            val logMonth = tempCal.get(java.util.Calendar.MONTH)
            val logDay = tempCal.get(java.util.Calendar.DAY_OF_MONTH)

            if (logYear == displayYear && logMonth == displayMonth) {
                daysWithWorkout.add(logDay)
            }
        }
        daysWithWorkout
    }

    // LIFTING DATA
    val liftingPeriodLogs = remember(workoutLogs, liftingPeriodMonth) {
        workoutLogs.filter { isInCurrentPeriod(it.timestamp, liftingPeriodMonth) }
    }
    val hasLiftingData = liftingPeriodLogs.any { it.completedSets > 0 || it.liftingVolumeKg > 0.0 }
    val liftingStats = remember(liftingPeriodLogs, liftingPeriodMonth) {
            val size = if (liftingPeriodMonth) 12 else 7
            val volumeArray = FloatArray(size)
            val setsArray = FloatArray(size)
            var totalVolume = 0.0
            var totalSets = 0

            liftingPeriodLogs.forEach { log ->
                val sets = log.completedSets
                val volume = log.liftingVolumeKg
                val idx = if (liftingPeriodMonth) getMonthIndex(log.timestamp) else getDayOfWeekIndex(log.timestamp)
                if (idx in 0 until size) {
                    volumeArray[idx] += volume.toFloat()
                    setsArray[idx] += sets.toFloat()
                    totalVolume += volume
                    totalSets += sets
                }
            }

            val maxVolume = volumeArray.maxOrNull() ?: 0f
            val maxSets = setsArray.maxOrNull() ?: 0f

            val chartD = List(size) { idx ->
                val wNorm = if (maxVolume > 0f) (volumeArray[idx] / maxVolume) * 0.95f else 0f
                val sNorm = if (maxSets > 0f) (setsArray[idx] / maxSets) * 0.85f else 0f
                wNorm to sNorm
            }

            val formattedVolume = java.text.DecimalFormat("#,##0.#").format(totalVolume)
            Triple("$formattedVolume kg", totalSets.toString(), chartD)
    }

    // ACTIVITY DATA
    val activityPeriodLogs = remember(workoutLogs, activityPeriodMonth) {
        workoutLogs.filter { isInCurrentPeriod(it.timestamp, activityPeriodMonth) }
    }
    val hasActivityData = activityPeriodLogs.any { it.recordedDurationSeconds() > 0 }
    val activityStats = remember(activityPeriodLogs, activityPeriodMonth) {
            val size = if (activityPeriodMonth) 12 else 7
            val durationArray = FloatArray(size)
            val workoutsArray = FloatArray(size)
            var totalDurationSeconds = 0
            var totalWorkouts = 0

            activityPeriodLogs.forEach { log ->
                val durationSeconds = log.recordedDurationSeconds()
                val idx = if (activityPeriodMonth) getMonthIndex(log.timestamp) else getDayOfWeekIndex(log.timestamp)
                if (idx in 0 until size) {
                    durationArray[idx] += durationSeconds.toFloat()
                    workoutsArray[idx] += 1f
                    totalDurationSeconds += durationSeconds
                    totalWorkouts += 1
                }
            }

            val maxDuration = durationArray.maxOrNull() ?: 0f
            val maxWorkouts = workoutsArray.maxOrNull() ?: 0f

            val chartD = List(size) { idx ->
                val dNorm = if (maxDuration > 0f) (durationArray[idx] / maxDuration) * 0.95f else 0f
                val wNorm = if (maxWorkouts > 0f) (workoutsArray[idx] / maxWorkouts) * 0.85f else 0f
                dNorm to wNorm
            }

            val hours = totalDurationSeconds / 3600
            val minutes = (totalDurationSeconds % 3600) / 60
            val seconds = totalDurationSeconds % 60
            val formattedDuration = if (hours > 0) {
                "${hours}h : ${minutes}m : ${seconds}s"
            } else {
                "${minutes}m : ${seconds}s"
            }

            Triple(formattedDuration, totalWorkouts.toString(), chartD)
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundColor),
        containerColor = backgroundColor,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "ANALYTICS",
                        fontFamily = OswaldFontFamily,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 20.sp,
                        color = onSurfaceColor,
                        letterSpacing = 1.2.sp
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("analytics_back_btn")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = onSurfaceColor
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { showResetConfirmDialog = true },
                        modifier = Modifier.testTag("analytics_reset_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Reset Analytics",
                            tint = onSurfaceColor
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = backgroundColor
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(backgroundColor)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Screen Header Section
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Detailed Progress Analytics",
                    fontFamily = OswaldFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 24.sp,
                    color = onSurfaceColor
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "View detailed analytics of your workout progress",
                    fontFamily = InterFontFamily,
                    fontSize = 14.sp,
                    color = onSurfaceSecondaryColor
                )
            }

            // 0. WORKOUT CONSISTENCY CALENDAR CARD
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, cardBorderColor, RoundedCornerShape(16.dp))
                    .testTag("workout_consistency_calendar_card"),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = cardBgColor)
            ) {
                Column(
                    modifier = Modifier.padding(18.dp)
                ) {
                    // Header with Title and Month Controls
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Consistency Tracker",
                                fontFamily = OswaldFontFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp,
                                color = accentOrange
                            )
                            Text(
                                text = "Marking active workout days",
                                fontFamily = InterFontFamily,
                                fontSize = 11.sp,
                                color = onSurfaceSecondaryColor
                            )
                        }

                        // Month Navigation Control
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            IconButton(
                                onClick = {
                                    val newCal = java.util.Calendar.getInstance().apply {
                                        timeInMillis = currentMonthCalendar.timeInMillis
                                        add(java.util.Calendar.MONTH, -1)
                                    }
                                    currentMonthCalendar = newCal
                                },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ChevronLeft,
                                    contentDescription = "Previous Month",
                                    tint = onSurfaceColor,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            val monthNames = listOf(
                                "JAN", "FEB", "MAR", "APR", "MAY", "JUN",
                                "JUL", "AUG", "SEP", "OCT", "NOV", "DEC"
                            )
                            val monthName = monthNames[currentMonthCalendar.get(java.util.Calendar.MONTH)]
                            val yearName = currentMonthCalendar.get(java.util.Calendar.YEAR).toString()

                            Text(
                                text = "$monthName $yearName",
                                fontFamily = OswaldFontFamily,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 14.sp,
                                color = onSurfaceColor,
                                modifier = Modifier.widthIn(min = 64.dp),
                                textAlign = TextAlign.Center
                            )

                            IconButton(
                                onClick = {
                                    val newCal = java.util.Calendar.getInstance().apply {
                                        timeInMillis = currentMonthCalendar.timeInMillis
                                        add(java.util.Calendar.MONTH, 1)
                                    }
                                    currentMonthCalendar = newCal
                                },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ChevronRight,
                                    contentDescription = "Next Month",
                                    tint = onSurfaceColor,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    if (workoutLogs.isEmpty()) {
                        Text(
                            text = "No workouts logged yet",
                            fontFamily = InterFontFamily,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = onSurfaceSecondaryColor,
                            modifier = Modifier.testTag("analytics_calendar_empty_state")
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    // Calendar Grid Layout
                    val totalSlots = firstDayOffset + daysInMonth
                    val rowsCount = (totalSlots + 6) / 7
                    val todayCalendar = java.util.Calendar.getInstance()
                    val todayDate = WorkoutCalendarDate(
                        year = todayCalendar.get(java.util.Calendar.YEAR),
                        month = todayCalendar.get(java.util.Calendar.MONTH) + 1,
                        day = todayCalendar.get(java.util.Calendar.DAY_OF_MONTH),
                    )

                    Column(modifier = Modifier.fillMaxWidth()) {
                        // Weekday headers
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            val weekdays = listOf("M", "T", "W", "T", "F", "S", "S")
                            weekdays.forEach { day ->
                                Text(
                                    text = day,
                                    modifier = Modifier.weight(1f),
                                    textAlign = TextAlign.Center,
                                    fontFamily = OswaldFontFamily,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    color = onSurfaceSecondaryColor
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Calendar days grid rows
                        for (r in 0 until rowsCount) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                for (c in 0 until 7) {
                                    val slotIndex = r * 7 + c
                                    if (slotIndex >= firstDayOffset && slotIndex < totalSlots) {
                                        val dayNum = slotIndex - firstDayOffset + 1

                                        val hasWorkout = workoutDaysThisMonth.contains(dayNum)
                                        val displayDate = WorkoutCalendarDate(
                                            year = currentMonthCalendar.get(java.util.Calendar.YEAR),
                                            month = currentMonthCalendar.get(java.util.Calendar.MONTH) + 1,
                                            day = dayNum,
                                        )
                                        val isToday = displayDate == todayDate

                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .aspectRatio(1f)
                                                .padding(2.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(
                                                    when {
                                                        hasWorkout -> accentOrange.copy(alpha = 0.2f)
                                                        isToday -> onSurfaceColor.copy(alpha = 0.08f)
                                                        else -> Color.Transparent
                                                    }
                                                )
                                                .border(
                                                    width = if (hasWorkout) 1.5.dp else if (isToday) 1.dp else 0.dp,
                                                    color = when {
                                                        hasWorkout -> accentOrange
                                                        isToday -> onSurfaceColor.copy(alpha = 0.4f)
                                                        else -> Color.Transparent
                                                    },
                                                    shape = RoundedCornerShape(8.dp)
                                                )
                                                .clickable {
                                                    val displayMonthName = listOf(
                                                        "January", "February", "March", "April", "May", "June",
                                                        "July", "August", "September", "October", "November", "December"
                                                    )[currentMonthCalendar.get(java.util.Calendar.MONTH)]
                                                    val classification = classifyWorkoutCalendarDay(
                                                        date = displayDate,
                                                        today = todayDate,
                                                        hasWorkoutLog = hasWorkout,
                                                    )
                                                    android.widget.Toast.makeText(
                                                        context,
                                                        workoutCalendarDayMessage(classification, displayMonthName, dayNum),
                                                        android.widget.Toast.LENGTH_SHORT,
                                                    ).show()
                                                },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Column(
                                                horizontalAlignment = Alignment.CenterHorizontally,
                                                verticalArrangement = Arrangement.Center
                                            ) {
                                                Text(
                                                    text = dayNum.toString(),
                                                    fontFamily = InterFontFamily,
                                                    fontWeight = if (hasWorkout || isToday) FontWeight.Bold else FontWeight.Normal,
                                                    fontSize = 13.sp,
                                                    color = if (hasWorkout) accentOrange else onSurfaceColor
                                                )
                                                if (hasWorkout) {
                                                    Box(
                                                        modifier = Modifier
                                                            .padding(top = 2.dp)
                                                            .size(4.dp)
                                                            .clip(RoundedCornerShape(100))
                                                            .background(accentOrange)
                                                    )
                                                }
                                            }
                                        }
                                    } else {
                                        Spacer(modifier = Modifier.weight(1f).aspectRatio(1f).padding(2.dp))
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    // Key Stats Panel
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // 1. Active Days Box
                        Card(
                            modifier = Modifier
                                .weight(1f)
                                .border(1.dp, cardBorderColor.copy(alpha = 0.5f), RoundedCornerShape(10.dp)),
                            shape = RoundedCornerShape(10.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isDarkTheme) Color(0xFF262626) else Color(0xFFF1F3F5)
                            )
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "Active Days",
                                    fontFamily = InterFontFamily,
                                    fontSize = 11.sp,
                                    color = onSurfaceSecondaryColor,
                                    fontWeight = FontWeight.Medium
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "${workoutDaysThisMonth.size} Days",
                                    fontFamily = OswaldFontFamily,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp,
                                    color = onSurfaceColor
                                )
                            }
                        }

                        // 2. Consistency Box
                        Card(
                            modifier = Modifier
                                .weight(1f)
                                .border(1.dp, cardBorderColor.copy(alpha = 0.5f), RoundedCornerShape(10.dp)),
                            shape = RoundedCornerShape(10.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isDarkTheme) Color(0xFF262626) else Color(0xFFF1F3F5)
                            )
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "Consistency",
                                    fontFamily = InterFontFamily,
                                    fontSize = 11.sp,
                                    color = onSurfaceSecondaryColor,
                                    fontWeight = FontWeight.Medium
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                val percentage = if (daysInMonth > 0) {
                                    (workoutDaysThisMonth.size.toFloat() / daysInMonth * 100).toInt()
                                } else 0
                                Text(
                                    text = "$percentage%",
                                    fontFamily = OswaldFontFamily,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp,
                                    color = if (percentage > 50) Color(0xFF4CAF50) else onSurfaceColor
                                )
                            }
                        }

                        // 3. Current Streak Box
                        Card(
                            modifier = Modifier
                                .weight(1f)
                                .border(1.dp, cardBorderColor.copy(alpha = 0.5f), RoundedCornerShape(10.dp)),
                            shape = RoundedCornerShape(10.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isDarkTheme) Color(0xFF262626) else Color(0xFFF1F3F5)
                            )
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "Streak",
                                    fontFamily = InterFontFamily,
                                    fontSize = 11.sp,
                                    color = onSurfaceSecondaryColor,
                                    fontWeight = FontWeight.Medium
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "$currentStreak Days",
                                        fontFamily = OswaldFontFamily,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 16.sp,
                                        color = accentOrange
                                    )
                                    Spacer(modifier = Modifier.width(2.dp))
                                    Text(
                                        text = "🔥",
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 1. LIFTING VOLUME CARD
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, cardBorderColor, RoundedCornerShape(16.dp))
                    .testTag("lifting_volume_card"),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = cardBgColor)
            ) {
                Column(
                    modifier = Modifier.padding(18.dp)
                ) {
                    // Title and Chevron Right
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Lifting Volume",
                                fontFamily = OswaldFontFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp,
                                color = accentBlue
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.Default.ChevronRight,
                                contentDescription = null,
                                tint = accentBlue,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        // Week/Month segmented switcher
                        PeriodToggle(
                            isMonthSelected = liftingPeriodMonth,
                            onToggle = { liftingPeriodMonth = it },
                            isDarkTheme = isDarkTheme
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    if (!hasLiftingData) {
                        Text(
                            text = "Complete a workout to see analytics",
                            fontFamily = InterFontFamily,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = onSurfaceSecondaryColor,
                            modifier = Modifier.testTag("lifting_analytics_empty_state")
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    // Statistical Readout
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text = "Training Volume (kg)",
                                fontFamily = InterFontFamily,
                                fontSize = 12.sp,
                                color = onSurfaceSecondaryColor
                            )
                            Text(
                                text = liftingStats.first,
                                fontFamily = OswaldFontFamily,
                                fontWeight = FontWeight.Black,
                                fontSize = 28.sp,
                                color = onSurfaceColor
                            )
                            Text(
                                text = if (liftingPeriodMonth) "Jan 1 - Dec 31, $currentYear" else "This Week",
                                fontFamily = InterFontFamily,
                                fontSize = 11.sp,
                                color = onSurfaceSecondaryColor
                            )
                        }

                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = "Total Sets",
                                fontFamily = InterFontFamily,
                                fontSize = 12.sp,
                                color = onSurfaceSecondaryColor
                            )
                            Text(
                                text = liftingStats.second,
                                fontFamily = OswaldFontFamily,
                                fontWeight = FontWeight.Black,
                                fontSize = 28.sp,
                                color = onSurfaceColor
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Legend indicators
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        LegendItem(color = accentOrange, label = "Volume (kg)")
                        LegendItem(color = accentBlue, label = "Sets")
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // Custom-rendered interactive chart
                    val animateChartProgress by animateFloatAsState(
                        targetValue = 1f,
                        animationSpec = tween(durationMillis = 800),
                        label = "lifting_chart_anim"
                    )

                    LiftingVolumeChart(
                        chartData = liftingStats.third,
                        isMonth = liftingPeriodMonth,
                        progress = animateChartProgress,
                        accentOrange = accentOrange,
                        accentBlue = accentBlue,
                        gridColor = if (isDarkTheme) Color(0xFF333333) else Color(0xFFE0E0E0),
                        labelColor = onSurfaceSecondaryColor
                    )
                }
            }

            // 2. ACTIVITY CARD
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, cardBorderColor, RoundedCornerShape(16.dp))
                    .testTag("activity_analytics_card"),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = cardBgColor)
            ) {
                Column(
                    modifier = Modifier.padding(18.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Activity",
                            fontFamily = OswaldFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = accentBlue
                        )

                        PeriodToggle(
                            isMonthSelected = activityPeriodMonth,
                            onToggle = { activityPeriodMonth = it },
                            isDarkTheme = isDarkTheme
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    if (!hasActivityData) {
                        Text(
                            text = "No activity duration recorded yet",
                            fontFamily = InterFontFamily,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = onSurfaceSecondaryColor,
                            modifier = Modifier.testTag("activity_analytics_empty_state")
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text = "Total Duration",
                                fontFamily = InterFontFamily,
                                fontSize = 12.sp,
                                color = onSurfaceSecondaryColor
                            )
                            Text(
                                text = activityStats.first,
                                fontFamily = OswaldFontFamily,
                                fontWeight = FontWeight.Black,
                                fontSize = 28.sp,
                                color = onSurfaceColor
                            )
                            Text(
                                text = if (activityPeriodMonth) "Jan 1 - Dec 31, $currentYear" else "This Week",
                                fontFamily = InterFontFamily,
                                fontSize = 11.sp,
                                color = onSurfaceSecondaryColor
                            )
                        }

                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = "Workouts",
                                fontFamily = InterFontFamily,
                                fontSize = 12.sp,
                                color = onSurfaceSecondaryColor
                            )
                            Text(
                                text = activityStats.second,
                                fontFamily = OswaldFontFamily,
                                fontWeight = FontWeight.Black,
                                fontSize = 28.sp,
                                color = onSurfaceColor
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        LegendItem(color = accentOrange, label = "Duration")
                        LegendItem(color = accentBlue, label = "Workouts")
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    val animateActivityProgress by animateFloatAsState(
                        targetValue = 1f,
                        animationSpec = tween(durationMillis = 800),
                        label = "activity_chart_anim"
                    )

                    ActivityChart(
                        chartData = activityStats.third,
                        isMonth = activityPeriodMonth,
                        progress = animateActivityProgress,
                        accentOrange = accentOrange,
                        accentBlue = accentBlue,
                        gridColor = if (isDarkTheme) Color(0xFF333333) else Color(0xFFE0E0E0),
                        labelColor = onSurfaceSecondaryColor
                    )
                }
            }
        }
    }

    if (showResetConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showResetConfirmDialog = false },
            title = {
                Text(
                    text = "Reset Analytics?",
                    fontFamily = OswaldFontFamily,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "Are you sure you want to delete all logged workout sessions? This action cannot be undone.",
                    fontFamily = InterFontFamily
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showResetConfirmDialog = false
                        viewModel?.deleteAllWorkoutLogs()
                        android.widget.Toast.makeText(context, "Workout history has been reset!", android.widget.Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Text(
                        text = "RESET ALL",
                        color = Color.Red,
                        fontWeight = FontWeight.Bold,
                        fontFamily = InterFontFamily
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showResetConfirmDialog = false }
                ) {
                    Text(
                        text = "CANCEL",
                        fontFamily = InterFontFamily
                    )
                }
            }
        )
    }
}

// --- Period Toggle Button (Week vs Month Switcher) ---
@Composable
fun PeriodToggle(
    isMonthSelected: Boolean,
    onToggle: (Boolean) -> Unit,
    isDarkTheme: Boolean,
    modifier: Modifier = Modifier
) {
    val containerBg = if (isDarkTheme) Color(0xFF121212) else Color(0xFFF1F3F5)
    val selectedBg = Color(0xFF007AFF)
    val defaultText = if (isDarkTheme) Color(0xFFA0A0A0) else Color(0xFF495057)

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(containerBg)
            .padding(2.dp)
            .width(130.dp)
            .height(30.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Week Tab
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .clip(RoundedCornerShape(6.dp))
                .background(if (!isMonthSelected) selectedBg else Color.Transparent)
                .clickable { onToggle(false) },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Week",
                fontSize = 12.sp,
                fontFamily = InterFontFamily,
                fontWeight = FontWeight.Bold,
                color = if (!isMonthSelected) Color.White else defaultText
            )
        }

        // Month Tab
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .clip(RoundedCornerShape(6.dp))
                .background(if (isMonthSelected) selectedBg else Color.Transparent)
                .clickable { onToggle(true) },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Month",
                fontSize = 12.sp,
                fontFamily = InterFontFamily,
                fontWeight = FontWeight.Bold,
                color = if (isMonthSelected) Color.White else defaultText
            )
        }
    }
}

// --- Small Legend Item indicator ---
@Composable
fun LegendItem(
    color: Color,
    label: String
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(color)
        )
        Text(
            text = label,
            fontFamily = InterFontFamily,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = color
        )
    }
}

// --- Canvas Bar Chart for Lifting Volume ---
@Composable
fun LiftingVolumeChart(
    chartData: List<Pair<Float, Float>>,
    isMonth: Boolean,
    progress: Float,
    accentOrange: Color,
    accentBlue: Color,
    gridColor: Color,
    labelColor: Color
) {
    // Labels for normalized values derived from persisted workout logs.
    val xLabels = if (isMonth) {
        listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    } else {
        listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    }

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp)
    ) {
        val width = size.width
        val height = size.height

        val paddingBottom = 25.dp.toPx()
        val paddingTop = 10.dp.toPx()
        val paddingLeft = 30.dp.toPx()
        val paddingRight = 30.dp.toPx()

        val chartWidth = width - paddingLeft - paddingRight
        val chartHeight = height - paddingTop - paddingBottom

        // Draw dotted grid lines
        val numLines = 5
        val lineSpacing = chartHeight / (numLines - 1)
        val pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)

        for (i in 0 until numLines) {
            val y = paddingTop + i * lineSpacing
            drawLine(
                color = gridColor.copy(alpha = 0.5f),
                start = Offset(paddingLeft, y),
                end = Offset(width - paddingRight, y),
                strokeWidth = 1.dp.toPx(),
                pathEffect = pathEffect
            )
        }

        // Draw Bars
        val colWidth = chartWidth / xLabels.size
        val barWidth = (colWidth * 0.25f).coerceAtLeast(4.dp.toPx())
        val spacing = 2.dp.toPx()

        chartData.forEachIndexed { idx, pair ->
            if (idx < xLabels.size) {
                val colCenterX = paddingLeft + idx * colWidth + colWidth / 2f

                // Orange bar represents the first metric (volume or duration).
                val orangeHeight = pair.first * chartHeight * progress
                if (orangeHeight > 0) {
                    drawRoundRect(
                        color = accentOrange,
                        topLeft = Offset(colCenterX - barWidth - spacing, paddingTop + chartHeight - orangeHeight),
                        size = Size(barWidth, orangeHeight),
                        cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
                    )
                } else {
                    // Draw a small dot or baseline indicator like BeReal analytics
                    drawCircle(
                        color = labelColor.copy(alpha = 0.3f),
                        radius = 3.dp.toPx(),
                        center = Offset(colCenterX - barWidth/2f, paddingTop + chartHeight)
                    )
                }

                // Blue Bar (Sets)
                val blueHeight = pair.second * chartHeight * progress
                if (blueHeight > 0) {
                    drawRoundRect(
                        color = accentBlue,
                        topLeft = Offset(colCenterX + spacing, paddingTop + chartHeight - blueHeight),
                        size = Size(barWidth, blueHeight),
                        cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
                    )
                }
            }
        }

        // Draw X Axis labels
        val textPaint = android.graphics.Paint().apply {
            color = labelColor.hashCode()
            textAlign = android.graphics.Paint.Align.CENTER
            textSize = 10.sp.toPx()
            typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
        }

        xLabels.forEachIndexed { idx, label ->
            val colCenterX = paddingLeft + idx * colWidth + colWidth / 2f
            drawContext.canvas.nativeCanvas.drawText(
                label,
                colCenterX,
                height - 5.dp.toPx(),
                textPaint
            )
        }
    }
}

// --- Canvas Bar Chart for Activity ---
@Composable
fun ActivityChart(
    chartData: List<Pair<Float, Float>>,
    isMonth: Boolean,
    progress: Float,
    accentOrange: Color,
    accentBlue: Color,
    gridColor: Color,
    labelColor: Color
) {
    val xLabels = if (isMonth) {
        listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    } else {
        listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    }

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp)
    ) {
        val width = size.width
        val height = size.height

        val paddingBottom = 25.dp.toPx()
        val paddingTop = 10.dp.toPx()
        val paddingLeft = 30.dp.toPx()
        val paddingRight = 30.dp.toPx()

        val chartWidth = width - paddingLeft - paddingRight
        val chartHeight = height - paddingTop - paddingBottom

        // Draw dotted lines
        val numLines = 5
        val lineSpacing = chartHeight / (numLines - 1)
        val pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)

        for (i in 0 until numLines) {
            val y = paddingTop + i * lineSpacing
            drawLine(
                color = gridColor.copy(alpha = 0.5f),
                start = Offset(paddingLeft, y),
                end = Offset(width - paddingRight, y),
                strokeWidth = 1.dp.toPx(),
                pathEffect = pathEffect
            )
        }

        // Draw bars
        val colWidth = chartWidth / xLabels.size
        val barWidth = (colWidth * 0.25f).coerceAtLeast(4.dp.toPx())
        val spacing = 2.dp.toPx()

        chartData.forEachIndexed { idx, pair ->
            if (idx < xLabels.size) {
                val colCenterX = paddingLeft + idx * colWidth + colWidth / 2f

                // Orange (Minutes)
                val orangeHeight = pair.first * chartHeight * progress
                if (orangeHeight > 0) {
                    drawRoundRect(
                        color = accentOrange,
                        topLeft = Offset(colCenterX - barWidth - spacing, paddingTop + chartHeight - orangeHeight),
                        size = Size(barWidth, orangeHeight),
                        cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
                    )
                } else {
                    drawCircle(
                        color = labelColor.copy(alpha = 0.3f),
                        radius = 3.dp.toPx(),
                        center = Offset(colCenterX - barWidth/2f, paddingTop + chartHeight)
                    )
                }

                // Blue (Workouts)
                val blueHeight = pair.second * chartHeight * progress
                if (blueHeight > 0) {
                    drawRoundRect(
                        color = accentBlue,
                        topLeft = Offset(colCenterX + spacing, paddingTop + chartHeight - blueHeight),
                        size = Size(barWidth, blueHeight),
                        cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
                    )
                }
            }
        }

        // Labels
        val textPaint = android.graphics.Paint().apply {
            color = labelColor.hashCode()
            textAlign = android.graphics.Paint.Align.CENTER
            textSize = 10.sp.toPx()
            typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
        }

        xLabels.forEachIndexed { idx, label ->
            val colCenterX = paddingLeft + idx * colWidth + colWidth / 2f
            drawContext.canvas.nativeCanvas.drawText(
                label,
                colCenterX,
                height - 5.dp.toPx(),
                textPaint
            )
        }
    }
}
