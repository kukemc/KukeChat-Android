package ink.kuke.chat.feature.chat

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ink.kuke.chat.core.AppGraph
import ink.kuke.chat.core.net.userMessage
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 一个收藏表情（GET /stickers/favorites 的一项）。asset_id 是删除、排序用的 id */
data class FavoriteSticker(val assetId: Long, val url: String, val contentType: String?)

/**
 * 收藏表情接口 + 进程内共享列表。表情面板的「收藏」页和表情管理页读同一份（快照状态），
 * 一边删除、排序、添加，另一边直接跟着变。退出登录时清空。
 *
 *   GET    /stickers/favorites               按显示顺序（新加的在最前）
 *   POST   /stickers/favorites               { source_url }
 *   DELETE /stickers/favorites/{asset_id}
 *   POST   /stickers/favorites/batch-delete  { asset_ids } → { removed }
 *   PUT    /stickers/favorites/order         { asset_ids }：必须正好是当前全部 id，否则 409 sticker_order_mismatch
 */
object FavoriteStickers {
    /** null = 还没拉过 */
    var items by mutableStateOf<List<FavoriteSticker>?>(null)
        private set

    /** 写操作串行：排序要基于上一次写完的列表，不能和删除交错 */
    private val writes = Mutex()

    const val ORDER_MISMATCH = "sticker_order_mismatch"

    private fun parse(element: JsonElement): List<FavoriteSticker> {
        val array = element.asArr() ?: element.asObj()?.get("items").asArr() ?: return emptyList()
        return array.mapNotNull { item ->
            val obj = item.asObj() ?: return@mapNotNull null
            FavoriteSticker(obj.l("asset_id") ?: return@mapNotNull null, obj.s("url") ?: return@mapNotNull null, obj.s("content_type"))
        }
    }

    suspend fun load(graph: AppGraph): List<FavoriteSticker> =
        parse(graph.api.get("/stickers/favorites")).also { items = it }

    /** 把一张已经上传好的图（或聊天里的图片地址）加进收藏，然后重拉（新加的排在最前） */
    suspend fun add(graph: AppGraph, sourceUrl: String) {
        writes.withLock { graph.api.post("/stickers/favorites", buildJsonObject { put("source_url", sourceUrl) }) }
        runCatching { load(graph) }
    }

    /** 删除（一个走 DELETE，多个走 batch-delete）；返回删掉了几个 */
    suspend fun delete(graph: AppGraph, ids: Collection<Long>): Int = writes.withLock {
        if (ids.isEmpty()) return@withLock 0
        val removed = if (ids.size == 1) {
            graph.api.delete("/stickers/favorites/${ids.first()}")
            1
        } else {
            graph.api.post("/stickers/favorites/batch-delete", buildJsonObject { put("asset_ids", idArray(ids)) })
                .asObj()?.i("removed") ?: ids.size
        }
        val gone = ids.toSet()
        items = items?.filter { it.assetId !in gone }
        removed
    }

    /**
     * 保存顺序：[ids] 必须是当前全部表情。先在本地换好（面板和管理页立刻按新顺序显示），再提交；
     * 409 sticker_order_mismatch（别的设备刚增删过）时重拉列表再把错误抛给调用方提示；其他失败退回原来的顺序
     */
    suspend fun reorder(graph: AppGraph, ids: List<Long>) = writes.withLock {
        val before = items
        val byId = before.orEmpty().associateBy { it.assetId }
        if (ids.size == byId.size && ids.all { it in byId }) items = ids.map { byId.getValue(it) }
        try {
            val sorted = parse(graph.api.put("/stickers/favorites/order", buildJsonObject { put("asset_ids", idArray(ids)) }))
            if (sorted.isNotEmpty() || ids.isEmpty()) items = sorted
        } catch (e: Exception) {
            items = before
            if (e is ink.kuke.chat.core.net.ApiException && e.code == ORDER_MISMATCH) runCatching { load(graph) }
            throw e
        }
    }

    /** 把这几个移到最前（保持它们之间原来的先后），其余顺序不变 */
    suspend fun moveToFront(graph: AppGraph, ids: Collection<Long>) {
        val current = items ?: load(graph)
        val picked = ids.toSet()
        val order = current.filter { it.assetId in picked } + current.filter { it.assetId !in picked }
        if (order == current) return
        reorder(graph, order.map { it.assetId })
    }

    fun clear() {
        items = null
    }

    private fun idArray(ids: Collection<Long>) = JsonArray(ids.map(::JsonPrimitive))
}

/** 「添加」一次最多选几张 */
internal const val STICKER_PICK_MAX = 20

/**
 * 从相册选的一批图逐张加进收藏表情：上传到消息图片通道（动图原样传，超过 3MB 的挡掉）→ POST /stickers/favorites。
 * [onProgress] 报「正在添加 3/8」；返回给用户看的结果（「已添加 8 个表情」/ 失败原因汇总）
 */
internal suspend fun addFavoriteStickers(graph: AppGraph, context: Context, uris: List<Uri>, onProgress: (String) -> Unit): String {
    val failed = mutableListOf<String>()
    uris.forEachIndexed { index, uri ->
        onProgress("正在添加 ${index + 1}/${uris.size}")
        runCatching {
            checkStickerSource(context, uri)
            val url = uploadMessageImage(graph, context, uri)
            graph.api.post("/stickers/favorites", buildJsonObject { put("source_url", url) })
        }.onFailure { failed += "第 ${index + 1} 张：" + it.userMessage().ifBlank { "添加失败" } }
    }
    runCatching { FavoriteStickers.load(graph) }
    val ok = uris.size - failed.size
    return when {
        failed.isEmpty() -> "已添加 $ok 个表情"
        ok == 0 -> "添加失败：" + failed.take(3).joinToString("；")
        else -> "已添加 $ok 个，${failed.size} 个失败：" + failed.take(3).joinToString("；")
    }
}

/**
 * 收藏表情走消息图片上传（3MB，服务端 MAX_IMAGE_UPLOAD_BYTES）：静态图本地会压，动图不压（压了就不动了），超了先挡掉。
 * 动图按文件头认（AnimatedImages），不看 MIME：QQ / 微信存的动图常常叫 .jpg
 */
internal fun checkStickerSource(context: Context, uri: Uri) {
    AnimatedImages.detect(context, uri) ?: return
    val size = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }?.takeIf { it > 0 } ?: return
    if (size > AnimatedImages.MAX_BYTES) error(AnimatedImages.TOO_LARGE)
}
