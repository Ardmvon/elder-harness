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
    fun `the longest quoted text must have been produced by this run`() {
        val now = System.currentTimeMillis()
        val calls = listOf(ExecutedCall(tool = "paste_text", argument = "e1 我到家了", success = true, atMillis = now))
        val verdict = OutcomeCheck.check("已发送「我到家了」给「女儿」", calls, now)
        assertTrue(verdict is OutcomeVerdict.Supported)
    }

    @Test
    fun `a quoted fragment nobody typed is rejected`() {
        val now = System.currentTimeMillis()
        val calls = listOf(ExecutedCall(tool = "click", argument = "e1", success = true, atMillis = now))
        val verdict = OutcomeCheck.check("已发送「我到家了」", calls, now)
        assertTrue(verdict is OutcomeVerdict.Unsupported, "borrowed text must not pass R1")
    }
}
