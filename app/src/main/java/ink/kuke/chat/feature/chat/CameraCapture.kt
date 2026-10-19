package ink.kuke.chat.feature.chat

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID

/**
 * 拍照的临时文件：cacheDir/camera/<随机名>.jpg，经 FileProvider（name = camera，见 res/xml/file_paths.xml）交给系统相机写入。
 * 拍好的照片进发图确认面板，和相册选的图走同一条路（ImageSendSheet → sendImages → uploadMessageImage，EXIF 转正在那里做）。
 * 什么时候删：没拍（取消）、从确认面板去掉、整个面板取消时当场删；发出去的留着——聊天里还用本地这张来画（LocalImages），
 * 发送失败重发也要再读它，留到下次进程启动由 [sweep] 清掉（同 ShareCache）。
 */
object CameraShots {
    private const val TAG = "CameraShots"
    private const val DIR = "camera"

    private fun authority(context: Context) = "${context.packageName}.fileprovider"

    /** 新建一个空文件给相机写；返回文件和给相机的 content:// 地址 */
    fun newShot(context: Context): Pair<File, Uri> {
        val dir = File(context.cacheDir, DIR).apply { mkdirs() }
        val file = File(dir, UUID.randomUUID().toString().replace("-", "") + ".jpg")
        file.createNewFile()
        return file to FileProvider.getUriForFile(context, authority(context), file)
    }

    /** 这个地址是不是拍照临时文件（确认面板去掉图时只删这种，相册的图不动） */
    fun fileOf(context: Context, uri: Uri): File? {
        if (uri.authority != authority(context)) return null
        val segments = uri.pathSegments
        if (segments.size != 2 || segments[0] != DIR) return null
        val root = File(context.cacheDir, DIR)
        val file = File(root, segments[1])
        return file.takeIf { runCatching { it.canonicalFile.parentFile == root.canonicalFile }.getOrDefault(false) }
    }

    fun delete(context: Context, uri: Uri) {
        fileOf(context, uri)?.delete()
    }

    /** 清掉早于 [olderThanMs] 的照片：进程刚启动时发件箱、LocalImages 都是空的，没有东西还引用它们 */
    fun sweep(context: Context, olderThanMs: Long) {
        val cutoff = System.currentTimeMillis() - olderThanMs
        runCatching {
            File(context.cacheDir, DIR).listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
        }.onFailure { Log.w(TAG, "sweep failed", it) }
    }
}

/**
 * 拍一张照片：返回「开相机」的函数，拍好了回调 [onShot]（拍照临时文件的地址）。用系统相机（TakePicture），
 * 清单里没声明 CAMERA 权限，所以不用申请（声明了的话系统会要求先授权才能调相机）。
 * 正在拍的那个文件路径存进 rememberSaveable：相机在前台时我们的界面可能被系统回收重建，回来还能认出是哪张。
 */
@Composable
internal fun rememberTakePhoto(onShot: (Uri) -> Unit): () -> Unit {
    val context = LocalContext.current
    val latest by rememberUpdatedState(onShot)
    var pendingPath by rememberSaveable { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val path = pendingPath ?: return@rememberLauncherForActivityResult
        pendingPath = null
        val file = File(path)
        when {
            // 有的相机点了确定却什么都没写：空文件当没拍到
            saved && file.length() > 0 -> latest(FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file))
            saved -> {
                file.delete()
                toast(context, "没拍到照片，再试一次")
            }
            // 取消：不提示，删掉空文件
            else -> file.delete()
        }
    }
    return launch@{
        val (file, uri) = runCatching { CameraShots.newShot(context) }.getOrElse {
            toast(context, "相机打不开")
            return@launch
        }
        pendingPath = file.absolutePath
        // 没有相机应用（ActivityNotFoundException）、被系统策略禁用等：当场失败
        runCatching { launcher.launch(uri) }.onFailure {
            pendingPath = null
            file.delete()
            toast(context, "相机打不开")
        }
    }
}
