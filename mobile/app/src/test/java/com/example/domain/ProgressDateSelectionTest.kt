package com.example.domain

import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressDateSelectionTest {
    private val zone = TimeZone.getTimeZone("America/New_York")
    private val today = LocalCalendarDate(2026, 3, 31)

    @Test
    fun basicRejectsAnyFiniteSelectionOutsideRecentThirtyLocalDates() {
        val request = ProgressDateSelection.Range(
            startDate = LocalCalendarDate(2026, 3, 1),
            endDateInclusive = today
        )

        assertEquals(
            ProgressDateSelectionResult.RequiresFullHistory,
            ProgressDateSelectionPolicy.validate(request, today, canViewFullHistory = false, zone)
        )
    }

    @Test
    fun downgradeSanitizesAllHistoryBeforeBasicStateCanBeExposed() {
        assertEquals(
            ProgressDateSelection.Range(LocalCalendarDate(2026, 3, 2), today),
            ProgressDateSelectionPolicy.sanitize(
                ProgressDateSelection.AllHistory,
                today,
                canViewFullHistory = false,
                zone
            )
        )
    }

    @Test
    fun shiftUsesInclusiveRangeLengthAndNeverPartiallyLeaksOlderBasicDates() {
        val oldestWindow = ProgressDateSelection.Range(
            ProgressDatePolicy.basicOldestAllowedDate(today, zone),
            LocalCalendarDate(2026, 3, 8)
        )
        val currentWindow = ProgressDateSelection.Range(LocalCalendarDate(2026, 3, 25), today)

        assertEquals(
            ProgressDateSelectionResult.RequiresFullHistory,
            ProgressDateSelectionPolicy.shift(oldestWindow, direction = -1, today, false, zone)
        )
        assertEquals(
            ProgressDateSelectionResult.Future,
            ProgressDateSelectionPolicy.shift(currentWindow, direction = 1, today, true, zone)
        )
    }

    @Test
    fun previousPeriodIsAdjacentEqualLengthAndDstSafe() {
        val current = ProgressDateSelection.Range(
            LocalCalendarDate(2026, 3, 8),
            LocalCalendarDate(2026, 3, 14)
        )

        val previous = ProgressDateSelectionPolicy.previousPeriod(current, zone)

        assertEquals(LocalCalendarDate(2026, 3, 1), previous.startDate)
        assertEquals(LocalCalendarDate(2026, 3, 7), previous.endDateInclusive)
        assertEquals(7, ProgressDateSelectionPolicy.inclusiveDayCount(previous, zone))
    }

    @Test
    fun chartDensityUsesDailyWeeklyThenMonthlyBuckets() {
        fun range(days: Int) = ProgressDateSelection.Range(
            ProgressDatePolicy.minusCalendarDays(today, days - 1, zone),
            today
        )

        assertEquals(ProgressBucketGranularity.DAY, ProgressDateSelectionPolicy.bucketGranularity(range(31), zone))
        assertEquals(ProgressBucketGranularity.WEEK, ProgressDateSelectionPolicy.bucketGranularity(range(32), zone))
        assertEquals(ProgressBucketGranularity.WEEK, ProgressDateSelectionPolicy.bucketGranularity(range(180), zone))
        assertEquals(ProgressBucketGranularity.MONTH, ProgressDateSelectionPolicy.bucketGranularity(range(181), zone))
        assertEquals(
            ProgressBucketGranularity.MONTH,
            ProgressDateSelectionPolicy.bucketGranularity(ProgressDateSelection.AllHistory, zone)
        )
    }

    @Test
    fun invalidOrderingAndFutureEndFailWithoutMutation() {
        val reversed = ProgressDateSelection.Range(today, LocalCalendarDate(2026, 3, 30))
        val future = ProgressDateSelection.Range(today, LocalCalendarDate(2026, 4, 1))

        assertTrue(ProgressDateSelectionPolicy.validate(reversed, today, true, zone) is ProgressDateSelectionResult.Invalid)
        assertEquals(
            ProgressDateSelectionResult.Future,
            ProgressDateSelectionPolicy.validate(future, today, true, zone)
        )
    }
}
