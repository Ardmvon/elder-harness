package com.yinling.core

/**
 * One tool call as the run itself recorded it: what was attempted, whether the platform accepted it,
 * and when. This is the only evidence a mechanical check can rely on, because it is ours — the
 * model's account of what it did is exactly what has to be checked.
 */
data class ExecutedCall(
    val tool: String,
    /** Flattened arguments: the text that was typed, the label that was tapped, and so on. */
    val argument: String,
    val success: Boolean,
    val atMillis: Long,
)

sealed interface OutcomeVerdict {
    /** The claim is consistent with what this run actually did. */
    object Supported : OutcomeVerdict

    /** The claim asserts something this run cannot have caused; [reason] is shown to the person. */
    data class Unsupported(val reason: String) : OutcomeVerdict
}

/**
 * A floor under "it's done", not a proof.
 *
 * The failure this exists for: a run that tapped around a chat and then reported "已帮您把消息发出去了
 * …时间是 17:37，发送成功" — it had read a message the person had sent *before the run started* and
 * counted it as its own work. Nothing here judges whether the goal was achieved; it only rejects
 * claims that this run's own action log says are impossible, which is cheap, deterministic and
 * cannot be talked out of by a confident sentence.
 *
 * Evidence-based judging (a second model looking at before/after screenshots) is deliberately not
 * part of this: it is a separate, larger decision.
 */
object OutcomeCheck {

    private val TEXT_ENTRY = setOf("input_text", "paste_text", "type_text")

    /**
     * Actions that can produce an effect outside this run. Navigation-only tools such as
     * `back`, `home`, `open_app` and `wait` are deliberately absent: pressing them cannot be
     * evidence that a message was sent or an order was placed.
     *
     * `scroll`/`swipe` stay because a slider is adjusted through the scroll action on this
     * platform; the backstop is the claim-specific text/time check above.
     */
    private val STATE_CHANGING = setOf(
        "click", "tap_text", "tap_xy", "tap", "long_press", "input_text", "paste_text", "type_text",
        "swipe", "scroll", "set_slider",
    )

    /**
     * Phrases where the run claims it changed something outside itself, rather than merely read it.
     *
     * Deliberately narrow: generic completion words (办好了, 已完成, 看好了) are not evidence of a
     * change, and treating them as such would reject honest answers to read-only questions. What
     * goes here is only talk of an effect on the world.
     */
    private val CHANGE_CLAIMS = listOf(
        "已发送", "发送成功", "发出去了", "已经发出", "已发出", "已提交", "已下单", "已付款",
        "已支付", "已填", "已输入", "已保存", "已设置", "已开启", "已关闭", "已删除", "已关注",
        "已报名", "已预约", "已改", "已修改", "已添加", "已清空", "已上传", "已下载", "已安装",
        "已转发", "已回复", "已评论", "已下单成功", "下单成功", "提交成功", "改好了", "设置好了",
    )

    /** Quoted fragments: what the run claims it produced, or a label it read. */
    private val QUOTED = Regex("[「“\"']([^」”\"']{1,40})[」”\"']")

    /** Clock readings such as 17:37 — used as a time anchor against the run's own start. */
    private val CLOCK = Regex("(?<!\\d)([01]?\\d|2[0-3]):([0-5]\\d)(?!\\d)")

    /**
     * @param runStartedAt wall clock when this run started. Evidence older than this cannot be the
     *   run's own doing, whatever the claim says.
     */
    fun check(claim: String, calls: List<ExecutedCall>, runStartedAt: Long): OutcomeVerdict {
        // "已经设置好了" and "已设置" are the same assertion; normalise so the phrase list does not
        // have to enumerate every combination.
        val claim = claim.replace("已经", "已")
        val assertsChange = CHANGE_CLAIMS.any { claim.contains(it) }
        if (!assertsChange) return OutcomeVerdict.Supported
        val successful = calls.filter { it.success }

        // R1 — text provenance. If the run says it produced text, at least one quoted fragment must
        // have been typed into this run. Checking "at least one" deliberately avoids guessing which
        // quote is the payload: "在微信「文件传输助手」的输入框里填好了「我到家了」" quotes both the
        // recipient/context and the message; the longest fragments are often labels, not payload.
        // A claim whose every quote is borrowed still fails, which is the floor this check provides.
        val quoted = QUOTED.findAll(claim)
            .map { it.groupValues[1].trim() }
            .filter { it.isNotEmpty() }
            .toList()
        if (quoted.isNotEmpty()) {
            val typed = successful
                .filter { it.tool in TEXT_ENTRY }
                .joinToString(" ") { it.argument }
            val traceable = quoted.firstOrNull { typed.contains(it) }
            if (traceable == null) {
                val evidence = quoted.maxByOrNull { it.length } ?: quoted.first()
                return OutcomeVerdict.Unsupported(
                    "声明里引用的文字（「$evidence」）不是这一次输入进去的，" +
                        "可能是屏幕上本来就有的内容",
                )
            }
        }

        // R2 — time anchor. A time earlier than the run start is something that already existed.
        val startedClock = minutesOfDay(runStartedAt)
        CLOCK.find(claim)?.let { match ->
            val quotedClock = match.groupValues[1].toInt() * 60 + match.groupValues[2].toInt()
            if (quotedClock + TIME_SLACK_MINUTES < startedClock) {
                return OutcomeVerdict.Unsupported(
                    "声明里提到的时间（${match.value}）早于这次开始办事的时间，" +
                        "那时候它还没动手",
                )
            }
        }

        // R3 — an asserted change needs at least one action that could have caused a change.
        if (successful.none { it.tool in STATE_CHANGING }) {
            return OutcomeVerdict.Unsupported("这一次它只看了屏幕，没有真的动手")
        }

        return OutcomeVerdict.Supported
    }

    /** Person-facing sentence for a rejected completion. */
    fun explain(verdict: OutcomeVerdict): String = when (verdict) {
        is OutcomeVerdict.Supported -> ""
        is OutcomeVerdict.Unsupported ->
            "我没法确认这件事真的办成了：${verdict.reason}。请您自己看一眼；" +
                "要我接着办，就按下面的按钮。"
    }

    private const val TIME_SLACK_MINUTES = 1

    private fun minutesOfDay(millis: Long): Int {
        val calendar = java.util.Calendar.getInstance()
        calendar.timeInMillis = millis
        return calendar.get(java.util.Calendar.HOUR_OF_DAY) * 60 + calendar.get(java.util.Calendar.MINUTE)
    }
}
