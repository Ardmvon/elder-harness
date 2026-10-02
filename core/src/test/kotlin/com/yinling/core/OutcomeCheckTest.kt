package com.yinling.core

import kotlin.test.Test
import kotlin.test.assertTrue

class OutcomeCheckTest {

    @Test
    fun `navigation actions are not evidence that something was sent`() {
        val now = System.currentTimeMillis()
        val calls = listOf(ExecutedCall(tool = "back", argument = "", success = true, atMillis = now))
        val verdict = OutcomeCheck.check("已发送成功", calls, now)
        assertTrue(verdict is OutcomeVerdict.Unsupported, "back must not satisfy R3")
    }

    @Test
    fun `quoted message payload is traced without treating recipient as payload`() {
        val now = System.currentTimeMillis()
        // After the fix, ExecutedCall.argument for paste_text only contains the text, not the target
        val calls = listOf(ExecutedCall(tool = "paste_text", argument = "我到家了", success = true, atMillis = now))
        val verdict = OutcomeCheck.check("已填「我到家了」给「女儿」", calls, now)
        assertTrue(verdict is OutcomeVerdict.Supported)
    }

    @Test
    fun `a short message before the recipient is accepted`() {
        val now = System.currentTimeMillis()
        // After the fix, ExecutedCall.argument for paste_text only contains the text, not the target
        val calls = listOf(ExecutedCall(tool = "paste_text", argument = "好", success = true, atMillis = now))
        val verdict = OutcomeCheck.check("已填「好」给「女儿」", calls, now)
        assertTrue(verdict is OutcomeVerdict.Supported)
    }

    @Test
    fun `the recipient does not substitute for the message text`() {
        val now = System.currentTimeMillis()
        val calls = listOf(ExecutedCall(tool = "input_text", argument = "女儿", success = true, atMillis = now))
        val verdict = OutcomeCheck.check("已填「我到家了」给「女儿」", calls, now)
        assertTrue(verdict is OutcomeVerdict.Unsupported)
    }

    @Test
    fun `a quoted fragment nobody typed is rejected`() {
        val now = System.currentTimeMillis()
        val calls = listOf(ExecutedCall(tool = "click", argument = "e1", success = true, atMillis = now))
        val verdict = OutcomeCheck.check("已发送「我到家了」", calls, now)
        assertTrue(verdict is OutcomeVerdict.Unsupported, "borrowed text must not pass R1")
    }

    @Test
    fun `typing a message never proves it was sent`() {
        val now = System.currentTimeMillis()
        val calls = listOf(ExecutedCall("paste_text", "我到家了", true, now))
        assertTrue(OutcomeCheck.check("已发送「我到家了」给「女儿」", calls, now) is OutcomeVerdict.Unsupported)
    }

    @Test
    fun `recipient before payload cannot substitute for body`() {
        val now = System.currentTimeMillis()
        val calls = listOf(ExecutedCall("input_text", "女儿", true, now))
        assertTrue(OutcomeCheck.check("已给「女儿」填好了「我到家了」", calls, now) is OutcomeVerdict.Unsupported)
    }

    @Test
    fun `long page label does not invalidate a short real payload`() {
        val now = System.currentTimeMillis()
        val calls = listOf(ExecutedCall("paste_text", "好", true, now))
        assertTrue(OutcomeCheck.check("已在微信「文件传输助手」的输入框填好了「好」", calls, now) is OutcomeVerdict.Supported)
    }

    @Test
    fun `field label cannot replace field value`() {
        val now = System.currentTimeMillis()
        val calls = listOf(ExecutedCall("input_text", "姓名", true, now))
        assertTrue(OutcomeCheck.check("已填「姓名」为「张三」", calls, now) is OutcomeVerdict.Unsupported)
    }

    @Test
    fun `old or failed input cannot prove current payload`() {
        val now = System.currentTimeMillis()
        for (call in listOf(ExecutedCall("input_text", "好", false, now), ExecutedCall("input_text", "好", true, now - 1))) {
            assertTrue(OutcomeCheck.check("已填「好」", listOf(call), now) is OutcomeVerdict.Unsupported)
        }
    }

    @Test
    fun `unrelated input records cannot be joined into claimed text`() {
        val now = System.currentTimeMillis()
        val calls = listOf(ExecutedCall("input_text", "我到", true, now), ExecutedCall("paste_text", "家了", true, now))
        assertTrue(OutcomeCheck.check("已填「我到 家了」", calls, now) is OutcomeVerdict.Unsupported)
    }

    @Test
    fun `typing a form is not evidence of payment or submission`() {
        val now = System.currentTimeMillis()
        val calls = listOf(ExecutedCall("input_text", "123456", true, now))
        for (claim in listOf("已支付", "已下单", "已提交")) {
            assertTrue(OutcomeCheck.check(claim, calls, now) is OutcomeVerdict.Unsupported)
        }
    }

    @Test
    fun `reading a message is not an input completion claim`() {
        val now = System.currentTimeMillis()
        assertTrue(OutcomeCheck.check("页面显示「女儿」和「好」", emptyList(), now) is OutcomeVerdict.Supported)
    }
}
