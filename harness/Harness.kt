import com.yinling.core.*
import com.yinling.hotline.CloudPlanner
import com.yinling.hotline.ModelConfig
import kotlinx.coroutines.runBlocking

/** Fake phone whose page changes only after tap_text runs. */
private class FakePhone : AgentTools {
    var tapped = false
    var scrolled = false
    val ran = mutableListOf<String>()
    var revision = "rev-1"

    override val catalog = PhoneToolCatalog.specs

    override suspend fun observe(): ScreenSnapshot = ScreenSnapshot(
        app = "com.mock.app",
        labels = if (tapped) listOf("第二步", "提交") else listOf("下一步", "取消"),
        revision = revision,
        elements = listOf(
            ScreenElement("0.1", if (tapped) "第二步" else "下一步", "", "Button", listOf(0, 0, 100, 50), true, false, false, false, true),
            ScreenElement("0.2", "提交", "", "Button", listOf(0, 60, 100, 110), true, false, false, false, true),
            ScreenElement("0.5", "列表", "", "ScrollView", listOf(0, 120, 100, 400), false, false, false, true, true),
        ),
    )

    override suspend fun execute(call: ToolCall): ToolResult {
        ran += call.name
        val changed = when (call.name) {
            "tap_text" -> { tapped = true; revision = "rev-2"; true }
            "scroll" -> { scrolled = true; true }
            else -> false
        }
        return ToolResult(true, "已执行${call.name}", screenChanged = changed)
    }
}

private fun planner(): CloudPlanner =
    CloudPlanner(ModelConfig("http://127.0.0.1:8731", "mock-model", "test-key", visionEnabled = false))

private fun retryPlanner(): CloudPlanner =
    CloudPlanner(ModelConfig("http://127.0.0.1:8731", "test-mode:retry", "test-key", visionEnabled = false))

/** A provider that looks at the page and then claims a send it never performed. */
private fun claimPlanner(): CloudPlanner =
    CloudPlanner(ModelConfig("http://127.0.0.1:8731", "test-mode:claim", "test-key", visionEnabled = false))

private class Logging : AgentHook {
    val messages = mutableListOf<String>()
    override fun onMessage(display: String) { messages += display; println("  [msg] $display") }
    override fun onAction(display: String) { println("  [action] $display") }
    override fun onWarning(display: String) { println("  [warn] $display") }
    override fun onRetry(attempt: Int, ofTotal: Int, message: String) { println("  [retry] $attempt/$ofTotal $message") }
    override fun onApprovalRequest(display: String) { println("  [ask] $display") }
}

private fun header(t: String) = println("\n===== $t =====")

fun main() = runBlocking {
    var failures = 0
    var sawCatalogue = false
    fun check(name: String, ok: Boolean) {
        println((if (ok) "  PASS " else "  FAIL ") + name)
        if (!ok) failures++
    }

    // 1) multi-call step, approval resumed in place, natural completion
    header("多工具调用 + 批准 + 自然结束")
    run {
        val phone = FakePhone()
        val hook = Logging()
        var approvals = 0
        val loop = AgentLoop(planner(), phone, object : ActionApproval {
            override suspend fun confirm(invocation: ToolInvocation): Boolean {
                approvals++
                println("  [human] 批准 ${invocation.tool}")
                return true
            }
        }, CloudPlanner.INSTRUCTIONS, hook, renderScreen = { PhoneToolCatalog.render(it) })

        val outcome = loop.start("帮我走到第二步")
        println("  outcome=${outcome::class.simpleName} msg=${outcome.message} steps=${loop.stepCount}")
        check("任务完成", outcome is AgentOutcome.COMPLETED)
        check("同一步执行了两个工具", phone.ran.containsAll(listOf("tap_text", "scroll")))
        check("页面真的被点到了第二步", phone.tapped)
        check("同批操作只问一次确认", approvals == 1)
        check("对话记录包含工具结果", loop.conversation.count { it.role == AgentMessage.Role.TOOL } == 3)
        val pages = loop.conversation.count { it.role == AgentMessage.Role.USER && it.content.contains("当前页面：") }
        val unchanged = loop.conversation.count { it.role == AgentMessage.Role.USER && it.content.contains("页面没有变化") }
        println("  [debug] 完整页面=$pages 未变化提示=$unchanged")
        check("页面有变化时注入完整控件列表", pages >= 2)
        check("页面未变化时不重复整页", unchanged >= 1)
        check("最终说明来自模型", hook.messages.any { it.contains("办好了") })
    }

    // 2) provider failure is retried, not reported as failure
    header("服务端 503 自动重试")
    run {
        val phone = FakePhone()
        val hook = Logging()
        val loop = AgentLoop(retryPlanner(), phone, object : ActionApproval {
            override suspend fun confirm(invocation: ToolInvocation) = true
        }, CloudPlanner.INSTRUCTIONS, hook, renderScreen = { PhoneToolCatalog.render(it) })
        val outcome = loop.start("重试一次")
        println("  outcome=${outcome::class.simpleName}")
        check("重试后仍然完成任务", outcome is AgentOutcome.COMPLETED)
    }

    // 3) an unknown tool is a mistake to correct, not an attack: report the catalogue back
    header("未知工具 → 回传清单，可修复")
    run {
        var executed = false
        val phone = object : AgentTools {
            override val catalog = PhoneToolCatalog.specs
            // A page with real controls: an empty page would additionally trigger the
            // automatic screenshot for blind pages and confuse this test.
            override suspend fun observe() = ScreenSnapshot(
                app = "com.mock.app",
                labels = listOf("转账"),
                revision = "r",
                elements = listOf(
                    ScreenElement("e1", "转账", "", "Button", listOf(0, 0, 100, 50), true, false, false, false, true),
                ),
            )
            override suspend fun execute(call: ToolCall): ToolResult {
                executed = true
                return ToolResult(true, "不应执行")
            }
        }
        // Keeps asking for a tool that does not exist.
        val planner = object : AgentPlanner {
            override suspend fun decide(
                instructions: String, tools: List<AgentToolSpec>, transcript: List<AgentMessage>,
            ): AgentStep {
                val told = transcript.any { it.role == AgentMessage.Role.TOOL && it.content.contains("可用的工具是") }
                if (told) sawCatalogue = true
                return AgentStep.Calls(listOf(ToolInvocation("x1", "send_money", mapOf("amount" to "100"))))
            }
        }
        val loop = AgentLoop(planner, phone, object : ActionApproval {
            override suspend fun confirm(invocation: ToolInvocation) = true
        }, CloudPlanner.INSTRUCTIONS, Logging(), renderScreen = { PhoneToolCatalog.render(it) })
        val outcome = loop.start("转账")
        check("未知工具不再立刻交家人", outcome !is AgentOutcome.FAMILY)
        check("未知工具没有被执行", !executed)
        check("模型收到了真实工具清单", sawCatalogue)
        check("反复无效后停下", outcome is AgentOutcome.PAUSED)
    }

    // 4) explicit handoff is a legitimate decision
    header("模型主动 handoff → 交家人")
    run {
        val phone = FakePhone()
        val planner = object : AgentPlanner {
            override suspend fun decide(
                instructions: String, tools: List<AgentToolSpec>, transcript: List<AgentMessage>,
            ) = AgentStep.Calls(listOf(ToolInvocation("h1", "handoff", mapOf("reason" to "需要家人确认身份"))))
        }
        val loop = AgentLoop(planner, phone, object : ActionApproval {
            override suspend fun confirm(invocation: ToolInvocation) = true
        }, CloudPlanner.INSTRUCTIONS, Logging(), renderScreen = { PhoneToolCatalog.render(it) })
        val outcome = loop.start("办理")
        check("handoff 变成交家人", outcome is AgentOutcome.FAMILY)
        check("handoff 原因传达给用户", outcome.message.contains("家人确认身份"))
    }

    // 5) repeating a pure lookup must not loop until the step limit
    header("纯数据工具重复读取会被拦住")
    run {
        var calls = 0
        val phone = object : AgentTools {
            override val catalog = listOf(
                AgentToolSpec(
                    name = "load_skill", description = "读技巧",
                    parameters = listOf(AgentToolSpec.ToolParam("name", "string", "名称")),
                    informational = true,
                ),
            )
            override suspend fun observe() = ScreenSnapshot(
                app = "com.mock.app", labels = listOf("x"), revision = "r",
                elements = listOf(ScreenElement("e1", "x", "", "Button", listOf(0, 0, 1, 1), true, false, false, false, true)),
            )
            override suspend fun execute(call: ToolCall): ToolResult {
                calls++
                return ToolResult(true, "技巧正文")
            }
        }
        val planner = object : AgentPlanner {
            override suspend fun decide(instructions: String, tools: List<AgentToolSpec>, transcript: List<AgentMessage>) =
                AgentStep.Calls(listOf(ToolInvocation("k1", "load_skill", mapOf("name" to "blind_page"))))
        }
        val loop = AgentLoop(planner, phone, object : ActionApproval {
            override suspend fun confirm(invocation: ToolInvocation) = true
        }, CloudPlanner.INSTRUCTIONS, Logging(), renderScreen = { PhoneToolCatalog.render(it) })
        val outcome = loop.start("看技巧")
        check("重复读取只执行一次", calls == 1)
        check("不会跑到步数上限", outcome is AgentOutcome.PAUSED)
    }

    // 6) "cannot be done" must never look like a completion
    header("impossible → 明确做不到，而不是已完成")
    run {
        val phone = FakePhone()
        val planner = object : AgentPlanner {
            override suspend fun decide(
                instructions: String, tools: List<AgentToolSpec>, transcript: List<AgentMessage>,
            ) = AgentStep.Calls(listOf(ToolInvocation("i1", "impossible", mapOf("reason" to "文件传输助手不支持转账"))))
        }
        val loop = AgentLoop(planner, phone, object : ActionApproval {
            override suspend fun confirm(invocation: ToolInvocation) = true
        }, CloudPlanner.INSTRUCTIONS, Logging(), renderScreen = { PhoneToolCatalog.render(it) })
        val outcome = loop.start("转账")
        check("不会被当成已完成", outcome !is AgentOutcome.COMPLETED)
        check("结果是做不到", outcome is AgentOutcome.IMPOSSIBLE)
        check("原因被保留", outcome.message.contains("不支持转账"))
    }

    // 7) "you do this step" and "answer me" are distinct from "hand the task to family"
    header("ask_person / ask_user 是独立收尾")
    run {
        fun loopFor(tool: String, key: String, value: String) = AgentLoop(
            object : AgentPlanner {
                override suspend fun decide(
                    instructions: String, tools: List<AgentToolSpec>, transcript: List<AgentMessage>,
                ) = AgentStep.Calls(listOf(ToolInvocation("c1", tool, mapOf(key to value))))
            },
            FakePhone(),
            object : ActionApproval { override suspend fun confirm(invocation: ToolInvocation) = true },
            CloudPlanner.INSTRUCTIONS, Logging(), renderScreen = { PhoneToolCatalog.render(it) },
        )
        val person = loopFor("ask_person", "reason", "请点一下发送按钮").start("发消息")
        check("ask_person 不再被报成找家人", person !is AgentOutcome.FAMILY)
        check("ask_person 是请老人自己操作", person is AgentOutcome.NEEDS_PERSON)
        val asking = loopFor("ask_user", "question", "您想吃什么").start("点外卖")
        check("ask_user 不再被报成已完成", asking !is AgentOutcome.COMPLETED)
        check("ask_user 是提问等待", asking is AgentOutcome.ASKING)

        // Options travel with the question so the person can answer with one tap.
        val withOptions = AgentLoop(
            object : AgentPlanner {
                override suspend fun decide(
                    instructions: String, tools: List<AgentToolSpec>, transcript: List<AgentMessage>,
                ) = AgentStep.Calls(listOf(ToolInvocation("c2", "ask_user", mapOf(
                    "question" to "按默认的来吗",
                    "options" to "按默认|换别的| |按默认",
                ))))
            },
            FakePhone(),
            object : ActionApproval { override suspend fun confirm(invocation: ToolInvocation) = true },
            CloudPlanner.INSTRUCTIONS, Logging(), renderScreen = { PhoneToolCatalog.render(it) },
        ).start("点外卖")
        check("ask_user 带回选项", withOptions is AgentOutcome.ASKING)
        check("选项已去重去空", (withOptions as? AgentOutcome.ASKING)?.options == listOf("按默认", "换别的"))
    }

    // 8) the person's answer reaches the model and the task continues
    header("回答后继续")
    run {
        val seen = mutableListOf<List<AgentMessage>>()
        val planner = object : AgentPlanner {
            override suspend fun decide(
                instructions: String, tools: List<AgentToolSpec>, transcript: List<AgentMessage>,
            ): AgentStep {
                seen += transcript
                val answered = transcript.any { it.content.contains("老人的回答：") }
                return if (answered) AgentStep.Final("好的，我去找宫保鸡丁")
                else AgentStep.Calls(listOf(ToolInvocation("q1", "ask_user", mapOf("question" to "想吃什么"))))
            }
        }
        val loop = AgentLoop(planner, FakePhone(),
            object : ActionApproval { override suspend fun confirm(invocation: ToolInvocation) = true },
            CloudPlanner.INSTRUCTIONS, Logging(), renderScreen = { PhoneToolCatalog.render(it) })
        val first = loop.start("点外卖")
        check("先提问并停下", first is AgentOutcome.ASKING)
        val second = loop.answer("宫保鸡丁")
        check("回答进入了对话", seen.last().any { it.content.contains("老人的回答：宫保鸡丁") })
        check("回答后任务能继续", second is AgentOutcome.COMPLETED)
    }

    // 9) sliders must reach the model with their affordance and position
    header("滑动条会被呈现给模型")
    run {
        val screen = ScreenSnapshot(
            app = "com.android.settings", labels = listOf("字体大小"), revision = "r",
            elements = listOf(
                ScreenElement("e25", "字体大小", "", "SeekBar", listOf(0, 0, 100, 50),
                    false, false, false, false, true, null, 2, 0, 4),
                ScreenElement("e26", "默认", "", "TextView", listOf(0, 60, 100, 90),
                    true, false, false, false, true),
            ),
        )
        val rendered = PhoneToolCatalog.render(screen)
        println("  [debug] " + rendered.lines().take(3).joinToString(" | "))
        check("滑动条被列出", rendered.contains("滑动条"))
        check("说明了当前档位", rendered.contains("第3/5"))
        check("说明了怎么调", rendered.contains("scroll down 调大"))
    }

    // 10) an icon-only control that nothing else describes must still be listed
    header("无标签的可操作叶子会被列出（带位置）")
    run {
        val screen = ScreenSnapshot(
            app = "com.mock.app", labels = emptyList(), revision = "r",
            elements = listOf(
                // clickable container: its children carry the labels, so it stays hidden
                ScreenElement("e1", "", "", "LinearLayout", listOf(0, 0, 200, 200),
                    true, false, false, false, true, null),
                ScreenElement("e2", "", "", "Button", listOf(10, 10, 100, 60),
                    true, false, false, false, true, "e1"),
                ScreenElement("e9", "确定", "", "Button", listOf(0, 300, 100, 350),
                    true, false, false, false, true, "e1"),
            ),
        )
        val rendered = PhoneToolCatalog.render(screen)
        println("  [debug] " + rendered.lines().filter { it.startsWith("[") }.joinToString(" | "))
        check(
            "键盘控件单独列出，且不把页面误判成图像页",
            run {
                // Eight keyboard keys, most of them unlabelled: exactly what used to be counted as
                // "content is in the picture" and hidden the keyboard listing.
                val keys = (0 until 8).map { index ->
                    ScreenElement(
                        "k$index", if (index == 3) "我" else "", "", "Key", listOf(index * 10, 2000, index * 10 + 9, 2100),
                        true, false, false, false, true,
                    )
                }
                val page = ScreenSnapshot(
                    app = "微信", labels = emptyList(), revision = "r",
                    elements = listOf(
                        ScreenElement("e0", "文件传输助手", "", "TextView", listOf(0, 0, 500, 100),
                            true, false, false, false, true),
                    ) + keys,
                )
                val rendered = PhoneToolCatalog.render(page)
                rendered.contains("输入法键盘") && rendered.contains("[k3]") &&
                    !rendered.contains("内容多半在图像里")
            },
        )
        check("无标签叶子按钮被列出", rendered.contains("[e2]") && rendered.contains("未命名按钮"))
        check("带上了位置", rendered.contains("位置(10,10)-(100,60)"))
        check("有子节点的容器仍隐藏", !rendered.contains("[e1] "))
        check("有标签的按钮照常显示", rendered.contains("[e9] 确定"))
    }

    // 11) cycling between two pages is not progress either
    header("在两个页面之间来回切换会被停下")
    run {
        var executed = 0
        val phone = object : AgentTools {
            override val catalog = PhoneToolCatalog.specs
            // Two pages alternating, like "open detail -> back -> open detail".
            override suspend fun observe() = ScreenSnapshot(
                app = "com.mock.app", labels = listOf("x"), revision = "rev-" + (executed % 2),
                elements = listOf(ScreenElement("e1", "进去", "", "Button", listOf(0, 0, 100, 50),
                    true, false, false, false, true)),
            )
            override suspend fun execute(call: ToolCall): ToolResult {
                executed++
                return ToolResult(true, "已执行", screenChanged = true)
            }
        }
        val planner = object : AgentPlanner {
            override suspend fun decide(
                instructions: String, tools: List<AgentToolSpec>, transcript: List<AgentMessage>,
            ): AgentStep {
                val step = transcript.count { it.role == AgentMessage.Role.TOOL }
                return if (step % 2 == 0) {
                    AgentStep.Calls(listOf(ToolInvocation("enter$step", "tap_text", mapOf("argument" to "进去"))))
                } else {
                    AgentStep.Calls(listOf(ToolInvocation("back$step", "back", emptyMap())))
                }
            }
        }
        val loop = AgentLoop(planner, phone, object : ActionApproval {
            override suspend fun confirm(invocation: ToolInvocation) = true
        }, CloudPlanner.INSTRUCTIONS, Logging(), renderScreen = { PhoneToolCatalog.render(it) })
        val outcome = loop.start("看课表")
        check("不是跑到步数上限", outcome !is AgentOutcome.STEP_LIMIT)
        check("被判定为卡住", outcome is AgentOutcome.STUCK)
        check("很快停下（<=12 步）", executed <= 12)
        println("  [debug] steps=$executed outcome=${outcome::class.simpleName}")
    }

    // 12) a screenshot the model asked for must actually reach the next plan
    header("显式截图会被送到下一轮请求")
    run {
        var sawImage: String? = null
        var shots = 0
        val phone = object : AgentTools {
            override val catalog = PhoneToolCatalog.specs
            override suspend fun observe() = ScreenSnapshot(
                app = "com.mock.app", labels = listOf("我的课表"), revision = "rev-1",
                elements = listOf(ScreenElement("e1", "进去", "", "Button", listOf(0, 0, 100, 50),
                    true, false, false, false, true)),
            )
            override suspend fun execute(call: ToolCall): ToolResult {
                if (call.name == "screenshot") {
                    shots++
                    return ToolResult(true, "已截图", image = ScreenImage("SHOT", "rev-1"))
                }
                return ToolResult(true, "ok", screenChanged = true)
            }
        }
        val planner = object : AgentPlanner {
            override suspend fun decide(
                instructions: String, tools: List<AgentToolSpec>, transcript: List<AgentMessage>,
            ): AgentStep {
                if (shots == 0) return AgentStep.Calls(listOf(ToolInvocation("s1", "screenshot", emptyMap())))
                sawImage = transcript.lastOrNull()?.image?.base64
                return AgentStep.Final("看完了")
            }
        }
        val loop = AgentLoop(planner, phone, object : ActionApproval {
            override suspend fun confirm(invocation: ToolInvocation) = true
        }, CloudPlanner.INSTRUCTIONS, Logging(), renderScreen = { PhoneToolCatalog.render(it) })
        loop.start("看课表")
        check("截图确实发出去了", shots == 1)
        check("截图到达下一轮的观察消息", sawImage == "SHOT")
    }

    // 13) fetching facts must not be mistaken for lack of progress
    header("连续查阅不会被误判为停滞")
    run {
        var fetched = 0
        val catalog = (1..5).map { AgentToolSpec("info$it", "查资料", informational = true) }
        val phone = object : AgentTools {
            override val catalog = catalog
            override suspend fun observe() = ScreenSnapshot(
                app = "com.mock.app", labels = listOf("x"), revision = "rev-1",
                elements = listOf(ScreenElement("e1", "进去", "", "Button", listOf(0, 0, 100, 50),
                    true, false, false, false, true)),
            )
            override suspend fun execute(call: ToolCall): ToolResult {
                fetched++
                return ToolResult(true, "资料内容")
            }
        }
        val planner = object : AgentPlanner {
            override suspend fun decide(
                instructions: String, tools: List<AgentToolSpec>, transcript: List<AgentMessage>,
            ): AgentStep {
                val done = transcript.count { it.role == AgentMessage.Role.TOOL }
                return if (done < 5) AgentStep.Calls(listOf(ToolInvocation("i$done", "info${done + 1}", emptyMap())))
                else AgentStep.Final("看完了")
            }
        }
        val loop = AgentLoop(planner, phone, object : ActionApproval {
            override suspend fun confirm(invocation: ToolInvocation) = true
        }, CloudPlanner.INSTRUCTIONS, Logging(), renderScreen = { PhoneToolCatalog.render(it) })
        val outcome = loop.start("查资料")
        println("  [debug] fetched=$fetched outcome=${outcome::class.simpleName}")
        check("执行了 5 次查阅", fetched == 5)
        check("查阅不会被当成停滞", outcome is AgentOutcome.COMPLETED)
    }

    // 14) "enter -> back -> enter -> back" must stop even though every page differs
    header("进入→退出的循环会被停下")
    run {
        var steps = 0
        val phone = object : AgentTools {
            override val catalog = PhoneToolCatalog.specs
            // The rendered text must change every step, otherwise the page-fingerprint checks
            // (frozen/cycling) stop the run and this test would not exercise the cycle detector.
            override suspend fun observe() = ScreenSnapshot(
                app = "com.mock.app", labels = listOf("x"), revision = "r" + steps,
                elements = listOf(
                    ScreenElement("e1", "进去", "", "Button", listOf(0, 0, 100, 50), true, false, false, false, true),
                    ScreenElement("e2", "第" + steps + "屏", "", "TextView", listOf(0, 60, 100, 90), false, false, false, false, true),
                ),
            )
            override suspend fun execute(call: ToolCall): ToolResult {
                steps++
                return ToolResult(true, "ok", screenChanged = true)
            }
        }
        val planner = object : AgentPlanner {
            override suspend fun decide(
                instructions: String, tools: List<AgentToolSpec>, transcript: List<AgentMessage>,
            ): AgentStep {
                val k = transcript.count { it.role == AgentMessage.Role.TOOL }
                return if (k % 2 == 0)
                    AgentStep.Calls(listOf(ToolInvocation("a$k", "tap_text", mapOf("argument" to "进去"))))
                else
                    AgentStep.Calls(listOf(ToolInvocation("b$k", "back", emptyMap())))
            }
        }
        val loop = AgentLoop(planner, phone, object : ActionApproval {
            override suspend fun confirm(invocation: ToolInvocation) = true
        }, CloudPlanner.INSTRUCTIONS, Logging(), renderScreen = { PhoneToolCatalog.render(it) })
        val outcome = loop.start("看课表")
        println("  [debug] steps=$steps outcome=${outcome::class.simpleName}")
        check("进入退出循环被停下", outcome is AgentOutcome.STUCK)
        check("没有跑到步数上限", steps < 20)
    }

    // 15) repeating ONE action is legitimate (holding backspace) and must not be called a cycle
    header("重复单一动作不会被当成循环")
    run {
        var steps = 0
        val phone = object : AgentTools {
            override val catalog = PhoneToolCatalog.specs
            override suspend fun observe() = ScreenSnapshot(
                app = "com.mock.app", labels = listOf("x"), revision = "r" + steps,
                elements = listOf(
                    ScreenElement("e1", "删", "", "Button", listOf(0, 0, 100, 50), true, false, false, false, true),
                    ScreenElement("e2", "第" + steps + "屏", "", "TextView", listOf(0, 60, 100, 90), false, false, false, false, true),
                ),
            )
            override suspend fun execute(call: ToolCall): ToolResult {
                steps++
                return ToolResult(true, "ok", screenChanged = true)
            }
        }
        val planner = object : AgentPlanner {
            override suspend fun decide(
                instructions: String, tools: List<AgentToolSpec>, transcript: List<AgentMessage>,
            ) = AgentStep.Calls(listOf(ToolInvocation("s", "click", mapOf("target" to "e1"))))
        }
        val loop = AgentLoop(planner, phone, object : ActionApproval {
            override suspend fun confirm(invocation: ToolInvocation) = true
        }, CloudPlanner.INSTRUCTIONS, Logging(), renderScreen = { PhoneToolCatalog.render(it) })
        val outcome = loop.start("删字")
        println("  [debug] steps=$steps outcome=${outcome::class.simpleName}")
        check("单一重复动作不被判为循环", outcome is AgentOutcome.STEP_LIMIT)
    }

    // 16) periodic work is legitimate: filling three fields in a row must still finish
    header("逐个填表的周期性操作不会被拦下")
    run {
        var steps = 0
        val phone = object : AgentTools {
            override val catalog = PhoneToolCatalog.specs
            override suspend fun observe() = ScreenSnapshot(
                app = "com.mock.app", labels = listOf("x"), revision = "r" + steps,
                elements = listOf(
                    ScreenElement("e1", "姓名", "", "EditText", listOf(0, 0, 100, 50),
                        true, false, true, false, true),
                    ScreenElement("e2", "第" + steps + "屏", "", "TextView", listOf(0, 60, 100, 90),
                        false, false, false, false, true),
                ),
            )
            override suspend fun execute(call: ToolCall): ToolResult {
                steps++
                return ToolResult(true, "ok", screenChanged = true)
            }
        }
        val planner = object : AgentPlanner {
            override suspend fun decide(
                instructions: String, tools: List<AgentToolSpec>, transcript: List<AgentMessage>,
            ): AgentStep {
                val k = transcript.count { it.role == AgentMessage.Role.TOOL }
                if (k >= 6) return AgentStep.Final("三个字段都填好了")
                return if (k % 2 == 0)
                    AgentStep.Calls(listOf(ToolInvocation("t$k", "click", mapOf("target" to "e1"))))
                else
                    AgentStep.Calls(listOf(ToolInvocation("y$k", "input_text", mapOf("target" to "e1", "text" to "张"))))
            }
        }
        val loop = AgentLoop(planner, phone, object : ActionApproval {
            override suspend fun confirm(invocation: ToolInvocation) = true
        }, CloudPlanner.INSTRUCTIONS, Logging(), renderScreen = { PhoneToolCatalog.render(it) })
        val outcome = loop.start("填表")
        println("  [debug] steps=$steps outcome=${outcome::class.simpleName}")
        check("周期性的填表工作能正常结束", outcome is AgentOutcome.COMPLETED)
    }

    // 17) the host can narrow what interrupts the person
    header("宿主缩小确认范围后不再逐步打扰")
    run {
        var confirms = 0
        var executed = 0
        val phone = object : AgentTools {
            override val catalog = PhoneToolCatalog.specs
            override suspend fun observe() = ScreenSnapshot(
                app = "com.mock.app", labels = listOf("x"), revision = "r1",
                elements = listOf(ScreenElement("e1", "进去", "", "Button", listOf(0, 0, 100, 50),
                    true, false, false, false, true)),
            )
            override suspend fun execute(call: ToolCall): ToolResult {
                executed++
                return ToolResult(true, "已执行", screenChanged = true)
            }
        }
        val planner = object : AgentPlanner {
            private var done = false
            override suspend fun decide(
                instructions: String, tools: List<AgentToolSpec>, transcript: List<AgentMessage>,
            ): AgentStep {
                if (done) return AgentStep.Final("好了")
                done = true
                return AgentStep.Calls(listOf(ToolInvocation("t1", "tap_xy", mapOf("x" to "0.5", "y" to "0.5"))))
            }
        }
        val loop = AgentLoop(planner, phone, object : ActionApproval {
            // tap_xy carries needsApproval, but this host decides it is not worth asking about.
            override suspend fun needed(invocation: ToolInvocation) = false
            override suspend fun confirm(invocation: ToolInvocation): Boolean {
                confirms++
                return true
            }
        }, CloudPlanner.INSTRUCTIONS, Logging(), renderScreen = { PhoneToolCatalog.render(it) })
        val outcome = loop.start("点一下")
        println("  [debug] confirms=$confirms executed=$executed outcome=${outcome::class.simpleName}")
        check("没有打扰老人", confirms == 0)
        check("动作仍然执行了", executed == 1)
    }

    // 18) a step only the person may do must be flagged, so the panel can say so
    header("需要老人自己做的步骤会带标记")
    run {
        val phone = object : AgentTools {
            override val catalog = PhoneToolCatalog.specs
            override suspend fun observe() = ScreenSnapshot(
                app = "com.mock.app", labels = listOf("x"), revision = "r1",
                elements = listOf(ScreenElement("e1", "去结算", "", "Button", listOf(0, 0, 100, 50),
                    true, false, false, false, true)),
            )
            override suspend fun execute(call: ToolCall) =
                ToolResult(false, "这一步需要您亲自确认并操作，完成后可以接着办。", "requires_user")
        }
        val planner = object : AgentPlanner {
            override suspend fun decide(
                instructions: String, tools: List<AgentToolSpec>, transcript: List<AgentMessage>,
            ) = AgentStep.Calls(listOf(ToolInvocation("t1", "click", mapOf("target" to "e1"))))
        }
        val loop = AgentLoop(planner, phone, object : ActionApproval {
            override suspend fun needed(invocation: ToolInvocation) = false
            override suspend fun confirm(invocation: ToolInvocation) = true
        }, CloudPlanner.INSTRUCTIONS, Logging(), renderScreen = { PhoneToolCatalog.render(it) })
        val outcome = loop.start("买点东西")
        val paused = outcome as? AgentOutcome.PAUSED
        println("  [debug] outcome=${outcome::class.simpleName} needsPerson=${paused?.needsPerson}")
        check("判定为暂停", paused != null)
        check("标记了需要老人自己做", paused?.needsPerson == true)
    }

    // 19) peace-of-mind watch: never cry wolf, never judge while blind
    header("平安确认的判定")
    run {
        val today = java.time.LocalDate.of(2026, 9, 29)
        val settings = PeaceSettings(
            enabled = true, graceMinutes = 90, earliestMinuteOfDay = 8 * 60,
            minHistoryDays = 3, who = "妈妈",
        )
        // 平时七点出头开始用手机
        val history = mapOf(
            today.minusDays(1) to 7 * 60 + 10,
            today.minusDays(2) to 7 * 60 - 5,
            today.minusDays(3) to 7 * 60 + 20,
        )
        check(
            "未开启时不动",
            decidePeace(today, 11 * 60, history, null, settings.copy(enabled = false)) is PeaceDecision.Idle,
        )
        check(
            "没在看时不判定（否则会误报）",
            decidePeace(today, 11 * 60, history, null, settings, watching = false) is PeaceDecision.NotWatching,
        )
        check(
            "今天用过、还没到报平安时间，就先安静",
            decidePeace(today, 8 * 60, history + (today to 6 * 60 + 50), null, settings) is PeaceDecision.Active,
        )
        check(
            "还没到点就等着",
            decidePeace(today, 8 * 60, history, null, settings) is PeaceDecision.Waiting,
        )
        val alert = decidePeace(today, 11 * 60, history, null, settings)
        check("过了宽限期才提醒", alert is PeaceDecision.Alert)
        check("提醒里带上称呼", (alert as? PeaceDecision.Alert)?.message?.contains("妈妈") == true)
        check(
            "同一天只提醒一次",
            decidePeace(today, 12 * 60, history, today.toEpochDay(), settings) is PeaceDecision.AlreadyTold,
        )
        check(
            "历史不足时不下判断",
            decidePeace(today, 12 * 60, mapOf(today.minusDays(1) to 7 * 60), null, settings) is PeaceDecision.NoBaseline,
        )
        val usedToday = history + (today to 6 * 60 + 40)
        check(
            "到了报平安时间就发一条“今天正常”",
            run {
                val d = decidePeace(today, 9 * 60 + 5, usedToday, null, settings)
                d is PeaceDecision.DailyOk && d.message.contains("06:40") && d.message.contains("妈妈")
            },
        )
        check(
            "没到报平安时间先不发",
            decidePeace(today, 8 * 60, usedToday, null, settings) is PeaceDecision.Active,
        )
        check(
            "报平安一天只发一条",
            decidePeace(today, 11 * 60, usedToday, today.toEpochDay(), settings) is PeaceDecision.Active,
        )
        check(
            "发过报平安后不再补发异常提醒",
            decidePeace(today, 13 * 60, history, today.toEpochDay(), settings) is PeaceDecision.AlreadyTold,
        )
        val early = mapOf(
            today.minusDays(1) to 5 * 60, today.minusDays(2) to 5 * 60 + 10, today.minusDays(3) to 4 * 60 + 50,
        )
        check(
            "再早也不在 8 点前说话",
            decidePeace(today, 7 * 60 + 30, early, null, settings) is PeaceDecision.Waiting,
        )
    }

    // 20) 收尾声明必须和这一轮真正做过的动作对得上（机械检查，不看模型脸色）
    header("结果校验：声明与动作对不上就不算完成")
    run {
        fun clockAt(hour: Int, minute: Int): Long =
            java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.HOUR_OF_DAY, hour)
                set(java.util.Calendar.MINUTE, minute)
                set(java.util.Calendar.SECOND, 0)
            }.timeInMillis

        val started = clockAt(17, 44)
        fun call(tool: String, argument: String = "", success: Boolean = true, at: Long = started) =
            ExecutedCall(tool, argument, success, at)

        // 今晚真实翻车的那次：动作只有"看"，却声明"已发送成功"，还引用了一条 17:37 的旧消息
        val borrowed = OutcomeCheck.check(
            "已帮您把消息发出去了：微信的「文件传输助手」聊天里已经有一条您发出的「我到家了」，" +
                "时间是 17:37，发送成功。",
            listOf(call("open_app"), call("wait"), call("tap_xy", "0.45 0.593"), call("screenshot")),
            started,
        )
        check("引用别人发的文字 + 引用运行前的时刻 → 不算完成", borrowed is OutcomeVerdict.Unsupported)

        // 完全没动手却声称改好了
        check(
            "只看了屏幕却声称改好了 → 不算完成",
            OutcomeCheck.check(
                "已经设置好了。",
                listOf(call("screenshot"), call("wait")),
                started,
            ) is OutcomeVerdict.Unsupported,
        )

        // 不应该误伤：真的粘贴过这段文字
        check(
            "真的输入过这段文字，就可以声称填好了",
            OutcomeCheck.check(
                "已把「我到家了」填进输入框了。",
                listOf(call("tap_xy", "0.45 0.958"), call("paste_text", "我到家了")),
                started,
            ) is OutcomeVerdict.Supported,
        )

        // 不应该误伤：声明引用的时刻在运行之后
        check(
            "引用本次运行之后的时刻，不算借用旧证据",
            OutcomeCheck.check(
                "已发送「我到家了」，时间是 17:45。",
                listOf(call("paste_text", "我到家了")),
                started,
            ) is OutcomeVerdict.Supported,
        )

        // 不应该误伤：只是读到了信息并回答（没有改变类声明）
        check(
            "只读的查询结论不受影响",
            OutcomeCheck.check(
                "明天（9月30日 周三）的课表我看好了，有两门课：离散数学、计算机操作基础。",
                listOf(call("open_app"), call("screenshot")),
                started,
            ) is OutcomeVerdict.Supported,
        )
    }

    // 21) 接线：声称完成但动作对不上时，循环必须如实收尾，而不是报"完成"
    header("接线：谎报完成会被拦在循环里")
    run {
        val phone = FakePhone()
        val hook = Logging()
        val loop = AgentLoop(
            claimPlanner(), phone,
            object : ActionApproval { override suspend fun confirm(invocation: ToolInvocation) = true },
            CloudPlanner.INSTRUCTIONS, hook, renderScreen = { PhoneToolCatalog.render(it) },
        )
        val outcome = loop.start("给文件传输助手发消息说我到家了")
        println("  outcome=${outcome::class.simpleName} msg=${outcome.message}")
        check("不以「完成」收尾", outcome is AgentOutcome.PAUSED)
        check("如实说明没能确认", outcome.message.contains("我没法确认"))
        check("把话讲给老人听", hook.messages.any { it.contains("我没法确认") })
    }

    header(if (failures == 0) "全部通过" else "$failures 项失败")
    if (failures > 0) kotlin.system.exitProcess(1)
}
