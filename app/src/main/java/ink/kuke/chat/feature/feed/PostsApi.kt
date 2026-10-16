package ink.kuke.chat.feature.feed

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import ink.kuke.chat.core.model.User
import ink.kuke.chat.core.net.Api
import ink.kuke.chat.core.net.KcJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.net.URLEncoder

/** 接口有的返回数组、有的包一层 {items:[]}（电脑版 asList） */
internal fun JsonElement.asItems(): JsonArray = when (this) {
    is JsonArray -> this
    is JsonObject -> (this["items"] ?: this["data"] ?: this["results"]) as? JsonArray ?: JsonArray(emptyList())
    else -> JsonArray(emptyList())
}

/** 解析一个列表；个别元素坏了就跳过，不让整页失败 */
internal inline fun <reified T> JsonElement.decodeItems(): List<T> =
    asItems().mapNotNull { runCatching { KcJson.decodeFromJsonElement<T>(it) }.getOrNull() }

internal inline fun <reified T> JsonElement.decodeOne(): T = KcJson.decodeFromJsonElement<T>(this)

internal fun enc(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

data class LikeResult(val liked: Boolean, val count: Int)

data class CreatorPage(val items: List<CcwCreator>, val total: Int, val hasMore: Boolean)

/** 动态相关的全部接口（对应 KukeChat-Client/src/api/posts.ts）。失败直接抛，界面用 userMessage() 展示 */
class PostsApi(private val api: Api) {
    suspend fun feed(scope: String, topic: String?, sort: String, beforeId: Long?, limit: Int = POST_PAGE_SIZE): List<Post> {
        val query = buildString {
            append("?scope=").append(scope)
            if (beforeId != null) append("&before_id=").append(beforeId)
            append("&limit=").append(limit)
            if (!topic.isNullOrBlank()) append("&topic=").append(enc(topic))
            append("&sort=").append(sort)
        }
        return api.get("/posts/feed$query").decodeItems()
    }

    suspend fun post(id: Long): Post = api.get("/posts/$id").decodeOne()

    suspend fun toggleLike(id: Long): LikeResult {
        val obj = api.post("/posts/$id/like") as? JsonObject
        return LikeResult(obj?.get("liked")?.jsonPrimitive?.booleanOrNull == true, obj?.get("like_count")?.jsonPrimitive?.intOrNull ?: 0)
    }

    suspend fun delete(id: Long) { api.delete("/posts/$id") }

    suspend fun pin(id: Long, pinned: Boolean): Post =
        (if (pinned) api.post("/posts/$id/pin") else api.delete("/posts/$id/pin")).decodeOne()

    suspend fun trending(limit: Int = 8): List<PostTopic> = api.get("/posts/topics/trending?limit=$limit").decodeItems()

    suspend fun searchTopics(query: String, limit: Int = 8): List<PostTopic> =
        api.get("/posts/topics/search?q=${enc(query)}&limit=$limit").decodeItems()

    suspend fun comments(postId: Long): List<PostComment> = api.get("/posts/$postId/comments").decodeItems()

    suspend fun createComment(postId: Long, content: String, parentId: Long?, mentionIds: List<Long>): PostComment {
        val body = buildJsonObject {
            put("content", content)
            if (parentId != null) put("parent_id", parentId)
            put("mention_user_ids", buildJsonArray { mentionIds.forEach { add(JsonPrimitive(it)) } })
        }
        return api.post("/posts/$postId/comments", body).decodeOne()
    }

    suspend fun deleteComment(postId: Long, commentId: Long) { api.delete("/posts/$postId/comments/$commentId") }

    suspend fun toggleCommentLike(postId: Long, commentId: Long): LikeResult {
        val obj = api.post("/posts/$postId/comments/$commentId/like") as? JsonObject
        return LikeResult(obj?.get("liked")?.jsonPrimitive?.booleanOrNull == true, obj?.get("like_count")?.jsonPrimitive?.intOrNull ?: 0)
    }

    suspend fun create(content: String, visibility: String, imageUrls: List<String>, mentionIds: List<Long>, topicNames: List<String>): Post {
        val body = buildJsonObject {
            put("content", content)
            put("visibility", visibility)
            put("image_urls", buildJsonArray { imageUrls.forEach { add(JsonPrimitive(it)) } })
            put("mention_user_ids", buildJsonArray { mentionIds.forEach { add(JsonPrimitive(it)) } })
            put("topic_names", buildJsonArray { topicNames.forEach { add(JsonPrimitive(it)) } })
        }
        return api.post("/posts", body).decodeOne()
    }

    /** 编辑我的动态（PATCH）：正文、可见范围、保留的图片；@ 和话题按新正文重算 */
    suspend fun update(id: Long, content: String, visibility: String, imageUrls: List<String>, mentionIds: List<Long>, topicNames: List<String>): Post {
        val body = buildJsonObject {
            put("content", content)
            put("visibility", visibility)
            put("image_urls", buildJsonArray { imageUrls.forEach { add(JsonPrimitive(it)) } })
            put("mention_user_ids", buildJsonArray { mentionIds.forEach { add(JsonPrimitive(it)) } })
            put("topic_names", buildJsonArray { topicNames.forEach { add(JsonPrimitive(it)) } })
        }
        return api.patch("/posts/$id", body).decodeOne()
    }

    /** 转发到动态：一条带 repost_of_id 的新动态，可以附一句话 */
    suspend fun repost(id: Long, content: String, visibility: String, mentionIds: List<Long>, topicNames: List<String>): Post {
        val body = buildJsonObject {
            put("content", content)
            put("visibility", visibility)
            put("image_urls", buildJsonArray { })
            put("repost_of_id", id)
            put("mention_user_ids", buildJsonArray { mentionIds.forEach { add(JsonPrimitive(it)) } })
            put("topic_names", buildJsonArray { topicNames.forEach { add(JsonPrimitive(it)) } })
        }
        return api.post("/posts", body).decodeOne()
    }

    /** 赞过这条动态的人，最新的在前 */
    suspend fun likes(id: Long, limit: Int = 100): List<PostLike> = api.get("/posts/$id/likes?limit=$limit").decodeItems()

    /** 好友列表（@ 联想用）。接口把对方放在 friend 或 user 里，两种都认 */
    suspend fun friends(): List<User> = api.get("/friends").asItems().mapNotNull { element ->
        val obj = element as? JsonObject ?: return@mapNotNull null
        val user = obj["friend"] ?: obj["user"] ?: return@mapNotNull null
        runCatching { KcJson.decodeFromJsonElement<User>(user) }.getOrNull()
    }

    suspend fun creators(limit: Int, offset: Int): CreatorPage {
        val page = api.get("/users/ccw-creators?limit=$limit&offset=$offset")
        val obj = page as? JsonObject
        return CreatorPage(
            items = page.decodeItems<CcwCreator>(),
            total = obj?.get("total")?.jsonPrimitive?.intOrNull ?: 0,
            hasMore = obj?.get("has_more")?.jsonPrimitive?.booleanOrNull == true,
        )
    }

    /** 上传一张动态配图：先压到服务端上限（3MB）以内，再 multipart 传 /uploads/post-image，返回图片地址 */
    suspend fun uploadImage(context: Context, uri: Uri, path: String = "/uploads/post-image"): String {
        val (bytes, type, ext) = compressImage(context, uri)
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", "image.$ext", bytes.toRequestBody(type.toMediaType()))
            .build()
        val uploaded = api.upload(path, body)
        return (uploaded as? JsonObject)?.get("url")?.jsonPrimitive?.contentOrNull ?: error("上传没有返回地址")
    }
}

private const val MAX_IMAGE_BYTES = 3 * 1024 * 1024
private const val MAX_EDGE = 2048

/**
 * 读出图片 → 按 EXIF 转正 → 长边压到 2048 → JPEG 质量逐档降到 3MB 以内。
 * GIF 不能重编码（会丢动画），3MB 以内原样上传，超了直接报错。
 */
private suspend fun compressImage(context: Context, uri: Uri): Triple<ByteArray, String, String> = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    val mime = resolver.getType(uri) ?: "image/jpeg"
    val raw = resolver.openInputStream(uri)?.use { it.readBytes() } ?: error("读不到这张图片")
    if (mime == "image/gif") {
        if (raw.size > MAX_IMAGE_BYTES) error("GIF 图片不能超过 3MB")
        return@withContext Triple(raw, "image/gif", "gif")
    }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
    if (bounds.outWidth <= 0) error("这不是一张能识别的图片")
    var sample = 1
    while (bounds.outWidth / sample > MAX_EDGE * 2 || bounds.outHeight / sample > MAX_EDGE * 2) sample *= 2
    var bitmap = BitmapFactory.decodeByteArray(raw, 0, raw.size, BitmapFactory.Options().apply { inSampleSize = sample })
        ?: error("这不是一张能识别的图片")
    val rotation = runCatching {
        when (ExifInterface(raw.inputStream()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
    }.getOrDefault(0f)
    val scale = minOf(1f, MAX_EDGE.toFloat() / maxOf(bitmap.width, bitmap.height))
    if (scale < 1f || rotation != 0f) {
        val matrix = Matrix().apply { postScale(scale, scale); if (rotation != 0f) postRotate(rotation) }
        bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
    var quality = 88
    var out: ByteArray
    do {
        out = ByteArrayOutputStream().use { stream -> bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream); stream.toByteArray() }
        quality -= 10
    } while (out.size > MAX_IMAGE_BYTES && quality > 30)
    if (out.size > MAX_IMAGE_BYTES) error("图片太大，压缩后仍超过 3MB")
    Triple(out, "image/jpeg", "jpg")
}
