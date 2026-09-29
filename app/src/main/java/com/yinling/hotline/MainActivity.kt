package com.yinling.hotline

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import android.provider.Settings
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {
    private val session get() = (application as HotlineApp).session
    private var permissionVersion by mutableIntStateOf(0)

    /** Whether this phone has anything that can turn speech into text. */
    private fun hasSpeechRecognizer(): Boolean = runCatching {
        packageManager.queryIntentActivities(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH), 0).isNotEmpty()
    }.getOrDefault(false)

    /**
     * Our own screen already shows the state, so the floating panel would only sit on top of it —
     * including on top of the one button the elder is supposed to press.
     */
    override fun onResume() {
        super.onResume()
        permissionVersion++
        OverlayService.setHiddenInApp(true)
    }

    override fun onPause() {
        super.onPause()
        OverlayService.setHiddenInApp(false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleDebugIntent()
        // Opening the app once is enough to put the守护 in place.
        startOverlay()
        setContent {
            val state by session.state.collectAsState()
            var settings by remember { mutableStateOf(false) }
            var request by remember { mutableStateOf(state.goal) }
            // A phone can claim to have a speech recogniser and still fail to start it; once that has
            // happened, stop offering it and say what to do instead.
            var voiceBroken by remember { mutableStateOf(false) }
            var answer by remember { mutableStateOf("") }
            val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                val said = it.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                    ?.firstOrNull()?.trim().orEmpty()
                if (said.isNotBlank()) {
                    request = said
                    // One tap instead of two: the person has already said what they want, so start
                    // straight away when nothing is running and nothing is missing.
                    val ready = Settings.canDrawOverlays(this) && ScreenAccessService.active != null
                    if (ready && session.state.value.goal.isBlank()) {
                        startOverlay()
                        session.start(said)
                        moveTaskToBack(true)
                    }
                }
            }
            MaterialTheme(colorScheme = lightColorScheme(
                primary = Color(0xFF087E75),
                onPrimary = Color.White,
                secondary = Color(0xFFB34C35),
                background = Color(0xFFF8FAF8),
                surface = Color.White,
            )) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    if (settings) {
                        SettingsPage(session, onBack = { settings = false })
                    } else {
                        val tick = permissionVersion
                        val devMode = session.autoConfirm
                        val history = remember(tick) { session.history() }
                        HomePage(
                            state = state,
                            autoConfirm = devMode,
                            history = history,
                            request = request,
                            onRequest = { request = it },
                            overlayReady = remember(tick) { Settings.canDrawOverlays(this) },
                            accessReady = remember(tick) { ScreenAccessService.active != null },
                            voiceAvailable = remember(tick) { !voiceBroken && hasSpeechRecognizer() },
                            onOverlayPermission = {
                                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
                            },
                            onAccessPermission = {
                                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                            },
                            onVoice = {
                                val ask = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                                    putExtra(RecognizerIntent.EXTRA_PROMPT, "请说要办的事")
                                }
                                // Launching an intent nobody handles kills the app; the person is left
                                // staring at a home screen with no idea what happened.
                                try {
                                    voice.launch(ask)
                                } catch (_: android.content.ActivityNotFoundException) {
                                    voiceBroken = true
                                    Toast.makeText(
                                        this,
                                        "这台手机没有语音识别，请打字告诉它。",
                                        Toast.LENGTH_LONG,
                                    ).show()
                                }
                            },
                            onStart = {
                                startOverlay()
                                session.start(request)
                                moveTaskToBack(true)
                            },
                            onResumeTask = {
                                startOverlay()
                                session.resume()
                                moveTaskToBack(true)
                            },
                            onFamily = {
                                // Never drop the elder into the installer's settings wall: if nothing
                                // is set up, say who can fix it.
                                if (!session.hasHelpChannel()) {
                                    Toast.makeText(
                                        this,
                                        "还没设置家人联系方式，请让家人帮您设置一下。",
                                        Toast.LENGTH_LONG,
                                    ).show()
                                } else {
                                    session.requestHelp(state.goal.ifBlank { "使用手机" }, state.message)
                                }
                            },
                            onCall = {
                                if (session.familyPhone.isBlank()) {
                                    Toast.makeText(this, "还没设置家人的电话号码。", Toast.LENGTH_LONG).show()
                                } else {
                                    session.call()
                                }
                            },
                            onSettings = { settings = true },
                            onFinish = session::finish,
                            answer = answer,
                            onAnswerChange = { answer = it },
                            onAnswer = { text ->
                                startOverlay()
                                session.answerQuestion(text)
                                answer = ""
                                moveTaskToBack(true)
                            },
                            onRestore = { id ->
                                startOverlay()
                                session.restore(id)
                                moveTaskToBack(true)
                            },
                        )
                    }
                }
            }
        }
    }

    /**
     * Debug-only entry point. Handled from both [onCreate] and [onNewIntent]: when the activity
     * is already on screen `am start` delivers a new intent instead of recreating it.
     */
    private fun handleDebugIntent() {
        val started = DebugCommand.apply(this, application as HotlineApp, DebugCommand.extras(intent))
        if (started) {
            startOverlay()
            moveTaskToBack(true)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDebugIntent()
    }

    private fun startOverlay() {
        if (!Settings.canDrawOverlays(this)) return
        // Foreground from here on: this service is what keeps the process (and with it the
        // accessibility service) alive, so it starts as soon as the app is opened, not just when a
        // task is running.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(Intent(this, OverlayService::class.java))
        } else {
            startService(Intent(this, OverlayService::class.java))
        }
    }
}

@Composable
private fun HomePage(
    state: SessionState,
    autoConfirm: Boolean,
    history: List<SavedSession>,
    request: String,
    onRequest: (String) -> Unit,
    overlayReady: Boolean,
    accessReady: Boolean,
    voiceAvailable: Boolean,
    onOverlayPermission: () -> Unit,
    onAccessPermission: () -> Unit,
    onVoice: () -> Unit,
    onStart: () -> Unit,
    onResumeTask: () -> Unit,
    onFamily: () -> Unit,
    onCall: () -> Unit,
    onSettings: () -> Unit,
    onFinish: () -> Unit,
    onRestore: (String) -> Unit,
    answer: String,
    onAnswerChange: (String) -> Unit,
    onAnswer: (String) -> Unit,
) {
    val hasTask = state.goal.isNotBlank()
    val busy = hasTask && state.phase == TaskPhase.WORKING
    // One line, and only when it says something the person would want to know.
    val statusText = when (state.phase) {
        TaskPhase.WORKING -> "正在办，请稍等"
        TaskPhase.CONFIRMING -> "等您点一下确认"
        TaskPhase.ASKING -> "等您回答一句"
        TaskPhase.NEEDS_PERSON -> "这一步要您自己做"
        TaskPhase.NEEDS_FAMILY -> "已经找家人了"
        TaskPhase.PAUSED -> "停下了"
        TaskPhase.CANNOT -> "这件事我办不了"
        TaskPhase.COMPLETED -> "办好了"
        TaskPhase.IDLE -> "我在"
    }
    val tone = when (state.phase) {
        TaskPhase.CANNOT -> Elder.problem
        TaskPhase.COMPLETED, TaskPhase.IDLE -> Elder.good
        TaskPhase.WORKING -> Elder.brand
        else -> Elder.attention
    }
    var typing by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(Elder.screenPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                // Small and out of the way: the family sets the phone up once, the elder never needs it.
                IconButton(onClick = onSettings) {
                    Icon(Icons.Default.Settings, contentDescription = "家人设置", tint = Elder.line)
                }
            }

            Spacer(Modifier.weight(1f))

            ElderStatusLine(statusText, tone, modifier = Modifier.fillMaxWidth())

            Spacer(Modifier.height(Elder.gap))

            ElderVoiceCircle(
                caption = when {
                    busy -> "停下来"
                    // Chinese OEM phones frequently ship no system speech recogniser at all. Offering
                    // a button that throws when pressed is worse than offering the one that works.
                    voiceAvailable -> "说给接线员听"
                    else -> "打字告诉它"
                },
                hint = when {
                    busy -> "正在办事，点一下就停"
                    voiceAvailable -> "点一下，说出您要办的事"
                    else -> "这台手机没有语音识别，请打字（或点键盘上的话筒说话）"
                },
                listening = false,
                busy = busy,
                icon = if (voiceAvailable) Icons.Default.Mic else Icons.Default.Edit,
                onClick = { if (busy) onFinish() else if (voiceAvailable) onVoice() else typing = true },
            )

            Spacer(Modifier.height(Elder.gap))

            // Cards appear only when the person actually has a decision to make.
            when (state.phase) {
                TaskPhase.ASKING -> ElderCard {
                    Text(state.goal, fontSize = Elder.hint, color = Elder.inkSoft)
                    Text(state.message, fontSize = Elder.body)
                    OutlinedTextField(
                        value = answer,
                        onValueChange = onAnswerChange,
                        label = { Text("回答接线员") },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = Elder.body),
                    )
                    ElderPrimaryButton("回答", { onAnswer(answer) }, enabled = answer.isNotBlank())
                }

                TaskPhase.NEEDS_PERSON, TaskPhase.PAUSED, TaskPhase.NEEDS_FAMILY, TaskPhase.CANNOT -> ElderCard {
                    Text(state.message, fontSize = Elder.body)
                    ElderPrimaryButton("我做好了，继续", onResumeTask)
                    ElderSecondaryButton("停下来", onFinish)
                }

                TaskPhase.COMPLETED -> ElderCard {
                    Text(state.message, fontSize = Elder.body)
                    ElderPrimaryButton("知道了", onFinish)
                }

                else -> Unit
            }

            if (!hasTask && request.isNotBlank()) {
                ElderCard {
                    Text("您说的是：", fontSize = Elder.hint, color = Elder.inkSoft)
                    Text(request, fontSize = Elder.body)
                    ElderPrimaryButton("就这么办", onStart)
                }
            }

            if (!hasTask && typing) {
                ElderCard {
                    OutlinedTextField(
                        value = request,
                        onValueChange = onRequest,
                        label = { Text("写下要办的事") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = Elder.body),
                    )
                    ElderPrimaryButton("就这么办", onStart, enabled = request.isNotBlank())
                }
            }

            // Only when something is broken does the page ask for anything else.
            if (!accessReady) {
                Spacer(Modifier.height(Elder.gapSmall))
                ElderNotice("我现在看不见屏幕：无障碍服务没开。", Elder.problem) {
                    ElderSecondaryButton("开启屏幕协助", onAccessPermission)
                }
            } else if (!overlayReady) {
                Spacer(Modifier.height(Elder.gapSmall))
                ElderNotice("悬浮窗没开，办事时我看不到您点哪儿。", Elder.attention) {
                    ElderSecondaryButton("开启悬浮窗", onOverlayPermission)
                }
            }

            Spacer(Modifier.weight(1f))

            // The safety net, kept quiet but never removed: when the assistant cannot help, a person
            // is the answer.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                if (!typing && !hasTask) {
                    TextButton(onClick = { typing = true }) {
                        Text("打字", fontSize = Elder.hint, color = Elder.inkSoft)
                    }
                }
                TextButton(onClick = onFamily) {
                    Text("请家人帮忙", fontSize = Elder.hint, color = Elder.inkSoft)
                }
                TextButton(onClick = onCall) {
                    Text("给家人打电话", fontSize = Elder.hint, color = Elder.inkSoft)
                }
            }
        }
    }
}


@Composable
private fun SettingsPage(session: SessionController, onBack: () -> Unit) {
    val context = LocalContext.current
    var familyName by remember { mutableStateOf(session.familyName) }
    var familyPhone by remember { mutableStateOf(session.familyPhone) }
    var endpoint by remember { mutableStateOf(session.endpoint) }
    var model by remember { mutableStateOf(session.model) }
    var key by remember { mutableStateOf(session.apiKey) }
    var vision by remember { mutableStateOf(session.visionEnabled) }
    var developer by remember { mutableStateOf(session.developerMode) }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
            Text("家人设置", fontSize = 27.sp, fontWeight = FontWeight.Bold)
        }
        Text(
            "这些是给家人装的，老人不需要进来（首页长按标题可以进来）。",
            fontSize = 15.sp,
            color = Color.DarkGray,
        )
        var speak by remember { mutableStateOf(session.speakerEnabled) }
        var speakerState by remember { mutableStateOf(session.speakerStatus()) }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(modifier = Modifier.weight(1f)) {
                Text("语音播报", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Text("接线员说话时念出来，看不清屏幕也听得见。", fontSize = 15.sp, color = Color.DarkGray)
                Text(speakerState, fontSize = 14.sp, color = Color.DarkGray)
            }
            Switch(
                checked = speak,
                onCheckedChange = { on ->
                    speak = on
                    session.speakerEnabled = on
                    // Ask the engine to start now so the status line tells the truth immediately.
                    session.tryPrepareSpeaker()
                    speakerState = session.speakerStatus()
                },
            )
        }
        if (!speakerState.startsWith("可用")) {
            // Chinese OEM phones often ship no usable engine at all; the family has to install one,
            // and that is a one-tap trip to the system screen rather than something we can do.
            OutlinedButton(
                onClick = { (context.applicationContext as HotlineApp).openTextToSpeechSettings() },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("去设置语音（系统 → 文字转语音）", fontSize = 16.sp) }
        }
        Text("家人", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        OutlinedTextField(familyName, { familyName = it }, label = { Text("称呼") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(familyPhone, { familyPhone = it }, label = { Text("电话号码") }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Text("智能接线员", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Text("办事时，当前页面的可见文字会发送到这里填写的模型服务。", fontSize = 15.sp)
        OutlinedTextField(endpoint, { endpoint = it }, label = { Text("服务地址") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(model, { model = it }, label = { Text("模型") }, modifier = Modifier.fillMaxWidth())
        Text(
            "建议 deepseek-chat：实测同一任务步数少得多、坐标也更准（3~6 步 vs 19~32 步）。" +
                "带思考的模型（如 deepseek-flash）每步都要权衡，反而容易来回试、点不准。",
            fontSize = 14.sp,
            color = Color.DarkGray,
        )
        OutlinedTextField(
            key, { key = it }, label = { Text("访问密钥") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("允许请求屏幕图像", fontSize = 17.sp)
            Switch(checked = vision, onCheckedChange = { vision = it })
        }
        Text("仅在模型支持图片时开启。每次截图发送前会请您确认。", fontSize = 14.sp)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("开发者模式", fontSize = 17.sp)
            Switch(checked = developer, onCheckedChange = { developer = it })
        }
        Text("调试试用：所有操作不再询问，直接执行；完整对话写入 files/loop.log。给老人用请关闭。", fontSize = 14.sp)
        Text("访问密钥保存在本机加密存储（Android Keystore），重启后仍然可用；换手机需要重新填写。", fontSize = 14.sp, color = Color.DarkGray)
        Spacer(Modifier.height(8.dp))
        KeepAliveSection()
        Spacer(Modifier.height(8.dp))
        PeaceSection()
        Spacer(Modifier.height(8.dp))
        ServerSection()
        Button(onClick = {
            session.familyName = familyName.trim()
            session.familyPhone = familyPhone.trim()
            session.endpoint = endpoint.trim()
            session.model = model.trim()
            session.apiKey = key.trim()
            session.visionEnabled = vision
            session.developerMode = developer
            onBack()
        }, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("保存", fontSize = 19.sp) }
    }
}

/**
 * "Keep me running": the three switches an app cannot flip for itself. Each one states plainly
 * whether it is on, because the failure this screen exists to prevent is the person believing the
 * assistant is watching when it is not.
 */
@Composable
private fun KeepAliveSection() {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val running = remember(tick) { ScreenAccessService.isRunning() }
    val enabled = remember(tick) { ScreenAccessService.isEnabled(context) }
    val batteryFree = remember(tick) { KeepAlive.isIgnoringBatteryOptimizations(context) }
    val canNotify = remember(tick) { KeepAlive.notificationsAllowed(context) }
    var autoStartFound by remember { mutableStateOf<Boolean?>(null) }
    val askNotifications = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { tick++ }

    val good = Color(0xFF087E75)
    val bad = Color(0xFFC46A14)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("一直运行", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Text("这三样决定我能不能一直在后台看着手机。给老人用的手机上都要打开。", fontSize = 15.sp)

        Text(
            when {
                running -> "① 无障碍服务：运行中 ✓"
                enabled -> "① 无障碍服务：已开启，等系统连接…"
                else -> "① 无障碍服务：未开启 ✗ 我既看不到屏幕，也没法操作"
            },
            fontSize = 16.sp,
            color = if (running) good else bad,
        )
        if (!running) {
            OutlinedButton(
                onClick = { KeepAlive.openAccessibilitySettings(context) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("去开启无障碍服务") }
        }

        Text(
            if (batteryFree) "② 电池优化：已忽略 ✓" else "② 电池优化：系统可能随时杀掉我 ✗",
            fontSize = 16.sp,
            color = if (batteryFree) good else bad,
        )
        if (!batteryFree) {
            OutlinedButton(
                onClick = { KeepAlive.requestIgnoreBatteryOptimizations(context) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("把本应用加入电池白名单") }
        }

        Text(
            if (autoStartFound == true) "③ 自启动：已打开设置页" else "③ 自启动：需要在系统设置里允许本应用自启动",
            fontSize = 16.sp,
            color = if (autoStartFound == true) good else bad,
        )
        OutlinedButton(
            onClick = { autoStartFound = KeepAlive.openAutoStartSettings(context) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("打开自启动设置") }
        if (autoStartFound == false) {
            Text("没找到自启动页，已打开应用详情：请在系统的“省电/自启动”里允许本应用。", fontSize = 14.sp)
        }

        if (!canNotify) {
            Text("④ 通知权限：未开启（掉线时我无法提醒您）", fontSize = 16.sp, color = bad)
            OutlinedButton(
                onClick = { askNotifications.launch(android.Manifest.permission.POST_NOTIFICATIONS) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("允许通知") }
        }

        TextButton(onClick = { tick++ }) { Text("重新检查") }
    }
}

/**
 * The peace-of-mind agreement, written as an agreement rather than a promise.
 *
 * On the phones this runs on, an app cannot be a dependable 24/7 watch: the accessibility service is
 * removed by the system after a reboot and background execution is restricted. So the mechanism is a
 * daily message the family expects — a missing message is the alarm — and the screen says so instead
 * of implying that a phone can be trusted to notice everything.
 */
@Composable
private fun PeaceSection() {
    val context = LocalContext.current
    val peace = remember { PeaceCheck(context) }
    var tick by remember { mutableIntStateOf(0) }
    var enabled by remember { mutableStateOf(peace.enabled) }
    var who by remember { mutableStateOf(peace.who) }
    var okMinute by remember { mutableStateOf(peace.okMinuteOfDay) }
    val canSms = remember(tick) {
        context.checkSelfPermission(android.Manifest.permission.SEND_SMS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }
    val askSms = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { tick++ }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("平安确认（和家人的约定）", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "开启后每天给家人发一条“报平安”。和家人的约定是：收不到这条消息，就打个电话。" +
                "这不是系统级的看护——手机没电、或系统把服务杀掉时我发不出去，所以这条约定比功能本身更重要。",
            fontSize = 15.sp,
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("开启平安确认", fontSize = 17.sp)
            Switch(checked = enabled, onCheckedChange = { enabled = it; peace.enabled = it; tick++ })
        }
        OutlinedTextField(
            who, { who = it; peace.who = it },
            label = { Text("老人称呼（如：妈妈）") },
            modifier = Modifier.fillMaxWidth(),
        )
        Text("每天几点前发这条消息", fontSize = 16.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(8 * 60, 9 * 60, 10 * 60).forEach { minute ->
                OutlinedButton(
                    onClick = { okMinute = minute; peace.okMinuteOfDay = minute; tick++ },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        "%02d:00".format(minute / 60),
                        fontWeight = if (okMinute == minute) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }
        }
        if (!canSms) {
            Text("短信权限未开启，我发不出这条消息。", fontSize = 16.sp, color = Color(0xFFC46A14))
            OutlinedButton(
                onClick = { askSms.launch(android.Manifest.permission.SEND_SMS) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("允许发送短信") }
        }
        Text(remember(tick) { peace.status() }, fontSize = 15.sp)
        OutlinedButton(
            onClick = {
                val decision = peace.preview()
                LoopLog.event("[peace] 手动检查：$decision")
                tick++
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("现在检查一次（不会发送）") }
    }
}

/**
 * Pairing this phone with the trusted circle.
 *
 * The family sets this up once. After that the phone reports what happened, and the people who care
 * open a web link — no app for them to install, and no extra permission for the elder to grant.
 */
@Composable
private fun ServerSection() {
    val context = LocalContext.current
    val app = context.applicationContext as HotlineApp
    val server = remember { ServerClient(app) }
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    var address by remember { mutableStateOf(server.baseUrl) }
    var elder by remember { mutableStateOf(server.elderName) }
    var busy by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("家人与社区（可信的人）", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "配对后，遇到办不了的事会通知家人和社区。他们不用装应用：打开网页就能看到，并接手处理。" +
                "只有名单里的人算数。",
            fontSize = 15.sp,
        )
        OutlinedTextField(
            elder, { elder = it; server.elderName = it },
            label = { Text("老人称呼（家人看到的，如：妈妈）") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            address, { address = it },
            label = { Text("服务器地址（如 http://192.168.1.5:8787）") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(
            onClick = {
                server.baseUrl = address
                server.elderName = elder
                busy = true
                scope.launch {
                    runCatching { server.pair() }
                    busy = false
                    tick++
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (busy) "正在配对…" else "配对 / 重新配对") }

        if (server.pairCode.isNotBlank()) {
            Text("配对码", fontSize = 16.sp)
            Text(server.pairCode, fontSize = 34.sp, fontWeight = FontWeight.Bold)
            Text(
                "让家人在手机浏览器打开 ${server.familyUrl()}，输入这个配对码，选自己的身份（家人/社区/邻居）。",
                fontSize = 15.sp,
            )
        }
        Text(remember(tick) { server.lastResult.ifBlank { "还没联系过服务器" } }, fontSize = 15.sp)
        OutlinedButton(
            onClick = {
                busy = true
                scope.launch {
                    runCatching { server.heartbeat("手动联系") }
                    busy = false
                    tick++
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("现在联系一次") }
        OutlinedButton(
            onClick = {
                busy = true
                scope.launch {
                    runCatching {
                        server.postEvent(
                            kind = "help",
                            title = "${server.elderName.ifBlank { "老人" }}需要人帮忙（测试）",
                            body = "这是一条测试求助，用来确认家人那边收得到。",
                            context = "目标：测试家人端\\n卡在：测试按钮",
                        )
                    }
                    busy = false
                    tick++
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("发一条测试求助") }
    }
}
