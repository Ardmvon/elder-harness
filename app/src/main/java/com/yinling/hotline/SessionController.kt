package com.yinling.hotline

import android.content.Intent
import android.net.Uri
import com.yinling.core.ActionApproval
import com.yinling.core.AgentHook
import com.yinling.core.AgentLoop
import com.yinling.core.AgentOutcome
import com.yinling.core.DecisionCache
import com.yinling.core.ScreenSnapshot
import com.yinling.core.ToolInvocation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class TaskPhase { IDLE, WORKING, CONFIRMING, PAUSED, NEEDS_FAMILY, NEEDS_PERSON, ASKING, COMPLETED, CANNOT }

data class SessionState(
    val goal: String = "",
    val message: String = "说出要办的事，我来帮您看下一步。",
    val phase: TaskPhase = TaskPhase.IDLE,
    val step: Int = 0,
    /** One-tap answers offered by the model while it waits for the person. */
    val options: List<String> = emptyList(),
    /** A confirmation is on screen; the answers apply to the call that is waiting. */
    val hasPendingApproval: Boolean = false,
    /** The agent stopped because this step has to be done by the person themselves. */
    val needsPersonStep: Boolean = false,
)

interface FamilyGateway {
    fun requestHelp(goal: String, reason: String)
    fun call()
}

/**
 * Owns the one running task: its loop, its transcript and the person-facing state.
 *
 * The loop instance is kept across pauses, so approving or continuing resumes the same
 * conversation instead of planning the whole task again.
 */
class SessionController(private val app: HotlineApp) : FamilyGateway {

    private val prefs = app.getSharedPreferences("hotline", 0)

    /** The trusted-circle server, if the family has paired this phone with one. */
    val server = ServerClient(app)

    /** Whether this phone can actually speak, for the settings screen. */
    fun speakerStatus(): String = app.speaker.status()

    /** Called when the family switches speech on, so the status can be reported honestly at once. */
    fun tryPrepareSpeaker() = app.speaker.prepare()

    /**
     * The open microphone, shared by the home screen and the floating panel so there is only ever
     * one conversation, and one place that decides what a spoken sentence means.
     */
    val voice: VoiceSession = VoiceSession(app) { pcm -> server.transcribe(pcm) }.also { session ->
        session.onInstruction = { spoken -> handleVoiceInstruction(spoken) }
    }

    /** Whether the assistant reads its lines out loud. */
    var speakerEnabled: Boolean
        get() = app.speaker.enabled
        set(value) {
            app.speaker.enabled = value
        }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mutableState = MutableStateFlow(initialState())
    val state = mutableState.asStateFlow()

    var endpoint: String
        get() = prefs.getString("endpoint", "https://api.deepseek.com").orEmpty()
        set(value) { prefs.edit().putString("endpoint", value).apply() }
    var model: String
        get() = prefs.getString("model", "deepseek-chat").orEmpty()
        set(value) { prefs.edit().putString("model", value).apply() }
    var familyName: String
        get() = prefs.getString("family_name", "家人").orEmpty()
        set(value) { prefs.edit().putString("family_name", value).apply() }
    var familyPhone: String
        get() = prefs.getString("family_phone", "").orEmpty()
        set(value) { prefs.edit().putString("family_phone", value).apply() }
    var visionEnabled: Boolean
        get() = prefs.getBoolean("vision", false)
        set(value) { prefs.edit().putBoolean("vision", value).apply() }

    /**
     * Auto-confirm every action. Deliberately separate from [developerMode]: the debug channel is
     * how a task is launched from adb, and conflating the two made it impossible to test what the
     * person is actually asked in normal use.
     */
    var autoConfirm: Boolean
        get() = prefs.getBoolean("auto_confirm", false)
        set(value) { prefs.edit().putBoolean("auto_confirm", value).apply() }

    /** Developer mode: the debug channel is open and the full loop is written to a log file. */
    var developerMode: Boolean
        get() = prefs.getBoolean("developer_mode", false)
        set(value) {
            prefs.edit().putBoolean("developer_mode", value).apply()
            LoopLog.enabled = value
        }

    /** Cached copy of the encrypted key, so the loop does not touch the Keystore every step. */
    private var cachedApiKey: String? = null

    /**
     * Encrypted at rest (see [SecretStore]): the family configures it once, and a restart must not
     * silently disable the assistant. Never written to the transcript or the log.
     */
    var apiKey: String
        get() = cachedApiKey ?: SecretStore.load(app).also { cachedApiKey = it }
        set(value) {
            val trimmed = value.trim()
            cachedApiKey = trimmed
            SecretStore.save(app, trimmed)
        }

    private val cache = RecipeCache(prefs)
    private val phoneTools = AndroidPhoneTools(app)
    private val sessions = SessionStore(app)

    /** Id of the task currently loaded, so its transcript can be saved and resumed. */
    private var sessionId: String = newSessionId()
    private var loop: AgentLoop? = null
    private var job: Job? = null
    private var confirmation: CompletableDeferred<Boolean>? = null

    /** Once the person allows a coordinate tap in this run, do not ask again. */
    private var blindTapApproved = false
    private var pendingApprovalPrompt: String? = null

    private fun initialState(): SessionState {
        val goal = prefs.getString("goal", "").orEmpty()
        return SessionState(
            goal = goal,
            message = if (goal.isBlank()) "说出要办的事，我来帮您看下一步。" else "上次的事还可以接着办。",
            phase = if (goal.isBlank()) TaskPhase.IDLE else TaskPhase.PAUSED,
        )
    }

    // ---- task lifecycle -----------------------------------------------------

    fun start(goal: String) {
        LoopLog.event("[session] start goal=${goal.take(40)}")
        if (goal.isBlank()) return
        job?.cancel()
        // Keep the interrupted task instead of throwing its transcript (and its cache prefix) away.
        saveCurrentSession()
        sessionId = newSessionId()
        prefs.edit().putString("goal", goal.trim()).apply()
        loop = null
        blindTapApproved = false
        mutableState.value = SessionState(goal.trim(), "正在看看当前页面。", TaskPhase.WORKING)
        launch(resume = false)
    }

    /** Continues a task from the saved list, restoring the exact conversation the model saw. */
    fun restore(id: String) {
        val saved = sessions.load(id) ?: return
        val running = buildLoop()
        if (!running.restore(saved.messages, saved.steps)) return
        job?.cancel()
        sessionId = saved.id
        loop = running
        prefs.edit().putString("goal", saved.goal).apply()
        mutableState.value = SessionState(
            goal = saved.goal,
            message = "接着上次没办完的事。",
            phase = TaskPhase.PAUSED,
            step = saved.steps,
        )
        LoopLog.event("[session] restore id=$id goal=${saved.goal.take(30)} messages=${saved.messages.size} cached prefix reused")
        launch(resume = true)
    }

    fun history(): List<SavedSession> = sessions.list()

    fun deleteSession(id: String) {
        sessions.delete(id)
        if (id == sessionId) finish()
    }

    private fun newSessionId(): String = "s" + System.currentTimeMillis().toString(36)

    /**
     * Writes the running conversation so it can be resumed later, including after a restart.
     * The write is small (tens of KB) and deliberately synchronous: it also runs from `start()`,
     * which is not a coroutine, and losing the transcript would cost a whole task.
     */
    private fun saveCurrentSession() {
        val running = loop ?: return
        val conversation = running.conversation
        if (conversation.size < 2) return
        sessions.save(
            SavedSession(
                id = sessionId,
                goal = state.value.goal,
                updatedAt = System.currentTimeMillis(),
                status = when (state.value.phase) {
                    TaskPhase.COMPLETED -> "done"
                    else -> "paused"
                },
                steps = running.stepCount,
                messages = conversation,
            ),
        )
    }

    fun resume() {
        if (state.value.goal.isBlank()) return
        launch(resume = true)
    }

    /**
     * Developer diagnostic: observe the current screen once and record what the model would get.
     * Two numbers matter — what the accessibility tree offered, and how much of it survives into
     * the text the model actually reads.
     */
    fun censusOnce() {
        scope.launch {
            delay(900) // let this activity move to the back so the target app is in front
            val screen = phoneTools.observe()
            val rendered = com.yinling.core.PhoneToolCatalog.render(screen)
            val shown = rendered.lineSequence().count { it.startsWith("[") }
            LoopLog.event(
                "[census] app=${screen.app} elements=${screen.elements.size} shown=$shown " +
                    "missing=${screen.elements.size - shown}",
            )
            LoopLog.event("[census-tree] ${ScreenAccessService.lastTreeCensus ?: "no accessibility service"}")
            // Which collected elements never reach the model, and what are they?
            val hidden = screen.elements.filter {
                it.text.isBlank() && it.description.isBlank() && !it.editable && !it.scrollable && !it.isSlider
            }
            val roles = hidden.groupingBy { it.role.ifBlank { "?" } }.eachCount()
                .entries.sortedByDescending { it.value }.take(5).joinToString(",") { "${it.key}:${it.value}" }
            val actionable = hidden.count { it.clickable || it.longClickable }
            LoopLog.event("[census-hidden] n=${hidden.size} actionable=$actionable roles=[$roles]")
        }
    }

    /** The person answered a question; their words go into the conversation and the task continues. */
    fun answerQuestion(text: String) {
        if (text.isBlank()) return
        val running = loop ?: return
        job?.cancel()
        mutableState.value = state.value.copy(phase = TaskPhase.WORKING, message = "您说的是：${text.trim()}")
        job = scope.launch {
            val outcome = try {
                running.answer(text)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            }
            settle(outcome)
        }
    }

    private fun launch(resume: Boolean) {
        job?.cancel()
        pendingApprovalPrompt = null
        mutableState.value = state.value.copy(phase = TaskPhase.WORKING, hasPendingApproval = false)
        job = scope.launch {
            try {
                // Let the host app come back to the foreground before the first observation.
                delay(650)
                LoopLog.event("[session] launching loop resume=$resume access=${ScreenAccessService.active != null}")
                val running = loop ?: buildLoop().also { loop = it }
            if (resume && loop === running && running.conversation.isEmpty()) {
                // Coming back after a restart: reload the saved conversation before planning, so the
                // request prefix (and therefore the provider cache) is the same as last time.
                sessions.load(sessionId)?.let { saved ->
                    if (running.restore(saved.messages, saved.steps)) {
                        LoopLog.event("[session] restored from disk id=$sessionId messages=${saved.messages.size}")
                    }
                }
            }
                val outcome = if (resume) running.resume() else running.start(state.value.goal)
                settle(outcome)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                LoopLog.event("[session] cancelled")
                throw cancelled
            } catch (error: Throwable) {
                LoopLog.event("[session] CRASH ${error.javaClass.simpleName}: ${error.message}")
                mutableState.value = state.value.copy(
                    message = "接线员出错了：${error.message}",
                    phase = TaskPhase.PAUSED,
                )
            }
        }
    }

    private fun buildLoop(): AgentLoop {
        val hook = hook()
        return AgentLoop(
        planner = CloudPlanner(
            config = ModelConfig(endpoint, model, apiKey, visionEnabled),
            log = ::logPlanner,
            onUsage = { prompt, completion, cached -> hook.onUsage(prompt, completion, cached) },
        ),
        tools = phoneTools,
        approval = approval(),
        instructions = CloudPlanner.INSTRUCTIONS + SkillCatalog.summary(),
        hook = hook,
        cache = cache,
        renderScreen = { screen ->
            com.yinling.core.PhoneToolCatalog.render(screen) +
                SkillCatalog.hintFor(screen.app) +
                keyboardNote(screen)
        },
        logger = { LoopLog.event(it) },
        )
    }

    /**
     * When the keyboard is up, say exactly where it is and what not to do with it.
     *
     * Two things went wrong without this: the model guessed the input box position (and the keyboard
     * moves it), and it tried to tap individual keys — the smallest, densest targets on the screen,
     * where a coordinate estimate of tens of pixels lands on the wrong key. The keys are not in the
     * accessibility tree either, so there is no node to fall back on.
     */
    private fun keyboardNote(screen: com.yinling.core.ScreenSnapshot): String {
        val height = app.resources.displayMetrics.heightPixels
        // Two independent signals, both must agree: the service saw a keyboard window, and the
        // system says the IME is actually visible.
        val ime = if (ScreenAccessService.keyboardVisible) ScreenAccessService.imeHeight(app) else 0
        if (ime <= 0 || height <= 0) return ""
        val top = 1f - ime.toFloat() / height
        val percent = ime * 100 / height
        return "\n键盘占据了屏幕下方 $percent%（y ≥ ${"%.2f".format(top)} 都是键盘）。" +
            "键盘上的字母键很小、读不到控件编号，不要逐个去点；" +
            "但键盘的候选词和功能键（如删除）在页面里以 k 开头的编号列出，可以直接点它们。" +
            "要输入整句文字时用剪贴板粘贴（见 wechat_input 技巧）：先点输入框（紧邻键盘上沿），再 paste_text。"
    }

    private fun logPlanner(message: String) = LoopLog.event("[planner] $message")

    private fun approval() = object : ActionApproval {
        /**
         * What actually needs the person's go-ahead. Labelled actions are already screened by the
         * service (payment/password/send targets are refused and handed to the person), so asking
         * about every tap only made the person press "确认" a dozen times per task. A coordinate tap
         * is the one action nothing can check, so it is asked about once per run.
         */
        override suspend fun needed(invocation: ToolInvocation): Boolean {
            if (autoConfirm) return false
            val ask = when (invocation.tool) {
                "tap_xy" -> !blindTapApproved
                else -> false
            }
            LoopLog.event(if (ask) "[approval] 需要老人确认 ${invocation.tool}" else "[approval] 直接执行 ${invocation.tool}")
            return ask
        }

        override suspend fun confirm(invocation: ToolInvocation): Boolean {
            // Developer mode skips the person entirely. The call is still validated, still
            // revision-bound, and still visible in the log.
            if (autoConfirm) {
                LoopLog.event("[dev] 自动确认 ${invocation.tool}(${invocation.arguments})")
                return true
            }
            val answer = CompletableDeferred<Boolean>()
            confirmation = answer
            mutableState.value = state.value.copy(
                message = when {
                    invocation.tool == "tap_xy" ->
                        "这一页读不到控件，我要直接点屏幕上的位置。可以吗？（同意后这次不再多问）"
                    else -> pendingApprovalPrompt ?: "确认进行这一步操作吗？"
                },
                phase = TaskPhase.CONFIRMING,
                hasPendingApproval = true,
            )
            return try {
                val approved = answer.await()
                if (approved) {
                    if (invocation.tool == "tap_xy") blindTapApproved = true
                    mutableState.value = state.value.copy(
                        phase = TaskPhase.WORKING,
                        hasPendingApproval = false,
                    )
                }
                approved
            } finally {
                if (confirmation === answer) {
                    confirmation = null
                    pendingApprovalPrompt = null
                }
            }
        }
    }

    private fun hook() = object : AgentHook {
        override fun onAction(display: String) {
            LoopLog.transcript(loop?.conversation.orEmpty())
            mutableState.value = state.value.copy(
                message = display,
                phase = TaskPhase.WORKING,
                // A new action means the person's own step is behind us.
                needsPersonStep = false,
            )
            // Say what is happening, not only how it ended. Someone who cannot see the screen has
            // no other way to know the phone is still working on their behalf rather than stuck.
            app.speaker.say("正在$display")
            voice.expectReply()
            saveCurrentSession()
        }

        override fun onMessage(display: String) {
            mutableState.value = state.value.copy(message = display, phase = TaskPhase.WORKING)
            app.speaker.say(display)
            // The assistant has just said something: a reply is now expected, so the microphone
            // stays open instead of closing on the idle timer mid-sentence.
            voice.expectReply()
        }

        override fun onWarning(display: String) {
            mutableState.value = state.value.copy(message = display, phase = TaskPhase.WORKING)
            app.speaker.say(display)
        }

        override fun onUsage(promptTokens: Int, completionTokens: Int, cachedTokens: Int) {
            val rate = if (promptTokens > 0) cachedTokens * 100 / promptTokens else 0
            lastUsage = "输入${promptTokens}（缓存${cachedTokens}，$rate%）/ 输出${completionTokens}"
            LoopLog.event("usage prompt=$promptTokens completion=$completionTokens cached=$cachedTokens rate=$rate%")
        }

        override fun onRetry(attempt: Int, ofTotal: Int, message: String) {
            mutableState.value = state.value.copy(
                message = "网络不稳，正在重试（$attempt/$ofTotal）。",
                phase = TaskPhase.WORKING,
            )
        }

        override fun onApprovalRequest(display: String) {
            pendingApprovalPrompt = display
        }
    }

    /** Most recent token usage, for the settings screen. */
    var lastUsage: String = ""
        private set

    private suspend fun settle(outcome: AgentOutcome) {
        val running = loop
        val steps = running?.stepCount ?: 0
        LoopLog.transcript(running?.conversation.orEmpty())
        LoopLog.event("settle: ${outcome::class.simpleName} steps=$steps msg=${outcome.message}")
        LoopLog.saveScreenshot()
        when (outcome) {
            is AgentOutcome.COMPLETED -> {
                rememberRecipe()
                mutableState.value = state.value.copy(
                    message = outcome.message, phase = TaskPhase.COMPLETED,
                    step = steps, hasPendingApproval = false,
                )
            }
            is AgentOutcome.PAUSED -> mutableState.value = state.value.copy(
                message = outcome.message, phase = TaskPhase.PAUSED,
                step = steps, hasPendingApproval = false, needsPersonStep = outcome.needsPerson,
            )
            is AgentOutcome.STUCK -> mutableState.value = state.value.copy(
                message = outcome.message, phase = TaskPhase.PAUSED,
                step = steps, hasPendingApproval = false,
            )
            is AgentOutcome.STEP_LIMIT -> mutableState.value = state.value.copy(
                message = outcome.message, phase = TaskPhase.PAUSED,
                step = steps, hasPendingApproval = false,
            )
            is AgentOutcome.FAMILY -> mutableState.value = state.value.copy(
                message = outcome.message, phase = TaskPhase.NEEDS_FAMILY,
                step = steps, hasPendingApproval = false,
            )
            // Reported as its own state: the task was not achieved, and showing "已完成" here
            // would be the most damaging kind of wrong answer for the person relying on it.
            is AgentOutcome.IMPOSSIBLE -> mutableState.value = state.value.copy(
                message = outcome.message, phase = TaskPhase.CANNOT,
                step = steps, hasPendingApproval = false,
            )
            // The person does this step themselves; the family is not involved.
            is AgentOutcome.NEEDS_PERSON -> mutableState.value = state.value.copy(
                message = outcome.message, phase = TaskPhase.NEEDS_PERSON,
                step = steps, hasPendingApproval = false,
            )
            // Waiting for an answer: the task is not finished, it is blocked on information.
            is AgentOutcome.ASKING -> mutableState.value = state.value.copy(
                message = outcome.message, phase = TaskPhase.ASKING,
                step = steps, hasPendingApproval = false, options = outcome.options,
            )
        }
        // Every ending is read out loud: the conclusion, the question, and the refusal alike. These
        // are the lines that matter most to someone who cannot comfortably read the screen.
        app.speaker.say(outcome.message)

        // Saved after the phase is updated, otherwise a finished task would be stored as paused.
        saveCurrentSession()
    }

    /** Saves the action sequence that just worked, so a repeat visit can replay it. */
    private suspend fun rememberRecipe() {
        val running = loop ?: return
        val goal = state.value.goal
        if (goal.isBlank()) return
        val actions = running.conversation
            .flatMap { it.toolCalls }
            .filter { it.tool in REPLAYABLE }
        if (actions.isEmpty()) return
        val screen = phoneTools.observe()
        if (screen.revision.isBlank()) return
        cache.remember(goal, screen, actions)
    }

    fun answer(approved: Boolean) {
        confirmation?.complete(approved)
    }

    fun stop() {
        job?.cancel()
        confirmation?.cancel()
        mutableState.value = state.value.copy(
            message = "已停下。需要时可以接着办。",
            phase = TaskPhase.PAUSED,
            hasPendingApproval = false,
        )
    }

    fun finish() {
        stop()
        loop = null
        prefs.edit().remove("goal").apply()
        mutableState.value = SessionState()
    }

    // ---- family ------------------------------------------------------------

    /**
     * What to do with something the person just said.
     *
     * One place, so the home screen and the floating panel cannot drift apart: answering a question,
     * stopping, or starting something new all look the same from the microphone's point of view.
     */
    fun handleVoiceInstruction(spoken: String) {
        val text = spoken.trim().trimEnd('。', '，', '.', ',', '!', '！', '?', '？')
        if (text.count { it.isLetterOrDigit() } < 2) return
        val stopWords = setOf("停", "停下", "停下来", "别弄了", "算了", "不用了", "取消")
        when {
            stopWords.any { text == it || text.endsWith(it) } -> stop()
            state.value.phase == TaskPhase.ASKING -> answerQuestion(text)
            state.value.goal.isNotBlank() -> {
                // A task is already running: the person talking over it means "do this instead".
                stop()
                start(text)
            }
            else -> start(text)
        }
    }

    /**
     * Whether there is any way to reach the circle at all: a paired server, or the fallback of a
     * phone number the person can text. Asking for a number that is no longer needed was how
     * "请家人帮忙" ended up opening Settings instead of asking for help.
     */
    fun hasHelpChannel(): Boolean = server.isConfigured() || familyPhone.isNotBlank()

    override fun requestHelp(goal: String, reason: String) {
        stop()
        val context = "目标：$goal\n卡在：${reason.ifBlank { "说不清楚，需要人看一下" }}"
        if (server.isConfigured()) {
            // The circle gets a structured request they can see on a web page and take over, instead
            // of a text-message draft the person still has to send themselves.
            mutableState.value = state.value.copy(
                message = "已经告诉家人了，他们会尽快联系您。",
                phase = TaskPhase.NEEDS_FAMILY,
            )
            scope.launch {
                val ok = server.postEvent(
                    kind = "help",
                    title = "${server.elderName.ifBlank { "老人" }}需要人帮忙",
                    body = reason.ifBlank { "需要人帮忙看一下" },
                    context = context,
                )
                if (!ok) {
                    // Server unreachable: fall back to the channel that needs nothing from anyone.
                    smsHelp(goal, reason)
                }
            }
            return
        }
        mutableState.value = state.value.copy(
            message = "请在短信应用发送求助，家人收到后可回电。",
            phase = TaskPhase.NEEDS_FAMILY,
        )
        smsHelp(goal, reason)
    }

    /** The zero-infrastructure fallback: a text-message draft the person sends themselves. */
    private fun smsHelp(goal: String, reason: String) {
        val phone = familyPhone
        if (phone.isBlank()) return
        val body = "银龄专线求助：我想办“$goal”。目前卡在：$reason。请给我回电话。"
        app.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$phone")).apply {
            putExtra("sms_body", body)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    override fun call() {
        if (familyPhone.isBlank()) return
        app.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$familyPhone")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /**
     * Remembers an action sequence per screen. Deliberately local and exact-match: it stores
     * what the same goal did on the same page, and never guesses an action on a page it has
     * not seen. Replay still goes through the loop's validation and the person's approval.
     */
    private class RecipeCache(private val prefs: android.content.SharedPreferences) : DecisionCache {
        private val entries = linkedMapOf<String, List<ToolInvocation>>()

        override suspend fun suggest(goal: String, screen: ScreenSnapshot): List<ToolInvocation>? =
            entries[key(goal, screen)]

        override suspend fun remember(goal: String, screen: ScreenSnapshot, invocations: List<ToolInvocation>) {
            if (invocations.isEmpty()) return
            entries[key(goal, screen)] = invocations
            while (entries.size > MAX_RECIPES) {
                entries.remove(entries.keys.first())
            }
        }

        private fun key(goal: String, screen: ScreenSnapshot): String =
            "${goal.trim()}|${screen.app.orEmpty()}|${screen.revision}"

        private companion object {
            const val MAX_RECIPES = 20
        }
    }

    private companion object {
        /** Tools whose replay is safe: they never send, pay, submit or authenticate. */
        val REPLAYABLE = setOf(
            "tap_text", "click", "scroll", "back", "home", "recents",
            "open_app", "open_settings", "wait", "read_screen",
        )
    }
}
