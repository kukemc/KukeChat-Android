package ink.kuke.chat

import android.app.Application
import android.os.Build
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import ink.kuke.chat.core.AppGraph
import ink.kuke.chat.service.Notifier
import kotlinx.coroutines.launch

class KukeApp : Application(), ImageLoaderFactory {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        Notifier.ensureChannels(this)
        // 语音播放器（ExoPlayer）要 Context
        ink.kuke.chat.feature.chat.VoicePlayer.bind(this)
        // 最近会话发布成直接分享快捷方式（会话列表变了就更新）
        ink.kuke.chat.feature.share.ShareShortcuts.bind(this, graph)
        // 自动检查更新：发版推送、回前台超过 1 小时再查（启动那次由 UpdateHost 发起）
        ink.kuke.chat.feature.settings.UpdateCenter.watch(graph)
        // 上次进程里分享进来的附件副本：新进程里已经没有任何东西引用它们了（只留刚拷的，防同一进程里正在准备发送的）
        graph.scope.launch(kotlinx.coroutines.Dispatchers.IO) { ink.kuke.chat.feature.share.ShareCache.sweep(this@KukeApp, olderThanMs = 10 * 60 * 1000L) }
    }

    // Coil 默认只解第一帧；装上动图解码器，GIF / 动态 WebP 表情才会动（Android 9+ 用系统 ImageDecoder，更省内存）
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .components {
            if (Build.VERSION.SDK_INT >= 28) add(ImageDecoderDecoder.Factory()) else add(GifDecoder.Factory())
        }
        .crossfade(true)
        .build()
}
