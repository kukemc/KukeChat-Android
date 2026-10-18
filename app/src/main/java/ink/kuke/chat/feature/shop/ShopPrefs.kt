package ink.kuke.chat.feature.shop

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 装扮相关的本机设置（同电脑版 store/shopPrefs）：目前只有「显示别人的气泡皮肤」，默认开。
 * 只存在这台手机上，不上传。值放在 Compose 状态里，改了以后聊天里的气泡立刻跟着变。
 */
object ShopPrefs {
    private const val FILE = "shop_prefs"
    private const val KEY_SHOW_OTHERS = "show_others_skins"

    private var loaded = false
    private var showOthers by mutableStateOf(true)

    fun showOthersSkins(context: Context): Boolean {
        if (!loaded) {
            loaded = true
            showOthers = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_SHOW_OTHERS, true)
        }
        return showOthers
    }

    fun setShowOthersSkins(context: Context, value: Boolean) {
        loaded = true
        showOthers = value
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(KEY_SHOW_OTHERS, value).apply()
    }
}
