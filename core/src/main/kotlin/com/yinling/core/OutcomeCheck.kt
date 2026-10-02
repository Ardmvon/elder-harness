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

    private val TEXT_ENTRY = ManualActionPolicy.textTools

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
        "填好了", "填入了", "输入好了",
    )

    /** Quoted fragments: what the run claims it produced, or a label it read. */
    private val QUOTED = Regex("[「“\"']([^」”\"']{1,2000})[」”\"']")

    private val UNVERIFIED_EFFECTS = listOf(
        "已发送", "发送成功", "发出去了", "已发出", "已付款", "已支付", "已下单", "下单成功", "已提交", "提交成功",
    )
    private val PAYLOAD_PREFIX = Regex("(?:填(?:好|入|进)?(?:了)?|输入(?:好)?(?:了)?|写入(?:了)?|(?:改|设(?:置)?|填)为|为)[：:\\s]*$")
    private val PAYLOAD_SUFFIX = Regex("^[\\s]*(?:填(?:好|入|进)|写入|输入|放进)")
    private val FIELD_SUFFIX = Regex("^[\\s]*(?:为|是)")

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
        val successful = calls.filter { it.success && it.atMillis >= runStartedAt }
        if (UNVERIFIED_EFFECTS.any(claim::contains)) {
            return OutcomeVerdict.Unsupported("执行记录没有核验发送、支付或提交结果，输入文字不等于已完成这些操作")
        }

        val matches = QUOTED.findAll(claim).toList()
        val payloads = matches.filter { match ->
            val prefix = claim.substring(0, match.range.first)
            val suffix = claim.substring(match.range.last + 1)
            !FIELD_SUFFIX.containsMatchIn(suffix) &&
                (PAYLOAD_PREFIX.containsMatchIn(prefix) || PAYLOAD_SUFFIX.containsMatchIn(suffix))
        }.map { it.groupValues[1].trim() }.filter { it.isNotEmpty() }
        if (matches.isNotEmpty()) {
            val typed = successful.filter { it.tool in TEXT_ENTRY }.map { it.argument }
            if (payloads.isEmpty() || payloads.any { payload -> typed.none { it.contains(payload) } }) {
                return OutcomeVerdict.Unsupported("声明中的输入正文无法对应到本轮成功输入的文字，收件人或页面标签不能代替正文")
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
            "我没法确认这件事真的办成了：${verdict.reason}。请您查看原页面，或结束这件事。"
    }

    private const val TIME_SLACK_MINUTES = 1

    private fun minutesOfDay(millis: Long): Int {
        val calendar = java.util.Calendar.getInstance()
        calendar.timeInMillis = millis
        return calendar.get(java.util.Calendar.HOUR_OF_DAY) * 60 + calendar.get(java.util.Calendar.MINUTE)
    }
}
