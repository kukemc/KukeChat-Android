package ink.kuke.chat.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import ink.kuke.chat.core.session.SessionStore

/** 开机、App 更新之后：登录着就把常驻服务拉起来（remoteMessaging 类型的前台服务允许在开机广播里启动） */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (SessionStore.snapshotToken(context).isNullOrBlank()) return
        RealtimeService.start(context)
        CatchUpWorker.schedule(context)
    }
}
