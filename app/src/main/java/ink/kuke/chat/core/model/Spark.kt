package ink.kuke.chat.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 好友火花（契约 KukeChat-Server/docs/sparks.md 第 2 节）。会话列表、详情、spark.updated 事件里都是这一个结构，按我的视角。
 * status：none 还没有 / lit 今天已续 / pending 今天还没续 / dying 快熄灭 / out 已熄灭。
 */
@Serializable
data class Spark(
    val status: String = "none",
    val days: Int = 0,
    val level: Int = 0,
    @SerialName("level_name") val levelName: String? = null,
    /** ember / spark / flame / blaze / inferno / eternal */
    @SerialName("level_key") val levelKey: String? = null,
    @SerialName("today_lit") val todayLit: Boolean = false,
    @SerialName("i_spoke_today") val iSpokeToday: Boolean = false,
    @SerialName("friend_spoke_today") val friendSpokeToday: Boolean = false,
    val dying: Boolean = false,
    @SerialName("best_days") val bestDays: Int = 0,
    @SerialName("next_level") val nextLevel: Int? = null,
    @SerialName("next_level_name") val nextLevelName: String? = null,
    @SerialName("next_level_days") val nextLevelDays: Int? = null,
    @SerialName("days_to_next") val daysToNext: Int? = null,
    @SerialName("lit_on") val litOn: String? = null,
    @SerialName("extinguished_at") val extinguishedAt: String? = null,
    @SerialName("lost_days") val lostDays: Int = 0,
    @SerialName("lost_level_key") val lostLevelKey: String? = null,
    @SerialName("can_relight") val canRelight: Boolean = false,
    /** not_out / expired / cooldown；能重燃为 null */
    @SerialName("relight_block") val relightBlock: String? = null,
    @SerialName("relight_cost") val relightCost: Int = 100,
    @SerialName("relight_deadline") val relightDeadline: String? = null,
    @SerialName("relight_cooldown_until") val relightCooldownUntil: String? = null,
    @SerialName("relit_at") val relitAt: String? = null,
) {
    val isOut: Boolean get() = status == "out"
    val isDying: Boolean get() = status == "dying"
    /** 值得画出来：有天数，或者熄灭了还有能找回的天数 */
    val visible: Boolean get() = days > 0 || (isOut && lostDays > 0)
}

@Serializable
data class SparkLevelRule(
    val level: Int = 0,
    val name: String = "",
    val key: String = "",
    @SerialName("min_days") val minDays: Int = 0,
)

@Serializable
data class SparkRules(
    val timezone: String = "UTC+8",
    val levels: List<SparkLevelRule> = emptyList(),
    @SerialName("grace_missed_days") val graceMissedDays: Int = 1,
    @SerialName("relight_cost") val relightCost: Int = 100,
    @SerialName("relight_window_days") val relightWindowDays: Int = 3,
    @SerialName("relight_cooldown_days") val relightCooldownDays: Int = 30,
    val rules: List<String> = emptyList(),
)

@Serializable
data class SparkDetail(
    @SerialName("friend_id") val friendId: Long = 0,
    @SerialName("conversation_id") val conversationId: Long? = null,
    val spark: Spark = Spark(),
    val rules: SparkRules = SparkRules(),
)

@Serializable
data class SparkRelightResult(
    @SerialName("friend_id") val friendId: Long = 0,
    @SerialName("restored_days") val restoredDays: Int = 0,
    val spark: Spark = Spark(),
)
