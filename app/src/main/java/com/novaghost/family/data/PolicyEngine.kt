package com.novaghost.family.data

import com.novaghost.family.core.DailyUsage
import com.novaghost.family.core.Fmt
import com.novaghost.family.core.Policy

enum class AlertKind { GOAL_NEAR, GOAL_EXCEEDED, GAME_NEAR, GAME_EXCEEDED, BEDTIME_SOON, BEDTIME_NOW, STUDY_TIME }

data class Alert(val kind: AlertKind, val key: String, val title: String, val text: String)

object PolicyEngine {
    fun inWindow(nowMin: Int, start: Int, end: Int): Boolean =
        if (start <= end) nowMin in start until end else nowMin >= start || nowMin < end

    fun evaluate(p: Policy, u: DailyUsage, nowMin: Int, date: String): List<Alert> {
        val out = ArrayList<Alert>()
        fun add(k: AlertKind, t: String, x: String) = out.add(Alert(k, "$date|${k.name}", t, x))
        if (p.dailyGoalMin > 0) {
            if (u.totalMin >= p.dailyGoalMin) add(AlertKind.GOAL_EXCEEDED, "Daily screen-time goal reached",
                "${Fmt.minutes(u.totalMin)} used today (goal ${Fmt.minutes(p.dailyGoalMin)}).")
            else if (u.totalMin * 10 >= p.dailyGoalMin * 8) add(AlertKind.GOAL_NEAR, "Almost at your daily goal",
                "${Fmt.minutes(p.dailyGoalMin - u.totalMin)} left of ${Fmt.minutes(p.dailyGoalMin)}.")
        }
        if (p.gamingLimitMin > 0) {
            if (u.gamingMin >= p.gamingLimitMin) add(AlertKind.GAME_EXCEEDED, "Gaming limit reached",
                "${Fmt.minutes(u.gamingMin)} of games today. Time for a break!")
            else if (u.gamingMin * 10 >= p.gamingLimitMin * 8) add(AlertKind.GAME_NEAR, "Gaming limit approaching",
                "${Fmt.minutes(p.gamingLimitMin - u.gamingMin)} of gaming left today.")
        }
        if (p.bedtimeEnabled) {
            if (inWindow(nowMin, p.bedtimeStart, p.bedtimeEnd)) add(AlertKind.BEDTIME_NOW, "Bedtime",
                "Downtime is active until ${Fmt.clock(p.bedtimeEnd)}.")
            else if (inWindow((nowMin + 30) % 1440, p.bedtimeStart, p.bedtimeEnd)) add(AlertKind.BEDTIME_SOON,
                "Bedtime soon", "Downtime starts at ${Fmt.clock(p.bedtimeStart)}.")
        }
        if (p.studyEnabled && inWindow(nowMin, p.studyStart, p.studyEnd)) add(AlertKind.STUDY_TIME, "Study time",
            "Study block until ${Fmt.clock(p.studyEnd)}.")
        return out
    }
}
