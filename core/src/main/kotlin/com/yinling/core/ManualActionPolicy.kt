package com.yinling.core

data class TextInputTarget(
    val app: String?,
    val editable: Boolean,
    val enabled: Boolean,
    val visible: Boolean,
    val password: Boolean,
)

object ManualActionPolicy {
    val textTools = setOf("type_text", "paste_text", "input_text")

    val manualActionWords = listOf(
        "支付", "付款", "转账", "发出", "发送", "提交", "下单", "认证", "授权", "验证码", "密码",
        "删除", "购买", "呼叫", "拨打", "结算", "拼单", "收银台", "去支付", "立即支付", "确认支付",
        "立即购买", "一键购买", "确认下单", "提交订单", "确认付款", "付款码", "免密", "先用后付",
        "充值", "提现", "还款", "打赏", "订阅", "续费", "确认收货", "立即预订", "确认预订",
    )

    private val screenTools = setOf(
        "click", "tap_text", "tap_xy", "tap", "long_press", "input_text", "paste_text", "type_text",
        "scroll", "swipe", "screenshot", "set_slider",
    )

    fun textHit(text: String): String? = manualActionWords.firstOrNull(text::contains)

    fun checkText(invocation: ToolInvocation): ToolResult? =
        checkText(invocation.tool, invocation.arguments["text"].orEmpty())

    fun checkText(tool: String, text: String): ToolResult? {
        if (tool !in textTools) return null
        val hit = textHit(text) ?: return null
        return ToolResult(
            false,
            "输入内容包含「$hit」，请您亲自确认并输入，完成后按继续。",
            "requires_user",
        )
    }

    fun checkScreen(tool: String, screen: ScreenSnapshot): ToolResult? =
        if (screen.sensitive && tool in screenTools) {
            ToolResult(
                false,
                "当前页面涉及身份或支付验证，请您自己操作，完成后按继续。",
                "requires_user",
            )
        } else {
            null
        }

    fun checkFocusedInput(screen: ScreenSnapshot, target: TextInputTarget?): ToolResult? {
        if (screen.sensitive || screen.app == null || target == null || target.app != screen.app ||
            !target.editable || !target.enabled || !target.visible || target.password
        ) {
            return ToolResult(
                false,
                "无法确认当前输入框是否安全，请您自己输入后按继续。",
                "requires_user",
            )
        }
        return null
    }
}
