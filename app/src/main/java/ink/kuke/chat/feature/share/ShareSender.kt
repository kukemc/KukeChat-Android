package ink.kuke.chat.feature.share

import android.content.Context
import android.net.Uri
import android.util.Log
import android.widget.Toast
import ink.kuke.chat.core.AppGraph
import ink.kuke.chat.core.net.userMessage
import ink.kuke.chat.feature.chat.ChatController
import ink.kuke.chat.feature.chat.ConversationOutbox
import ink.kuke.chat.feature.chat.PendingMessage
import ink.kuke.chat.feature.contacts.SocialApi
import ink.kuke.chat.feature.groupfiles.PickedFile
import ink.kuke.chat.feature.groupplus.FileQuota
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 分享的一个目标：已有的会话（conversationId），或还没有私聊会话的好友（userId，发送前再建会话） */
data class ShareTarget(
    val key: String,
    val conversationId: Long?,
    val userId: Long?,
    val group: Boolean,
    val name: String,
    val avatar: String?,
    val tone: Long,
    val plusTier: String?,
    val detail: String?,
    /** 根本发不了（全员禁言中…）：行置灰 */
    val blockedReason: String? = null,
)

/** 发到一个目标的计划：文字 / 图片一律发（私聊、群聊都行），[files] 是这个群能收的群文件 */
class ShareJob(val target: ShareTarget, val files: List<ShareItem>, val direct: Boolean, val fileLimit: Long)

class ShareOutcome(val conversationIds: List<Long>, val errors: List<String>)

/**
 * 把分享的内容交给已有的发送链路：每个目标建一个 ChatController（不 start，只借它的 sendText / sendImages / sendFiles），
 * 本地消息进进程级的 Outbox，分享页关掉后照样在后台上传、发出；聊天页开着的话立刻就能看到那几条发送中的消息。
 *
 * 顺序：分享的文字（有图时作为图片的说明，和图同一条）→ 图片（每 9 张一条）→ 群文件 → 留言。
 */
object ShareSender {
    private const val TAG = "ShareSender"
    /** 盯着后台上传多久（传完删本地副本、失败了提示一次） */
    private const val WATCH_MS = 6L * 60 * 60 * 1000

    suspend fun send(context: Context, graph: AppGraph, payload: SharePayload, jobs: List<ShareJob>, note: String): ShareOutcome {
        val app = context.applicationContext
        val errors = mutableListOf<String>()
        val batch = withContext(Dispatchers.IO) { ShareCache.newBatch(app) }

        // 1. 先把要用的附件拷进自己的缓存（来源应用的临时读权限在分享页关掉后可能失效）。每个附件只拷一次，几个目标共用
        val images = mutableListOf<PickedFile>()
        val copiedFiles = HashMap<ShareItem, PickedFile>()
        withContext(Dispatchers.IO) {
            if (jobs.isNotEmpty()) payload.images.forEach { item ->
                runCatching { ShareCache.copy(app, batch, item, ShareCache.MAX_IMAGE_BYTES) }
                    .onSuccess { images += it }
                    .onFailure { errors += it.shareMessage() }
            }
            jobs.flatMap { it.files }.distinct().forEach { item ->
                val limit = jobs.filter { item in it.files }.maxOf { it.fileLimit }
                runCatching { ShareCache.copy(app, batch, item, limit) }
                    .onSuccess { copiedFiles[item] = it }
                    .onFailure { errors += it.shareMessage() }
            }
        }

        // 2. 好友还没有私聊会话的：先建（取）会话
        val resolved = jobs.mapNotNull { job ->
            val id = job.target.conversationId ?: runCatching { SocialApi(graph.api).openDirect(job.target.userId ?: return@mapNotNull null) }
                .onFailure { if (it is CancellationException) throw it; errors += "${job.target.name}：${it.userMessage()}" }
                .getOrNull()
            id?.let { job to it }
        }

        // 3. 进发件箱（主线程：发件箱的列表是 Compose 状态）
        val tracked = mutableListOf<Pair<ConversationOutbox, PendingMessage>>()
        val sentTo = mutableListOf<Long>()
        withContext(Dispatchers.Main.immediate) {
            resolved.forEach { (job, conversationId) ->
                val controller = ChatController(graph, conversationId, graph.scope)
                val box = controller.outbox
                val before = box.items.toList()
                var any = false
                val text = payload.text.trim()
                val chunks = images.map { it.uri }.chunked(ChatController.MAX_IMAGES)
                if (chunks.isEmpty()) {
                    if (text.isNotEmpty()) any = controller.sendText(text) || any
                } else {
                    chunks.forEachIndexed { index, chunk ->
                        any = controller.sendImages(chunk, caption = if (index == 0) text else "") || any
                    }
                }
                val files = job.files.mapNotNull { item -> copiedFiles[item]?.takeIf { it.size <= job.fileLimit } }
                if (files.isNotEmpty()) any = controller.sendFiles(files, job.direct) || any
                if (any && note.isNotBlank()) controller.sendText(note.trim())
                box.items.filter { item -> before.none { it === item } }.forEach { tracked += box to it }
                if (any) {
                    sentTo += conversationId
                    ShareShortcuts.reportUsed(app, conversationId)
                }
            }
        }
        if (tracked.isEmpty()) {
            withContext(Dispatchers.IO) { ShareCache.deleteBatch(batch) }
        } else {
            watch(app, graph, tracked, batch)
        }
        return ShareOutcome(sentTo, errors)
    }

    /**
     * 盯着这批本地消息：群文件传完（或被删掉）就删掉它的本地副本；第一次全部有结果时，有失败的提示一次（重发在聊天页里点）。
     * 图片副本不删：发出去后聊天里还用本地这张画（LocalImages），下次进程启动时 ShareCache.sweep 清掉。
     */
    private fun watch(context: Context, graph: AppGraph, tracked: List<Pair<ConversationOutbox, PendingMessage>>, batch: File) {
        graph.scope.launch(Dispatchers.Main) {
            val started = System.currentTimeMillis()
            val fileCopies = tracked.mapNotNull { (_, pending) -> pending.file?.picked?.uri?.let { pending to it } }.toMap(HashMap<PendingMessage, Uri>())
            var reported = false
            while (System.currentTimeMillis() - started < WATCH_MS) {
                delay(2000)
                val alive = tracked.filter { (box, pending) -> box.items.any { it === pending } }
                // 传完或被删了：群文件的副本可以删了
                fileCopies.keys.filter { pending -> alive.none { it.second === pending } }.forEach { pending ->
                    fileCopies.remove(pending)?.let { uri -> withContext(Dispatchers.IO) { ShareCache.delete(context, uri) } }
                }
                if (!reported && alive.all { it.second.failed }) {
                    reported = true
                    val failed = alive.size
                    if (failed > 0) Toast.makeText(context, "分享的内容有 $failed 条没发出去，进入聊天点一下重发", Toast.LENGTH_LONG).show()
                }
                if (alive.isEmpty()) break
            }
            // 群文件都处理完了、也没有图片：整批目录删掉
            if (fileCopies.isEmpty() && tracked.none { it.second.hasLocalImages }) withContext(Dispatchers.IO) { ShareCache.deleteBatch(batch) }
        }
    }

    /** 群能不能收群文件、单个文件上限多少（同聊天页「+」→「文件」的判断） */
    fun fileRule(quota: FileQuota?): Pair<String?, Long> = when (quota?.state) {
        // 查不到（网络、旧服务端）：照常发，服务端把关
        null -> null to DEFAULT_MAX_FILE
        "active" -> null to quota.maxFileBytes
        "grace" -> "群升级已到期，群文件暂停上传" to 0L
        else -> "群文件需要群升级 Pro 及以上" to 0L
    }

    const val DEFAULT_MAX_FILE = 104857600L

    private fun Throwable.shareMessage(): String = when (this) {
        is ShareCopyError -> message ?: "读不到附件"
        else -> {
            Log.w(TAG, "share failed", this)
            userMessage()
        }
    }
}
