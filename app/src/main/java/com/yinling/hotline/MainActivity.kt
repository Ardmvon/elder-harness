package com.yinling.hotline

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
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

    override fun onResume() {
        super.onResume()
        permissionVersion++
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
            var answer by remember { mutableStateOf("") }
            val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                val words = it.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                if (!words.isNullOrEmpty()) request = words.first()
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
                            onOverlayPermission = {
                                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
                            },
                            onAccessPermission = {
                                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                            },
                            onVoice = {
                                voice.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                                    putExtra(RecognizerIntent.EXTRA_PROMPT, "请说要办的事")
                                })
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
                                if (session.familyPhone.isBlank()) settings = true
                                else session.requestHelp(state.goal.ifBlank { "使用手机" }, state.message)
                            },
                            onCall = {
                                if (session.familyPhone.isBlank()) settings = true else session.call()
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
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("银龄专线", fontSize = 30.sp, fontWeight = FontWeight.Bold)
            IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, contentDescription = "设置") }
        }
        Text("今天有什么事要办？", fontSize = 22.sp)
        OutlinedTextField(
            value = request,
            onValueChange = onRequest,
            label = { Text("说或写下要办的事") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 20.sp),
        )
        Button(onClick = onVoice, modifier = Modifier.fillMaxWidth().height(64.dp)) {
            Icon(Icons.Default.Mic, contentDescription = null)
            Spacer(Modifier.padding(5.dp))
            Text("说给接线员听", fontSize = 20.sp)
        }
        if (!overlayReady) OutlinedButton(onClick = onOverlayPermission, modifier = Modifier.fillMaxWidth()) {
            Text("开启悬浮窗", fontSize = 18.sp)
        }
        if (!accessReady) OutlinedButton(onClick = onAccessPermission, modifier = Modifier.fillMaxWidth()) {
            Text("开启屏幕协助", fontSize = 18.sp)
        }
        Button(
            onClick = onStart,
            enabled = request.isNotBlank() && overlayReady && accessReady,
            modifier = Modifier.fillMaxWidth().height(64.dp),
        ) { Text("开始办事", fontSize = 20.sp) }

        if (state.goal.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text("正在办", fontSize = 17.sp, color = MaterialTheme.colorScheme.primary)
            Text(state.goal, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
            Text(state.message, fontSize = 18.sp)
            if (autoConfirm) {
                Text("演示模式：所有操作自动确认，不询问您。", fontSize = 15.sp, color = MaterialTheme.colorScheme.secondary)
            }
            if (state.phase == TaskPhase.ASKING) {
                OutlinedTextField(
                    value = answer,
                    onValueChange = onAnswerChange,
                    label = { Text("回答接线员") },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 19.sp),
                )
                Button(
                    onClick = { onAnswer(answer) },
                    enabled = answer.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                ) { Text("回答", fontSize = 19.sp) }
            }
            if (state.phase == TaskPhase.PAUSED || state.phase == TaskPhase.NEEDS_FAMILY ||
                state.phase == TaskPhase.CANNOT || state.phase == TaskPhase.NEEDS_PERSON
            ) {
                OutlinedButton(onClick = onResumeTask, modifier = Modifier.fillMaxWidth()) {
                    Text("接着办", fontSize = 18.sp)
                }
            }
            if (state.phase == TaskPhase.COMPLETED) {
                TextButton(onClick = onFinish) { Text("这件事办好了", fontSize = 18.sp) }
            }
        }
        if (history.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text("上次没办完的事", fontSize = 17.sp, color = MaterialTheme.colorScheme.primary)
            history.forEach { item ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(item.goal, fontSize = 18.sp, maxLines = 2)
                        Text(
                            if (item.unfinished) "还没办完 · 走到第${item.steps}步" else "上次已经办好了",
                            fontSize = 14.sp,
                            color = Color.DarkGray,
                        )
                    }
                    OutlinedButton(onClick = { onRestore(item.id) }) { Text("接着办", fontSize = 16.sp) }
                }
            }
        }
        OutlinedButton(onClick = onFamily, modifier = Modifier.fillMaxWidth().height(58.dp)) {
            Text("请家人帮忙", fontSize = 19.sp)
        }
        TextButton(onClick = onCall, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Call, contentDescription = null)
            Text("给家人打电话", fontSize = 18.sp)
        }
    }
}

@Composable
private fun SettingsPage(session: SessionController, onBack: () -> Unit) {
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
            Text("设置", fontSize = 27.sp, fontWeight = FontWeight.Bold)
        }
        Text("家人", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        OutlinedTextField(familyName, { familyName = it }, label = { Text("称呼") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(familyPhone, { familyPhone = it }, label = { Text("电话号码") }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Text("智能接线员", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Text("办事时，当前页面的可见文字会发送到这里填写的模型服务。", fontSize = 15.sp)
        OutlinedTextField(endpoint, { endpoint = it }, label = { Text("服务地址") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(model, { model = it }, label = { Text("模型") }, modifier = Modifier.fillMaxWidth())
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
        Text("访问密钥仅在本次打开应用期间保留。", fontSize = 14.sp, color = Color.DarkGray)
        Spacer(Modifier.height(8.dp))
        KeepAliveSection()
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
