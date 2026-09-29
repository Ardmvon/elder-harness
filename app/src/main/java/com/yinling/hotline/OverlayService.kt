package com.yinling.hotline

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.provider.Settings
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.view.View
import android.view.ViewTreeObserver
import android.view.Gravity
import android.view.WindowManager
import kotlinx.coroutines.delay
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class OverlayService : Service() {
    companion object {
        private var active: OverlayService? = null

        /** Hidden for the duration of one action, so our own panel does not appear in screenshots. */
        private var hiddenForAction = false

        /** Hidden while the elder is looking at our own screen, which says the same thing already. */
        private var hiddenInApp = false

        fun hideForAction(hide: Boolean) {
            hiddenForAction = hide
            active?.updateVisibility()
        }

        fun setHiddenInApp(hide: Boolean) {
            hiddenInApp = hide
            active?.updateVisibility()
        }
    }

    private fun updateVisibility() {
        if (!::root.isInitialized) return
        root.visibility = when {
            hiddenInApp -> View.GONE
            hiddenForAction -> View.INVISIBLE
            else -> View.VISIBLE
        }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var window: WindowManager
    private lateinit var root: LinearLayout
    private var collector: Job? = null
    private var expanded = false

    /** Shape of the last render; rebuilding on every progress update would restart animations. */
    private var lastShape: String? = null
    private var lastPhase: TaskPhase? = null
    private var lastExpanded: Boolean? = null

    private val panelColor = OverlayUi.panel
    private val pillColor = OverlayUi.brand

    /** Linear blend of two ARGB colours, used so the shell does not jump between states. */
    private fun blend(from: Int, to: Int, t: Float): Int = Color.argb(
        (Color.alpha(from) + (Color.alpha(to) - Color.alpha(from)) * t).toInt(),
        (Color.red(from) + (Color.red(to) - Color.red(from)) * t).toInt(),
        (Color.green(from) + (Color.green(to) - Color.green(from)) * t).toInt(),
        (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * t).toInt(),
    )

    /** The rounded shell, kept so its corner radius can change while the window morphs. */
    private var shell: GradientDrawable? = null

    /** Runs the size morph between bubble and panel. */
    private var morph: ValueAnimator? = null

    /** The collapsed bubble, kept so a new step can update its label without a rebuild. */
    private var bubble: TextView? = null

    /** Last time the person was nudged about the accessibility service. */
    private var lastAccessibilityNudge = 0L

    /**
     * The person opened the panel while the agent is working. The panel is collapsed by default so it
     * cannot cover the page being read, but that must never take away the stop button: tapping the
     * bubble forces it open, and only an explicit collapse (or a phase change) closes it again.
     */
    private var openedWhileWorking = false

    /** Breathing animation on the collapsed bubble while the agent works. */
    private var pulse: ObjectAnimator? = null
    private val session get() = (application as HotlineApp).session

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Foreground from the first moment: it is what keeps this process — and with it the
        // accessibility service — from being killed in the background.
        Notices.ensureChannels(this)
        startForeground(Notices.ID_ALIVE, Notices.alive(this, "正在守护"))
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        active = this
        if (!::root.isInitialized) {
            window = getSystemService(WINDOW_SERVICE) as WindowManager
            root = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                elevation = dp(10).toFloat()
            }
            window.addView(root, WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    // Needed to sit level with the camera cut-out instead of below the status bar.
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply {
                // Top centre, just under the status bar: bottom-right covered the app's own
                // navigation bar and primary buttons, which are exactly what gets tapped.
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                x = 0
                // Just below the status bar. Inside the status bar strip the window still draws but
                // never receives touches, so the bubble there could not be tapped at all.
                y = statusBarHeight() + dp(2)
            })
            collector = scope.launch {
                session.state.collect { state -> render(state) }
            }
            scope.launch { watchAccessibility() }
        }
        return START_STICKY
    }

    /**
     * Watches the one thing this app cannot repair by itself.
     *
     * Nothing an app can do re-enables its own accessibility service, so a drop is not something to
     * paper over: it is something to notice quickly and hand back to the person, because while it
     * lasts the app is blind — and a watch that is blind must not pretend to be watching.
     */
    private suspend fun watchAccessibility() {
        var misses = 0
        val server = ServerClient(applicationContext as HotlineApp)
        // The daily peace message never actually ran before: nothing called PeaceCheck.tick(). It is
        // wired here because this loop is the only thing that is reliably alive while the app runs.
        val peace = PeaceCheck(this)
        while (true) {
            delay(ACCESSIBILITY_CHECK_MS)
            checkInWithTheCircle(server)
            runCatching { peace.tick() }
                .onFailure { LoopLog.event("[peace] 定时检查失败：${it.message}") }
            if (ScreenAccessService.isRunning()) {
                misses = 0
                Notices.clearAccessibility(this)
                continue
            }
            val enabled = ScreenAccessService.isEnabled(this)
            val now = System.currentTimeMillis()
            if (now - lastAccessibilityNudge < ACCESSIBILITY_NUDGE_MS) continue
            misses++
            LoopLog.event("[health] 无障碍未运行（已启用=$enabled，连续 $misses 次）")
            if (misses >= ACCESSIBILITY_MISSES_BEFORE_NUDGE) {
                Notices.postAccessibilityOff(this)
                lastAccessibilityNudge = now
                misses = 0
            }
        }
    }

    /**
     * Proof of life for the server, and the phone's inbox.
     *
     * The heartbeat is what lets the circle notice a phone that has gone quiet — the one thing the
     * phone cannot report about itself. Anything the circle sent back is logged for now; M2 turns it
     * into the full-screen card and speaks it aloud.
     */
    private suspend fun checkInWithTheCircle(server: ServerClient) {
        if (!server.isConfigured()) return
        val due = System.currentTimeMillis() - server.lastHeartbeatAt >= server.heartbeatSeconds * 1000L
        if (!due) return
        runCatching {
            val watching = ScreenAccessService.isRunning()
            val pending = server.heartbeat(note = if (watching) "看护中" else "无障碍未运行")
            pending.forEach { message ->
                LoopLog.event("[server] 收到 ${message.kind}：${message.title} ${message.body}")
            }
        }.onFailure { LoopLog.event("[server] 心跳失败：${it.message}") }
    }

    private fun render(state: SessionState) {
        val phaseChanged = state.phase != lastPhase

        // Anything that waits for the person opens the panel by itself — but only when the
        // situation changes. Forcing it open on every update meant "知道了" could never close a
        // finished task: the panel re-opened itself immediately.
        if (state.phase in AUTO_EXPAND) {
            if (phaseChanged) expanded = true
        } else if (state.phase == TaskPhase.WORKING) {
            // While it works, stay collapsed unless the person deliberately opened it. An open panel
            // covers part of the screen, and the agent reads that screen from a screenshot: our own
            // window was hiding the columns it was trying to read.
            if (!phaseChanged) expanded = openedWhileWorking
            if (phaseChanged) openedWhileWorking = false
        } else {
            openedWhileWorking = false
        }

        val shape = "${state.phase}|$expanded|${state.goal}|${state.message}|${state.options}"
        if (shape == lastShape) {
            // Same shape, but the run may have moved on a step: that is what the bubble shows.
            bubble?.text = pillLabel(state)
            Notices.updateAlive(this, pillLabel(state))
            return
        }
        Notices.updateAlive(this, pillLabel(state))
        // Opening and closing get their own transition, so the panel reads as growing out of the
        // bubble (and shrinking back into it) instead of being swapped in place.
        val expanding = lastExpanded == false && expanded
        val collapsing = lastExpanded == true && !expanded
        // Size we are morphing away from (0 on the very first render).
        val fromWidth = root.width
        val fromHeight = root.height

        (root.layoutParams as WindowManager.LayoutParams).y = statusBarHeight() + dp(2)
        lastShape = shape
        lastPhase = state.phase
        lastExpanded = expanded

        pulse?.cancel()
        pulse = null
        morph?.cancel()
        morph = null
        root.removeAllViews()
        root.setPadding(0, 0, 0, 0)
        // Clip the new content to the shell: that is what makes the panel look like it grows out of
        // the bubble instead of being swapped in. Content is revealed, never scaled.
        root.clipToOutline = true
        shell = OverlayUi.shell(
            color = when {
                // Start from the colour of the state we are leaving, otherwise a collapsing panel
                // shows one full-size green frame before it starts shrinking (the "flash").
                collapsing -> panelColor
                expanding -> pillColor
                expanded -> panelColor
                else -> pillColor
            },
            radiusPx = (if (expanded) dp(14) else dp(19)).toFloat(),
            strokeColor = OverlayUi.line,
            strokePx = dp(1),
        )
        root.background = shell

        if (!expanded) {
            val bubble = OverlayUi.bubble(this, pillLabel(state), pillColor) {
                expanded = true
                openedWhileWorking = session.state.value.phase == TaskPhase.WORKING
                render(session.state.value)
            }
            root.addView(bubble)
            this.bubble = bubble
            if (collapsing) morph(fromWidth, fromHeight, bubble, false) else settle()
            // While it is working, the bubble breathes so the person can see it is busy.
            if (state.phase == TaskPhase.WORKING) {
                pulse = ObjectAnimator.ofFloat(bubble, "alpha", 1f, 0.45f).apply {
                    duration = 900
                    repeatMode = ValueAnimator.REVERSE
                    repeatCount = ValueAnimator.INFINITE
                    start()
                }
            }
            return
        }

        bubble = null
        root.setPadding(dp(16), dp(12), dp(16), dp(12))
        val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(card)

        // A result renders its own heading, goal and conclusion; adding the generic header too
        // would print all three twice.
        val isResult = state.phase == TaskPhase.COMPLETED || state.phase == TaskPhase.CANNOT
        // Only the cases where the next move is the person's own: the agent refused an irreversible
        // step for them, or asked them to do it. A run that merely got stuck keeps the generic panel.
        val isPersonStep = state.needsPersonStep || state.phase == TaskPhase.NEEDS_PERSON
        if (!isResult && !isPersonStep) {
            // Same dot-and-word status line as the home screen, so the two surfaces agree.
            card.addView(OverlayUi.statusRow(this, state.phase))
            card.addView(OverlayUi.gap(this))
            if (state.goal.isNotBlank()) {
                card.addView(
                    OverlayUi.text(this, state.goal, Elder.hint.value, OverlayUi.inkSoft, maxLines = 2),
                )
            }
            if (state.message.isNotBlank()) {
                card.addView(
                    OverlayUi.text(this, state.message, Elder.body.value, OverlayUi.ink, maxLines = 6),
                )
            }
        }

        when {
            state.phase == TaskPhase.CONFIRMING -> {
                row(card, "确认" to { session.answer(true) }, "取消" to { session.answer(false) })
            }

            state.phase == TaskPhase.ASKING && state.options.isNotEmpty() -> {
                // One tap answers. The overlay window is not focusable, so typing here is not an
                // option; offering buttons is what makes answering possible without leaving the app.
                state.options.forEachIndexed { index, option ->
                    val button = optionButton(option) { session.answerQuestion(option) }
                    card.addView(button)
                    if (phaseChanged && !expanding) animateIn(button, 120L + index * 60L)
                }
                row(
                    card,
                    "其他…" to { openHome() },
                    "找家人" to { familyRow(card, state) },
                )
                row(
                    card,
                    (if (state.phase.isResumable()) "接着办" else "停下来") to {
                        if (state.phase.isResumable()) session.resume() else session.stop()
                    },
                    "收起" to { expanded = false; render(session.state.value) },
                )
            }

            state.phase == TaskPhase.COMPLETED || state.phase == TaskPhase.CANNOT ->
                resultCard(card, state, phaseChanged && !expanding)

            isPersonStep -> personStepCard(card, state, phaseChanged && !expanding)

            // Opened by hand while the agent is working: keep it short, so it stays out of the way
            // while still giving one obvious way to stop.
            state.phase == TaskPhase.WORKING -> {
                row(
                    card,
                    "停下来" to { session.stop() },
                    "收起" to { openedWhileWorking = false; expanded = false; render(session.state.value) },
                )
            }

            else -> {
                row(card, "打开" to { openHome() }, "找家人" to { familyRow(card, state) })
                row(
                    card,
                    (if (state.phase == TaskPhase.ASKING) "回答" else if (state.phase.isResumable()) "接着办" else "停下来") to {
                        when {
                            state.phase == TaskPhase.ASKING -> openHome()
                            state.phase.isResumable() -> session.resume()
                            else -> session.stop()
                        }
                    },
                    "收起" to { expanded = false; render(session.state.value) },
                )
            }
        }

        if (expanding) {
            morph(fromWidth, fromHeight, card, true)
        } else {
            settle()
            if (phaseChanged) animateIn(card, 0)
        }
    }

    /**
     * What the collapsed bubble says. It sits over the status bar, which covers no app content, so
     * it can afford to be informative: an elderly person left staring at an unmoving phone for a
     * minute assumes it is broken.
     */
    private fun pillLabel(state: SessionState): String = when {
        state.needsPersonStep -> "等您操作"
        state.phase == TaskPhase.WORKING && state.step > 0 -> "正在办 ${state.step}"
        state.phase == TaskPhase.WORKING -> "正在办"
        state.phase == TaskPhase.IDLE -> "银龄"
        else -> statusText(state.phase)
    }

    /** Widest the panel is allowed to get. */
    private fun panelWidth(): Int =
        (resources.displayMetrics.widthPixels - dp(28)).coerceAtMost(dp(420))

    /** Natural size of the tree that was just built. */
    private fun naturalSize(): Pair<Int, Int> {
        root.measure(
            View.MeasureSpec.makeMeasureSpec(panelWidth(), View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        return root.measuredWidth to root.measuredHeight
    }

    /** Lets the window wrap its content again, at the final offset. */
    private fun settle() {
        shell?.setColor(if (expanded) panelColor else pillColor)
        (root.layoutParams as WindowManager.LayoutParams).apply {
            y = statusBarHeight() + dp(2)
            width = if (expanded) panelWidth() else WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
        }.let { window.updateViewLayout(root, it) }
    }

    /**
     * The island-style transition: the window itself grows or shrinks frame by frame, the top edge
     * stays put, the corner radius eases from a full pill to a card, and the content is revealed by
     * clipping rather than scaled (scaling is what made it look like it stretched sideways).
     */
    private fun morph(fromWidth: Int, fromHeight: Int, content: View, opening: Boolean) {
        val (toWidth, toHeight) = naturalSize()
        if (fromWidth <= 0 || fromHeight <= 0) {
            settle()
            content.alpha = 1f
            return
        }
        val lp = root.layoutParams as WindowManager.LayoutParams
        val radiusFrom = if (opening) fromHeight / 2f else dp(14).toFloat()
        val radiusTo = if (opening) dp(14).toFloat() else toHeight / 2f
        val colorFrom = if (opening) pillColor else panelColor
        val colorTo = if (opening) panelColor else pillColor
        content.alpha = 0f
        morph = ValueAnimator.ofFloat(0f, 1f).apply {
            // Front-loaded and eased out, the way the island settles; no overshoot, or the panel
            // would clip against the status bar.
            duration = if (opening) 340L else 260L
            interpolator = PathInterpolator(0.22f, 1f, 0.3f, 1f)
            addUpdateListener { animator ->
                val t = animator.animatedValue as Float
                lp.width = (fromWidth + (toWidth - fromWidth) * t).toInt().coerceAtLeast(1)
                lp.height = (fromHeight + (toHeight - fromHeight) * t).toInt().coerceAtLeast(1)
                shell?.apply {
                    cornerRadius = radiusFrom + (radiusTo - radiusFrom) * t
                    setColor(blend(colorFrom, colorTo, t))
                }
                window.updateViewLayout(root, lp)
                // Content arrives early in the growth, so the panel never looks like an empty frame.
                content.alpha = (t * 2.4f).coerceAtMost(1f)
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    content.alpha = 1f
                    settle()
                }
            })
            start()
        }
    }

    /**
     * The agent stopped at a step only the person may perform. They need to know what to do and how
     * to hand control back, without having to work out what "接着办" refers to.
     */
    private fun personStepCard(card: LinearLayout, state: SessionState, animate: Boolean) {
        // Same words and colours as the home screen's status line for this state.
        val heading = OverlayUi.statusRow(this, TaskPhase.NEEDS_PERSON)
        card.addView(heading)
        card.addView(OverlayUi.gap(this))
        val body = OverlayUi.tinted(this, state.message, OverlayUi.waitTint)
        card.addView(body, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(8) })
        val go = optionButton("我做好了，继续") { session.resume() }
        card.addView(go)
        row(card, "找家人" to { familyRow(card, state) }, "停下来" to { session.stop() })
        if (animate) {
            animatePop(heading, 0)
            animateIn(body, 90)
            animatePop(go, 170)
        }
    }

    /**
     * The conclusion. The message is the deliverable for the person, so it gets a highlighted
     * block and the panel appears with a pop instead of the usual gentle slide.
     */
    private fun resultCard(card: LinearLayout, state: SessionState, phaseChanged: Boolean) {
        val done = state.phase == TaskPhase.COMPLETED
        val accent = if (done) OverlayUi.good else OverlayUi.attention
        val tint = if (done) OverlayUi.doneTint else OverlayUi.waitTint

        // Same dot-and-word heading the home screen shows for this state, with a mark on the left
        // because a result is worth noticing at a glance.
        val heading = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        heading.addView(OverlayUi.text(this, if (done) "✓" else "!", 24f, accent, bold = true))
        heading.addView(
            OverlayUi.text(this, statusText(state.phase), Elder.status.value, accent, bold = true)
                .apply { setPadding(dp(8), 0, 0, 0) },
        )
        card.addView(heading)

        card.addView(
            OverlayUi.text(
                this,
                state.goal.ifBlank { "刚才这件事" },
                Elder.hint.value,
                OverlayUi.inkSoft,
                maxLines = 2,
            ),
        )

        val conclusion = OverlayUi.tinted(this, state.message, tint)
        // Answers can be long (a timetable, a list of trains). Cut off mid-sentence is worse than a
        // scroll, so the block grows with its content up to a cap and then scrolls.
        val scroller = ScrollView(this).apply { addView(conclusion) }
        card.addView(scroller, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(8) })
        var capped = false
        scroller.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (capped) return
                val cap = dp(340)
                if (conclusion.height > cap) {
                    capped = true
                    scroller.layoutParams = scroller.layoutParams.apply { height = cap }
                    // A half-visible line looks like a bug unless we say it can be scrolled.
                    scroller.isScrollbarFadingEnabled = false
                    card.addView(
                        OverlayUi.text(
                            this@OverlayService,
                            "内容较长，可以上下滑动看全",
                            14f,
                            OverlayUi.inkSoft,
                        ).apply { setPadding(0, dp(4), 0, 0) },
                        card.indexOfChild(scroller) + 1,
                    )
                    scroller.requestLayout()
                }
            }
        })

        val dismiss = optionButton("知道了") { expanded = false; render(session.state.value) }
        card.addView(dismiss)
        row(card, "打开" to { openHome() }, "找家人" to { familyRow(card, state) })

        if (phaseChanged) {
            animatePop(heading, 0)
            animateIn(scroller, 90)
            animatePop(dismiss, 170)
        }
    }

    /** A springy appearance: the panel is announcing a result, so it should feel like one. */
    private fun animatePop(view: View, delayMs: Long) {
        view.alpha = 0f
        view.scaleX = 0.92f
        view.scaleY = 0.92f
        view.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setStartDelay(delayMs)
            .setDuration(300)
            .setInterpolator(OvershootInterpolator(1.6f))
            .start()
    }

    private fun familyRow(card: LinearLayout, state: SessionState) {
        if (!session.hasHelpChannel()) openHome()
        else session.requestHelp(state.goal, state.message)
    }

    /** Slide up and fade in; used when the panel changes shape, not on every progress update. */
    private fun animateIn(view: View, delayMs: Long) {
        view.alpha = 0f
        // The panel hangs from the top now, so it drops into place instead of rising.
        view.translationY = -dp(14).toFloat()
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay(delayMs)
            .setDuration(240)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun optionButton(text: String, onClick: () -> Unit) =
        OverlayUi.option(this, text, onClick)

    /**
     * Two side-by-side secondary actions: the quiet choices, kept below the primary one.
     *
     * They share the panel's width evenly instead of being fixed-width and left-aligned — the old
     * pair sat against the left edge with dead space beside it, which reads as a layout mistake.
     */
    private fun row(card: LinearLayout, first: Pair<String, () -> Unit>, second: Pair<String, () -> Unit>) {
        val height = dp(Elder.secondaryHeight.value.toInt())
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(
                OverlayUi.secondary(this@OverlayService, first.first, first.second),
                LinearLayout.LayoutParams(0, height, 1f).apply { rightMargin = dp(5) },
            )
            addView(
                OverlayUi.secondary(this@OverlayService, second.first, second.second),
                LinearLayout.LayoutParams(0, height, 1f).apply { leftMargin = dp(5) },
            )
        })
    }

    private fun openHome() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /** Height of the status bar, so the bubble sits just below the camera cut-out area. */
    private fun statusBarHeight(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else dp(28)
    }

    override fun onDestroy() {
        pulse?.cancel()
        if (active === this) active = null
        collector?.cancel()
        scope.cancel()
        if (::root.isInitialized) window.removeView(root)
        super.onDestroy()
    }
}

private const val ACCESSIBILITY_CHECK_MS = 60_000L
private const val ACCESSIBILITY_NUDGE_MS = 60 * 60_000L
private const val ACCESSIBILITY_MISSES_BEFORE_NUDGE = 3

private val AUTO_EXPAND = setOf(
    TaskPhase.CONFIRMING, TaskPhase.NEEDS_FAMILY, TaskPhase.ASKING, TaskPhase.NEEDS_PERSON,
    // A finished task must show its conclusion by itself: staying collapsed hides the answer,
    // which is the whole point of the task.
    TaskPhase.COMPLETED, TaskPhase.CANNOT,
    // Stopped at a step only the person may do (payment, verification, sending): they have to be
    // told, and offered a way to continue once they have done it.
    TaskPhase.PAUSED,
)

private fun TaskPhase.isResumable(): Boolean =
    this == TaskPhase.PAUSED || this == TaskPhase.CANNOT || this == TaskPhase.NEEDS_PERSON

