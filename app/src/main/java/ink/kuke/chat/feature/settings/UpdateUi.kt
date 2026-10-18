package ink.kuke.chat.feature.settings

import android.content.Context
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import ink.kuke.chat.core.AppGraph
import ink.kuke.chat.core.net.userMessage
import ink.kuke.chat.ui.theme.KcTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.random.Random

/**
 * 更新状态（进程内单例）：启动时检查一次，设置页「检查更新」再查；下载放在 AppGraph 的作用域里，离开页面不会中断。
 * 进程常驻后台（实时连接服务）时另有两种自动检查，见 [watch]：后端推送 release.published、回到前台且距上次检查超过 1 小时。
 * 对话框由 MainActivity 里的 [UpdateHost] 统一画，盖在整个应用上面（强制更新时挡住使用）。
 */
object UpdateCenter {
    sealed interface Phase {
        data object Idle : Phase
        data object Checking : Phase
        data class UpToDate(val version: String) : Phase
        data class Available(val info: AppUpdater.Info) : Phase
        data class Downloading(val info: AppUpdater.Info, val progress: Float) : Phase
        data class Ready(val info: AppUpdater.Info, val file: File, val waitingPermission: Boolean = false) : Phase
        data class Error(val message: String, val info: AppUpdater.Info?) : Phase
    }

    private const val PREFS = "kc_app_update"
    private const val KEY_SKIPPED = "skipped_version"
    /** 回到前台时，距上次检查超过这么久就再查一次 */
    private const val FOREGROUND_RECHECK_MS = 60 * 60 * 1000L
    /** 收到 release.published 后随机等 0–30 秒再查，免得所有在线客户端同一秒打到服务器 */
    private const val PUSH_JITTER_MS = 30_000L

    private val _phase = MutableStateFlow<Phase>(Phase.Idle)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    private val _dialogOpen = MutableStateFlow(false)
    val dialogOpen: StateFlow<Boolean> = _dialogOpen.asStateFlow()

    /** 最近一次成功检查的结果（设置页、底栏红点用） */
    private val _latest = MutableStateFlow<AppUpdater.Info?>(null)
    val latest: StateFlow<AppUpdater.Info?> = _latest.asStateFlow()

    private var startupChecked = false
    private var watching = false
    private var pushCheck: Job? = null
    /** 上次发起检查的时间（elapsedRealtime，含休眠）；0 表示还没查过 */
    @Volatile private var lastCheckedAt = 0L

    /** 当前对话框关联的版本信息（有的话） */
    fun infoOf(phase: Phase): AppUpdater.Info? = when (phase) {
        is Phase.Available -> phase.info
        is Phase.Downloading -> phase.info
        is Phase.Ready -> phase.info
        is Phase.Error -> phase.info
        else -> null
    }

    fun checkOnStartup(graph: AppGraph) {
        if (startupChecked) return
        startupChecked = true
        check(graph, manual = false)
    }

    /**
     * 进程启动时（KukeApp.onCreate，主线程）调一次。进程可能在后台活好几天，只靠启动检查会一直发现不了新版本：
     * - 后端发版时推 release.published（KukeChat-Server/docs/releases.md 2.3）：是安卓 stable 的就随机等 0–30 秒后查一次；
     *   在后台收到也查，对话框等用户回到应用时由 [UpdateHost] 画出来；
     * - 回到前台（整个应用，不是单个页面）时距上次检查超过 1 小时就再查。
     * 都是普通的自动检查：「稍后」跳过的版本不弹，强制更新照常弹。
     */
    fun watch(graph: AppGraph) {
        if (watching) return
        watching = true
        graph.scope.launch {
            graph.realtime.events.collect { event ->
                if (event.type != "release.published") return@collect
                val platform = event.data["platform"]?.jsonPrimitive?.contentOrNull
                val channel = event.data["channel"]?.jsonPrimitive?.contentOrNull
                if (platform != AppUpdater.PLATFORM || channel != AppUpdater.CHANNEL) return@collect
                pushCheck?.cancel()
                pushCheck = graph.scope.launch(Dispatchers.Main) {
                    delay(Random.nextLong(PUSH_JITTER_MS))
                    check(graph, manual = false)
                }
            }
        }
        ProcessLifecycleOwner.get().lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event != Lifecycle.Event.ON_START || !startupChecked) return@LifecycleEventObserver
            if (SystemClock.elapsedRealtime() - lastCheckedAt > FOREGROUND_RECHECK_MS) check(graph, manual = false)
        })
    }

    /** 手动检查总是打开对话框显示结果；自动检查只在有没跳过的新版本（或强制更新）时打开 */
    fun check(graph: AppGraph, manual: Boolean) {
        val now = _phase.value
        if (now is Phase.Checking || now is Phase.Downloading) {
            if (manual) _dialogOpen.value = true
            return
        }
        if (now is Phase.Ready) {
            if (manual) _dialogOpen.value = true
            return
        }
        _phase.value = Phase.Checking
        lastCheckedAt = SystemClock.elapsedRealtime()
        if (manual) _dialogOpen.value = true
        graph.scope.launch {
            runCatching { AppUpdater.check(graph.api) }
                .onSuccess { info ->
                    _latest.value = info
                    if (info.hasUpdate) {
                        _phase.value = Phase.Available(info)
                        val skipped = skippedVersion(graph.appContext) == info.latestVersion
                        if (manual || info.forced || !skipped) _dialogOpen.value = true
                    } else {
                        _phase.value = Phase.UpToDate(info.currentVersion)
                    }
                }
                .onFailure { error ->
                    _phase.value = Phase.Error(error.userMessage(), null)
                    if (!manual) _phase.value = Phase.Idle
                }
        }
    }

    fun download(graph: AppGraph) {
        val info = infoOf(_phase.value) ?: return
        if (_phase.value is Phase.Downloading) return
        _phase.value = Phase.Downloading(info, 0f)
        graph.scope.launch {
            runCatching { AppUpdater.download(graph.appContext, graph.api.http, info) { p -> _phase.value = Phase.Downloading(info, p) } }
                .onSuccess { file ->
                    _phase.value = Phase.Ready(info, file)
                    install(graph.appContext)
                }
                .onFailure { _phase.value = Phase.Error(it.userMessage(), info) }
        }
    }

    fun install(context: Context) {
        val ready = _phase.value as? Phase.Ready ?: return
        when (val result = AppUpdater.install(context, ready.file)) {
            AppUpdater.InstallResult.Started -> _phase.value = ready.copy(waitingPermission = false)
            AppUpdater.InstallResult.NeedPermission -> _phase.value = ready.copy(waitingPermission = true)
            is AppUpdater.InstallResult.Failed -> _phase.value = Phase.Error(result.message, ready.info)
        }
    }

    /** 从「安装未知应用」设置页回来：权限给了就直接拉起安装 */
    fun onResume(context: Context) {
        val ready = _phase.value as? Phase.Ready ?: return
        if (ready.waitingPermission && AppUpdater.canInstall(context)) install(context)
    }

    fun open() { _dialogOpen.value = true }

    /** 「稍后」：关掉对话框，记下这个版本，自动检查不再弹（手动检查、强制更新照常弹） */
    fun later(context: Context) {
        val info = infoOf(_phase.value)
        if (info?.forced == true) return
        if (info != null) prefs(context).edit().putString(KEY_SKIPPED, info.latestVersion).apply()
        _dialogOpen.value = false
    }

    fun close() {
        if (infoOf(_phase.value)?.forced == true && _phase.value !is Phase.Error) return
        _dialogOpen.value = false
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun skippedVersion(context: Context): String? = prefs(context).getString(KEY_SKIPPED, null)
}

/** 放在 MainActivity 最外层：启动检查一次、回前台时续上安装、画更新对话框 */
@Composable
fun UpdateHost(graph: AppGraph) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(Unit) { UpdateCenter.checkOnStartup(graph) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) UpdateCenter.onResume(context) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val open by UpdateCenter.dialogOpen.collectAsState()
    val phase by UpdateCenter.phase.collectAsState()
    if (open && phase != UpdateCenter.Phase.Idle) UpdateDialog(graph, phase)
}

@Composable
private fun UpdateDialog(graph: AppGraph, phase: UpdateCenter.Phase) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val context = LocalContext.current
    val info = UpdateCenter.infoOf(phase)
    val forced = info?.forced == true
    Dialog(
        onDismissRequest = { UpdateCenter.close() },
        properties = DialogProperties(dismissOnBackPress = !forced, dismissOnClickOutside = !forced, usePlatformDefaultWidth = false),
    ) {
        Column(
            Modifier
                .padding(horizontal = 24.dp)
                .widthIn(max = 440.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(colors.panel)
                .padding(horizontal = 24.dp, vertical = 24.dp),
        ) {
            if (info == null) {
                // 检查中 / 已是最新 / 检查失败
                val (title, body) = when (phase) {
                    UpdateCenter.Phase.Checking -> "检查更新" to "正在检查更新…"
                    is UpdateCenter.Phase.UpToDate -> "已是最新版本" to "当前版本 v${phase.version}"
                    is UpdateCenter.Phase.Error -> "检查更新失败" to phase.message
                    else -> "检查更新" to ""
                }
                Text(title, style = type.title, color = colors.text, fontWeight = FontWeight.W600)
                Spacer(Modifier.height(8.dp))
                Text(body, style = type.secondary, color = if (phase is UpdateCenter.Phase.Error) colors.danger else colors.muted)
                if (phase == UpdateCenter.Phase.Checking) {
                    Spacer(Modifier.height(16.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(2.dp), color = colors.text, trackColor = colors.border)
                }
                Spacer(Modifier.height(24.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    if (phase is UpdateCenter.Phase.Error) {
                        GhostAction("关闭") { UpdateCenter.close() }
                        Spacer(Modifier.width(8.dp))
                        InkPill("重试") { UpdateCenter.check(graph, manual = true) }
                    } else if (phase != UpdateCenter.Phase.Checking) {
                        InkPill("好") { UpdateCenter.close() }
                    }
                }
                return@Column
            }

            Text(if (forced) "需要更新" else "发现新版本", style = type.caption, color = colors.muted)
            Spacer(Modifier.height(4.dp))
            Text("KukeChat ${info.latestVersion}", style = type.title, color = colors.text, fontWeight = FontWeight.W600)
            Spacer(Modifier.height(6.dp))
            val meta = listOfNotNull("当前 v${info.currentVersion}", AppUpdater.shortDate(info.publishedAt), info.size?.let(AppUpdater::humanSize))
            Text(meta.joinToString("  ·  "), style = type.caption, color = colors.muted)
            if (forced) {
                Spacer(Modifier.height(14.dp))
                Text("当前版本已停止支持，请更新后继续使用", style = type.secondary, color = colors.text, fontWeight = FontWeight.W500)
            }
            Spacer(Modifier.height(18.dp))
            Hairline()
            Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()).padding(vertical = 14.dp)) {
                if (info.changelog.isBlank()) Text("这个版本没有填写更新日志。", style = type.secondary, color = colors.muted)
                else ChangelogText(info.changelog)
            }
            Hairline()
            Spacer(Modifier.height(18.dp))

            when (phase) {
                is UpdateCenter.Phase.Downloading -> {
                    val pct = if (phase.progress >= 0f) "  ${(phase.progress * 100).toInt()}%" else ""
                    Text("正在下载$pct", style = type.caption, color = colors.muted)
                    Spacer(Modifier.height(8.dp))
                    val barModifier = Modifier.fillMaxWidth().height(3.dp).clip(CircleShape)
                    if (phase.progress >= 0f) LinearProgressIndicator(progress = { phase.progress }, modifier = barModifier, color = colors.text, trackColor = colors.border, drawStopIndicator = {})
                    else LinearProgressIndicator(modifier = barModifier, color = colors.text, trackColor = colors.border)
                    Spacer(Modifier.height(18.dp))
                }
                is UpdateCenter.Phase.Ready -> if (phase.waitingPermission) {
                    Text("请在系统设置里允许 KukeChat 安装应用，回来后会自动继续。", style = type.caption, color = colors.muted)
                    Spacer(Modifier.height(14.dp))
                }
                is UpdateCenter.Phase.Error -> {
                    Text(phase.message, style = type.caption, color = colors.danger)
                    Spacer(Modifier.height(14.dp))
                }
                else -> Unit
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                if (!forced && phase !is UpdateCenter.Phase.Downloading) {
                    GhostAction("稍后") { UpdateCenter.later(context) }
                    Spacer(Modifier.width(8.dp))
                }
                when (phase) {
                    is UpdateCenter.Phase.Available -> InkPill("下载并安装") { UpdateCenter.download(graph) }
                    is UpdateCenter.Phase.Downloading -> InkPill("下载中…", enabled = false) {}
                    is UpdateCenter.Phase.Ready -> InkPill("安装") { UpdateCenter.install(context) }
                    is UpdateCenter.Phase.Error -> InkPill("重新下载") { UpdateCenter.download(graph) }
                    else -> Unit
                }
            }
        }
    }
}

/** 更新日志正文：「- 」开头的行画成列表，其余按段落 */
@Composable
fun ChangelogText(text: String, modifier: Modifier = Modifier) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        text.lines().map { it.trimEnd() }.filter { it.isNotBlank() }.forEach { line ->
            val trimmed = line.trimStart()
            val bullet = trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("• ")
            if (bullet) {
                Row(verticalAlignment = Alignment.Top) {
                    Box(Modifier.padding(top = 8.dp, end = 10.dp).size(4.dp).clip(CircleShape).background(colors.muted))
                    Text(trimmed.drop(2).trim(), style = type.secondary, color = colors.text)
                }
            } else {
                Text(trimmed.trimStart('#', ' '), style = type.secondary, color = colors.muted)
            }
        }
    }
}

@Composable
private fun Hairline() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(KcTheme.colors.border))
}

/** 墨色胶囊主按钮（亮色下近黑底白字，暗色下反过来） */
@Composable
private fun InkPill(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    val colors = KcTheme.colors
    Box(
        Modifier
            .heightIn(min = 40.dp)
            .clip(CircleShape)
            .background(if (enabled) colors.text else colors.text.copy(alpha = 0.35f))
            .clickable(enabled = enabled, interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = KcTheme.type.secondary, color = colors.panel, fontWeight = FontWeight.W600, maxLines = 1)
    }
}

@Composable
private fun GhostAction(text: String, onClick: () -> Unit) {
    Box(
        Modifier
            .heightIn(min = 40.dp)
            .clip(CircleShape)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = KcTheme.type.secondary, color = KcTheme.colors.muted, fontWeight = FontWeight.W500, maxLines = 1)
    }
}

/** 设置 › 关于与更新 › 更新日志：已发布的安卓版本，从新到旧 */
@Composable
fun ChangelogPage(graph: AppGraph) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    var items by remember { mutableStateOf<List<AppUpdater.Release>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        runCatching { AppUpdater.history(graph.api) }
            .onSuccess { items = it }
            .onFailure { error = it.userMessage() }
    }
    val list = items
    when {
        error != null -> CenterText(error ?: "")
        list == null -> CenterText("正在加载…")
        list.isEmpty() -> CenterText("还没有发布过版本")
        else -> Column(Modifier.fillMaxWidth().background(colors.chat).verticalScroll(rememberScrollState()).navigationBarsPadding().padding(vertical = 8.dp)) {
            list.forEachIndexed { index, release ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("v${release.version}", style = type.rowTitle, color = colors.text, fontWeight = FontWeight.W600)
                        if (AppUpdater.compare(release.version, AppUpdater.currentVersion) == 0) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "当前版本",
                                style = type.caption,
                                color = colors.muted,
                                modifier = Modifier.clip(CircleShape).background(colors.panelMuted).padding(horizontal = 8.dp, vertical = 2.dp),
                            )
                        }
                    }
                    val meta = listOfNotNull(AppUpdater.shortDate(release.publishedAt), release.size?.let(AppUpdater::humanSize))
                    if (meta.isNotEmpty()) {
                        Spacer(Modifier.height(2.dp))
                        Text(meta.joinToString("  ·  "), style = type.caption, color = colors.muted)
                    }
                    Spacer(Modifier.height(10.dp))
                    if (release.changelog.isBlank()) Text("没有填写更新日志。", style = type.secondary, color = colors.muted)
                    else ChangelogText(release.changelog)
                }
                if (index < list.lastIndex) Box(Modifier.padding(horizontal = 20.dp).fillMaxWidth().height(1.dp).background(colors.border))
            }
        }
    }
}
