package ink.kuke.chat.core.net

import ink.kuke.chat.BuildConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 全局用的 JSON：服务端会加字段，不认识的忽略；null 和缺省一样对待。 */
val KcJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
    isLenient = true
}

/**
 * 部署地址（来自 BuildConfig，构建时从 local.properties 读，代码里不写死）。
 * API_BASE_URL 可以带也可以不带 /api/v1，和 KukeChat-Client 的 client.ts 一样处理。
 */
object Endpoints {
    val apiRoot: String = BuildConfig.API_BASE_URL.trimEnd('/').removeSuffix("/api/v1")
    val api: String = "$apiRoot/api/v1"
    val ws: String = BuildConfig.WS_URL

    /** 把 /uploads/... 这类相对地址补成绝对地址 */
    fun absolute(url: String?): String? = when {
        url.isNullOrBlank() -> null
        url.startsWith("http://") || url.startsWith("https://") || url.startsWith("data:") -> url
        // 本地图（发送中的消息）：原样交给 Coil
        url.startsWith("content:") || url.startsWith("file:") -> url
        url.startsWith("/") -> apiRoot + url
        else -> "$apiRoot/$url"
    }
}

/**
 * 客户端证明（服务端 app/core/request_guard.py）：/api/v1 下每个请求都要带，否则 403。
 * proof = sha256("METHOD|path|timestamp|nonce|KukeChat")，path 不含 /api/v1 和查询串、不做 URL 编码。
 * 服务端只认 kukechat-web/、kukechat-admin/、kukechat-bot/ 三个前缀，原生版暂用 web 前缀。
 */
object ClientProof {
    private const val CLIENT = "kukechat-web/android-2.0.0"
    private val random = SecureRandom()

    fun headers(method: String, path: String): Map<String, String> {
        val timestamp = System.currentTimeMillis().toString()
        val nonce = ByteArray(8).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        return mapOf(
            "X-Kuke-Client" to CLIENT,
            "X-Kuke-Timestamp" to timestamp,
            "X-Kuke-Nonce" to nonce,
            "X-Kuke-Proof" to proof(method, path, timestamp, nonce),
        )
    }

    /** sha256("METHOD|path|timestamp|nonce|KukeChat")，path 不含查询串（同 request_guard.py） */
    fun proof(method: String, path: String, timestamp: String, nonce: String): String {
        val raw = "${method.uppercase()}|${path.substringBefore('?')}|$timestamp|$nonce|KukeChat"
        return MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}

/** 账号被封禁（403 account_suspended）：任何接口都可能返回，收到就要退回登录页并说明原因。 */
data class Suspension(val message: String, val reason: String?, val bannedUntil: String?, val permanent: Boolean)

/** 设备被封禁（403 device_banned）：换账号也没用，任何接口都可能返回，处理同账号封禁。 */
data class DeviceBan(val message: String, val reason: String?, val expiresAt: String?, val permanent: Boolean)

class ApiException(val status: Int, val code: String?, override val message: String, val detail: JsonElement?) : IOException(message) {
    val deviceBan: DeviceBan?
        get() {
            if (status != 403 || code != "device_banned") return null
            val obj = detail as? JsonObject
            return DeviceBan(
                message = message,
                reason = (obj?.get("reason") as? JsonPrimitive)?.contentOrNull,
                expiresAt = (obj?.get("expires_at") as? JsonPrimitive)?.contentOrNull,
                permanent = (obj?.get("permanent") as? JsonPrimitive)?.booleanOrNull == true,
            )
        }

    val suspension: Suspension?
        get() {
            if (status != 403 || code != "account_suspended") return null
            val obj = (detail as? JsonObject) ?: return null
            return Suspension(
                message = message,
                reason = obj["reason"]?.jsonPrimitive?.contentOrNull,
                bannedUntil = obj["banned_until"]?.jsonPrimitive?.contentOrNull,
                permanent = obj["permanent"]?.jsonPrimitive?.booleanOrNull == true,
            )
        }
}

/**
 * HTTP 接口。令牌由 tokenProvider 给（SessionStore）；任何响应里出现封禁就回调 onSuspended / onDeviceBanned。
 * 失败抛 ApiException（服务端拒绝）或 IOException（网络问题）。
 * 设备标识头由 http 上的拦截器统一加（直接用 http 发的上传 / 下载也带上），响应里发了新设备令牌就存下来。
 */
class Api(
    private val tokenProvider: () -> String?,
    private val onSuspended: (Suspension) -> Unit,
    private val onUnauthorized: () -> Unit,
    val device: DeviceIdentity,
    private val onDeviceBanned: (DeviceBan) -> Unit = {},
) {
    val http: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val request = chain.request()
            // 只给自家 /api/v1 加（预签名直传、更新包下载这些第三方地址不带）
            if (!request.url.toString().startsWith(Endpoints.api)) return@addInterceptor chain.proceed(request)
            val builder = request.newBuilder()
            device.headers().forEach { (key, value) -> if (request.header(key) == null) builder.header(key, value) }
            val response = chain.proceed(builder.build())
            response.header(DeviceIdentity.HEADER_ISSUE)?.let(device::accept)
            response
        }
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .pingInterval(0, TimeUnit.SECONDS)
        .build()

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    suspend fun get(path: String): JsonElement = send("GET", path, null)
    suspend fun post(path: String, body: JsonElement? = null): JsonElement = send("POST", path, body?.toString()?.toRequestBody(jsonType) ?: ByteArray(0).toRequestBody(null))
    suspend fun put(path: String, body: JsonElement): JsonElement = send("PUT", path, body.toString().toRequestBody(jsonType))
    suspend fun patch(path: String, body: JsonElement): JsonElement = send("PATCH", path, body.toString().toRequestBody(jsonType))
    suspend fun delete(path: String): JsonElement = send("DELETE", path, null)

    /** multipart 上传（字段名 file） */
    suspend fun upload(path: String, body: RequestBody): JsonElement = send("POST", path, body)

    private suspend fun send(method: String, path: String, body: RequestBody?, auth: Boolean = true): JsonElement = withContext(Dispatchers.IO) {
        // 登录 / 注册前确保要过设备令牌，第一次注册就能带上（最多等 3 秒，要不到也照常提交）
        if (path.startsWith("/auth/login") || path.startsWith("/auth/register")) withTimeoutOrNull(3_000) { primeDevice().await() }
        val builder = Request.Builder().url(Endpoints.api + path).method(method, body)
        ClientProof.headers(method, path).forEach { (key, value) -> builder.header(key, value) }
        if (auth) tokenProvider()?.let { builder.header("Authorization", "Bearer $it") }
        val response = http.newCall(builder.build()).await()
        response.use { parse(it) }
    }

    private val deviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val deviceLock = Any()
    private var deviceJob: Deferred<Unit>? = null

    /**
     * 本地没有设备令牌就向 GET /auth/device 要一个（不带登录令牌）。同一时间只发一个请求；
     * 上次失败了（断网）再调会重新要。已有令牌直接返回完成。
     */
    fun primeDevice(): Deferred<Unit> = synchronized(deviceLock) {
        deviceJob?.takeIf { !it.isCompleted }?.let { return it }
        if (device.token() != null) return CompletableDeferred(Unit)
        deviceScope.async {
            runCatching {
                val result = send("GET", "/auth/device", null, auth = false)
                device.accept(((result as? JsonObject)?.get("device") as? JsonPrimitive)?.contentOrNull)
            }
            Unit
        }.also { deviceJob = it }
    }

    private fun parse(response: Response): JsonElement {
        val text = response.body?.string().orEmpty()
        val element = if (text.isBlank()) JsonObject(emptyMap()) else runCatching { KcJson.parseToJsonElement(text) }.getOrElse { JsonPrimitive(text) }
        if (response.isSuccessful) return element
        val detail = (element as? JsonObject)?.get("detail")
        val code = (detail as? JsonObject)?.get("code")?.jsonPrimitive?.contentOrNull
        val message = when (detail) {
            is JsonObject -> detail["message"]?.jsonPrimitive?.contentOrNull
            is JsonPrimitive -> detail.contentOrNull
            else -> null
        } ?: "请求失败（${response.code}）"
        val error = ApiException(response.code, code, message, detail)
        error.suspension?.let(onSuspended)
        error.deviceBan?.let(onDeviceBanned)
        if (response.code == 401) onUnauthorized()
        throw error
    }
}

suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!continuation.isCancelled) continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response)
        }
    })
    continuation.invokeOnCancellation { runCatching { cancel() } }
}

/** 给界面看的错误文案 */
fun Throwable.userMessage(): String = when (this) {
    is ApiException -> message
    is IOException -> "网络不太好，稍后再试"
    else -> message ?: "出了点问题"
}

fun JsonElement.obj(): JsonObject = jsonObject
