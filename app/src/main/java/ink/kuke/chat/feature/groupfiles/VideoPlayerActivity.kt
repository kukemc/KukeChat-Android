package ink.kuke.chat.feature.groupfiles

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color as AndroidColor
import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import ink.kuke.chat.KukeApp
import ink.kuke.chat.core.AppGraph
import ink.kuke.chat.core.net.userMessage
import ink.kuke.chat.ui.icons.KcIcon
import ink.kuke.chat.ui.icons.KcIcons
import ink.kuke.chat.ui.theme.KcTheme
import ink.kuke.chat.ui.theme.KukeTheme
import kotlinx.coroutines.launch

/**
 * 群文件里的视频：全屏播放页（ExoPlayer 流式播放，不用先下完）。
 * 下好了的放本地文件；没下的现取预签名直链（5 分钟过期，过期后拖进度条会 403，自动重取一次接着放），
 * 服务器没开直连时走带鉴权头的下载接口（每次请求现算证明头）。
 * 单独一个 Activity：横屏全屏时不重建（manifest 里声明了 configChanges），返回键直接关。
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class VideoPlayerActivity : ComponentActivity() {
    companion object {
        private const val EXTRA_CONVERSATION = "conversation_id"
        private const val EXTRA_FILE = "file_id"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_SIZE = "size"
        private const val MAX_RELINKS = 3

        fun open(context: Context, conversationId: Long, fileId: Long, name: String, size: Long) {
            val intent = Intent(context, VideoPlayerActivity::class.java)
                .putExtra(EXTRA_CONVERSATION, conversationId)
                .putExtra(EXTRA_FILE, fileId)
                .putExtra(EXTRA_NAME, name)
                .putExtra(EXTRA_SIZE, size)
            if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    private lateinit var graph: AppGraph
    private var conversationId = 0L
    private var fileId = 0L
    private var fileName = ""
    private var fileSize = 0L

    private var player: ExoPlayer? = null
    /** true：当前地址走后端下载接口，每个请求都要现加鉴权头 */
    private var backendMode = false
    private var relinks = 0

    private var loading by mutableStateOf(true)
    private var error by mutableStateOf<String?>(null)
    private var controlsVisible by mutableStateOf(true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
        )
        graph = (application as KukeApp).graph
        conversationId = intent.getLongExtra(EXTRA_CONVERSATION, 0L)
        fileId = intent.getLongExtra(EXTRA_FILE, 0L)
        fileName = intent.getStringExtra(EXTRA_NAME) ?: "视频"
        fileSize = intent.getLongExtra(EXTRA_SIZE, 0L)

        val http = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true).setUserAgent("KukeChat-Android")
        val resolving = ResolvingDataSource.Factory(http) { spec ->
            if (backendMode) spec.withAdditionalHeaders(FileDownloads.backendSource(graph, conversationId, fileId).headers) else spec
        }
        val exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(this, resolving)))
            .build()
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) loading = false
            }

            override fun onPlayerError(e: PlaybackException) = handleError(e)
        })
        player = exo

        setContent {
            KukeTheme(dark = true) {
                BackHandler { finish() }
                PlayerScreen(exo)
            }
        }
        load(resume = 0L)
    }

    /** 选播放地址并开始放：本地下好的 → 本地；否则现取直链 / 后端下载接口 */
    private fun load(resume: Long) {
        loading = true
        error = null
        lifecycleScope.launch {
            try {
                val local = FileDownloads.current(this@VideoPlayerActivity, fileId) as? FileDownload.Done
                val uri = if (local != null) {
                    backendMode = false
                    local.uri.toString()
                } else {
                    val source = FileDownloads.resolveSource(graph, conversationId, fileId)
                    backendMode = source.headers.isNotEmpty()
                    source.url
                }
                val exo = player ?: return@launch
                exo.setMediaItem(MediaItem.fromUri(uri), resume)
                exo.prepare()
                exo.playWhenReady = true
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                loading = false
                error = e.userMessage()
            }
        }
    }

    private fun handleError(e: PlaybackException) {
        val code = (e.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
        // 预签名地址过期（对象存储回 403）或后端证明头过期：重新取地址，从当前位置接着放
        if ((code == 403 || code == 401) && relinks < MAX_RELINKS) {
            relinks++
            load(resume = player?.currentPosition ?: 0L)
            return
        }
        loading = false
        error = when {
            code == 410 || code == 404 -> "文件已过期或被删除"
            code != null -> "播放失败（HTTP $code）"
            e.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                e.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "网络不太好，稍后再试"
            e.errorCode in 3000..3999 || e.errorCode in 4000..4999 -> "这个视频格式播放不了，可以保存到下载后用别的应用打开"
            else -> "播放失败（${e.errorCodeName}）"
        }
    }

    private fun applyFullscreen(on: Boolean) {
        val size = player?.videoSize
        val portraitVideo = size != null && size.height > size.width
        requestedOrientation = when {
            !on -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            portraitVideo -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            else -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (on) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    @androidx.compose.runtime.Composable
    private fun PlayerScreen(exo: ExoPlayer) {
        val type = KcTheme.type
        val barAlpha by animateFloatAsState(if (controlsVisible || error != null) 1f else 0f, tween(200), label = "bar")
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(
                factory = { context ->
                    PlayerView(context).apply {
                        player = exo
                        keepScreenOn = true
                        setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                        setShowNextButton(false)
                        setShowPreviousButton(false)
                        setShowShuffleButton(false)
                        setShowSubtitleButton(false)
                        setShowVrButton(false)
                        controllerShowTimeoutMs = 3000
                        setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility -> controlsVisible = visibility == View.VISIBLE })
                        setFullscreenButtonClickListener { on -> applyFullscreen(on) }
                    }
                },
                modifier = Modifier.fillMaxSize().systemBarsPadding().displayCutoutPadding(),
            )
            // 顶栏：返回 · 文件名 · 保存到下载（跟着播放控制条一起显隐）
            Row(
                Modifier
                    .fillMaxWidth()
                    .alpha(barAlpha)
                    .background(Color.Black.copy(alpha = 0.35f))
                    .systemBarsPadding()
                    .displayCutoutPadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(40.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.12f)).clickable(enabled = barAlpha > 0.5f) { finish() },
                    contentAlignment = Alignment.Center,
                ) {
                    KcIcon(KcIcons.chevronLeft, tint = Color.White, size = 22.dp)
                }
                Text(
                    fileName,
                    style = type.rowTitle,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                )
                SavePill(enabled = barAlpha > 0.5f)
            }
            if (loading && error == null) {
                Text("正在加载…", style = type.secondary, color = Color.White.copy(alpha = 0.7f), modifier = Modifier.align(Alignment.Center))
            }
            error?.let { message ->
                Column(
                    Modifier.align(Alignment.Center).padding(horizontal = 32.dp).widthIn(max = 320.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(message, style = type.body, color = Color.White, textAlign = TextAlign.Center)
                    Text(
                        "重试",
                        style = type.rowTitle,
                        color = Color.Black,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(Color.White).clickable {
                            relinks = 0
                            load(resume = player?.currentPosition ?: 0L)
                        }.padding(horizontal = 28.dp, vertical = 10.dp),
                    )
                }
            }
        }
    }

    /** 右上角胶囊：保存到下载 / 下载中 42% / 已保存 */
    @androidx.compose.runtime.Composable
    private fun SavePill(enabled: Boolean) {
        val state = FileDownloads.states[fileId]
        val label = when (state) {
            is FileDownload.Running -> state.ratio(fileSize)?.let { "下载中 ${(it * 100).toInt()}%" } ?: "下载中"
            is FileDownload.Done -> "已保存"
            else -> "保存到下载"
        }
        Row(
            Modifier
                .clip(RoundedCornerShape(50))
                .border(1.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(50))
                .clickable(enabled = enabled) { FileDownloads.download(graph, this@VideoPlayerActivity, conversationId, fileId, fileName, fileSize, openAfter = false) }
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            KcIcon(KcIcons.download, tint = Color.White, size = 16.dp)
            Text(label, style = KcTheme.type.secondary, color = Color.White, modifier = Modifier.padding(start = 6.dp))
        }
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }
}
