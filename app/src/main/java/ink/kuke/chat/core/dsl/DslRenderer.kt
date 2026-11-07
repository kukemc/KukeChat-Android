package ink.kuke.chat.core.dsl

import android.annotation.SuppressLint
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import android.util.LruCache
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

/** 一条编译报错（行号、列号从 1 起；渲染本身失败时 code 是 E_RENDER / E_RENDERER / E_INTERNAL，没有行号） */
@Serializable
data class DslDiagnostic(
    val line: Int? = null,
    val column: Int? = null,
    val code: String = "",
    val message: String = "",
    val fix: String? = null,
)

/**
 * 一段积木文本的渲染结果。[bitmap] 是渲染页按设备密度画的 PNG（透明底）；[width] / [height] 是 1 倍尺寸，
 * 直接当 dp 用就是原大小。ok=false 时 [diagnostics] 里是报错，界面显示代码。
 */
class DslRenderResult(
    val ok: Boolean,
    val bitmap: Bitmap?,
    val width: Float,
    val height: Float,
    val diagnostics: List<DslDiagnostic>,
    val detected: Boolean?,
) {
    /** 编译通过了，只是画不出来（这台设备的 WebView 不行、渲染页出错） */
    val renderFailed: Boolean get() = !ok && diagnostics.isNotEmpty() && diagnostics.all { it.line == null && it.code in RENDER_ERRORS }

    companion object {
        val RENDER_ERRORS = setOf("E_RENDER", "E_RENDERER", "E_INTERNAL", "E_TIMEOUT")
        fun failure(code: String, message: String) = DslRenderResult(false, null, 0f, 0f, listOf(DslDiagnostic(code = code, message = message)), null)
    }
}

/**
 * 积木渲染服务：一个隐藏的 WebView 打开打进包里的渲染页（assets/dsl-render/，由 tools/sync-dsl-render.mjs 从电脑版同步），
 * 调页面上的 `kukeDslRender` / `kukeDslDetect`（契约见 KukeChat-Client/docs/gandi-dsl-blocks.md「安卓渲染页」）。
 *
 * - WebView 第一次用到时才在主线程创建，不挂到任何界面上；页面走 https://appassets.androidplatform.net/dsl-render/ 这个
 *   虚拟地址，由 [shouldInterceptRequest] 从 assets 里取文件（同源，fetch / canvas 都不受 file:// 的限制），别的地址一律拦掉，不联网；
 * - 请求排队一条一条画（同一个积木库实例），每条带编号，结果从 JS 回调 [Bridge.onResult] 回来，超时 20 秒；
 * - 结果按「文本 + 主题 + 倍率」放进 LRU 缓存（按位图字节数计），同一段积木同时被几处要时只画一次；
 * - 闲置 2 分钟、系统内存紧张（onTrimMemory）时销毁 WebView，下次用到再建。
 */
object DslRenderer {
    private const val TAG = "DslRenderer"
    private const val HOST = "appassets.androidplatform.net"
    private const val PAGE = "https://$HOST/dsl-render/index.html"
    private const val ASSET_DIR = "dsl-render/"
    private const val RENDER_TIMEOUT_MS = 20_000L
    private const val LOAD_TIMEOUT_MS = 15_000L
    private const val IDLE_RELEASE_MS = 120_000L

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val lock = Mutex()
    private val ids = AtomicLong()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<String>>()
    private val inFlight = ConcurrentHashMap<String, Deferred<DslRenderResult>>()

    // 按位图字节数算，最多 24 MB
    private val cache = object : LruCache<String, DslRenderResult>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: DslRenderResult): Int = (value.bitmap?.allocationByteCount ?: 0) + 512
    }
    private val detectCache = LruCache<String, Boolean>(256)

    private var app: Context? = null
    private var webView: WebView? = null
    private var ready: CompletableDeferred<Boolean>? = null
    private var idleJob: Job? = null

    /** Application.onCreate 里调一次：记下 Context，内存紧张时释放 WebView */
    fun init(context: Context) {
        if (app != null) return
        val appContext = context.applicationContext
        app = appContext
        appContext.registerComponentCallbacks(object : ComponentCallbacks2 {
            @Suppress("DEPRECATION") // RUNNING_* 在 Android 14 起不再下发，老系统上照样有用
            override fun onTrimMemory(level: Int) {
                // 界面退到后台 / 运行中内存吃紧：WebView 放掉；进了后台缓存列表或更严重时连缓存的图一起清
                if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) scope.launch { release() }
                if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND || level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) cache.evictAll()
            }

            override fun onConfigurationChanged(newConfig: Configuration) = Unit

            @Deprecated("Deprecated in Java")
            override fun onLowMemory() {
                cache.evictAll()
                scope.launch { release() }
            }
        })
    }

    /** 缓存里现成的结果（不触发渲染）：列表滚回来时直接出图，不闪占位 */
    fun cached(text: String, dark: Boolean, scale: Float): DslRenderResult? = cache.get(key(text, dark, scale))

    /**
     * 画一段积木文本（整条消息也行：有积木围栏时页面取第一段）。不会抛异常，失败时 ok=false 带一条报错。
     * [scale] 是 PNG 的倍率，传设备密度（页面限制在 (0, 4]）。
     */
    suspend fun render(text: String, dark: Boolean, scale: Float): DslRenderResult {
        val s = scale.coerceIn(1f, 4f)
        val key = key(text, dark, s)
        cache.get(key)?.let { return it }
        // LAZY：先登记进 inFlight 再开跑，免得同步就结束的任务在登记前就把自己移除、留下一个过期的结果
        val job = inFlight.getOrPut(key) {
            scope.async(start = CoroutineStart.LAZY) {
                try {
                    renderNow(text, dark, s).also { if (!it.renderFailed) cache.put(key, it) }
                } finally {
                    inFlight.remove(key)
                }
            }
        }
        job.start()
        return job.await()
    }

    /**
     * 没有围栏的整条消息是不是积木（同电脑版 detectDsl：零错误、编出积木、用到真积木）。
     * 先过 [DslText.looksLikeDsl] 预筛，不过的不碰 WebView。
     */
    suspend fun detect(text: String): Boolean {
        if (DslText.split(text) != null) return true
        if (!DslText.looksLikeDsl(text)) return false
        detectCache.get(text)?.let { return it }
        val result = try {
            lock.withLock {
                withTimeout(RENDER_TIMEOUT_MS) {
                    val view = ensureReady() ?: return@withTimeout null
                    evaluate(view, "String(!!window.kukeDslDetect(${json.encodeToString(String.serializer(), text)}))")
                }
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            null
        } finally {
            scheduleIdleRelease()
        }
        val detected = result?.trim('"') == "true"
        if (result != null) detectCache.put(text, detected)
        return detected
    }

    private fun key(text: String, dark: Boolean, scale: Float) = "${if (dark) 'd' else 'l'}${"%.2f".format(java.util.Locale.ROOT, scale)}\u0000$text"

    private suspend fun renderNow(text: String, dark: Boolean, scale: Float): DslRenderResult {
        val raw = try {
            lock.withLock {
                withTimeout(RENDER_TIMEOUT_MS) {
                    val view = ensureReady() ?: return@withTimeout null
                    val id = "r${ids.incrementAndGet()}"
                    val deferred = CompletableDeferred<String>()
                    pending[id] = deferred
                    try {
                        val textJs = json.encodeToString(String.serializer(), text)
                        val theme = if (dark) "dark" else "light"
                        // svg 不往回传（安卓存图存 PNG），省掉一大段跨线程的字符串
                        view.evaluateJavascript(
                            """(function(){var id='$id';function done(r){try{if(r)delete r.svg;KukeDslBridge.onResult(id,JSON.stringify(r))}catch(e){KukeDslBridge.onResult(id,JSON.stringify({ok:false,diagnostics:[{code:'E_INTERNAL',message:String(e)}]}))}}
                            try{Promise.resolve(window.kukeDslRender($textJs,{theme:'$theme',scale:$scale})).then(done,function(e){done({ok:false,diagnostics:[{code:'E_INTERNAL',message:String(e&&e.message||e)}]})})}catch(e){done({ok:false,diagnostics:[{code:'E_INTERNAL',message:String(e&&e.message||e)}]})}})();""",
                            null,
                        )
                        deferred.await()
                    } finally {
                        pending.remove(id)
                    }
                }
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            Log.w(TAG, "render timeout")
            // 卡住的页面不再信任：放掉，下次重建
            release()
            return DslRenderResult.failure("E_TIMEOUT", "积木生成超时")
        } finally {
            scheduleIdleRelease()
        }
        raw ?: return DslRenderResult.failure("E_RENDERER", "这台设备画不了积木")
        return withContext(Dispatchers.Default) { parse(raw) }
    }

    @Serializable
    private class RawResult(
        val ok: Boolean = false,
        val png: String? = null,
        val width: Double? = null,
        val height: Double? = null,
        val diagnostics: List<DslDiagnostic> = emptyList(),
        val detected: Boolean? = null,
    )

    private fun parse(raw: String): DslRenderResult {
        val result = runCatching { json.decodeFromString(RawResult.serializer(), raw) }.getOrElse {
            return DslRenderResult.failure("E_INTERNAL", "渲染结果读不出来")
        }
        if (!result.ok) {
            val diagnostics = result.diagnostics.ifEmpty { listOf(DslDiagnostic(code = "E_RENDER", message = "积木画不出来")) }
            return DslRenderResult(false, null, 0f, 0f, diagnostics, result.detected)
        }
        val bitmap = result.png?.let(::decodeDataUrl) ?: return DslRenderResult.failure("E_RENDER", "积木图片解不开")
        val width = result.width?.toFloat()?.takeIf { it > 0f } ?: bitmap.width.toFloat()
        val height = result.height?.toFloat()?.takeIf { it > 0f } ?: bitmap.height.toFloat()
        return DslRenderResult(true, bitmap, width, height, emptyList(), result.detected)
    }

    /** data:image/png;base64,… → Bitmap。太大的图（超过 16 MB 像素内存）按 2 的幂缩小解码，免得长脚本把内存撑爆 */
    private fun decodeDataUrl(dataUrl: String): Bitmap? = runCatching {
        val comma = dataUrl.indexOf(',')
        if (comma < 0) return null
        val bytes = Base64.decode(dataUrl.substring(comma + 1), Base64.DEFAULT)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeStream(ByteArrayInputStream(bytes), null, bounds)
        var sample = 1
        while (bounds.outWidth.toLong() / sample * (bounds.outHeight.toLong() / sample) * 4 > 16L * 1024 * 1024) sample *= 2
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()

    private suspend fun evaluate(view: WebView, script: String): String? = withContext(Dispatchers.Main.immediate) {
        suspendCancellableCoroutine { cont -> view.evaluateJavascript(script) { value -> if (cont.isActive) cont.resume(value) } }
    }

    /** 拿一个页面已就绪的 WebView（必要时现建）。建不出来（没有 WebView 组件、正在升级）返回 null */
    private suspend fun ensureReady(): WebView? = withContext(Dispatchers.Main.immediate) {
        idleJob?.cancel()
        val context = app ?: return@withContext null
        val existing = webView
        val waiting = ready
        if (existing != null && waiting != null) {
            return@withContext if (runCatching { withTimeout(LOAD_TIMEOUT_MS) { waiting.await() } }.getOrDefault(false)) existing else null.also { release() }
        }
        val view = runCatching { createWebView(context) }.getOrElse {
            Log.w(TAG, "WebView unavailable", it)
            return@withContext null
        }
        val loaded = CompletableDeferred<Boolean>()
        webView = view
        ready = loaded
        view.loadUrl(PAGE)
        val ok = runCatching { withTimeout(LOAD_TIMEOUT_MS) { loaded.await() } }.getOrDefault(false) &&
            evaluate(view, "String(window.kukeDslReady===true)")?.trim('"') == "true"
        if (!ok) {
            release()
            return@withContext null
        }
        view
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun createWebView(context: Context): WebView {
        val view = WebView(context)
        view.settings.apply {
            javaScriptEnabled = true
            // 页面从虚拟 https 地址加载，不需要任何本地文件访问
            allowFileAccess = false
            allowContentAccess = false
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = false
            domStorageEnabled = false
            setGeolocationEnabled(false)
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            mediaPlaybackRequiresUserGesture = true
        }
        view.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        view.addJavascriptInterface(Bridge, "KukeDslBridge")
        view.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse = serveAsset(context, request)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true

            override fun onPageFinished(view: WebView, url: String?) {
                if (view === webView) ready?.complete(true)
            }

            override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail?): Boolean {
                // 渲染进程被系统杀了：丢掉这个 WebView，等着的请求全部失败，下次重建
                if (view === webView) {
                    webView = null
                    ready?.complete(false)
                    ready = null
                }
                pending.values.forEach { it.complete("""{"ok":false,"diagnostics":[{"code":"E_RENDERER","message":"积木渲染进程退出了"}]}""") }
                view.destroy()
                return true
            }
        }
        // 不挂到界面上：给一个固定的布局尺寸（离屏 iframe 里量积木尺寸要有视口）
        val metrics = context.resources.displayMetrics
        val w = metrics.widthPixels.coerceAtLeast(720)
        val h = metrics.heightPixels.coerceAtLeast(1280)
        view.measure(android.view.View.MeasureSpec.makeMeasureSpec(w, android.view.View.MeasureSpec.EXACTLY), android.view.View.MeasureSpec.makeMeasureSpec(h, android.view.View.MeasureSpec.EXACTLY))
        view.layout(0, 0, w, h)
        view.onResume()
        view.resumeTimers()
        return view
    }

    /** 只认 https://appassets.androidplatform.net/dsl-render/…，从 assets 取；其他一律 404（渲染页本身也不该联网） */
    private fun serveAsset(context: Context, request: WebResourceRequest): WebResourceResponse {
        val url = request.url
        val path = url.path.orEmpty().removePrefix("/")
        if (url.scheme != "https" || url.host != HOST || !path.startsWith(ASSET_DIR) || path.contains("..")) return notFound()
        return runCatching {
            val stream = context.assets.open(path)
            WebResourceResponse(mimeOf(path), if (mimeOf(path).startsWith("image/") && !path.endsWith(".svg")) null else "utf-8", stream).apply {
                responseHeaders = mapOf("Access-Control-Allow-Origin" to "*", "Cache-Control" to "no-cache")
            }
        }.getOrElse { notFound() }
    }

    private fun notFound() = WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))

    private fun mimeOf(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
        "html" -> "text/html"
        "js" -> "text/javascript"
        "css" -> "text/css"
        "svg" -> "image/svg+xml"
        "gif" -> "image/gif"
        "png" -> "image/png"
        "json" -> "application/json"
        else -> "text/plain"
    }

    private fun scheduleIdleRelease() {
        scope.launch {
            idleJob?.cancel()
            idleJob = scope.launch {
                delay(IDLE_RELEASE_MS)
                if (!lock.isLocked && inFlight.isEmpty()) release()
            }
        }
    }

    /** 销毁 WebView（缓存的图留着）。只在主线程调 */
    private fun release() {
        val view = webView ?: return
        webView = null
        ready?.complete(false)
        ready = null
        pending.values.forEach { it.complete("""{"ok":false,"diagnostics":[{"code":"E_RENDERER","message":"积木渲染已中断"}]}""") }
        runCatching {
            view.removeJavascriptInterface("KukeDslBridge")
            view.stopLoading()
            view.destroy()
        }
    }

    /** JS → Kotlin：页面画完一条，带着请求编号回来（在 WebView 的 JavaBridge 线程上调） */
    private object Bridge {
        @JavascriptInterface
        fun onResult(id: String, result: String) {
            pending[id]?.complete(result)
        }
    }
}
