package ink.kuke.chat.feature.shop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import ink.kuke.chat.core.AppGraph
import ink.kuke.chat.core.model.User
import ink.kuke.chat.core.net.KcJson
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * 别人和自己装备的装扮（头像框、气泡皮肤）。
 *   · 各处 payload（消息 sender、会话 direct_user、资料）自带 cosmetics；
 *   · 实时事件 user.cosmetics.updated 收到的最新一份记在 [live] 里，压过 payload（不用去改各处缓存）；
 *   · 自己的另有一份跟着登录用户走（[mine]）。
 * 解析结果按 style 记住，同一件装扮全屏只解析一次。只在主线程用。
 */
object Cosmetics {
    private val live = mutableStateMapOf<Long, JsonObject>()
    var myId by mutableStateOf(0L)
        private set
    private var mine by mutableStateOf<JsonObject?>(null)

    private val frames = HashMap<JsonElement, AvatarFrame?>()
    private val bubbles = HashMap<JsonElement, BubbleSkin?>()

    /** 登录用户变了（含换了装扮被持久化）时调 */
    fun bindMe(user: User?) {
        myId = user?.id ?: 0L
        mine = user?.cosmetics
    }

    fun clear() {
        live.clear()
        frames.clear()
        bubbles.clear()
        bindMe(null)
    }

    /** 某人此刻的装扮：实时事件 > 自己的登录用户 > payload */
    fun of(userId: Long?, payload: JsonObject?): JsonObject? {
        val id = userId ?: return payload
        live[id]?.let { return it }
        if (id != 0L && id == myId) return mine ?: payload
        return payload
    }

    private fun styleOf(cosmetics: JsonObject?, slot: String): JsonElement? = (cosmetics?.get(slot) as? JsonObject)?.get("style")

    fun frame(cosmetics: JsonObject?): AvatarFrame? {
        val style = styleOf(cosmetics, "frame") ?: return null
        return cached(frames, style) { parseAvatarFrame(it) }
    }

    fun bubble(cosmetics: JsonObject?): BubbleSkin? {
        val style = styleOf(cosmetics, "bubble") ?: return null
        return cached(bubbles, style) { parseBubbleSkin(it) }
    }

    private fun <T> cached(map: HashMap<JsonElement, T?>, key: JsonElement, parse: (JsonElement) -> T?): T? {
        if (map.containsKey(key)) return map[key]
        if (map.size > 96) map.clear()
        val value = runCatching { parse(key) }.getOrNull()
        map[key] = value
        return value
    }

    /** 实时事件 user.cosmetics.updated：{ user_id, cosmetics }。是自己时顺手写回登录用户 */
    suspend fun onUpdated(graph: AppGraph, data: JsonObject) {
        val userId = (data["user_id"] as? JsonPrimitive)?.longOrNull ?: return
        val cosmetics = data["cosmetics"] as? JsonObject ?: JsonObject(emptyMap())
        live[userId] = cosmetics
        val me = graph.session.session.value?.user
        if (me != null && me.id == userId) {
            mine = cosmetics
            runCatching { graph.session.updateUser(me.copy(cosmetics = cosmetics)) }
        }
    }

    /** 重拉自己（/auth/me）：启动时、换装后。失败忽略 */
    suspend fun refreshMe(graph: AppGraph) {
        val user = runCatching { KcJson.decodeFromJsonElement(User.serializer(), graph.api.get("/auth/me")) }.getOrNull() ?: return
        if (graph.session.session.value?.user?.id != user.id) return
        live.remove(user.id)
        mine = user.cosmetics
        runCatching { graph.session.updateUser(user) }
    }
}

/** 这个人装备的头像框（没有 / 画不出为 null）。跟着实时事件重组 */
@Composable
fun rememberAvatarFrame(userId: Long?, payload: JsonObject?): AvatarFrame? {
    val cosmetics = Cosmetics.of(userId, payload)
    return remember(cosmetics) { Cosmetics.frame(cosmetics) }
}

/** 这个人装备的气泡皮肤（没有 / 画不出为 null）。关掉了「显示别人的气泡皮肤」时，别人的一律为 null，自己的照常 */
@Composable
fun rememberBubbleSkin(userId: Long?, payload: JsonObject?): BubbleSkin? {
    val cosmetics = Cosmetics.of(userId, payload)
    val skin = remember(cosmetics) { Cosmetics.bubble(cosmetics) }
    val mine = userId != null && userId != 0L && userId == Cosmetics.myId
    return if (mine || ShopPrefs.showOthersSkins(LocalContext.current)) skin else null
}

/**
 * 余额的实时变化（shop.balance.changed，只发给本人）。事件带完整余额就直接换进商店缓存；
 * 没带时发一个 null，开着的商店页静默重拉。
 */
object ShopLive {
    private val _balance = kotlinx.coroutines.flow.MutableSharedFlow<ShopBalance?>(extraBufferCapacity = 8)
    val balance: kotlinx.coroutines.flow.SharedFlow<ShopBalance?> = _balance

    fun onBalanceChanged(graph: AppGraph, data: JsonObject) {
        val pushed = (data["balance"] as? JsonObject)
            ?.takeIf { (it["balance"] as? JsonPrimitive)?.isString == false }
            ?.let { runCatching { KcJson.decodeFromJsonElement(ShopBalance.serializer(), it) }.getOrNull() }
        if (pushed != null) {
            graph.screenCache.get<ShopOverview>("shop")?.let { graph.screenCache.put("shop", it.copy(balance = pushed)) }
        }
        _balance.tryEmit(pushed)
    }
}
