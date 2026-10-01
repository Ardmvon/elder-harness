package com.yinling.hotline

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.provider.Settings
import android.os.Bundle
import android.util.Base64
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.yinling.core.ScreenElement
import com.yinling.core.ScreenImage
import com.yinling.core.ScreenSnapshot
import com.yinling.core.ToolCall
import com.yinling.core.ToolResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlin.coroutines.resume

class ScreenAccessService : AccessibilityService() {
    companion object {
        /** True when the last read saw an input-method window, i.e. the keyboard really is up. */
        @Volatile
        var keyboardVisible = false
            private set

        /** Where the input strip of a chat-style app sits, as a fraction of the screen. */
        private const val INPUT_STRIP_X = 0.45f
        private const val INPUT_STRIP_Y = 0.965f

        /** Role used for keyboard nodes so the page can list them separately. */
        const val KEYBOARD_ROLE = "Key"

        private const val JPEG_QUALITY = 80

        /**
         * Long edge sent to the model. Measured on a colour-coded timetable: at 1568 the small
         * white-on-colour cell text was misread about one run in three, while the same screen at
         * its native 2800 was read correctly. Phone UI is flat colour and repeated glyphs, so
         * lossless WebP at full size came out *smaller* than the downscaled JPEG.
         */
        private const val MODEL_IMAGE_MAX_WIDTH = 3000

        /** Above this, upload time matters more than fine detail; fall back to lossy. */
        private const val MODEL_IMAGE_MAX_BYTES = 700_000

        /** Throttle so accessibility traffic does not turn into a prefs write storm. */
        private const val ACTIVITY_NOTE_INTERVAL_MS = 60_000L
        @Volatile
        private var lastActivityNoteAt = 0L

        /** Size of the newest screenshot as the model sees it, for tap_xy accuracy checks. */
        @Volatile
        var lastScreenshotSize: Pair<Int, Int>? = null
            private set

        /** One-line summary of what the tree offered versus what we kept. Diagnostic only. */
        @Volatile
        var lastTreeCensus: String? = null
            private set

        /** Newest screenshot, for the developer log. Purely diagnostic. */
        @Volatile
        var lastScreenshot: ByteArray? = null
            private set
        var active: ScreenAccessService? = null
            private set

        /** True while the system has this service connected, i.e. we are really receiving events. */
        fun isRunning(): Boolean = active != null

        /**
         * Height of the on-screen keyboard in pixels, or 0 when it is not showing.
         *
         * Worth stating in the page text: on a page with no accessibility tree the model otherwise
         * has to guess where the input box and the candidate row are, and those are the small targets
         * it keeps missing. The keyboard itself is a separate window that (with Tencent's IME at
         * least) is not exposed to accessibility at all, so it cannot be tapped by node either.
         */
        fun imeHeight(context: Context): Int = try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                0
            } else {
                val wm = context.getSystemService(android.view.WindowManager::class.java)
                val insets = wm?.currentWindowMetrics?.windowInsets
                // isVisible, not the inset size: the system keeps reporting a remembered IME inset
                // after the keyboard is hidden, which would have told the model the keyboard was
                // covering the bottom of a screen it was not on.
                if (insets?.isVisible(android.view.WindowInsets.Type.ime()) == true) {
                    insets.getInsets(android.view.WindowInsets.Type.ime()).bottom
                } else {
                    0
                }
            }
        } catch (_: Throwable) {
            0
        }

        /**
         * Whether the service is switched on in system settings. It may be enabled and not yet
         * connected (right after a reboot), so this is the weaker of the two checks.
         */
        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ).orEmpty()
            val me = ComponentName(context, ScreenAccessService::class.java).flattenToString()
            return enabled.split(':').any { it.equals(me, ignoreCase = true) }
        }

        private val sensitiveWords = listOf("收款方", "付款码", "确认支付", "输入密码", "验证码", "人脸识别")
        private val manualActions = listOf(
            "支付", "付款", "转账", "发出", "发送", "提交", "下单", "认证", "授权", "验证码", "密码",
            "删除", "购买", "呼叫", "拨打",
            // Ordering vocabulary. With per-step confirmation gone, these words are the automatic
            // gate that keeps an irreversible step in the person's own hands; the list is what the
            // agent may never press by itself, not a restriction on what it may look at.
            "结算", "拼单", "收银台", "去支付", "立即支付", "确认支付", "立即购买", "一键购买",
            "确认下单", "提交订单", "确认付款", "付款码", "免密", "先用后付", "充值", "提现",
            "还款", "打赏", "订阅", "续费", "确认收货", "立即预订", "确认预订",
        )
    }

    override fun onServiceConnected() {
        active = this
        LoopLog.event("[health] 无障碍服务已连接")
        // Self-healing hook: the system rebinds this service whenever it is switched back on (after
        // a reboot, after the user re-enables it), and that is a far more reliable moment to put the
        // guard in place than waiting for a boot broadcast the OEM may never deliver.
        val guard = Intent(this, OverlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(guard)
        } else {
            startService(guard)
        }
        Notices.clearAccessibility(this)
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // This is the production hook for "the phone was used": accessibility events arrive from the
        // person's own apps, not only from our task loop. Our own overlays/activities are not user
        // activity and must not make the daily peace message report a use that never happened.
        if (event?.packageName?.toString() == packageName) return
        // Throttled to one cheap prefs write/minute.
        val now = System.currentTimeMillis()
        if (now - lastActivityNoteAt < ACTIVITY_NOTE_INTERVAL_MS) return
        lastActivityNoteAt = now
        (applicationContext as? HotlineApp)?.peace?.noteActivity(now)
    }
    override fun onInterrupt() { (application as HotlineApp).session.stop() }
    override fun onDestroy() {
        if (active === this) active = null
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private class Page(
        val screen: ScreenSnapshot,
        val nodes: Map<String, AccessibilityNodeInfo>,
        val parents: Map<String, String>,
    ) : AutoCloseable {
        override fun close() = nodes.values.forEach { it.recycle() }
    }

    /** How many visible nodes a tree holds; used by the window diagnostic. */
    private fun countNodes(node: AccessibilityNodeInfo): Int {
        var total = 0
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(node)
        while (stack.isNotEmpty() && total < 500) {
            val current = stack.removeLast()
            total++
            for (index in 0 until current.childCount) {
                current.getChild(index)?.let { stack.addLast(it) }
            }
        }
        return total
    }

    @Suppress("DEPRECATION")
    private fun readPage(): Page {
        // Ignore our overlay and the keyboard; observe the underlying application window.
        val appWindows = windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        // Only the window the person is actually on. Falling back to whichever window happened to
        // have a root meant a *background* window could be read instead — and if that window held a
        // password field, the whole screen was marked sensitive and every action, even a screenshot,
        // was refused. An unreadable active window is a blind page, which is honest; someone else's
        // window is not.
        val active = appWindows.firstOrNull { it.isActive } ?: appWindows.firstOrNull { it.isFocused }
        // Diagnostic: which windows we are allowed to see, and whether the keyboard is among them.
        // Keyboards are a separate window type, and typing on a blind page currently means guessing
        // pixel positions for keys that TalkBack would reach by node.
        LoopLog.event(
            "[windows] " + windows.joinToString(" | ") { window ->
                val kind = when (window.type) {
                    AccessibilityWindowInfo.TYPE_APPLICATION -> "app"
                    AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "ime"
                    AccessibilityWindowInfo.TYPE_SYSTEM -> "sys"
                    else -> "t${window.type}"
                }
                val nodes = if (window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                    val root = window.root
                    val count = if (root == null) 0 else countNodes(root)
                    root?.recycle()
                    count
                } else {
                    -1
                }
                "$kind:${window.title}:a=${window.isActive}:nodes=$nodes"
            },
        )
        val root = active?.root ?: rootInActiveWindow
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        if (root == null) {
            return Page(ScreenSnapshot(null, emptyList(), width = width, height = height), emptyMap(), emptyMap())
        }
        val nodes = linkedMapOf<String, AccessibilityNodeInfo>()
        val parents = HashMap<String, String>()
        val elements = mutableListOf<ScreenElement>()
        var sensitive = false
        var sensitiveReason: String? = null
        // Diagnostics: how much of the tree we actually pass on, and what kind of node we drop.
        var visited = 0
        var invisible = 0
        var filteredNoText = 0
        var filteredWithText = 0
        val droppedRoles = HashMap<String, Int>()

        /**
         * Ids are short and flat (`e0`, `e1`, …) instead of a positional path such as
         * `0.0.0.0.0.0.0.1.1.1.1.0`: a long path is hard for the model to copy back correctly,
         * and it moves whenever the app inserts a node. The parent map keeps the ability to walk
         * up to a clickable ancestor. Ids are only meaningful for one revision.
         */
        fun visit(node: AccessibilityNodeInfo, id: String, parent: String?, depth: Int, fromKeyboard: Boolean = false) {
            if (depth > 24 || nodes.size >= 800) { node.recycle(); return }
            visited++
            nodes[id] = node
            if (parent != null) parents[id] = parent
            val bounds = Rect().also(node::getBoundsInScreen)
            // Keyboard nodes live in a window that is not the active one, so the system often reports
            // them as not visible even while they are on screen. Their bounds are still real, so
            // accept them there; on the app's own window keep insisting on visibility.
            val shown = !bounds.isEmpty && (node.isVisibleToUser || fromKeyboard)
            if (shown) {
                if (node.isPassword && !fromKeyboard) {
                    sensitiveReason = "密码框 role=${node.className?.toString()?.substringAfterLast('.')}"
                    sensitive = true
                }
                val text = if (node.isPassword) "" else node.text?.toString().orEmpty().take(300)
                val description = if (node.isPassword) "" else node.contentDescription?.toString().orEmpty().take(300)
                // Sliders have no label and are usually not "clickable" (they respond to dragging),
                // so without this they were dropped entirely and the model only saw the static label
                // beside them. `rangeInfo` is the platform's own description of a range control.
                val range = node.rangeInfo
                val slider = range != null || node.className?.toString()?.endsWith("SeekBar") == true
                val interesting = text.isNotBlank() || description.isNotBlank() || node.isClickable ||
                    node.isEditable || node.isScrollable || node.isLongClickable || slider
                if (!interesting) {
                    val role = node.className?.toString().orEmpty().substringAfterLast('.')
                    droppedRoles[role] = (droppedRoles[role] ?: 0) + 1
                    if (text.isNotBlank() || description.isNotBlank()) filteredWithText++ else filteredNoText++
                }
                if (interesting) {
                    elements += ScreenElement(id, text, description,
                        if (fromKeyboard) KEYBOARD_ROLE
                        else node.className?.toString().orEmpty().substringAfterLast('.'),
                        listOf(bounds.left, bounds.top, bounds.right, bounds.bottom), node.isClickable,
                        node.isLongClickable, node.isEditable && !node.isPassword, node.isScrollable,
                        node.isEnabled && !node.isPassword, parent,
                        range?.current?.toInt(), range?.min?.toInt(), range?.max?.toInt(),
                        viewId = node.viewIdResourceName.orEmpty())
                }
            } else {
                invisible++
            }
            for (i in 0 until node.childCount) {
                // Keep the keyboard's own prefix and flag all the way down: children used to come
                // back as ordinary page elements (and were then checked for sensitive words), so the
                // candidate row the model needed was never listed as a keyboard control at all.
                val prefix = if (fromKeyboard) "k" else "e"
                node.getChild(i)?.let { visit(it, "$prefix${nodes.size}", id, depth + 1, fromKeyboard) }
            }
        }
        visit(root, "e0", null, 0)
        // The keyboard is its own window, and on a page with no accessibility tree the candidate
        // words and function keys are exactly what the model keeps missing by coordinate estimate.
        // Tencent's IME does expose them, so list them by id instead of guessing pixels.
        val imeWindow = windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        keyboardVisible = imeWindow?.root != null
        imeWindow
            ?.root
            ?.let { imeRoot ->
                val before = elements.size
                val visitedBefore = visited
                visit(imeRoot, "k${nodes.size}", null, 0, fromKeyboard = true)
                val added = elements.drop(before).take(6).joinToString(" / ") {
                    "${it.id}:${it.text.ifBlank { it.description }.take(8)}:${it.role}"
                }
                LoopLog.event("[ime] 节点=$visitedBefore→$visited 收录=${elements.size - before} 样本=[$added]")
            }
        val labels = elements.flatMap { listOf(it.text, it.description) }.filter(String::isNotBlank).distinct()
        // Page-level sensitivity needs strong evidence. Scanning every visible string made a news
        // banner ("关于铁路收款方变更的公告") mark the whole 12306 home page as sensitive, which
        // blocked every control-based action and made the app unusable. A visible password field,
        // or a sensitive word on something the person can actually operate, is evidence; body text
        // is not. The per-target check against [manualActions] still runs for every action.
        val actionable = elements.filter { it.clickable || it.editable || it.longClickable }
        val sensitiveWordHit = actionable.filter { it.role != KEYBOARD_ROLE }.firstNotNullOfOrNull { element ->
            sensitiveWords.firstOrNull { word ->
                element.text.contains(word) || element.description.contains(word)
            }
        }
        if (sensitiveWordHit != null) {
            sensitive = true
            if (sensitiveReason == null) sensitiveReason = "敏感词「$sensitiveWordHit」在可操作控件上"
        }
        if (sensitive) {
            LoopLog.event(
                "[sensitive] $sensitiveReason ｜ 窗口=${root.packageName} " +
                    "元素=${elements.size} 可见节点=$visited",
            )
        }
        val topDropped = droppedRoles.entries.sortedByDescending { it.value }.take(4)
            .joinToString(",") { "${it.key}:${it.value}" }
        lastTreeCensus = "visited=$visited invisible=$invisible collected=${elements.size} " +
            "filteredNoText=$filteredNoText filteredWithText=$filteredWithText dropped=[$topDropped]"
        val signature = "${root.windowId}:${root.packageName}:$width:$height:$sensitive:$elements"
        val revision = MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return Page(ScreenSnapshot(root.packageName?.toString(), labels.take(200), revision = revision,
            elements = elements.take(250), width = width, height = height, sensitive = sensitive), nodes, parents)
    }

    fun snapshot(): ScreenSnapshot = readPage().use { it.screen }

    private fun failure(code: String, message: String) = ToolResult(false, message, code)

    /** Re-observe immediately before dispatch. Approval never authorizes a different page. */
    suspend fun perform(call: ToolCall): ToolResult = readPage().use { page ->
        val screen = page.screen
        // A live page (video countdown, unread badge, ad rotation) churns its revision even though
        // nothing the person cares about moved. Demanding an exact revision match made actions fail
        // constantly, so a stale revision is accepted when the target itself is still there with
        // the same label. Identity is checked below; this is only a cheap first gate.
        val staleRevision = call.revision.isBlank() || call.revision != screen.revision
        if (call.name in setOf("back", "home", "recents")) {
            val action = when (call.name) {
                "home" -> GLOBAL_ACTION_HOME
                "recents" -> GLOBAL_ACTION_RECENTS
                else -> GLOBAL_ACTION_BACK
            }
            val ok = performGlobalAction(action)
            return ToolResult(ok, if (ok) "系统已接收导航操作，等待检查页面。" else "系统未接受导航操作。")
        }
        // Sensitive pages are refused before every action that can touch the app, including raw
        // coordinate gestures. "Back/home" above stay available so the person is never trapped.
        if (screen.sensitive) {
            return failure(
                "requires_user",
                if (call.name == "screenshot") {
                    "这一页涉及密码或验证码，我不能把它的画面发出去，也操作不了。请您自己完成这一步。"
                } else {
                    "这一页是身份或支付验证，得您自己来。做完按下面的按钮，我接着办。"
                },
            )
        }
        // Coordinate tapping exists for pages that expose no accessibility tree (WeChat and
        // friends), where there is nothing to look up by node at all.
        if (call.name == "tap") {
            if (call.x !in 0 until screen.width || call.y !in 0 until screen.height) {
                return failure("out_of_bounds", "点按位置超出屏幕范围，请根据截图重新估计。")
            }
            return tapAt(call)
        }
        if (call.name == "swipe") {
            if (call.x !in 0 until screen.width || call.endX !in 0 until screen.width ||
                call.y !in 0 until screen.height || call.endY !in 0 until screen.height) {
                return failure("out_of_bounds", "滑动坐标越过屏幕范围，请修正。")
            }
            return gesture(call)
        }
        if (call.name == "screenshot") return screenshot(screen)
        fun stillTheSame(id: String, wanted: String): Boolean {
            val element = screen.elements.find { it.id == id } ?: return false
            return when {
                wanted.startsWith("viewId:") -> element.viewId == wanted.removePrefix("viewId:")
                wanted == "slider" -> element.isSlider
                wanted.startsWith("bounds:") -> "bounds:${element.bounds}" == wanted
                wanted.isBlank() -> true
                else -> element.text == wanted || element.description == wanted
            }
        }

        val target = if (call.name == "tap_text") {
            // Be liberal about what the model sends back: it sometimes copies a whole rendered line,
            // annotations included ("30日（TextView）"), which then matches nothing at all.
            val wanted = normalizeLabel(call.argument)
            val exact = screen.elements.filter { it.text == call.argument || it.description == call.argument }
            val matches = exact.ifEmpty {
                if (wanted.isBlank()) emptyList()
                else screen.elements.filter {
                    normalizeLabel(it.text) == wanted || normalizeLabel(it.description) == wanted
                }
            }
            val found = matches.mapNotNull { clickableId(it.id, page.nodes, page.parents, false) }.distinct()
            if (matches.isEmpty()) {
                // Nothing on the page carries that text at all: say so, and show what is there, so
                // the model is not left guessing whether it mis-typed or the page changed.
                val nearby = screen.elements.mapNotNull { it.text.ifBlank { it.description }.takeIf(String::isNotBlank) }
                    .distinct().take(6).joinToString("、")
                return failure(
                    "missing_target",
                    "页面上没有“${call.argument.take(20)}”这段文字。当前可见的文字例如：$nearby",
                )
            }
            if (found.isEmpty()) {
                // The label matched, but nothing in its ancestry accepts a tap. Reporting this as
                // "ambiguous" sent the model to click the very id that cannot be clicked, and the
                // two error messages then contradicted each other until the step limit.
                val matched = matches.take(4).joinToString("、") { "${it.id}(${it.role})" }
                return failure(
                    "not_clickable",
                    "“${call.argument}”这段文字本身不可点按（匹配到 $matched），它多半是标题或说明。" +
                        "请改用同一区域里可点按的控件编号，或者滚动、返回换一种方式。",
                )
            }
            if (found.size > 1) {
                // Genuinely ambiguous: hand over the candidates so it can pick instead of guessing.
                val candidates = found.take(6).joinToString("、") { it }
                return failure(
                    "ambiguous_target",
                    "“${call.argument}”有 ${found.size} 个可点按的匹配项，请改用 click 指定编号：$candidates",
                )
            }
            found.single()
        } else call.target
        if (screen.elements.none { it.id == target } && call.name != "tap_text") {
            return failure("missing_target", "控件编号不存在于当前观察，请重新选择。")
        }
        if (staleRevision && call.name != "tap_text" && call.target.isNotBlank() &&
            (call.expected.isBlank() || !stillTheSame(call.target, call.expected))
        ) {
            return failure("stale_screen", "页面已变化，请根据新页面重新选择操作。")
        }
        val node = page.nodes[target] ?: return failure("missing_target", "控件已消失，请重新观察。")
        if (!node.isVisibleToUser || !node.isEnabled || node.isPassword) return failure("unavailable_target", "控件当前不可操作。")
        // Include descendants when a model targets a parent container instead of its label.
        val context = page.nodes.filterKeys { candidate ->
            candidate == target || page.parents[candidate] == target
        }.values.joinToString(" ") { if (it.isPassword) "" else "${it.text ?: ""} ${it.contentDescription ?: ""}" }
        if (call.name != "scroll") {
            val hit = manualActions.firstOrNull(context::contains)
            if (hit != null) {
                // Name the control: "得您自己点「去结算」" is actionable, "请亲自确认" is not.
                val label = page.nodes[target]?.text?.toString().orEmpty()
                    .ifBlank { page.nodes[target]?.contentDescription?.toString().orEmpty() }
                    .take(14)
                val what = if (label.isBlank()) "屏幕上的「$hit」按钮" else "「$label」"
                return failure(
                    "requires_user",
                    "这一步得您自己点 $what。点完按下面的按钮，我接着办。",
                )
            }
        }
        // Sliders are adjusted with the same accessibility action a screen reader uses: no dragging,
        // no coordinates, and each call moves exactly one step.
        if (call.name == "scroll" && !node.isScrollable &&
            (node.rangeInfo != null || node.className?.toString()?.endsWith("SeekBar") == true)
        ) {
            val increase = call.argument == "down" || call.argument == "right"
            val performed = node.performAction(
                if (increase) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD,
            )
            return ToolResult(
                performed,
                if (performed) "滑动条已调整一档，等待检查页面。" else "滑动条没有接受调整，可能已到端点。",
            )
        }

        val ok = when (call.name) {
            "click", "tap_text", "long_press" -> {
                val long = call.name == "long_press"
                val id = clickableId(target, page.nodes, page.parents, long)
                    ?: return failure("unsupported_action", "此控件不支持${if (long) "长按" else "点按"}，请选择其他控件。")
                page.nodes.getValue(id).performAction(if (long) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK)
            }
            "input_text" -> {
                if (!node.isEditable) {
                    return failure(
                        "not_editable",
                        "这个编号不是输入框。请选标记 [可输入] 的控件。",
                    )
                }
                node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, call.text)
                })
            }
            "scroll" -> {
                if (!node.isScrollable) return failure("not_scrollable", "请选择可滚动容器编号，或使用 swipe。")
                val action = when (call.argument) {
                    "down" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN
                    "up" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP
                    "left" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT
                    else -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT
                }
                val selected = if (node.actionList.contains(action)) action.id else when (call.argument) {
                    "down" -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                    "up" -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                    else -> return failure("unsupported_action", "此容器不支持横向滚动，请使用 swipe。")
                }
                node.performAction(selected)
            }
            else -> return failure("unknown_tool", "不支持这个操作，请查阅工具目录。")
        }
        ToolResult(ok, if (ok) "控件已接收操作，等待检查页面。" else "控件未接受操作，可能已到边界或动作不支持。")
    }

    /** Strips the annotations this app adds when rendering, so a copied line still matches. */
    private fun normalizeLabel(raw: String): String = raw
        .replace(Regex("[（(]\\s*[A-Za-z]+\\s*[）)]"), "")
        .replace(Regex("\\[[^\\]]*]"), "")
        .trim()

    private fun clickableId(
        id: String,
        nodes: Map<String, AccessibilityNodeInfo>,
        parents: Map<String, String>,
        long: Boolean,
    ): String? {
        var candidate: String? = id
        while (candidate != null) {
            val node = nodes[candidate] ?: return null
            if (node.isEnabled && node.isVisibleToUser && !node.isPassword &&
                (if (long) node.isLongClickable else node.isClickable)
            ) {
                return candidate
            }
            candidate = parents[candidate]
        }
        return null
    }

    /**
     * Pastes into whatever field currently has input focus.
     *
     * Apps like WeChat refuse node-level text writing and external input injection, but they do
     * honour a paste performed by their own focused field. That only works when the focused view
     * is reachable through accessibility; when it is not, the person has to paste manually.
     */
    suspend fun pasteIntoFocusedField(): ToolResult {
        tryPaste()?.let { return it }

        // Nothing to paste into. On a page with no accessibility tree the input box cannot be found
        // by node, so the model has to hit it by coordinate — and missing it is the single most
        // common failure in these flows (no focus means no keyboard, no candidate row, nothing).
        // The input strip is a platform convention at the bottom of the screen, so tap it for the
        // model instead of asking it to aim again.
        if (snapshot().elements.isEmpty()) {
            val metrics = resources.displayMetrics
            val tapped = dispatchTap(metrics.widthPixels * INPUT_STRIP_X, metrics.heightPixels * INPUT_STRIP_Y)
            if (tapped) {
                delay(500)
                tryPaste()?.let {
                    return ToolResult(true, "已自动点中输入框并粘贴，等待检查页面。", screenChanged = true)
                }
            }
        }
        return failure(
            "not_editable",
            "没能把文字放进输入框（这一页读不到控件）。输入框通常在屏幕最底部那条，" +
                "可以自己用 tap_xy 点它一下再 paste_text；不要点键盘上的按键。",
        )
    }

    /** Pastes into the focused editable node, or null when there is nothing to paste into. */
    private fun tryPaste(): ToolResult? {
        val focused = findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return null
        val editable = generateSequence(focused) { it.parent }
            .firstOrNull { it.isEditable }
            ?: focused.takeIf { it.isEditable }
            ?: return null
        val ok = editable.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        return if (ok) {
            ToolResult(true, "已粘贴文字，等待检查页面。", screenChanged = true)
        } else {
            failure("paste_rejected", "这个应用不接受程序粘贴，请让老人自己粘贴或输入。")
        }
    }

    /** Taps an absolute pixel position; used when the agent has to act without a readable tree. */
    private suspend fun dispatchTap(x: Float, y: Float): Boolean = withTimeoutOrNull(2000) {
        suspendCancellableCoroutine { continuation ->
            val path = Path().apply { moveTo(x, y); lineTo(x + 1f, y + 1f) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
                .build()
            dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (continuation.isActive) continuation.resume(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (continuation.isActive) continuation.resume(false)
                }
            }, null)
        }
    } ?: false

    /** Taps a screen position. `tap_xy` fractions are already converted to pixels by the loop. */
    private suspend fun tapAt(call: ToolCall): ToolResult = withTimeoutOrNull(2500) {
        suspendCancellableCoroutine { continuation ->
            val path = Path().apply {
                moveTo(call.x.toFloat(), call.y.toFloat())
                lineTo(call.x.toFloat() + 1f, call.y.toFloat() + 1f)
            }
            val stroke = GestureDescription.StrokeDescription(path, 0, 60)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (continuation.isActive) continuation.resume(ToolResult(true, "已按位置点按，等待检查页面。"))
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (continuation.isActive) continuation.resume(failure("gesture_cancelled", "点按被系统取消，请重新观察。"))
                }
            }, null)
            if (!accepted && continuation.isActive) continuation.resume(failure("gesture_rejected", "系统未接受点按。"))
        }
    } ?: failure("gesture_timeout", "点按结果等待超时，请重新观察。")

    private suspend fun gesture(call: ToolCall): ToolResult = withTimeoutOrNull(2500) {
        suspendCancellableCoroutine { continuation ->
            val path = Path().apply { moveTo(call.x.toFloat(), call.y.toFloat()); lineTo(call.endX.toFloat(), call.endY.toFloat()) }
            val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, call.durationMs.toLong())).build()
            val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (continuation.isActive) continuation.resume(ToolResult(true, "滑动完成，等待检查页面。"))
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (continuation.isActive) continuation.resume(failure("gesture_cancelled", "滑动被系统取消，请重新观察。"))
                }
            }, null)
            if (!accepted && continuation.isActive) continuation.resume(failure("gesture_rejected", "系统未接受滑动。"))

        }
    } ?: failure("gesture_timeout", "滑动结果等待超时，请重新观察，勿盲目重复。")

    /**
     * Draws a 10% coordinate grid on the screenshot.
     *
     * On pages with no accessibility tree the model can only point by estimating a ratio from the
     * picture, and that estimate carries tens of pixels of error — enough to miss a chat row or the
     * input strip at the bottom of WeChat. A labelled grid gives it a ruler instead of a guess.
     * Drawn before encoding, so it costs nothing per step.
     */
    private fun drawGrid(target: Bitmap) {
        val canvas = android.graphics.Canvas(target)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.argb(70, 255, 60, 60)
            strokeWidth = 2f
        }
        val label = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.argb(150, 200, 0, 0)
            textSize = target.height / 90f
        }
        for (step in 1..9) {
            val ratio = step / 10f
            val x = target.width * ratio
            val y = target.height * ratio
            canvas.drawLine(x, 0f, x, target.height.toFloat(), paint)
            canvas.drawLine(0f, y, target.width.toFloat(), y, paint)
            canvas.drawText("%.1f".format(ratio), x + 4f, label.textSize, label)
            canvas.drawText("%.1f".format(ratio), 4f, y - 4f, label)
        }
    }

    /** Keeps the long edge near [MODEL_IMAGE_MAX_WIDTH] so the upload stays small. */
    private fun scaleForModel(source: Bitmap, maxLongest: Int = MODEL_IMAGE_MAX_WIDTH): Bitmap {
        val longest = maxOf(source.width, source.height)
        if (longest <= maxLongest) return source
        val ratio = maxLongest.toFloat() / longest
        return Bitmap.createScaledBitmap(
            source,
            (source.width * ratio).toInt().coerceAtLeast(1),
            (source.height * ratio).toInt().coerceAtLeast(1),
            true,
        )
    }

    private suspend fun screenshot(screen: ScreenSnapshot): ToolResult {
        if (Build.VERSION.SDK_INT < 30) return failure("unsupported_api", "此手机系统不支持辅助功能截图（需要 Android 11）。")
        // Two phases on purpose. The timeout covers only the system capture: encoding a full-screen
        // bitmap is CPU-heavy, and doing it inside this window (or on the main thread, which also
        // runs this accessibility service) either reported a bogus timeout or froze the service.
        val captured = withTimeoutOrNull(3000) {
            suspendCancellableCoroutine<Pair<Bitmap?, ToolResult?>> { continuation ->
                takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        val buffer = result.hardwareBuffer
                        try {
                            val hardware = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                            val bitmap = hardware?.copy(Bitmap.Config.ARGB_8888, false)
                            hardware?.recycle()
                            if (continuation.isActive) continuation.resume(bitmap to null) else bitmap?.recycle()
                        } finally { buffer.close() }
                    }

                    override fun onFailure(errorCode: Int) {
                        val error = failure("screenshot_unavailable", "系统不允许截图或请求过于频繁（$errorCode），可继续用控件列表。")
                        if (continuation.isActive) continuation.resume(null to error)
                    }
                })
            }
        } ?: return failure("screenshot_timeout", "截图等待超时，可继续用控件列表。")

        captured.second?.let { return it }
        val bitmap = captured.first ?: return failure("screenshot_failed", "截图解码失败。")
        return withContext(Dispatchers.Default) { encodeShot(bitmap, screen) }
    }

    /** Scaling, compression and base64 for one screenshot. CPU-bound: call it off the main thread. */
    @android.annotation.TargetApi(android.os.Build.VERSION_CODES.R)
    private fun encodeShot(bitmap: Bitmap, screen: ScreenSnapshot): ToolResult {
        val scaled = scaleForModel(bitmap)
        // Drawing needs a mutable bitmap, and both scaleForModel's result and the hardware-buffer
        // copy can be immutable. Painting straight onto them threw and failed the whole screenshot.
        val target = if (scaled.isMutable) scaled else scaled.copy(Bitmap.Config.ARGB_8888, true) ?: scaled
        try {
            drawGrid(target)
            // Lossless first: JPEG's chroma subsampling is what destroys small coloured text, not
            // the resolution alone. Phone UI is flat colour, so this is often smaller than JPEG.
            var bytes = ByteArrayOutputStream().also {
                target.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, it)
            }.toByteArray()
            var mime = "image/webp"
            if (bytes.size > MODEL_IMAGE_MAX_BYTES) {
                // Photo-heavy screens do not compress losslessly; trade detail here.
                val smaller = scaleForModel(target, MODEL_IMAGE_MAX_WIDTH / 2)
                bytes = ByteArrayOutputStream().also {
                    smaller.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it)
                }.toByteArray()
                if (smaller !== target) smaller.recycle()
                mime = "image/jpeg"
            }
            lastScreenshot = bytes
            lastScreenshotSize = target.width to target.height
            return ToolResult(
                true,
                "已读取屏幕图像（${target.width}x${target.height}，图上有 10% 刻度网格，坐标按屏幕比例给出）。",
                image = ScreenImage(Base64.encodeToString(bytes, Base64.NO_WRAP), screen.revision, mime),
            )
        } finally {
            if (target !== scaled) target.recycle()
            if (scaled !== bitmap) bitmap.recycle()
            scaled.recycle()
        }
    }
}
