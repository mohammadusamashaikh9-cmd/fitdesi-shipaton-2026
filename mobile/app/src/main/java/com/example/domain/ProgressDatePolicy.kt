package com.example.domain

import com.example.subscription.SubscriptionPolicy
import java.util.Calendar
import java.util.TimeZone

data class LocalCalendarDate(
    val year: Int,
    val month: Int,
    val dayOfMonth: Int
) : Comparable<LocalCalendarDate> {
    override fun compareTo(other: LocalCalendarDate): Int =
        compareValuesBy(this, other, LocalCalendarDate::year, LocalCalendarDate::month, LocalCalendarDate::dayOfMonth)
}

data class LocalDateEpochRange(
    val startInclusive: Long,
    val endExclusive: Long
)

enum class HistoryDateAccess {
    ALLOWED,
    REQUIRES_FULL_HISTORY,
    FUTURE
}

object ProgressDatePolicy {
    fun localDateAt(epochMillis: Long, timeZone: TimeZone): LocalCalendarDate =
        Calendar.getInstance(timeZone).run {
            timeInMillis = epochMillis
            toLocalCalendarDate()
        }

    fun today(nowMillis: Long, timeZone: TimeZone): LocalCalendarDate =
        localDateAt(nowMillis, timeZone)

    fun previousLocalDate(date: LocalCalendarDate, timeZone: TimeZone): LocalCalendarDate =
        minusCalendarDays(date, 1, timeZone)

    fun plusCalendarDays(
        date: LocalCalendarDate,
        days: Int,
        timeZone: TimeZone
    ): LocalCalendarDate {
        require(days >= 0) { "days must be nonnegative" }
        return calendarAtStartOfDate(date, timeZone).run {
            add(Calendar.DAY_OF_MONTH, days)
            toLocalCalendarDate()
        }
    }

    fun minusCalendarDays(
        date: LocalCalendarDate,
        days: Int,
        timeZone: TimeZone
    ): LocalCalendarDate {
        require(days >= 0) { "days must be nonnegative" }
        return calendarAtStartOfDate(date, timeZone).run {
            add(Calendar.DAY_OF_MONTH, -days)
            toLocalCalendarDate()
        }
    }

    fun startOfLocalDateEpochMillis(date: LocalCalendarDate, timeZone: TimeZone): Long =
        calendarAtStartOfDate(date, timeZone).timeInMillis

    fun startOfNextLocalDateEpochMillis(date: LocalCalendarDate, timeZone: TimeZone): Long =
        calendarAtStartOfDate(date, timeZone).run {
            add(Calendar.DAY_OF_MONTH, 1)
            timeInMillis
        }

    fun inclusiveDateRange(
        startDate: LocalCalendarDate,
        endDateInclusive: LocalCalendarDate,
        timeZone: TimeZone
    ): LocalDateEpochRange {
        require(startDate <= endDateInclusive) { "startDate must not follow endDateInclusive" }
        return LocalDateEpochRange(
            startInclusive = startOfLocalDateEpochMillis(startDate, timeZone),
            endExclusive = startOfNextLocalDateEpochMillis(endDateInclusive, timeZone)
        )
    }

    fun basicOldestAllowedDate(
        today: LocalCalendarDate,
        timeZone: TimeZone
    ): LocalCalendarDate = minusCalendarDays(
        today,
        SubscriptionPolicy.BASIC_DETAILED_HISTORY_DAYS - 1,
        timeZone
    )

    fun historyAccess(
        recordDate: LocalCalendarDate,
        today: LocalCalendarDate,
        canViewFullHistory: Boolean,
        timeZone: TimeZone
    ): HistoryDateAccess = when {
        recordDate > today -> HistoryDateAccess.FUTURE
        canViewFullHistory -> HistoryDateAccess.ALLOWED
        recordDate >= basicOldestAllowedDate(today, timeZone) -> HistoryDateAccess.ALLOWED
        else -> HistoryDateAccess.REQUIRES_FULL_HISTORY
    }

    private fun calendarAtStartOfDate(
        date: LocalCalendarDate,
        timeZone: TimeZone
    ): Calendar = Calendar.getInstance(timeZone).apply {
        clear()
        set(date.year, date.month - 1, date.dayOfMonth, 0, 0, 0)
        set(Calendar.MILLISECOND, 0)
    }

    private fun Calendar.toLocalCalendarDate(): LocalCalendarDate = LocalCalendarDate(
        year = get(Calendar.YEAR),
        month = get(Calendar.MONTH) + 1,
        dayOfMonth = get(Calendar.DAY_OF_MONTH)
    )
}
