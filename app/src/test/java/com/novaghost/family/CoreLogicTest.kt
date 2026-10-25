package com.novaghost.family

import com.novaghost.family.core.*
import com.novaghost.family.data.AlertKind
import com.novaghost.family.data.PolicyEngine
import com.novaghost.family.data.UsageMath
import org.junit.Assert.*
import org.junit.Test

class CoreLogicTest {
    private fun usage(total: Int, gaming: Int) = DailyUsage("2026-01-01", total, gaming, 0, emptyList(), 0L)

    @Test fun foregroundSumsIntervalsAndOpenSession() {
        val ev = listOf(
            UsageMath.Ev("a", UsageMath.RESUMED, 1_000), UsageMath.Ev("a", UsageMath.PAUSED, 61_000),
            UsageMath.Ev("b", UsageMath.RESUMED, 100_000)
        )
        val r = UsageMath.foreground(ev, 160_000)
        assertEquals(60_000L, r["a"])
        assertEquals(60_000L, r["b"])
    }

    @Test fun pauseWithoutResumeIsIgnored() {
        assertTrue(UsageMath.foreground(listOf(UsageMath.Ev("a", UsageMath.PAUSED, 5)), 10).isEmpty())
    }

    @Test fun windowWrapsMidnight() {
        assertTrue(PolicyEngine.inWindow(23 * 60, 21 * 60 + 30, 7 * 60))
        assertTrue(PolicyEngine.inWindow(60, 21 * 60 + 30, 7 * 60))
        assertFalse(PolicyEngine.inWindow(12 * 60, 21 * 60 + 30, 7 * 60))
    }

    @Test fun goalNearThenExceeded() {
        val p = Policy(dailyGoalMin = 100, gamingLimitMin = 0)
        assertEquals(AlertKind.GOAL_NEAR, PolicyEngine.evaluate(p, usage(85, 0), 600, "d").single().kind)
        assertEquals(AlertKind.GOAL_EXCEEDED, PolicyEngine.evaluate(p, usage(100, 0), 600, "d").single().kind)
        assertTrue(PolicyEngine.evaluate(p, usage(50, 0), 600, "d").isEmpty())
    }

    @Test fun gamingLimitAlerts() {
        val p = Policy(dailyGoalMin = 0, gamingLimitMin = 60)
        assertEquals(AlertKind.GAME_EXCEEDED, PolicyEngine.evaluate(p, usage(90, 60), 600, "d").single().kind)
    }

    @Test fun bedtimeSoonAndNow() {
        val p = Policy(dailyGoalMin = 0, gamingLimitMin = 0, bedtimeEnabled = true)
        assertEquals(AlertKind.BEDTIME_SOON, PolicyEngine.evaluate(p, usage(0, 0), 21 * 60 + 10, "d").single().kind)
        assertEquals(AlertKind.BEDTIME_NOW, PolicyEngine.evaluate(p, usage(0, 0), 22 * 60, "d").single().kind)
    }

    @Test fun invitePayloadParsing() {
        assertEquals("ABCD2345", InvitePayload.parse(InvitePayload.encode("ABCD2345")))
        assertEquals("ABCD2345", InvitePayload.parse(" abcd-2345 "))
        assertNull(InvitePayload.parse("short"))
        assertNull(InvitePayload.parse("ABCD234!"))
    }

    @Test fun freshnessClassification() {
        assertEquals(Freshness.UNAVAILABLE, Fmt.freshness(0, 1_000_000))
        assertEquals(Freshness.LIVE, Fmt.freshness(1_000_000, 1_000_000 + 10 * 60_000))
        assertEquals(Freshness.DELAYED, Fmt.freshness(1_000_000, 1_000_000 + 60 * 60_000))
    }

    @Test fun policyMapRoundTrip() {
        val p = Policy(dailyGoalMin = 90, bedtimeEnabled = true, downtimeLock = true)
        assertEquals(p, Policy.fromMap(p.toMap()))
        assertEquals(Policy(), Policy.fromMap(null))
    }

    @Test fun consentDefaultsOff() {
        assertEquals(Consent(), Consent.fromMap(null))
        assertTrue(Consent.fromMap(mapOf("reminders" to true)).reminders)
    }

    @Test fun formatting() {
        assertEquals("1h 5m", Fmt.minutes(65))
        assertEquals("07:05", Fmt.clock(7 * 60 + 5))
    }
}
