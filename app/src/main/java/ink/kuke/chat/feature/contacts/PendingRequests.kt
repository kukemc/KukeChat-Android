package ink.kuke.chat.feature.contacts

import android.content.Context
import ink.kuke.chat.core.AppGraph
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 待处理的好友申请 / 入群申请（同 QQ「新朋友」）：联系人页的新朋友区、通知中心入口卡、底栏联系人角标共用这一份。
 * 服务端的 pending_count 不可靠（经常是 0），这里按通知列表里还能处理的申请自己数。
 *
 * 角标只提醒一次：打开过通知中心之后，角标只算之后新来的申请（旧的还在列表里等处理，但不再计数）。
 * 新服务端用 seen_at / unread 判断（多端同步）；旧服务端没有这个接口时，按本机记下的「看过的申请」判断。
 */
object PendingRequests {
    private val _items = MutableStateFlow<List<UnifiedNotification>?>(null)
    /** null = 还没拉过 */
    val items: StateFlow<List<UnifiedNotification>?> = _items.asStateFlow()

    private val _badge = MutableStateFlow(0)
    /** 角标数：还没看过的申请 */
    val badge: StateFlow<Int> = _badge.asStateFlow()

    private var ownerId: Long? = null
    /** 服务端支持「已看过」（列表里带 new_count） */
    private var serverSeen = false
    private var seenIds: MutableSet<String> = mutableSetOf()
    private var prefsContext: Context? = null

    fun isRequest(n: UnifiedNotification): Boolean = n.pending && (n.friendRequestId != null || n.groupJoinRequestId != null)

    suspend fun refresh(graph: AppGraph) {
        val uid = graph.session.session.value?.user?.id
        if (uid != ownerId) {
            ownerId = uid
            _items.value = null
            _badge.value = 0
            prefsContext = graph.appContext
            seenIds = loadSeen(graph.appContext, uid).toMutableSet()
        }
        runCatching { SocialApi(graph.api).notifications(100) }
            .onSuccess { list -> if (ownerId == uid) apply(list) }
    }

    /** 通知中心拉到的整份列表也顺手同步过来 */
    fun apply(list: NotificationList) {
        serverSeen = list.newCount != null
        val requests = list.items.filter(::isRequest)
        _items.value = requests
        recount()
    }

    private fun recount() {
        val requests = _items.value.orEmpty()
        _badge.value = if (serverSeen) requests.count { it.unread } else requests.count { it.id !in seenIds }
    }

    /** 打开了通知中心：现有的申请都算看过 */
    suspend fun markSeen(graph: AppGraph) {
        val requests = _items.value.orEmpty()
        seenIds.addAll(requests.map { it.id })
        saveSeen(graph.appContext, ownerId, seenIds)
        _items.value = requests.map { it.copy(unread = false) }
        _badge.value = 0
        runCatching { SocialApi(graph.api).markNotificationsSeen() }
    }

    /** 处理完（接受 / 拒绝 / 忽略）一条就从列表里拿掉（不等下次刷新） */
    fun remove(id: String) {
        _items.update { list -> list?.filterNot { it.id == id } }
        recount()
    }

    fun clear() {
        ownerId = null
        _items.value = null
        _badge.value = 0
        seenIds = mutableSetOf()
    }

    private fun prefs(context: Context) = context.getSharedPreferences("notification_seen", Context.MODE_PRIVATE)

    private fun loadSeen(context: Context, uid: Long?): Set<String> =
        if (uid == null) emptySet() else prefs(context).getStringSet("u$uid", emptySet()).orEmpty()

    private fun saveSeen(context: Context, uid: Long?, ids: Set<String>) {
        if (uid == null) return
        // 只留最近的 500 条，免得无限长
        prefs(context).edit().putStringSet("u$uid", ids.toList().takeLast(500).toSet()).apply()
    }
}
