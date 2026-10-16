package ink.kuke.chat.feature.level

import ink.kuke.chat.core.model.User
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * 账号等级接口的数据（契约：KukeChat-Server/docs/account-level.md）。
 * 活跃天数是带一位小数的数，用 Double；没有值的可选键在接口里是「不出现」，一律给默认。
 */

@Serializable
data class IconRule(val key: String = "", val label: String = "", val levels: Int = 1)

@Serializable
data class OnlineTier(val minutes: Int = 0, val days: Double = 0.0)

@Serializable
data class BonusRule(val key: String = "", val label: String = "", val days: Double = 0.0, val target: Double? = null)

@Serializable
data class Milestone(val level: Int = 0, val days: Double = 0.0)

/** GET /levels/rules */
@Serializable
data class LevelRules(
    @SerialName("max_level") val maxLevel: Int = 256,
    val formula: String = "",
    val icons: List<IconRule> = emptyList(),
    @SerialName("daily_cap") val dailyCap: Double = 0.0,
    val online: List<OnlineTier> = emptyList(),
    val bonuses: List<BonusRule> = emptyList(),
    val milestones: List<Milestone> = emptyList(),
)

@Serializable
data class BonusState(val key: String = "", val earned: Boolean = false, val days: Double = 0.0, val current: Double? = null, val target: Double? = null)

@Serializable
data class LevelToday(
    val date: String = "",
    @SerialName("online_minutes") val onlineMinutes: Double = 0.0,
    val base: Double = 0.0,
    val bonuses: List<BonusState> = emptyList(),
    val earned: Double = 0.0,
    val cap: Double = 0.0,
)

/** GET /levels/me */
@Serializable
data class LevelMe(
    val level: Int = 0,
    @SerialName("active_days") val activeDays: Double = 0.0,
    @SerialName("level_days") val levelDays: Double = 0.0,
    @SerialName("next_level") val nextLevel: Int? = null,
    @SerialName("next_level_days") val nextLevelDays: Double? = null,
    @SerialName("days_to_next") val daysToNext: Double? = null,
    val streak: Int = 0,
    val rank: Int? = null,
    @SerialName("registered_days") val registeredDays: Int = 0,
    val today: LevelToday? = null,
)

@Serializable
data class BoardItem(
    val rank: Int = 0,
    val user: User? = null,
    val level: Int = 0,
    @SerialName("active_days") val activeDays: Double = 0.0,
    val gained: Double = 0.0,
)

/** GET /levels/leaderboard */
@Serializable
data class LevelBoard(
    val period: String = "all",
    val items: List<BoardItem> = emptyList(),
    val total: Int = 0,
    val me: BoardItem? = null,
)

@Serializable
data class HistoryDay(
    val date: String = "",
    @SerialName("online_minutes") val onlineMinutes: Double = 0.0,
    val base: Double = 0.0,
    val bonus: Double = 0.0,
    val earned: Double = 0.0,
)

// ------------------------------------------------------------------ 格式

/** 活跃天数：最多一位小数，整数不带小数点 */
fun formatDays(value: Double): String {
    val rounded = Math.round(value * 10) / 10.0
    return if (rounded % 1.0 == 0.0) "%,d".format(rounded.toLong()) else "%,.1f".format(rounded)
}

/** 一天之内的活跃天：固定一位小数（0.5、1.0） */
fun formatDayPart(value: Double): String = "%.1f".format(Math.round(value * 10) / 10.0)

fun formatMinutes(minutes: Double): String {
    val whole = Math.round(minutes).toInt().coerceAtLeast(0)
    if (whole < 60) return "$whole 分钟"
    val hours = whole / 60
    val rest = whole % 60
    return if (rest == 0) "$hours 小时" else "$hours 小时 $rest 分"
}

fun formatRank(rank: Int?): String = if (rank != null && rank > 0) "第 %,d 名".format(rank) else "—"

/** 「2025-10-12」→「10/12」 */
fun formatDayLabel(date: String): String {
    val m = Regex("^(\\d{4})-(\\d{2})-(\\d{2})").find(date) ?: return date
    return "${m.groupValues[2].toInt()}/${m.groupValues[3].toInt()}"
}
