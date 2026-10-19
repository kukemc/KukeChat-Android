package ink.kuke.chat.feature.share

import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import ink.kuke.chat.KukeApp
import ink.kuke.chat.MainActivity
import ink.kuke.chat.core.AppGraph
import ink.kuke.chat.core.model.Conversation
import ink.kuke.chat.core.model.User
import ink.kuke.chat.feature.appearance.AppearanceStore
import ink.kuke.chat.feature.appearance.LocalAppearance
import ink.kuke.chat.feature.appearance.resolve
import ink.kuke.chat.feature.auth.LoginScreen
import ink.kuke.chat.feature.contacts.PinyinIndex
import ink.kuke.chat.feature.contacts.SocialApi
import ink.kuke.chat.feature.contacts.friendsSlot
import ink.kuke.chat.feature.groupplus.FileQuota
import ink.kuke.chat.feature.groupplus.GroupPlusApi
import ink.kuke.chat.service.Notifier
import ink.kuke.chat.ui.theme.KcTheme
import ink.kuke.chat.ui.theme.KukeTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 从别的应用分享进来（B 站、相册、浏览器、文件管理器……）：选一个或几个聊天发过去。
 *
 * 单独一个任务（taskAffinity 和主界面不同），不搅乱主界面的返回栈；发完就关。
 * 没登录时先在这里登录，登录完接着选。系统分享面板里点的是某个会话的直接分享快捷方式时，那个会话已经选好。
 */
class ShareReceiverActivity : ComponentActivity() {
    private lateinit var model: ShareModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        model = ViewModelProvider(this)[ShareModel::class.java]
        model.open(intent, force = false)
        val graph = model.graph

        setContent {
            val appearanceStore = remember { AppearanceStore.shared(this@ShareReceiverActivity) }
            val appearance by appearanceStore.state.collectAsStateWithLifecycle()
            val resolved = appearance.resolve(isSystemInDarkTheme())
            CompositionLocalProvider(LocalAppearance provides appearance) {
                KukeTheme(dark = resolved.dark, accent = resolved.accent, fontScale = resolved.fontScale, resolved = resolved.colors) {
                    val session by graph.session.session.collectAsStateWithLifecycle()
                    val signedOut by graph.signedOut.collectAsStateWithLifecycle()
                    var loaded by remember { mutableStateOf(graph.session.session.value != null) }
                    LaunchedEffect(Unit) {
                        if (graph.session.session.value == null) graph.session.load()
                        loaded = true
                    }
                    Box(Modifier.fillMaxSize().background(KcTheme.colors.panel)) {
                        when {
                            !loaded -> Unit
                            session == null -> {
                                LaunchedEffect(Unit) { Toast.makeText(this@ShareReceiverActivity, "登录后继续分享", Toast.LENGTH_SHORT).show() }
                                LoginScreen(graph, signedOut, onSignedIn = {})
                            }
                            else -> {
                                // 在这里登录的：主界面没开着，连接也顺手连上（发出去的消息靠它确认）
                                LaunchedEffect(session?.token) { graph.realtime.start() }
                                ShareScreen(model, onClose = ::finish, onSent = ::onSent)
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        model.open(intent, force = true)
    }

    /** 只发了一个聊天：直接进那个聊天；几个：提示一句就关 */
    private fun onSent(outcome: ShareOutcome) {
        outcome.errors.firstOrNull()?.let { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
        val ids = outcome.conversationIds
        if (ids.size == 1) {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(Notifier.EXTRA_CONVERSATION_ID, ids[0]),
            )
        } else if (ids.size > 1 && outcome.errors.isEmpty()) {
            Toast.makeText(this, "已发送到 ${ids.size} 个聊天", Toast.LENGTH_SHORT).show()
        }
        finish()
    }
}

/** 群文件配额：查询中 / 查到了（null = 查不到，照常发由服务端把关） */
sealed interface QuotaState {
    data object Loading : QuotaState
    data class Ready(val quota: FileQuota?) : QuotaState
}

/** 一个目标能发什么：全部 / 部分（带说明）/ 发不了（带原因）/ 还在查群文件配额 */
sealed interface Eligibility {
    data object Ok : Eligibility
    data object Checking : Eligibility
    data class Partial(val reason: String) : Eligibility
    data class Blocked(val reason: String) : Eligibility
}

class ShareModel(application: Application) : AndroidViewModel(application) {
    val graph: AppGraph = (application as KukeApp).graph

    var payload by mutableStateOf<SharePayload?>(null)
        private set
    var parsing by mutableStateOf(true)
        private set
    var query by mutableStateOf("")
    var note by mutableStateOf("")
    val selected = mutableStateListOf<ShareTarget>()
    val quotas = mutableStateMapOf<Long, QuotaState>()
    var friends by mutableStateOf<List<User>>(emptyList())
        private set
    var sending by mutableStateOf(false)
        private set
    /** 一句话提示（界面用 Toast 显示后清空） */
    var notice by mutableStateOf<String?>(null)
    /** 从直接分享快捷方式进来：要预选的会话（列表到了再选上） */
    var preselect by mutableStateOf<Long?>(null)
        private set

    private var opened = false
    private var parseJob: Job? = null
    private var listsLoaded = false

    fun open(intent: Intent?, force: Boolean) {
        if (opened && !force) return
        opened = true
        selected.clear()
        note = ""
        query = ""
        parsing = true
        preselect = ShareShortcuts.conversationOf(intent?.getStringExtra(ShortcutManagerCompat.EXTRA_SHORTCUT_ID))
        parseJob?.cancel()
        parseJob = viewModelScope.launch {
            payload = withContext(Dispatchers.IO) { runCatching { SharePayload.parse(getApplication(), intent) }.getOrNull() }
            parsing = false
        }
    }

    /** 会话列表和好友（登录后由分享页调；没登录时请求会 401，登录完再拉） */
    fun loadLists() {
        if (listsLoaded) return
        listsLoaded = true
        // 后台进程里会话列表可能还没拉过：先摆缓存，再刷新
        graph.conversations.restoreCached()
        viewModelScope.launch { graph.conversations.refresh() }
        val slot = graph.screenCache.friendsSlot()
        fun apply(list: List<ink.kuke.chat.feature.contacts.Friendship>) {
            friends = list.mapNotNull { it.other }.sortedWith { a, b -> PinyinIndex.compare(a.displayName, b.displayName) }
        }
        slot.get()?.let(::apply)
        viewModelScope.launch {
            runCatching { SocialApi(graph.api).friends() }
                .onSuccess {
                    slot.put(it)
                    apply(it)
                }
                // 下次进分享页再试
                .onFailure { listsLoaded = false }
        }
    }

    fun consumePreselect() {
        preselect = null
    }

    fun eligibility(target: ShareTarget): Eligibility {
        val p = payload ?: return Eligibility.Blocked("没有可以分享的内容")
        target.blockedReason?.let { return Eligibility.Blocked(it) }
        val files = p.files
        // 「我的电脑」：什么都能发（文件走临时文件），只挡超过单个上限的
        if (target.self) {
            val limit = ink.kuke.chat.feature.selfchat.SelfTransfers.lastList?.maxFileBytes ?: ink.kuke.chat.feature.selfchat.SelfTransfers.DEFAULT_MAX_FILE
            val over = files.filter { it.size > limit }
            return when {
                over.isEmpty() -> Eligibility.Ok
                over.size == files.size && !p.hasDirectSendable -> Eligibility.Blocked("文件超过 ${ink.kuke.chat.feature.groupplus.formatBytes(limit)}，不能发送")
                else -> Eligibility.Partial("${over.size} 个文件超过 ${ink.kuke.chat.feature.groupplus.formatBytes(limit)}，不会发出")
            }
        }
        if (!target.group) {
            if (files.isEmpty()) return Eligibility.Ok
            return if (p.hasDirectSendable) Eligibility.Partial("私聊暂时只能发送文字和图片，文件不会发出")
            else Eligibility.Blocked("私聊暂时只能发送文字和图片")
        }
        if (files.isEmpty()) return Eligibility.Ok
        val id = target.conversationId ?: return Eligibility.Ok
        val state = quotas[id] ?: return Eligibility.Ok
        if (state is QuotaState.Loading) return Eligibility.Checking
        val (blocked, limit) = ShareSender.fileRule((state as QuotaState.Ready).quota)
        if (blocked != null) {
            return if (p.hasDirectSendable) Eligibility.Partial("$blocked，只发送文字和图片") else Eligibility.Blocked(blocked)
        }
        val over = files.filter { it.size > limit }
        return when {
            over.isEmpty() -> Eligibility.Ok
            over.size == files.size && !p.hasDirectSendable -> Eligibility.Blocked("文件超过 ${ink.kuke.chat.feature.groupplus.formatBytes(limit)}，不能发送")
            else -> Eligibility.Partial("${over.size} 个文件超过 ${ink.kuke.chat.feature.groupplus.formatBytes(limit)}，不会发出")
        }
    }

    fun toggle(target: ShareTarget) {
        val existing = selected.indexOfFirst { it.key == target.key }
        if (existing >= 0) {
            selected.removeAt(existing)
            return
        }
        when (val e = eligibility(target)) {
            is Eligibility.Blocked -> {
                notice = e.reason
                return
            }
            else -> Unit
        }
        if (selected.size >= MAX_TARGETS) {
            notice = "最多选 $MAX_TARGETS 个聊天"
            return
        }
        selected.add(target)
        val id = target.conversationId
        // 「我的电脑」：顺手查一下临时文件的上限（查不到按默认 1 GB，上传时服务端把关）
        if (target.self && id != null && payload?.files?.isNotEmpty() == true && ink.kuke.chat.feature.selfchat.SelfTransfers.lastList == null) {
            viewModelScope.launch { runCatching { ink.kuke.chat.feature.selfchat.SelfTransfers.list(graph, id) } }
        }
        if (target.group && id != null && payload?.files?.isNotEmpty() == true && quotas[id] == null) {
            quotas[id] = QuotaState.Loading
            viewModelScope.launch {
                val quota = runCatching { GroupPlusApi(graph).usage(id) }.getOrNull()
                quotas[id] = QuotaState.Ready(quota)
                // 查完发现完全发不了：取消选中并说明
                val e = eligibility(target)
                if (e is Eligibility.Blocked) {
                    selected.removeAll { it.key == target.key }
                    notice = "「${target.name}」${e.reason}"
                }
            }
        }
    }

    val canSend: Boolean
        get() = !sending && payload?.isEmpty == false && selected.isNotEmpty() && selected.none { eligibility(it) is Eligibility.Checking }

    fun send(onDone: (ShareOutcome) -> Unit) {
        val p = payload ?: return
        if (!canSend) return
        sending = true
        val jobs = selected.map { target ->
            val id = target.conversationId
            if (target.self && id != null) {
                val limit = ink.kuke.chat.feature.selfchat.SelfTransfers.lastList?.maxFileBytes ?: ink.kuke.chat.feature.selfchat.SelfTransfers.DEFAULT_MAX_FILE
                return@map ShareJob(target, p.files.filter { it.size <= limit }, direct = false, fileLimit = limit)
            }
            if (!target.group || id == null || p.files.isEmpty()) {
                ShareJob(target, emptyList(), direct = false, fileLimit = 0)
            } else {
                val quota = (quotas[id] as? QuotaState.Ready)?.quota
                val (blocked, limit) = ShareSender.fileRule(quota)
                if (blocked != null) ShareJob(target, emptyList(), false, 0)
                else ShareJob(target, p.files.filter { it.size <= limit }, quota?.direct == true, limit)
            }
        }
        viewModelScope.launch {
            try {
                val outcome = ShareSender.send(getApplication(), graph, p, jobs, note)
                if (outcome.conversationIds.isEmpty()) {
                    notice = outcome.errors.firstOrNull() ?: "没有发出去，稍后再试"
                } else {
                    onDone(outcome)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notice = e.message ?: "没有发出去，稍后再试"
            } finally {
                sending = false
            }
        }
    }

    companion object {
        const val MAX_TARGETS = 9
    }
}

/** 会话 → 分享目标 */
internal fun Conversation.shareTarget(): ShareTarget = ShareTarget(
    key = "c:$id",
    conversationId = id,
    userId = directUser?.id,
    group = isGroup,
    name = name(),
    avatar = if (isGroup) avatarUrl else directUser?.avatarUrl ?: avatarUrl,
    tone = directUser?.id ?: id,
    plusTier = plusTier.takeIf { isGroup },
    detail = when {
        isSelf -> "发给自己的电脑 · 文件 24 小时后过期"
        isGroup -> if (memberCount > 0) "群聊 · $memberCount 人" else "群聊"
        directUser?.isBot == true -> "机器人"
        else -> null
    },
    blockedReason = if (isGroup && allMuted && myRole != "owner" && myRole != "admin") "全员禁言中" else null,
    self = isSelf,
)

/** 还没有私聊会话的好友 → 分享目标（发送前再建会话） */
internal fun User.shareTarget(): ShareTarget = ShareTarget(
    key = "u:$id",
    conversationId = null,
    userId = id,
    group = false,
    name = displayName,
    avatar = avatarUrl,
    tone = id,
    plusTier = null,
    detail = "好友",
)
