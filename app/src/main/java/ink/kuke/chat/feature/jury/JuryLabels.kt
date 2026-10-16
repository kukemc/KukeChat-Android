package ink.kuke.chat.feature.jury

import androidx.compose.ui.graphics.Color
import ink.kuke.chat.core.ui.parseInstant
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 票色：设计稿固定色，不跟主题 */
internal object VoteColors {
    val None = Color(0xFF0D8F69)
    val Minor = Color(0xFFC98A00)
    val General = Color(0xFFE0383E)
    val Severe = Color(0xFF8E1B4A)
    val Neutral = Color(0xFF676D7C)

    fun of(vote: String): Color = when (vote) {
        "none" -> None
        "l1" -> Minor
        "l2" -> General
        "l3" -> Severe
        else -> Neutral
    }
}

/** 票的名称。认不出的取值（服务端先加了新选项）原样显示 */
internal fun voteLabel(vote: String, short: Boolean = false): String = when (vote) {
    "none" -> "不违规"
    "l1" -> if (short) "轻微" else "轻微违规"
    "l2" -> if (short) "一般" else "一般违规"
    "l3" -> if (short) "严重" else "严重违规"
    "abstain" -> "弃权"
    "need_context" -> if (short) "上下文不足" else "上下文不足"
    "recuse" -> if (short) "回避" else "主动回避"
    else -> vote
}

internal fun tierLabel(tier: String?): String = when (tier) {
    "trainee" -> "见习"
    "regular" -> "正式"
    "senior" -> "资深"
    else -> ""
}

internal fun kindLabel(kind: String): String = if (kind == "post") "公开动态" else "群聊消息"

internal fun stageLabel(stage: String?): String = when (stage) {
    "jury" -> "委员评审中"
    "panel" -> "资深合议中"
    "admin" -> "等待管理员"
    "closed" -> "已定案"
    else -> ""
}

internal fun sourceLabel(source: String?): String = when (source) {
    "jury" -> "委员表决"
    "panel" -> "资深合议"
    "admin" -> "管理员裁定"
    "auto" -> "系统判定"
    else -> source.orEmpty()
}

internal fun outcomeLabel(outcome: String?): String = when (outcome) {
    "violation" -> "违规"
    "clean" -> "不违规"
    else -> ""
}

internal fun levelName(level: Int?): String = when (level) {
    1 -> "轻微"
    2 -> "一般"
    3 -> "严重"
    4 -> "特别严重"
    else -> ""
}

internal fun levelText(level: Int?): String = if (level == null) "" else "L$level ${levelName(level)}".trim()

/** 7 种成员状态的标题（同电脑版「我的身份」卡） */
internal fun headline(status: JuryStatus): String = when {
    status.isJuror && status.tier != null -> "你是在任的${tierLabel(status.tier)}委员"
    else -> when (status.status) {
        "none" -> "你还不是风纪委员"
        "applied" -> "申请已提交，等待管理员审批"
        "active" -> "你是在任的风纪委员"
        "expired" -> "你的任期已满"
        "rejected" -> "上一次申请没有通过"
        "suspended" -> "你的委员资格已被暂停"
        "retired" -> "你已卸任"
        else -> "风纪委员"
    }
}

internal fun membershipLabel(status: String): String = when (status) {
    "none" -> "未加入"
    "applied" -> "申请审核中"
    "active" -> "在任"
    "expired" -> "任期已满"
    "rejected" -> "申请未通过"
    "suspended" -> "资格已暂停"
    "retired" -> "已卸任"
    else -> status
}

internal fun statusText(status: JuryStatus, termDays: Int): String = when (status.status) {
    "none" -> "风纪委员匿名评审被举报的群聊消息和公开动态，几位委员判断一致就形成结论。这是社区自己管理自己的方式，满足条件的用户都可以申请。"
    "applied" -> "管理员通过后你会成为委员，任期 $termDays 天。审批有结果时这里会自动更新，不需要一直守着。"
    "active" -> "感谢你为社区秩序出力。请只根据内容本身判断；拿不准就弃权，比随手投一票更负责。"
    "expired" -> "条件仍然满足的话，续任立即生效，不需要再次审批。"
    "rejected" -> "可以在满足条件后重新申请。"
    "suspended" -> "暂停期间不能评审，也不能重新申请；你投过的案件仍然可以在「我的评审」里回看。"
    else -> "感谢你之前的付出。想回来的话可以重新申请。"
}

/** 比率 → 87%；null 显示成一道横线 */
internal fun rateText(rate: Double?): String = if (rate == null || rate.isNaN()) "—" else "${Math.round(rate * 100)}%"

/** 用时：不到 1 小时 / 5.2 小时 / 3 天 4 小时 */
internal fun hoursText(hours: Double?): String {
    if (hours == null || hours.isNaN()) return "—"
    if (hours < 1) return "不到 1 小时"
    if (hours < 48) return if (hours % 1.0 == 0.0) "${hours.toInt()} 小时" else "%.1f 小时".format(hours)
    val days = (hours / 24).toInt()
    val rest = Math.round(hours - days * 24).toInt()
    return if (rest > 0) "$days 天 $rest 小时" else "$days 天"
}

private val DateTime = DateTimeFormatter.ofPattern("M月d日 HH:mm")

internal fun dateTimeText(iso: String?): String = parseInstant(iso)?.atZone(ZoneId.systemDefault())?.format(DateTime) ?: "—"

/** 还剩多久：3 天 / 5 小时 / 不到 1 小时；已过期返回 null */
internal fun remainingText(iso: String?): String? {
    val instant = parseInstant(iso) ?: return null
    val hours = Duration.between(Instant.now(), instant).toMinutes() / 60.0
    return when {
        hours <= 0 -> null
        hours < 1 -> "不到 1 小时"
        hours < 48 -> "${hours.toInt()} 小时"
        else -> "${(hours / 24).toInt()} 天"
    }
}

/** 额度恢复提示：「今天 14:32 恢复一个名额」 */
internal fun slotResetText(iso: String?): String? {
    val instant = parseInstant(iso) ?: return null
    val local = instant.atZone(ZoneId.systemDefault())
    val today = java.time.LocalDate.now(ZoneId.systemDefault())
    val clock = "%02d:%02d".format(local.hour, local.minute)
    val day = when (java.time.temporal.ChronoUnit.DAYS.between(today, local.toLocalDate()).toInt()) {
        0 -> "今天"
        1 -> "明天"
        else -> "${local.monthValue}月${local.dayOfMonth}日"
    }
    return "$day $clock 恢复一个名额"
}

/** 举报理由合成一句：「辱骂骚扰 ×2、垃圾广告」 */
internal fun reasonsText(reasons: List<ReasonCount>): String =
    reasons.joinToString("、") { if (it.count > 1) "${it.reason} ×${it.count}" else it.reason }.ifBlank { "—" }

/** 处置编码（mute:24 / ban:permanent）翻成人话 */
internal fun penaltyText(code: String?): String {
    if (code.isNullOrBlank() || code == "none") return "无"
    if (code == "notice") return "提醒"
    if (code == "warning") return "警告"
    val parts = code.split(':')
    val kind = when (parts[0]) { "mute" -> "禁言"; "ban" -> "封禁"; else -> code }
    val amount = parts.getOrNull(1) ?: return kind
    if (amount == "permanent") return "永久$kind"
    val hours = amount.toIntOrNull() ?: return kind
    if (hours <= 0) return kind
    return if (hours % 24 == 0) "$kind ${hours / 24} 天" else "$kind $hours 小时"
}

/** 表决规则的统一说法，所有讲「几票定案」的地方都用它 */
internal fun consensusRuleText(rules: JuryRules, hasPanel: Boolean): String =
    "全票一致（至少 ${rules.quickConsensusVotes} 票）即定案；出现分歧时收满 ${rules.seatsPerCase} 票，其中 ${rules.fullConsensusVotes} 票一致才定案，否则转交${if (hasPanel) "资深合议或管理员" else "管理员"}。"

internal fun reviewNo(caseId: Long) = "评审 #$caseId"
internal fun publicNo(caseId: Long) = "案件 #$caseId"
