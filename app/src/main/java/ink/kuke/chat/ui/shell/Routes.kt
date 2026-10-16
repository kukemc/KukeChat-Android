package ink.kuke.chat.ui.shell

import androidx.compose.runtime.compositionLocalOf
import kotlinx.serialization.Serializable

/**
 * 页面栈里所有的页面（Navigation Compose 的类型安全路由）。各功能的页面在 feature/<名字>/ 下，由 MainShell 接到这些路由上。
 * 任何地方想跳转：LocalAppNav.current.go(UserRoute(id))。
 */
@Serializable data object TabsRoute
@Serializable data class ChatRoute(val conversationId: Long, val focusMessageId: Long? = null)
/** 群升级页；reason = members / files / messages 时顶部说明是因为哪项到了上限才来的 */
@Serializable data class GroupPlusRoute(val conversationId: Long, val reason: String? = null)
@Serializable data class GroupFilesRoute(val conversationId: Long)
/** 聊天记录搜索：conversationId 为 0 表示不限定会话（只搜我自己的会话） */
@Serializable data class ChatSearchRoute(val conversationId: Long, val query: String = "")
@Serializable data class GroupInfoRoute(val conversationId: Long)
@Serializable data class UserRoute(val userId: Long)
@Serializable data class PostRoute(val postId: Long)
@Serializable data object ComposePostRoute
/** 赞过某条动态的人 */
@Serializable data class PostLikesRoute(val postId: Long)
/** 话题页：某个 #话题 下的全部动态 */
@Serializable data class TopicRoute(val name: String)
@Serializable data object SearchRoute
@Serializable data object NotificationsRoute
/** 通知中心的某一类（friend / group / post / interact / system） */
@Serializable data class NotificationCategoryRoute(val category: String)
@Serializable data object AddFriendRoute
@Serializable data object CreateGroupRoute
@Serializable data object BlocksRoute
@Serializable data object OnlineUsersRoute
@Serializable data object HomeRoute
@Serializable data object TeamupRoute
@Serializable data class TeamupProfileRoute(val profileId: Long)
@Serializable data object TasksRoute
@Serializable data class TaskRoute(val taskId: String)
@Serializable data object BotsRoute
@Serializable data class BotRoute(val botId: Long)
@Serializable data object ShopRoute
/** 红包记录：收到的 / 发出的 */
@Serializable data object RedPacketRecordsRoute
@Serializable data object LevelRoute
@Serializable data class IntimacyRoute(val friendId: Long)
@Serializable data object FavoritesRoute
@Serializable data object AnnouncementsRoute
@Serializable data object JuryRoute
@Serializable data object SettingsRoute
@Serializable data class SettingsPageRoute(val page: String)
@Serializable data object KeepAliveRoute
@Serializable data object RankingRoute
/** 我的礼物记录（收到的 / 送出的） */
@Serializable data object GiftRecordsRoute

interface AppNav {
    fun go(route: Any)
    fun back()
}

val LocalAppNav = compositionLocalOf<AppNav> { error("LocalAppNav 没有提供：只能在 MainShell 里面用") }

/** 邀请链接落地页（加群 / 加好友）：token 来自 `?invite=` 或 kukechat://invite/<token> */
@Serializable data class InviteRoute(val token: String)
/** 某个群的任务列表（聊天顶栏「群任务」） */
@Serializable data class GroupTasksRoute(val conversationId: Long)
