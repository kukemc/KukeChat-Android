package ink.kuke.chat

import android.app.Application
import android.os.Build
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import ink.kuke.chat.core.AppGraph
import ink.kuke.chat.service.Notifier

class KukeApp : Application(), ImageLoaderFactory {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        Notifier.ensureChannels(this)
    }

    // Coil 默认只解第一帧；装上动图解码器，GIF / 动态 WebP 表情才会动（Android 9+ 用系统 ImageDecoder，更省内存）
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .components {
            if (Build.VERSION.SDK_INT >= 28) add(ImageDecoderDecoder.Factory()) else add(GifDecoder.Factory())
        }
        .crossfade(true)
        .build()
}
