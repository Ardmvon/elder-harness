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
    val body: String,
)

object SkillCatalog {

    val skills: List<Skill> = listOf(
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
            name = "canvas_content",
            description = "要的信息不在页面文字里时（课表、图表、地图、图片文字）该怎么做",
            body = """
页面文字里找不到你要的信息，但这个页面看起来有图形内容（课表、图表、地图、海报、图片里的文字）时：
- 用 screenshot 把当前页面拍下来看清楚。图形里的内容通常只能在图里读到，文字列表里没有。
- 不要靠"点进一个格子再退出来"逐个探索：那样绕很久，而且每次都会回到原点。
- 看清之后再决定操作；如果截图也看不清，就说明做不到或用 handoff。
""".trimIndent(),
        ),
        Skill(
            name = "tool_errors",
            description = "工具报错（编号失效、找不到目标、控件不支持）时怎么办",
            body = """
工具报错说明你对页面的假设过期了。换办法，不要重复同一个调用：
- stale_screen / missing_target：编号来自上一次观察，页面已经变了。以最新页面为准重新选编号。
- ambiguous_target：同一段文字有多处匹配。错误信息里会列出候选编号，改用 click 指定其中一个。
- unsupported_action：这个控件不支持该动作（例如点到了外层容器）。改点子控件，或改用 tap_text。
- not_editable：选中的不是输入框。请选标记 [可输入] 的控件。
连续两次失败就换思路：滚动、搜索、返回重进，或者直接说明卡在哪里。
""".trimIndent(),
        ),
        Skill(
            name = "find_target",
            description = "要点的东西当前不在屏幕上时，怎么找最快",
            body = """
- 先找搜索入口。多数应用顶部或底部有搜索框，搜索比滚动列表快得多，也省步骤。
- 没有搜索再滚动：用 scroll 滚动容器，一次不要滚太远；内容区也可以 swipe。
- 不要忽略底部导航栏。某个栏目（如"我的""动态""关注"）始终没出现时，它通常在屏幕最底部。
- 先确认当前是不是正确的标签页或分类，再考虑滚动。目标是沉底的会话或商品时，搜索几乎总是更快。
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
            description = "微信里如何把文字填进输入框（它不接受程序写入和外部注入）",
            apps = setOf("com.tencent.mm"),
            body = """
微信不接受程序写入文字（input_text）也不接受外部注入（input text），但输入法可以帮忙：
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

    /**
     * Skills are listed once, in the [toolSpec] description: the model must read that schema to
     * call the tool at all. Listing them again in the system prompt only wasted fixed tokens.
     */
    fun summary(): String = ""

    /** A one-line nudge shown with the page when the current app has know-how available. */
    fun hintFor(app: String?): String {
        val relevant = skills.filter { app != null && app in it.apps }
        if (relevant.isEmpty()) return ""
        return "提示：当前应用有可用技巧 " + relevant.joinToString("、") { it.name } +
            "，遇到困难时可用 load_skill 查看。\n"
    }

    fun find(name: String): Skill? = skills.find { it.name == name }

    val toolSpec = AgentToolSpec(
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
