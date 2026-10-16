package ink.kuke.chat.service

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * 后台保活要用户手动放开的几样东西（第 1 层的最后一块）：系统不让 App 自己改，只能检测、再把用户带到对应的设置页。
 *
 * 实测（Redmi / HyperOS，Android 17）：省电策略不是「无限制」时，App 退到后台几秒就被 Greezer 冻住，前台服务也挡不住，
 * 心跳停、连接实际上已经断了。设成「无限制」等于加进系统的电池优化白名单，isIgnoringBatteryOptimizations 能读到。
 * 自启动（开机、被杀后拉起）各家都没有公开的查询接口，只能引导用户去确认。
 */
object KeepAlive {
    enum class Vendor(val label: String) { Xiaomi("小米 / Redmi"), Huawei("华为"), Honor("荣耀"), Oppo("OPPO / 一加 / realme"), Vivo("vivo / iQOO"), Other("这台手机") }

    val vendor: Vendor
        get() {
            val brand = (Build.MANUFACTURER + " " + Build.BRAND).lowercase()
            return when {
                "xiaomi" in brand || "redmi" in brand || "poco" in brand -> Vendor.Xiaomi
                "honor" in brand -> Vendor.Honor
                "huawei" in brand -> Vendor.Huawei
                "oppo" in brand || "oneplus" in brand || "realme" in brand -> Vendor.Oppo
                "vivo" in brand || "iqoo" in brand -> Vendor.Vivo
                else -> Vendor.Other
            }
        }

    fun ignoringBatteryOptimizations(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    /** 该做的都做了（通知权限 + 电池白名单）。自启动查不到，不算在内 */
    fun ready(context: Context): Boolean = Notifier.canPost(context) && ignoringBatteryOptimizations(context)

    /** 省电策略 / 电池优化。小米直接打开应用详情（里面有「省电策略」）；其余先弹系统的「允许忽略电池优化」对话框 */
    @SuppressLint("BatteryLife")
    fun batteryIntents(context: Context): List<Intent> {
        val pkg = context.packageName
        val list = mutableListOf<Intent>()
        if (vendor == Vendor.Xiaomi) {
            list += Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.appmanager.ApplicationsDetailsActivity"))
                .putExtra("package_name", pkg).putExtra("package_label", "KukeChat")
        }
        list += Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$pkg"))
        list += Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        list += appDetails(context)
        return list
    }

    /** 自启动管理页（各家入口不同、会随系统版本变，按顺序试，都打不开就去应用详情） */
    fun autostartIntents(context: Context): List<Intent> {
        val components = when (vendor) {
            Vendor.Xiaomi -> listOf("com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity")
            Vendor.Huawei, Vendor.Honor -> listOf(
                "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
                "com.huawei.systemmanager" to "com.huawei.systemmanager.optimize.process.ProtectActivity",
                "com.hihonor.systemmanager" to "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            )
            Vendor.Oppo -> listOf(
                "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity",
                "com.oplus.safecenter" to "com.oplus.safecenter.startupapp.StartupAppListActivity",
                "com.coloros.safecenter" to "com.coloros.privacypermissionsentry.PermissionTopActivity",
            )
            Vendor.Vivo -> listOf(
                "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
                "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager",
            )
            Vendor.Other -> emptyList()
        }
        return components.map { (pkg, cls) -> Intent().setComponent(ComponentName(pkg, cls)) } + appDetails(context)
    }

    fun notificationSettings(context: Context): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

    private fun appDetails(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))

    /** 按顺序试，第一个能打开的就用 */
    fun open(context: Context, intents: List<Intent>): Boolean {
        for (intent in intents) {
            val launch = Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { context.startActivity(launch) }.isSuccess) return true
        }
        return false
    }

    /** 这家手机要在设置里点哪几下（找不到直达入口时照着点） */
    fun batteryHint(): String = when (vendor) {
        Vendor.Xiaomi -> "应用详情 › 省电策略 › 选「无限制」"
        Vendor.Huawei, Vendor.Honor -> "设置 › 电池 › 应用启动管理 › 关掉 KukeChat 的「自动管理」，三项都打开"
        Vendor.Oppo -> "设置 › 电池 › 应用耗电管理 › KukeChat › 允许后台行为"
        Vendor.Vivo -> "设置 › 电池 › 后台耗电管理 › KukeChat › 允许后台高耗电"
        Vendor.Other -> "允许 KukeChat 忽略电池优化"
    }

    fun autostartHint(): String = when (vendor) {
        Vendor.Xiaomi -> "应用详情 › 自启动 › 打开"
        Vendor.Huawei, Vendor.Honor -> "应用启动管理里的「允许自启动」"
        Vendor.Oppo -> "应用详情 › 允许自动启动"
        Vendor.Vivo -> "i 管家 › 应用管理 › 权限管理 › 自启动 › 打开 KukeChat"
        Vendor.Other -> "如果系统有「自启动」或「后台运行」开关，打开它"
    }
}
