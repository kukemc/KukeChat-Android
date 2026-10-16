package ink.kuke.chat.feature.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import ink.kuke.chat.core.AppGraph
import ink.kuke.chat.core.net.KcJson
import ink.kuke.chat.core.ui.parseInstant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/*
 * 设置各页共用的数据模型和小工具。接口契约以 KukeChat-Client/src/api 和 KukeChat-Server/docs 为准。
 */

/** GET /auth/me：设置页要用到的字段（核心的 User 模型里没有 CCW 绑定、主页自定义这些） */
@Serializable
data class MyProfile(
    val id: Long,
    val username: String = "",
    val nickname: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    val bio: String? = null,
    @SerialName("profile_title") val profileTitle: String? = null,
    @SerialName("profile_tagline") val profileTagline: String? = null,
    @SerialName("profile_status") val profileStatus: String? = null,
    @SerialName("profile_location") val profileLocation: String? = null,
    @SerialName("profile_interests") val profileInterests: String? = null,
    @SerialName("profile_layout") val profileLayout: String? = null,
    @SerialName("profile_card_style") val profileCardStyle: String? = null,
    @SerialName("profile_accent_color") val profileAccentColor: String? = null,
    @SerialName("profile_cover_url") val profileCoverUrl: String? = null,
    @SerialName("profile_cover_pending_url") val profileCoverPendingUrl: String? = null,
    @SerialName("share_typing") val shareTyping: Boolean = true,
    @SerialName("ccw_student_oid") val ccwStudentOid: String? = null,
    @SerialName("ccw_name") val ccwName: String? = null,
    @SerialName("ccw_avatar_url") val ccwAvatarUrl: String? = null,
    @SerialName("ccw_bio") val ccwBio: String? = null,
    @SerialName("ccw_follower_count") val ccwFollowerCount: Int? = null,
    @SerialName("ccw_like_count") val ccwLikeCount: Int? = null,
    @SerialName("ccw_creation_count") val ccwCreationCount: Int? = null,
    @SerialName("ccw_view_count") val ccwViewCount: Int? = null,
    @SerialName("ccw_synced_at") val ccwSyncedAt: String? = null,
) {
    val displayName: String get() = nickname?.takeIf { it.isNotBlank() } ?: username
}

/** 我的资料在页面缓存里的 key（我的资料 / 账号与安全 / 通用三页共用） */
const val MY_PROFILE_CACHE_KEY = "me.profile"

suspend fun AppGraph.fetchMyProfile(): MyProfile = KcJson.decodeFromJsonElement(MyProfile.serializer(), api.get("/auth/me"))

/** 服务端返回 null（比如没有绑定挑战）时接口体是 JSON null 或空 */
fun JsonElement.orNullObject(): JsonObject? = if (this is JsonNull) null else this as? JsonObject

fun JsonObject.string(key: String): String? = this[key]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }

private val chinaFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("Asia/Shanghai"))

/** 同电脑版 formatChinaDateTime：按北京时间显示 */
fun chinaTime(iso: String?): String = parseInstant(iso)?.let { chinaFormatter.format(it) } ?: "—"

fun Context.copyToClipboard(label: String, text: String) {
    val manager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    manager.setPrimaryClip(ClipData.newPlainText(label, text))
}

/** 打开外部链接。只放行 http(s)，别的 scheme 不处理 */
fun Context.openExternal(url: String): Boolean {
    if (!url.startsWith("http://") && !url.startsWith("https://")) return false
    return runCatching {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess
}

/**
 * 读取用户选的图片，缩到长边不超过 maxEdge 并压成 JPEG（头像 512，封面 1600），再 POST 到上传接口。
 * 返回服务端给的 url。
 */
suspend fun AppGraph.uploadImage(context: Context, uri: Uri, path: String, maxEdge: Int, maxBytes: Int = 2_000_000): String = withContext(Dispatchers.IO) {
    val raw = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("读不到这张图片")
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
    var sample = 1
    while (bounds.outWidth / sample > maxEdge * 2 || bounds.outHeight / sample > maxEdge * 2) sample *= 2
    val decoded = BitmapFactory.decodeByteArray(raw, 0, raw.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: error("这不是一张能识别的图片")
    val scale = minOf(1f, maxEdge.toFloat() / maxOf(decoded.width, decoded.height))
    val bitmap = if (scale < 1f) Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt(), (decoded.height * scale).toInt(), true) else decoded
    var quality = 90
    var out: ByteArray
    do {
        out = ByteArrayOutputStream().use { stream -> bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream); stream.toByteArray() }
        quality -= 10
    } while (out.size > maxBytes && quality > 30)
    val body = MultipartBody.Builder().setType(MultipartBody.FORM)
        .addFormDataPart("file", "image.jpg", out.toRequestBody("image/jpeg".toMediaType()))
        .build()
    val result = api.upload(path, body)
    (result as? JsonObject)?.string("url") ?: error("上传没有返回地址")
}
