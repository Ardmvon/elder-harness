package com.yinling.hotline

import com.yinling.core.AgentMessage

/**
 * Turns one verified task transcript into a candidate skill.
 *
 * This is deliberately a single text-only model call with a strict output format. It never decides
 * whether a task succeeded; the caller only invokes it after the loop's completion check passed and
 * the person confirmed the result.
 */
class SkillWriter(private val ask: suspend (instructions: String, prompt: String) -> String?) {

    suspend fun draft(
        goal: String,
        appPackage: String?,
        transcript: List<AgentMessage>,
    ): Skill? {
        val answer = ask(INSTRUCTIONS, prompt(goal, appPackage, transcript))?.trim().orEmpty()
        if (answer.isBlank()) return null
        return parse(answer, goal, appPackage)
    }

    private fun prompt(goal: String, appPackage: String?, transcript: List<AgentMessage>): String = buildString {
        appendLine("目标：${redact(goal)}")
        appendLine("当前 App：${appPackage.orEmpty().ifBlank { "未知" }}")
        appendLine()
        appendLine("操作记录：")
        transcript.forEach { message ->
            when (message.role) {
                AgentMessage.Role.USER -> appendLine("用户：${redact(message.content).take(400)}")
                AgentMessage.Role.ASSISTANT -> {
                    append("助手：")
                    if (message.content.isNotBlank()) append(redact(message.content).take(300))
                    if (message.toolCalls.isNotEmpty()) {
                        append(" [调用] ")
                        append(
                            message.toolCalls.joinToString("；") { call ->
                                val args = call.arguments.entries.joinToString(",") {
                                    "${it.key}=${redact(it.value).take(60)}"
                                }
                                "${call.tool}($args)"
                            },
                        )
                    }
                    appendLine()
                }
                AgentMessage.Role.TOOL -> appendLine("工具结果：${redact(message.content).take(240)}")
                AgentMessage.Role.SYSTEM -> Unit
            }
        }
    }.take(MAX_PROMPT_CHARS)

    /**
     * The transcript may contain a phone number, ID or email seen on screen. The model is asked not
     * to put them in the skill, but rule-based redaction keeps the most obvious identifiers out of
     * the extra summarization request as well.
     */
    private fun redact(text: String): String = text
        .replace(PHONE, "[手机号]")
        .replace(ID_CARD, "[身份证]")
        .replace(EMAIL, "[邮箱]")

    private fun parse(answer: String, goal: String, appPackage: String?): Skill {
        val text = answer
            .removePrefix("```markdown")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val lines = text.lines()
        val meta = mutableMapOf<String, String>()
        var bodyStart = 0
        if (lines.firstOrNull()?.trim() == "---") {
            bodyStart = lines.size
            for (index in 1 until lines.size) {
                val line = lines[index].trim()
                if (line == "---") {
                    bodyStart = index + 1
                    break
                }
                val colon = line.indexOf(':')
                if (colon > 0) meta[line.substring(0, colon).trim()] = line.substring(colon + 1).trim()
            }
        }
        val body = lines.drop(bodyStart).joinToString("\n").trim().ifBlank { text }
        val fallback = "learned_" + System.currentTimeMillis().toString(36)
        val name = meta["name"].orEmpty()
            .lowercase()
            .replace(Regex("[^a-z0-9_]+"), "_")
            .trim('_')
            .ifBlank { fallback }
            .take(48)
        return Skill(
            name = name,
            description = redact(meta["description"] ?: meta["title"] ?: goal).take(80),
            apps = meta["apps"].orEmpty()
                .split(',')
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .toSet()
                .ifEmpty { appPackage?.let { setOf(it) } ?: emptySet() },
            hint = redact(meta["hint"].orEmpty()),
            body = redact(body).take(MAX_BODY_CHARS),
            version = meta["version"]?.toIntOrNull() ?: 1,
            source = "learned",
        )
    }

    companion object {
        private const val MAX_PROMPT_CHARS = 12_000
        private const val MAX_BODY_CHARS = 6_000
        private val PHONE = Regex("(?<!\\d)1[3-9]\\d{9}(?!\\d)")
        private val ID_CARD = Regex("(?<!\\d)\\d{17}[0-9Xx](?!\\d)")
        private val EMAIL = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")

        val INSTRUCTIONS = """
你是银龄专线的技能提炼器。用户刚刚完成一次任务，系统将把这次记录交给家人查看。
请把记录总结成一个可复用的 skill.md，只输出 Markdown 内容，不要解释，不要代码围栏。

格式必须是：

---
name: 英文小写短名，例如 meituan_waimai_order
description: 一句中文说明
version: 1
apps: 应用包名，多个用逗号分隔；不确定就留空
source: learned
---

## 适用条件
...

## 流程
1. ...
2. ...

## 失败恢复
...

## 安全边界
到付款、发送、验证码、密码、提交订单等步骤必须交给本人，使用 ask_person，不允许自动执行。

要求：
- 不要写截图、坐标、控件编号、姓名、电话、地址、验证码、密码、金额或聊天内容。
- 步骤写“页面条件 + 动作意图”，不要写死位置。
- 信息不足就写保守的候选流程，不要编造没有发生的步骤。
""".trimIndent()
    }
}
