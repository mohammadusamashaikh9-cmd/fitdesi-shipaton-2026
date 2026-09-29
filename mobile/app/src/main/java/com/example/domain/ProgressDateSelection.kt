package com.example.domain

import java.util.Calendar
import java.util.TimeZone

sealed interface ProgressDateSelection {
    data class Range(
        val startDate: LocalCalendarDate,
        val endDateInclusive: LocalCalendarDate
    ) : ProgressDateSelection

    data object AllHistory : ProgressDateSelection
}

enum class ProgressDatePreset {
    RECENT_30_DAYS,
    THIS_WEEK,
    THIS_MONTH,
    CUSTOM,
    ALL_HISTORY
}

enum class ProgressBucketGranularity {
    DAY,
    WEEK,
    MONTH
}

sealed interface ProgressDateSelectionResult {
    data class Allowed(val selection: ProgressDateSelection) : ProgressDateSelectionResult
    data object RequiresFullHistory : ProgressDateSelectionResult
    data object Future : ProgressDateSelectionResult
    data class Invalid(val reason: String) : ProgressDateSelectionResult
}

object ProgressDateSelectionPolicy {
    fun recentThirtyDays(today: LocalCalendarDate, timeZone: TimeZone): ProgressDateSelection.Range =
        ProgressDateSelection.Range(
            startDate = ProgressDatePolicy.basicOldestAllowedDate(today, timeZone),
            endDateInclusive = today
        )

    fun preset(
        preset: ProgressDatePreset,
        today: LocalCalendarDate,
        canViewFullHistory: Boolean,
        timeZone: TimeZone,
        customRange: ProgressDateSelection.Range? = null
    ): ProgressDateSelectionResult {
        val requested = when (preset) {
            ProgressDatePreset.RECENT_30_DAYS -> recentThirtyDays(today, timeZone)
            ProgressDatePreset.THIS_WEEK -> ProgressDateSelection.Range(
                startDate = startOfLocalWeek(today, timeZone),
                endDateInclusive = today
            )
            ProgressDatePreset.THIS_MONTH -> ProgressDateSelection.Range(
                startDate = LocalCalendarDate(today.year, today.month, 1),
                endDateInclusive = today
            )
            ProgressDatePreset.CUSTOM -> customRange
                ?: return ProgressDateSelectionResult.Invalid("Choose a start and end date")
            ProgressDatePreset.ALL_HISTORY -> ProgressDateSelection.AllHistory
        }
        return validate(requested, today, canViewFullHistory, timeZone)
    }

    fun validate(
        requested: ProgressDateSelection,
        today: LocalCalendarDate,
        canViewFullHistory: Boolean,
        timeZone: TimeZone
    ): ProgressDateSelectionResult = when (requested) {
        ProgressDateSelection.AllHistory -> if (canViewFullHistory) {
            ProgressDateSelectionResult.Allowed(requested)
        } else {
            ProgressDateSelectionResult.RequiresFullHistory
        }
        is ProgressDateSelection.Range -> when {
            requested.startDate > requested.endDateInclusive ->
                ProgressDateSelectionResult.Invalid("Start date must not follow end date")
            requested.endDateInclusive > today -> ProgressDateSelectionResult.Future
            !canViewFullHistory && requested.startDate < ProgressDatePolicy.basicOldestAllowedDate(today, timeZone) ->
                ProgressDateSelectionResult.RequiresFullHistory
            else -> ProgressDateSelectionResult.Allowed(requested)
        }
    }

    fun sanitize(
        selection: ProgressDateSelection,
        today: LocalCalendarDate,
        canViewFullHistory: Boolean,
        timeZone: TimeZone
    ): ProgressDateSelection = when (val result = validate(selection, today, canViewFullHistory, timeZone)) {
        is ProgressDateSelectionResult.Allowed -> result.selection
        else -> recentThirtyDays(today, timeZone)
    }

    fun shift(
        selection: ProgressDateSelection,
        direction: Int,
        today: LocalCalendarDate,
        canViewFullHistory: Boolean,
        timeZone: TimeZone
    ): ProgressDateSelectionResult {
        require(direction == -1 || direction == 1) { "direction must be -1 or 1" }
        if (selection is ProgressDateSelection.AllHistory) {
            return ProgressDateSelectionResult.Invalid("All History cannot be shifted")
        }
        selection as ProgressDateSelection.Range
        val days = inclusiveDayCount(selection, timeZone)
        val shifted = if (direction < 0) {
            val end = ProgressDatePolicy.previousLocalDate(selection.startDate, timeZone)
            ProgressDateSelection.Range(
                startDate = ProgressDatePolicy.minusCalendarDays(end, days - 1, timeZone),
                endDateInclusive = end
            )
        } else {
            val start = ProgressDatePolicy.plusCalendarDays(selection.endDateInclusive, 1, timeZone)
            ProgressDateSelection.Range(
                startDate = start,
                endDateInclusive = ProgressDatePolicy.plusCalendarDays(start, days - 1, timeZone)
            )
        }
        return validate(shifted, today, canViewFullHistory, timeZone)
    }

    fun previousPeriod(
        selection: ProgressDateSelection.Range,
        timeZone: TimeZone
    ): ProgressDateSelection.Range {
        val days = inclusiveDayCount(selection, timeZone)
        val previousEnd = ProgressDatePolicy.previousLocalDate(selection.startDate, timeZone)
        return ProgressDateSelection.Range(
            startDate = ProgressDatePolicy.minusCalendarDays(previousEnd, days - 1, timeZone),
            endDateInclusive = previousEnd
        )
    }

    fun inclusiveDayCount(selection: ProgressDateSelection.Range, timeZone: TimeZone): Int {
        require(selection.startDate <= selection.endDateInclusive) { "startDate must not follow endDateInclusive" }
        var count = 1
        var cursor = selection.startDate
        while (cursor < selection.endDateInclusive) {
            cursor = ProgressDatePolicy.plusCalendarDays(cursor, 1, timeZone)
            count += 1
        }
        return count
    }

    fun bucketGranularity(selection: ProgressDateSelection, timeZone: TimeZone): ProgressBucketGranularity =
        when (selection) {
            ProgressDateSelection.AllHistory -> ProgressBucketGranularity.MONTH
            is ProgressDateSelection.Range -> when (inclusiveDayCount(selection, timeZone)) {
                in 1..31 -> ProgressBucketGranularity.DAY
                in 32..180 -> ProgressBucketGranularity.WEEK
                else -> ProgressBucketGranularity.MONTH
            }
        }

    fun epochRange(selection: ProgressDateSelection.Range, timeZone: TimeZone): LocalDateEpochRange =
        ProgressDatePolicy.inclusiveDateRange(selection.startDate, selection.endDateInclusive, timeZone)

    private fun startOfLocalWeek(date: LocalCalendarDate, timeZone: TimeZone): LocalCalendarDate {
        val calendar = Calendar.getInstance(timeZone).apply {
            timeInMillis = ProgressDatePolicy.startOfLocalDateEpochMillis(date, timeZone)
        }
        val daysSinceMonday = (calendar.get(Calendar.DAY_OF_WEEK) + 5) % 7
        return ProgressDatePolicy.minusCalendarDays(date, daysSinceMonday, timeZone)
    }
}
