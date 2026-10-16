package ink.kuke.chat.ui.components

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View

/** 设计稿「触感反馈」：轻（CLOCK_TICK）· 中（CONFIRM）· 重（REJECT）。Compose 自带的类型不全，直接走 View */
object Haptics {
    fun tick(view: View) = view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)

    fun confirm(view: View) = view.performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY)

    fun reject(view: View) = view.performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS)
}
