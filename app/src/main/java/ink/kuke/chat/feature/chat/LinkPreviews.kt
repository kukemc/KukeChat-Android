package ink.kuke.chat.feature.chat

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import ink.kuke.chat.KukeApp
import ink.kuke.chat.core.AppGraph
import ink.kuke.chat.core.model.Message
import ink.kuke.chat.core.net.Endpoints
import ink.kuke.chat.core.net.KcJson
import ink.kuke.chat.feature.feed.CcwCreation
import ink.kuke.chat.feature.feed.wanCount
import ink.kuke.chat.ui.components.Avatar
import ink.kuke.chat.ui.components.pressScale
import ink.kuke.chat.ui.components.rememberInteraction
import ink.kuke.chat.ui.theme.KcTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.net.URLEncoder

/**
 * 消息里的外站链接卡片：CCW 作品、B 站视频。画在气泡下面，点卡片打开（CCW 交给系统，装了 CCW App 且它接管 ccw.site 链接就进 App；
 * B 站优先 bilibili:// 进 App，没装退回浏览器）。拿不到信息就不画卡片，只留文字里的链接。
 *
 * CCW：发消息时服务端已经把作品信息放进 metadata.ccw_creations（同电脑版）；没有的（老消息、当时没取到）按正文里的链接调
 *   GET /ccw/creations/{oid}?accessKey=… 补。识别规则同电脑版 utils/ccwLinks.ts。
 * B 站：GET /link-preview/bilibili?url=…（422 不是视频 / 502 拿不到 → 不画）。结果都按链接缓存在内存里。
 */

// ------------------------------------------------------------------ 识别

private val CCW_LINK = Regex("""https?://(?:www\.)?ccw\.site/detail/([0-9a-fA-F]{24})(?:[/?#][^\s]*)?""")
private val BILI_LINK = Regex(
    """(?<![\w.])(?:https?://)?(?:(?:www|m)\.)?bilibili\.com/video/(?:BV[0-9A-Za-z]{10}|av\d{1,12})[^\s]*|(?<![\w.])(?:https?://)?b23\.tv/[0-9A-Za-z]{4,16}""",
    RegexOption.IGNORE_CASE,
)
private val BVID = Regex("""BV[0-9A-Za-z]{10}""")
private val AVID = Regex("""/av(\d{1,12})""", RegexOption.IGNORE_CASE)
/** 链接后面紧跟的中英文标点不算链接（同电脑版） */
private val TRAILING = Regex("""[，。！？；：,.!?;:)\]}）】》"'”’]+$""")

internal data class CcwLinkRef(val oid: String, val accessKey: String?) {
    val key: String get() = "$oid:${accessKey.orEmpty()}"
}

/** 正文里的 CCW 作品链接，去重、最多 [limit] 个 */
internal fun ccwLinksIn(text: String, limit: Int = 3): List<CcwLinkRef> {
    val seen = LinkedHashMap<String, CcwLinkRef>()
    for (match in CCW_LINK.findAll(text)) {
        val oid = match.groupValues[1].lowercase()
        val url = match.value.replace(TRAILING, "")
        val key = runCatching { Uri.parse(url).getQueryParameter("accessKey") }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        val ref = CcwLinkRef(oid, key)
        seen.putIfAbsent(ref.key, ref)
        if (seen.size >= limit) break
    }
    return seen.values.toList()
}

/** 正文里的 B 站视频链接（补全 https://），按视频去重、最多 [limit] 个 */
internal fun bilibiliLinksIn(text: String, limit: Int = 2): List<String> {
    val seen = LinkedHashMap<String, String>()
    for (match in BILI_LINK.findAll(text)) {
        val raw = match.value.replace(TRAILING, "")
        val url = if (raw.startsWith("http", ignoreCase = true)) raw else "https://$raw"
        val key = BVID.find(url)?.value ?: AVID.find(url)?.groupValues?.get(1)?.let { "av$it" } ?: url.lowercase()
        seen.putIfAbsent(key, url)
        if (seen.size >= limit) break
    }
    return seen.values.toList()
}

// ------------------------------------------------------------------ 数据与缓存

internal data class BiliVideo(
    val bvid: String,
    val title: String,
    val cover: String?,
    val ownerName: String?,
    val duration: Int,
    val views: Int,
    val url: String,
)

/** 一个链接的预览：加载中 / 拿到了 / 拿不到（拿不到就不画卡片） */
internal sealed interface LinkSlot<out T> {
    data object Loading : LinkSlot<Nothing>
    data object Failed : LinkSlot<Nothing>
    data class Ready<T>(val value: T) : LinkSlot<T>
}

internal object LinkPreviews {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val ccw = mutableStateMapOf<String, LinkSlot<CcwCreation>>()
    val bili = mutableStateMapOf<String, LinkSlot<BiliVideo>>()

    fun ensureCcw(graph: AppGraph, ref: CcwLinkRef) {
        if (ccw.containsKey(ref.key)) return
        ccw[ref.key] = LinkSlot.Loading
        scope.launch {
            ccw[ref.key] = try {
                val suffix = ref.accessKey?.let { "?accessKey=" + URLEncoder.encode(it, "UTF-8") }.orEmpty()
                val creation = KcJson.decodeFromJsonElement(CcwCreation.serializer(), graph.api.get("/ccw/creations/${ref.oid}$suffix"))
                if (creation.title.isBlank()) LinkSlot.Failed else LinkSlot.Ready(creation)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                LinkSlot.Failed
            }
        }
    }

    fun ensureBili(graph: AppGraph, url: String) {
        if (bili.containsKey(url)) return
        bili[url] = LinkSlot.Loading
        scope.launch {
            bili[url] = try {
                val obj = graph.api.get("/link-preview/bilibili?url=" + URLEncoder.encode(url, "UTF-8")).asObj()
                val bvid = obj?.s("bvid")
                val title = obj?.s("title")
                if (obj == null || bvid.isNullOrBlank() || title.isNullOrBlank()) LinkSlot.Failed
                else LinkSlot.Ready(
                    BiliVideo(
                        bvid = bvid,
                        title = title,
                        cover = obj.s("cover"),
                        ownerName = obj.s("owner_name")?.takeIf { it.isNotBlank() },
                        duration = obj.i("duration") ?: 0,
                        views = obj.i("views") ?: 0,
                        url = obj.s("url")?.takeIf { it.startsWith("http") } ?: "https://www.bilibili.com/video/$bvid",
                    ),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                LinkSlot.Failed
            }
        }
    }
}

/** metadata.ccw_creations：发消息时服务端取好的作品信息 */
private fun ccwFromMetadata(message: Message): List<CcwCreation> =
    message.metadata?.a("ccw_creations")?.mapNotNull { element ->
        runCatching { KcJson.decodeFromJsonElement(CcwCreation.serializer(), element) }.getOrNull()?.takeIf { it.oid.isNotBlank() && it.title.isNotBlank() }
    }.orEmpty()

// ------------------------------------------------------------------ 打开

/** 交给系统打开网页：有 App 接管这个链接（比如 CCW App）就进 App，否则浏览器 */
private fun openWeb(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/** B 站视频：先试 bilibili://video/{bvid} 进 App 直接播放，没装 App 退回浏览器打开网页 */
private fun openBilibili(context: Context, video: BiliVideo) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("bilibili://video/${video.bvid}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        openWeb(context, video.url)
    } catch (_: SecurityException) {
        openWeb(context, video.url)
    }
}

private fun ccwUrl(creation: CcwCreation): String = creation.url.takeIf { it.startsWith("http") } ?: "https://www.ccw.site/detail/${creation.oid}"

// ------------------------------------------------------------------ 卡片

/** 一条消息正文下面的链接卡片；没有可识别的链接时什么都不画 */
@Composable
internal fun LinkPreviewCards(message: Message, modifier: Modifier = Modifier) {
    val content = message.content
    val preset = remember(message.id, message.metadata) { ccwFromMetadata(message) }
    val ccwRefs = remember(content, preset) { if (preset.isNotEmpty()) emptyList() else ccwLinksIn(content) }
    val biliLinks = remember(content) { bilibiliLinksIn(content) }
    if (preset.isEmpty() && ccwRefs.isEmpty() && biliLinks.isEmpty()) return
    val graph = (LocalContext.current.applicationContext as KukeApp).graph
    Column(modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        preset.forEach { CcwLinkCard(it) }
        ccwRefs.forEach { ref ->
            LaunchedEffect(ref.key) { LinkPreviews.ensureCcw(graph, ref) }
            (LinkPreviews.ccw[ref.key] as? LinkSlot.Ready)?.let { CcwLinkCard(it.value) }
        }
        biliLinks.forEach { url ->
            LaunchedEffect(url) { LinkPreviews.ensureBili(graph, url) }
            (LinkPreviews.bili[url] as? LinkSlot.Ready)?.let { BiliLinkCard(it.value) }
        }
    }
}

/** 卡片外框：面板底、细描边、按下微缩 */
@Composable
private fun LinkCardFrame(onClick: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val colors = KcTheme.colors
    val press = rememberInteraction()
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier
            .width(260.dp)
            .pressScale(press, 0.98f)
            .clip(shape)
            .background(colors.panel)
            .border(1.dp, colors.border, shape)
            .clickable(interactionSource = press, indication = null, onClick = onClick),
        content = content,
    )
}

/** 封面：16:9，墨色底；左上角来源标签 */
@Composable
private fun LinkCover(url: String?, fit: Boolean, badge: @Composable () -> Unit, corner: (@Composable () -> Unit)? = null) {
    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color(0xFF0E0E10))) {
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = if (fit) ContentScale.Fit else ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        }
        Box(Modifier.align(Alignment.TopStart).padding(8.dp)) { badge() }
        if (corner != null) Box(Modifier.align(Alignment.BottomEnd).padding(8.dp)) { corner() }
    }
}

/** 封面上的小胶囊：黑底 60%、白字 */
@Composable
private fun CoverPill(text: String, dot: Color? = null) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 7.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dot != null) Box(Modifier.padding(end = 4.dp).size(5.dp).clip(CircleShape).background(dot))
        Text(text, fontSize = 10.sp, fontWeight = FontWeight.W600, color = Color.White, maxLines = 1)
    }
}

/** CCW 作品卡（同电脑版手机布局）：封面 · 标题 · 简介两行 · 作者 + 版本 · 浏览 / 点赞 / 评论 */
@Composable
private fun CcwLinkCard(creation: CcwCreation) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val context = LocalContext.current
    LinkCardFrame(onClick = { openWeb(context, ccwUrl(creation)) }) {
        LinkCover(Endpoints.absolute(creation.coverUrl), fit = true, badge = { CoverPill("CCW") })
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(creation.title, style = type.rowTitle, color = colors.text, fontWeight = FontWeight.W600, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val summary = remember(creation.description) {
                creation.description?.replace(Regex("""(?m)^\s{0,3}#{1,6}\s*"""), "")?.replace(Regex("""[*_`~>\[\]-]+"""), " ")?.replace(Regex("""\s+"""), " ")?.trim()?.takeIf { it.isNotEmpty() }
            }
            if (summary != null) Text(summary, style = type.caption, color = colors.muted, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(Endpoints.absolute(creation.authorAvatarUrl), creation.authorName.ifBlank { "作者" }, tone = creation.oid.hashCode().toLong(), size = 18.dp)
                Text(
                    creation.authorName.ifBlank { "CCW 作者" },
                    style = type.caption,
                    color = colors.text,
                    fontWeight = FontWeight.W600,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 6.dp).weight(1f, fill = false),
                )
                creation.version?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        "v$it",
                        fontSize = 10.sp,
                        color = colors.muted,
                        maxLines = 1,
                        modifier = Modifier.padding(start = 6.dp).clip(RoundedCornerShape(50)).border(1.dp, colors.border, RoundedCornerShape(50)).padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                }
            }
            val stats = listOfNotNull(
                creation.viewCount?.let { "浏览 ${wanCount(it)}" },
                creation.likeCount?.let { "点赞 ${wanCount(it)}" },
                creation.commentCount?.let { "评论 ${wanCount(it)}" },
            )
            if (stats.isNotEmpty()) Text(stats.joinToString("  ·  "), style = type.caption, color = colors.subtle, maxLines = 1, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

private val BiliPink = Color(0xFFFB7299)

/** 视频时长：m:ss / h:mm:ss */
private fun durationText(seconds: Int): String {
    val s = seconds.coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/** hdslb 图片可以按尺寸取缩略图，卡片用不着原图 */
private fun biliThumb(url: String?): String? {
    if (url.isNullOrBlank()) return null
    val https = url.replaceFirst("http://", "https://")
    return if ("hdslb.com" in https && '@' !in https) "$https@480w_270h_1c.webp" else https
}

/** B 站视频卡：封面（右下时长）· 标题两行 · UP 主 · 播放量。B 站粉只点在来源标签的小圆点上 */
@Composable
private fun BiliLinkCard(video: BiliVideo) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val context = LocalContext.current
    LinkCardFrame(onClick = { openBilibili(context, video) }) {
        LinkCover(
            biliThumb(video.cover),
            fit = false,
            badge = { CoverPill("哔哩哔哩", dot = BiliPink) },
            corner = if (video.duration > 0) ({ CoverPill(durationText(video.duration)) }) else null,
        )
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(video.title, style = type.rowTitle, color = colors.text, fontWeight = FontWeight.W600, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(Modifier.padding(top = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "UP",
                    fontSize = 9.sp,
                    fontWeight = FontWeight.W600,
                    color = colors.muted,
                    modifier = Modifier.clip(RoundedCornerShape(3.dp)).border(1.dp, colors.borderStrong, RoundedCornerShape(3.dp)).padding(horizontal = 3.dp),
                )
                Text(
                    video.ownerName ?: "未知 UP 主",
                    style = type.caption,
                    color = colors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 6.dp).weight(1f, fill = false),
                )
                Text("  ·  ${wanCount(video.views)} 播放", style = type.caption, color = colors.subtle, maxLines = 1)
            }
        }
    }
}
