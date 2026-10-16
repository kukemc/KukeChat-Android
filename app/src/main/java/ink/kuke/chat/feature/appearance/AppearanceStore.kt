package ink.kuke.chat.feature.appearance

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

private val Context.appearanceData by preferencesDataStore(name = "appearance")

/**
 * 外观设置的存取。启动时同步读一次（几十字节，避免先闪一下默认主题再变），之后改动先改内存里的 state、再异步落盘。
 * 整个进程共用一份：外壳（套主题）和设置页（改主题）用 [shared] 拿到的是同一个实例。
 */
class AppearanceStore(context: Context) {
    private val data = context.applicationContext.appearanceData
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(runBlocking { read(data.data.first()) })
    val state: StateFlow<Appearance> = _state.asStateFlow()

    /** 改一项或几项；立刻生效，界面实时预览靠这个 */
    fun update(change: (Appearance) -> Appearance) {
        val next = change(_state.value)
        _state.value = next
        scope.launch { data.edit { write(it, next) } }
    }

    fun reset() = update { Appearance() }

    private fun read(p: Preferences): Appearance {
        val d = Appearance()
        return Appearance(
            mode = DarkMode.entries.getOrElse(p[KMode] ?: d.mode.ordinal) { d.mode },
            presetId = p[KPreset] ?: d.presetId,
            accent = p[KAccent],
            bubbleKind = p[KBubbleKind]?.let { BubbleKind.entries.getOrNull(it) },
            bubbleColor = p[KBubbleColor],
            bubbleRadius = (p[KRadius] ?: d.bubbleRadius).coerceIn(RadiusMin, RadiusMax),
            density = Density.entries.getOrElse(p[KDensity] ?: 0) { Density.Cozy },
            avatarShape = AvatarShape.entries.getOrElse(p[KShape] ?: 0) { AvatarShape.Circle },
            fontIndex = (p[KFont] ?: d.fontIndex).coerceIn(0, 4),
        )
    }

    private fun write(p: MutablePreferences, a: Appearance) {
        p[KMode] = a.mode.ordinal
        p[KPreset] = a.presetId
        if (a.accent != null) p[KAccent] = a.accent else p.remove(KAccent)
        if (a.bubbleKind != null) p[KBubbleKind] = a.bubbleKind.ordinal else p.remove(KBubbleKind)
        if (a.bubbleColor != null) p[KBubbleColor] = a.bubbleColor else p.remove(KBubbleColor)
        p[KRadius] = a.bubbleRadius
        p[KDensity] = a.density.ordinal
        p[KShape] = a.avatarShape.ordinal
        p[KFont] = a.fontIndex
    }

    companion object {
        private val KMode = intPreferencesKey("mode")
        private val KPreset = stringPreferencesKey("preset")
        private val KAccent = longPreferencesKey("accent")
        private val KBubbleKind = intPreferencesKey("bubble_kind")
        private val KBubbleColor = longPreferencesKey("bubble_color")
        private val KRadius = intPreferencesKey("bubble_radius")
        private val KDensity = intPreferencesKey("density")
        private val KShape = intPreferencesKey("avatar_shape")
        private val KFont = intPreferencesKey("font_index")

        @Volatile private var instance: AppearanceStore? = null

        /** 进程共用的实例 */
        fun shared(context: Context): AppearanceStore =
            instance ?: synchronized(this) { instance ?: AppearanceStore(context.applicationContext).also { instance = it } }
    }
}
