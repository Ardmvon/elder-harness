package com.yinling.core

import java.time.LocalDate

/**
 * The passive peace-of-mind watch: did the person use the phone today, and is that later than
 * usual enough to be worth telling the family about?
 *
 * Deliberately pure: the Android side feeds it timestamps and sends the message. Two things matter
 * more than the rule itself — never cry wolf (a family that ignores two alarms ignores all of
 * them), and never speak before there is a baseline to compare against.
 */
sealed interface PeaceDecision {
    /** Watching is off. */
    data object Idle : PeaceDecision

    /**
     * Nothing is being observed: the accessibility service is not running, so "no activity today"
     * cannot be distinguished from "nobody is looking". Must never be reported as an alert — a
     * watch that cries wolf is worse than no watch.
     */
    data class NotWatching(val why: String) : PeaceDecision

    /** Still collecting a baseline; nothing to compare against yet. */
    data class NoBaseline(val daysSoFar: Int, val daysNeeded: Int) : PeaceDecision

    /** The person has already used the phone today. */
    data class Active(val firstUseMinuteOfDay: Int) : PeaceDecision

    /** No use yet, but within the usual window. */
    data class Waiting(val usualMinuteOfDay: Int, val nowMinuteOfDay: Int) : PeaceDecision

    /** Already told the family today. */
    data class AlreadyTold(val usualMinuteOfDay: Int) : PeaceDecision

    /**
     * The daily "all is well" message. It exists because silence is not evidence of safety: the
     * family agrees to expect one message a day, so a missing message is itself the alarm — and the
     * message doubles as proof that the watch is still running.
     */
    data class DailyOk(val firstUseMinuteOfDay: Int, val message: String) : PeaceDecision

    /** Past the usual time by more than the grace period: speak up. */
    data class Alert(
        val usualMinuteOfDay: Int,
        val nowMinuteOfDay: Int,
        val message: String,
    ) : PeaceDecision
}

data class PeaceSettings(
    val enabled: Boolean = false,
    /** How long past the usual first-use time to wait before saying anything. */
    val graceMinutes: Int = 90,
    /** Never speak before this time, however early the usual time was. */
    val earliestMinuteOfDay: Int = 8 * 60,
    /** When the daily "all is well" message is due, once the person has used the phone. */
    val okMinuteOfDay: Int = 9 * 60,
    /** Days of history required before any judgement is made. */
    val minHistoryDays: Int = 3,
    /** How to refer to the person in the message; blank keeps it neutral. */
    val who: String = "",
)

/**
 * @param firstUseByDay first use of the phone per day, as minutes past midnight. Includes today once
 *   the person has touched the phone.
 * @param lastAlertEpochDay the day (epoch day) the family was last told, if any.
 */
fun decidePeace(
    today: LocalDate,
    nowMinuteOfDay: Int,
    firstUseByDay: Map<LocalDate, Int>,
    lastAlertEpochDay: Long?,
    settings: PeaceSettings,
    /** False when the observation source (the accessibility service) is not running. */
    watching: Boolean = true,
): PeaceDecision {
    if (!settings.enabled) return PeaceDecision.Idle
    if (!watching) return PeaceDecision.NotWatching("无障碍服务未在运行")

    val toldToday = lastAlertEpochDay == today.toEpochDay()
    val who = settings.who.trim().ifBlank { "家里老人" }

    // Used the phone today: report it once, so the family gets a positive signal rather than silence.
    firstUseByDay[today]?.let { firstUse ->
        if (toldToday) return PeaceDecision.Active(firstUse)
        if (nowMinuteOfDay < settings.okMinuteOfDay) return PeaceDecision.Active(firstUse)
        return PeaceDecision.DailyOk(
            firstUseMinuteOfDay = firstUse,
            message = "报平安：今天 ${clock(firstUse)} $who 就用过手机了，一切正常。" +
                "（这条消息说明看护还在运行；没收到就请打个电话。）",
        )
    }

    val history = firstUseByDay.filterKeys { it.isBefore(today) }.values.sorted()
    if (history.size < settings.minHistoryDays) {
        return PeaceDecision.NoBaseline(history.size, settings.minHistoryDays)
    }

    val usual = median(history)
    if (toldToday) return PeaceDecision.AlreadyTold(usual)

    val speakAt = maxOf(settings.earliestMinuteOfDay, usual + settings.graceMinutes)
    if (nowMinuteOfDay < speakAt) return PeaceDecision.Waiting(usual, nowMinuteOfDay)

    return PeaceDecision.Alert(
        usualMinuteOfDay = usual,
        nowMinuteOfDay = nowMinuteOfDay,
        // Calm and factual: the family should read a question, not an emergency.
        message = "平安确认：今天到现在还没看到$who 用手机，平时大约 ${clock(usual)} 就会用了。" +
            "方便的话打个电话回去看看。",
    )
}

/** Short status line for the settings screen, so the person can see the watch is alive. */
fun peaceStatus(
    today: LocalDate,
    firstUseByDay: Map<LocalDate, Int>,
    lastAlertEpochDay: Long?,
    settings: PeaceSettings,
    watching: Boolean = true,
): String {
    if (!settings.enabled) return "平安确认：未开启"
    if (!watching) return "平安确认：已暂停——无障碍服务未在运行，无法判断今天有没有用手机"
    val todayUse = firstUseByDay[today]
    val history = firstUseByDay.filterKeys { it.isBefore(today) }.values.sorted()
    val baseline = if (history.size >= settings.minHistoryDays) {
        "平时 ${clock(median(history))}"
    } else {
        "基线积累中 ${history.size}/${settings.minHistoryDays} 天"
    }
    val todayText = if (todayUse != null) "今天 ${clock(todayUse)} 用过" else "今天还没用过"
    val alertText = if (lastAlertEpochDay == today.toEpochDay()) " · 今天已给家人发过消息" else ""
    return "平安确认：$todayText · $baseline$alertText"
}

internal fun median(sorted: List<Int>): Int {
    if (sorted.isEmpty()) return 0
    val mid = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
}

internal fun clock(minuteOfDay: Int): String {
    val m = minuteOfDay.coerceIn(0, 24 * 60 - 1)
    return "%02d:%02d".format(m / 60, m % 60)
}
