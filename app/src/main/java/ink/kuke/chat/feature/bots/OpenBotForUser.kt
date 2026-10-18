package ink.kuke.chat.feature.bots

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import ink.kuke.chat.core.AppGraph
import ink.kuke.chat.core.net.ApiException
import ink.kuke.chat.core.net.userMessage
import ink.kuke.chat.ui.shell.BotRoute
import ink.kuke.chat.ui.shell.LocalAppNav
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

// 机器人的用户账号和 bot_id 一一对应且不会变：查过一次就记住，下次点直接跳
private val botIdByUserId = ConcurrentHashMap<Long, Long>()

/**
 * 聊天里点机器人的头像 / 名字、资料页 / 成员列表里的机器人：打开这个机器人的详情页（BotRoute）。
 *
 * 消息和成员里只有机器人的用户账号（users.is_bot），用 GET /bots/by-user/{user_id} 换到 bot_id 再跳。
 * 没公开、已删除、网络失败都只弹一条 Toast，不崩。
 */
@Composable
fun rememberOpenBotForUser(graph: AppGraph): (Long) -> Unit {
    val nav = LocalAppNav.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    return remember<(Long) -> Unit>(graph, nav, context, scope) {
        val api = BotApi(graph.api)
        val inFlight = mutableSetOf<Long>()
        val open: (Long) -> Unit = open@{ userId ->
            botIdByUserId[userId]?.let {
                nav.go(BotRoute(it))
                return@open
            }
            if (!inFlight.add(userId)) return@open
            scope.launch {
                try {
                    val bot = api.botByUser(userId)
                    botIdByUserId[userId] = bot.id
                    nav.go(BotRoute(bot.id))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    val text = when ((e as? ApiException)?.status) {
                        403 -> "这个机器人没有公开，看不了详情"
                        404 -> "这个机器人已经不在了"
                        else -> "机器人详情打开失败：${e.userMessage()}"
                    }
                    Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
                } finally {
                    inFlight.remove(userId)
                }
            }
        }
        open
    }
}
