package ink.kuke.chat.feature.share

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.Log
import androidx.core.app.Person
import androidx.core.content.LocusIdCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.transform.CircleCropTransformation
import ink.kuke.chat.MainActivity
import ink.kuke.chat.core.AppGraph
import ink.kuke.chat.core.model.Conversation
import ink.kuke.chat.core.net.Endpoints
import ink.kuke.chat.service.Notifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.absoluteValue

/**
 * 系统分享面板里的「直接分享」：把最近的几个会话发布成分享快捷方式（res/xml/shortcuts.xml 里的 share-target 指到 ShareReceiverActivity）。
 * 用户在分享面板上直接点某个会话头像时，分享页打开并已选好这个会话（Intent 里带 EXTRA_SHORTCUT_ID）。
 * 同时它们也出现在桌面长按图标的菜单里，点了直接进那个会话。
 *
 * 会话列表变了就更新（防抖 3 秒，只看前几个会话的 id / 名字 / 头像有没有变）；退出登录全部撤掉。
 * Android 9 及以下由 androidx.sharetarget 的 ChooserTargetServiceCompat 转给系统。
 */
object ShareShortcuts {
    private const val TAG = "ShareShortcuts"
    /** 和 res/xml/shortcuts.xml 里 share-target 的 category 一致 */
    const val CATEGORY = "ink.kuke.chat.category.SHARE_TARGET"
    const val ACTION_OPEN = "ink.kuke.chat.action.OPEN_CONVERSATION"
    private const val PREFIX = "conv_"
    private const val COUNT = 5
    private const val ICON_PX = 192

    fun idOf(conversationId: Long) = "$PREFIX$conversationId"

    fun conversationOf(shortcutId: String?): Long? = shortcutId?.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)?.toLongOrNull()

    private data class Entry(val id: Long, val name: String, val avatar: String?, val tone: Long, val group: Boolean, val userId: Long?)

    @OptIn(FlowPreview::class)
    fun bind(context: Context, graph: AppGraph) {
        val app = context.applicationContext
        graph.scope.launch {
            combine(graph.session.session, graph.conversations.items) { session, items ->
                if (session == null) null else top(items)
            }
                // 先去重再过滤：退出后同一个账号重新登录、列表一样，也要重新发布（退出时已经撤掉了）
                .distinctUntilChanged()
                // 没登录（退出登录由 clear 撤掉）或列表还没拉到：不动现有的
                .filter { !it.isNullOrEmpty() }
                .debounce(3000)
                .collectLatest { entries ->
                    // 防抖期间退出登录了：不要再发布（clear 已经撤掉了）
                    if (graph.session.session.value == null) return@collectLatest
                    runCatching { publish(app, entries!!) }.onFailure { Log.w(TAG, "publish failed", it) }
                }
        }
    }

    /** 最近聊过的几个（按最后一条消息的时间，不按置顶） */
    private fun top(items: List<Conversation>): List<Entry> = items
        .sortedByDescending { it.updatedAt }
        .take(COUNT)
        .map { c ->
            Entry(
                c.id, c.name(),
                if (c.isGroup) c.avatarUrl else c.directUser?.avatarUrl ?: c.avatarUrl,
                c.directUser?.id ?: c.id, c.isGroup, c.directUser?.id,
            )
        }

    private suspend fun publish(context: Context, entries: List<Entry>) {
        val max = ShortcutManagerCompat.getMaxShortcutCountPerActivity(context).takeIf { it > 0 } ?: COUNT
        val wanted = entries.take(minOf(COUNT, max))
        val ids = wanted.map { idOf(it.id) }.toSet()
        // 不在最近里的（退群、删了会话、被挤下去的）撤掉，免得分享面板里还推荐它
        val stale = ShortcutManagerCompat.getDynamicShortcuts(context).map { it.id }.filter { it.startsWith(PREFIX) && it !in ids }
        if (stale.isNotEmpty()) {
            ShortcutManagerCompat.removeDynamicShortcuts(context, stale)
            runCatching { ShortcutManagerCompat.removeLongLivedShortcuts(context, stale) }
        }
        wanted.forEachIndexed { rank, entry ->
            val icon = IconCompat.createWithBitmap(iconOf(context, entry))
            val intent = Intent(context, MainActivity::class.java)
                .setAction(ACTION_OPEN)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(Notifier.EXTRA_CONVERSATION_ID, entry.id)
            val builder = ShortcutInfoCompat.Builder(context, idOf(entry.id))
                .setShortLabel(entry.name.take(24).ifBlank { "会话" })
                .setLongLabel(entry.name.ifBlank { "会话" })
                .setIcon(icon)
                .setIntent(intent)
                .setLongLived(true)
                .setRank(rank)
                .setLocusId(LocusIdCompat(idOf(entry.id)))
                .setCategories(setOf(CATEGORY))
            if (!entry.group) {
                builder.setPerson(Person.Builder().setName(entry.name).setKey("user_${entry.userId ?: entry.id}").setIcon(icon).build())
            }
            ShortcutManagerCompat.pushDynamicShortcut(context, builder.build())
        }
    }

    /** 用户从分享页发到了这个会话：告诉系统，分享面板的排序会更准 */
    fun reportUsed(context: Context, conversationId: Long) {
        runCatching { ShortcutManagerCompat.reportShortcutUsed(context, idOf(conversationId)) }
    }

    /** 退出登录：全部撤掉（包括系统缓存的长期快捷方式） */
    fun clear(context: Context) {
        runCatching {
            val ids = ShortcutManagerCompat.getDynamicShortcuts(context).map { it.id }.filter { it.startsWith(PREFIX) }
            ShortcutManagerCompat.removeAllDynamicShortcuts(context)
            if (ids.isNotEmpty()) ShortcutManagerCompat.removeLongLivedShortcuts(context, ids)
        }.onFailure { Log.w(TAG, "clear failed", it) }
    }

    /** 头像：圆形裁切；没有头像或加载失败画首字（底色同 App 里的占位头像） */
    private suspend fun iconOf(context: Context, entry: Entry): Bitmap {
        val url = Endpoints.absolute(entry.avatar)
        if (url != null) {
            val request = ImageRequest.Builder(context)
                .data(url)
                .size(ICON_PX)
                .allowHardware(false)
                .transformations(CircleCropTransformation())
                .build()
            val result = runCatching { context.imageLoader.execute(request) }.getOrNull()
            val drawable = (result as? SuccessResult)?.drawable
            if (drawable != null) return withContext(Dispatchers.Default) { drawable.toBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888) }
        }
        return initials(entry.name, entry.tone)
    }

    private val Tones = intArrayOf(0xFF1F6BFF.toInt(), 0xFF7950F2.toInt(), 0xFF12B886.toInt(), 0xFFF76707.toInt(), 0xFFF06595.toInt(), 0xFF0C8599.toInt(), 0xFF2B8A3E.toInt(), 0xFFF59F00.toInt())

    private fun initials(name: String, tone: Long): Bitmap {
        val bitmap = Bitmap.createBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Tones[(tone.absoluteValue % Tones.size).toInt()] }
        canvas.drawCircle(ICON_PX / 2f, ICON_PX / 2f, ICON_PX / 2f, paint)
        val letter = name.trim().take(1).ifBlank { "?" }
        paint.color = android.graphics.Color.WHITE
        paint.textSize = ICON_PX * 0.42f
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        paint.textAlign = Paint.Align.CENTER
        val y = ICON_PX / 2f - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(letter, ICON_PX / 2f, y, paint)
        return bitmap
    }
}
