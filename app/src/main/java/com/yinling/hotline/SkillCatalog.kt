package com.yinling.hotline

import com.yinling.core.AgentToolSpec
import com.yinling.core.ToolCall
import com.yinling.core.ToolResult

/**
 * Optional know-how the model can pull in when it needs it.
 *
 * The prompt only carries the one-line catalog below. The detail is fetched through the
 * `load_skill` tool and lands in the transcript, so ordinary tasks never pay for app-specific
 * text, and adding knowledge for a new app means adding data here rather than growing the
 * system prompt. This keeps the core loop free of third-party app scripts.
 */
data class Skill(
    val name: String,
    val description: String,
    /** When the current package is one of these, the page shows a one-line pointer to the skill. */
    val apps: Set<String> = emptySet(),
    /** Shown in the page text when this app is in the foreground; empty falls back to a generic line. */
    val hint: String = "",
    val body: String,
    /** Generated skills use these; the six hand-written skills keep the defaults. */
    val version: Int = 1,
    val status: String = "active",
    val source: String = "manual",
)

object SkillCatalog {

    /**
     * Skills the model has earned from a verified run. [HotlineApp] refreshes this from [SkillStore]
     * after startup and after the family adopts or rolls back a candidate.
     */
    @Volatile
    var generated: List<Skill> = emptyList()

    private val builtin: List<Skill> = listOf(
        Skill(
            name = "reading_tables",
            description = "读表格、课表、账单这类格子内容时的方法（防止把相邻列的内容读成目标列）",
            body = """
从截图里读表格时，按这个顺序做：
1. 先找表头：哪一行是列名（日期、星期、商品、科目…），哪一列是行名（节次、时间、序号…）。
2. 再定位：把老人问的那一项换算成表头里的具体标签（例如"明天"=9月30日=周三），确认它在第几列。
3. 逐条读：每条都写成"列头 + 行 + 内容"，例如"周三 第1-2节 离散数学"。
4. 自检：把这一列的条目连起来看，如果中间的列头跳变、或者目标列看着是空的而相邻列有内容，
   就说明列数错了，重新数一遍再答。
5. 拿不准就说拿不准：宁可回答"这一列我看不太清"，也不要把相邻列的内容算进来。
""".trimIndent(),
        ),
        Skill(
            name = "blind_page",
            description = "页面读不到任何控件时（如微信、银行类应用）如何观察和操作",
            // Known blind apps: the page hint then names this skill while the person is in one.
            apps = setOf("com.tencent.mm"),
            body = """
这类应用屏蔽了无障碍读取，控件列表是空的，只能靠截图。系统会自动附上截图。
- 点按用 tap_xy，x、y 是屏幕比例：左上角 0,0，右下角 1,1。
- 不要用 click、tap_text、input_text、scroll —— 它们都需要控件编号，在这里必然失败。
- 需要滑动时用 swipe，坐标是像素。
- 如果截图连续失败，你实际上看不到页面：不要再猜位置，用 ask_person 请老人自己操作，或 handoff 交给家人。
""".trimIndent(),
        ),
        Skill(
            name = "wechat_input",
            hint = "微信里不要试图点屏幕键盘打字（点不准、也打不进去）：先点一下输入框，再用 paste_text 粘贴。" +
                "详细步骤见 wechat_input 技巧。",
            description = "微信里如何把文字填进输入框（它不接受程序写入和外部注入）",
            apps = setOf("com.tencent.mm"),
            body = """
微信不接受程序写入文字（input_text）也不接受外部注入（input text），但输入法可以帮忙：
0. 截图上有 **10% 主刻度 + 5% 辅助网格**（横竖红线标着 0.1~0.9），用它定位，不要靠目测：
   - 没有键盘时，输入框是屏幕**最底部那条细长框**（约 y=0.95）。
   - **键盘弹出后输入框会被顶到键盘上方**（约 y=0.55~0.6），之前算的坐标立刻失效，要重新截图。
   - 不要点键盘上的字母键找字——键盘又小又密，点不准；输入法候选栏才是要点的目标。
1. 用 tap_xy 点中底部输入框，让键盘弹出。
2. 调用 paste_text 把文字写进系统剪贴板。它很可能返回"不接受程序粘贴"，这不影响下一步。
3. 输入法通常把剪贴板内容显示为**键盘上方候选栏的第一项**。用 tap_xy 点那一栏靠左的第一项，
   文字就进入输入框了。

输入框里已有旧文字时先清空：用 tap_xy 点键盘右下角的删除键（退格），每点一次删一个字，
连续点是允许的；也可以长按输入框选"全选"再删除。

如果点候选栏没有效果，说明这台手机的输入法不支持这个做法：不要继续试，也不要一个键一个键地
敲屏幕键盘，用 ask_person 请老人自己输入。发送消息同理，由老人自己完成。
""".trimIndent(),
        ),
    )

    /** Built-in knowledge plus generated skills the family has adopted. */
    val skills: List<Skill>
        get() = builtin + generated

    /**
     * Skills are listed once, in the [toolSpec] description: the model must read that schema to
     * call the tool at all. Listing them again in the system prompt only wasted fixed tokens.
     */
    fun summary(): String = ""

    /**
     * A one-line nudge shown with the page when the current app has know-how available. A skill may
     * supply its own wording: "there is a skill" was too weak to stop the model from trying the
     * on-screen keyboard in WeChat first and only reaching for the clipboard much later.
     */
    fun hintFor(app: String?): String {
        val relevant = skills.filter { app != null && app in it.apps }
        if (relevant.isEmpty()) return ""
        relevant.firstOrNull { it.hint.isNotBlank() }?.let { return "提示：${it.hint}" }
        return "提示：当前应用有可用技巧 " + relevant.joinToString("、") { it.name } +
            "，遇到困难时可用 load_skill 查看。\n"
    }

    fun find(name: String): Skill? = skills.find { it.name == name }

    val toolSpec: AgentToolSpec
        get() = AgentToolSpec(
            // A pure lookup: asking again returns the same text, which is never progress.
            informational = true,
            name = "load_skill",
            description = "读取某个经验技巧的详细步骤。包含：" +
                skills.joinToString("；") { "${it.name}（${it.description}）" },
            parameters = listOf(
                // Named "argument" on purpose: the loop only forwards a fixed set of argument names
                // (argument/target/text/...), so a parameter called "name" would arrive empty.
                AgentToolSpec.ToolParam("argument", "string", "技巧名称，见本工具说明", required = true),
            ),
        )

    /** @return the skill body, or a failure explaining what names exist. */
    fun load(call: ToolCall): ToolResult {
        val skill = find(call.argument) ?: return ToolResult(
            false,
            "没有名为“${call.argument}”的技巧。可用：" + skills.joinToString("、") { it.name },
            "unknown_skill",
        )
        return ToolResult(true, skill.body)
    }
}
