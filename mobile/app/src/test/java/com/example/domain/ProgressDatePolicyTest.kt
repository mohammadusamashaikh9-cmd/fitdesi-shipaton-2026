package com.example.domain

import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

class ProgressDatePolicyTest {
    @Test
    fun `basic history includes today and previous 29 local dates but restricts previous 30`() {
        val zone = TimeZone.getTimeZone("Asia/Karachi")
        val today = LocalCalendarDate(2026, 9, 4)

        assertEquals(
            HistoryDateAccess.ALLOWED,
            ProgressDatePolicy.historyAccess(today, today, canViewFullHistory = false, zone)
        )
        assertEquals(
            HistoryDateAccess.ALLOWED,
            ProgressDatePolicy.historyAccess(
                ProgressDatePolicy.minusCalendarDays(today, 29, zone),
                today,
                canViewFullHistory = false,
                zone
            )
        )
        assertEquals(
            HistoryDateAccess.REQUIRES_FULL_HISTORY,
            ProgressDatePolicy.historyAccess(
                ProgressDatePolicy.minusCalendarDays(today, 30, zone),
                today,
                canViewFullHistory = false,
                zone
            )
        )
        assertEquals(
            HistoryDateAccess.ALLOWED,
            ProgressDatePolicy.historyAccess(
                ProgressDatePolicy.minusCalendarDays(today, 30, zone),
                today,
                canViewFullHistory = true,
                zone
            )
        )
    }

    @Test
    fun `calendar subtraction crosses month and year boundaries`() {
        val zone = TimeZone.getTimeZone("UTC")

        assertEquals(
            LocalCalendarDate(2026, 2, 28),
            ProgressDatePolicy.minusCalendarDays(LocalCalendarDate(2026, 3, 1), 1, zone)
        )
        assertEquals(
            LocalCalendarDate(2025, 12, 31),
            ProgressDatePolicy.minusCalendarDays(LocalCalendarDate(2026, 1, 1), 1, zone)
        )
    }

    @Test
    fun `spring forward uses adjacent local dates and a 23 hour epoch range`() {
        val zone = TimeZone.getTimeZone("America/New_York")
        val date = LocalCalendarDate(2026, 3, 8)
        val range = ProgressDatePolicy.inclusiveDateRange(date, date, zone)

        assertEquals(date, ProgressDatePolicy.localDateAt(range.startInclusive, zone))
        assertEquals(LocalCalendarDate(2026, 3, 9), ProgressDatePolicy.localDateAt(range.endExclusive, zone))
        assertEquals(23L * 60L * 60L * 1_000L, range.endExclusive - range.startInclusive)
    }

    @Test
    fun `fall back uses adjacent local dates and a 25 hour epoch range`() {
        val zone = TimeZone.getTimeZone("America/New_York")
        val date = LocalCalendarDate(2026, 11, 1)
        val range = ProgressDatePolicy.inclusiveDateRange(date, date, zone)

        assertEquals(date, ProgressDatePolicy.localDateAt(range.startInclusive, zone))
        assertEquals(LocalCalendarDate(2026, 11, 2), ProgressDatePolicy.localDateAt(range.endExclusive, zone))
        assertEquals(25L * 60L * 60L * 1_000L, range.endExclusive - range.startInclusive)
    }

    @Test
    fun `future local dates are excluded even for full history`() {
        val zone = TimeZone.getTimeZone("UTC")
        assertEquals(
            HistoryDateAccess.FUTURE,
            ProgressDatePolicy.historyAccess(
                LocalCalendarDate(2026, 9, 5),
                LocalCalendarDate(2026, 9, 4),
                canViewFullHistory = true,
                zone
            )
        )
    }
}
