package ink.kuke.chat.feature.jury

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/*
 * 风纪三期的数据：资深合议、申诉复审、判例、成长、议事厅规则。契约见 docs/jury-v2.md 3.x。
 * 隐私：合议 / 复审里的内容同样是举报时固化、匿名化的快照。
 */

/** 合议 / 复审的席位与时限 */
@Serializable
data class SeniorRules(
    val seats: Int = 3,
    @SerialName("consensus_votes") val consensusVotes: Int = 2,
    @SerialName("timeout_hours") val timeoutHours: Int = 72,
)

/** 议事厅规则（config.hall） */
@Serializable
data class HallRules(
    @SerialName("title_max") val titleMax: Int = 60,
    @SerialName("content_max") val contentMax: Int = 5000,
    @SerialName("comment_max") val commentMax: Int = 1000,
    @SerialName("posts_per_day") val postsPerDay: Int = 0,
    @SerialName("vote_days") val voteDays: Int = 7,
    val guidelines: String = "",
    @SerialName("report_reasons") val reportReasons: List<String> = emptyList(),
)

/* ───────── 资深合议 ───────── */

@Serializable
data class PanelOrigin(
    /** no_consensus / need_context / timeout */
    val reason: String? = null,
    val tally: JsonObject? = null,
    val opinions: List<Opinion> = emptyList(),
)

@Serializable
data class PanelProgress(
    val seats: Int = 3,
    @SerialName("votes_cast") val votesCast: Int = 0,
    @SerialName("expires_at") val expiresAt: String? = null,
)

/** GET /jury/panel/next 的案件：普通案件视图 + 原审情况 + 合议进度 */
@Serializable
data class PanelCase(
    @SerialName("case_id") val caseId: Long = 0,
    val kind: String = "message",
    val reasons: List<ReasonCount> = emptyList(),
    @SerialName("report_count") val reportCount: Int = 0,
    val messages: List<CaseMessage> = emptyList(),
    val post: CasePost? = null,
    @SerialName("assigned_at") val assignedAt: String? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("suggested_category") val suggestedCategory: String? = null,
    @SerialName("case_origin") val caseOrigin: String? = null,
    val origin: PanelOrigin = PanelOrigin(),
    val panel: PanelProgress = PanelProgress(),
) {
    /** 给表决面板用的普通案件形状 */
    fun asCase() = JuryCase(
        caseId = caseId, kind = kind, reasons = reasons, reportCount = reportCount, messages = messages, post = post,
        assignedAt = assignedAt, expiresAt = expiresAt, suggestedCategory = suggestedCategory, caseOrigin = caseOrigin,
    )
}

/* ───────── 申诉复审 / 举报复议 ───────── */

@Serializable
data class ReviewPenalty(val kind: String = "none", val hours: Int? = null)

@Serializable
data class ReviewContent(val text: String? = null, @SerialName("image_count") val imageCount: Int = 0)

@Serializable
data class ReviewOrigin(val tally: JsonObject? = null, val opinions: List<Opinion> = emptyList())

@Serializable
data class ReviewVerdict(val outcome: String = "clean", val source: String? = null, @SerialName("closed_at") val closedAt: String? = null)

@Serializable
data class AppealReview(
    @SerialName("review_id") val reviewId: Long = 0,
    /** appeal 申诉复审 / reconsider 举报复议 */
    val type: String = "appeal",
    val kind: String = "message",
    /** jury / panel / machine */
    val source: String = "",
    val category: String? = null,
    @SerialName("category_label") val categoryLabel: String? = null,
    val level: Int? = null,
    val penalty: ReviewPenalty? = null,
    val messages: List<CaseMessage> = emptyList(),
    val post: CasePost? = null,
    /** 机器处罚只有这一条内容，没有上下文 */
    val content: ReviewContent? = null,
    val origin: ReviewOrigin? = null,
    @SerialName("appeal_text") val appealText: String? = null,
    @SerialName("appealed_at") val appealedAt: String? = null,
    val verdict: ReviewVerdict? = null,
    @SerialName("reconsider_text") val reconsiderText: String? = null,
    @SerialName("requested_at") val requestedAt: String? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
    val seats: Int = 3,
    @SerialName("votes_cast") val votesCast: Int = 0,
) {
    val reconsider: Boolean get() = type == "reconsider"
}

@Serializable
data class AppealVoteResult(
    @SerialName("review_id") val reviewId: Long = 0,
    val settled: Boolean = false,
    val outcome: String? = null,
    val reward: Reward? = null,
)

@Serializable
data class AppealMineItem(
    @SerialName("review_id") val reviewId: Long = 0,
    val type: String = "appeal",
    val kind: String = "message",
    val source: String = "",
    @SerialName("category_label") val categoryLabel: String? = null,
    val level: Int? = null,
    val penalty: ReviewPenalty? = null,
    @SerialName("my_vote") val myVote: String? = null,
    @SerialName("voted_at") val votedAt: String? = null,
    val outcome: String? = null,
    @SerialName("settled_at") val settledAt: String? = null,
)

@Serializable
data class AppealMinePage(val items: List<AppealMineItem> = emptyList(), val total: Int = 0)

/* ───────── 成长 ───────── */

@Serializable
data class PointItem(
    val kind: String = "",
    val label: String = "",
    val points: Int = 0,
    @SerialName("case_id") val caseId: Long? = null,
    @SerialName("created_at") val createdAt: String? = null,
)

/** total 是风纪值总数，count 是明细条数 */
@Serializable
data class PointsPage(val total: Int = 0, val items: List<PointItem> = emptyList(), val count: Int = 0)

@Serializable
data class Achievement(
    val key: String = "",
    val label: String = "",
    val description: String = "",
    @SerialName("earned_at") val earnedAt: String? = null,
    val current: Int = 0,
    val target: Int = 1,
)

@Serializable
data class HistoryItem(
    @SerialName("case_id") val caseId: Long = 0,
    val kind: String = "message",
    val vote: String? = null,
    @SerialName("voted_at") val votedAt: String? = null,
    val agreed: Boolean? = null,
    val outcome: String? = null,
)

/* ───────── 判例 ───────── */

@Serializable
data class Precedent(
    val id: Long = 0,
    /** 案件的公开编号 */
    @SerialName("case_id") val caseId: Long = 0,
    val title: String = "",
    val summary: String = "",
    val kind: String = "message",
    val outcome: String = "",
    val category: String? = null,
    @SerialName("category_label") val categoryLabel: String? = null,
    val level: Int? = null,
    val by: String = "senior",
    val mine: Boolean = false,
    @SerialName("created_at") val createdAt: String? = null,
)

@Serializable
data class PrecedentPage(val items: List<Precedent> = emptyList(), val total: Int = 0)

@Serializable
data class PrecedentCaseContent(val messages: List<CaseMessage> = emptyList(), val post: CasePost? = null)

@Serializable
data class PrecedentDetail(
    val id: Long = 0,
    @SerialName("case_id") val caseId: Long = 0,
    val title: String = "",
    val summary: String = "",
    val kind: String = "message",
    val outcome: String = "",
    @SerialName("category_label") val categoryLabel: String? = null,
    val level: Int? = null,
    val by: String = "senior",
    val mine: Boolean = false,
    @SerialName("created_at") val createdAt: String? = null,
    val content: PublicContent? = null,
    @SerialName("content_hidden_reason") val contentHiddenReason: String? = null,
    /** 在任委员才有：匿名的案件快照 */
    val case: PrecedentCaseContent? = null,
)

/* ───────── 文案 ───────── */

internal fun panelReasonLabel(reason: String?) = when (reason) {
    "no_consensus" -> "原审没有共识"
    "need_context" -> "原审认为上下文不足"
    "timeout" -> "原审超时"
    else -> "转入合议"
}

/** 复审票的名称：申诉和复议里的「维持」不是一回事 */
internal fun appealVoteLabel(vote: String?, reconsider: Boolean = false) = when (vote) {
    "uphold" -> if (reconsider) "维持原结论" else "维持原处罚"
    "reduce" -> "减轻处罚"
    "revoke" -> "撤销处罚"
    "overturn" -> "改判为违规"
    null -> "—"
    else -> vote
}

internal fun penaltySourceLabel(source: String?) = when (source) {
    "jury" -> "委员表决"
    "panel" -> "资深合议"
    "machine" -> "机器自动处罚"
    else -> source.orEmpty()
}

/** { kind: mute, hours: 24 } → 禁言 1 天 */
internal fun penaltyLabel(penalty: ReviewPenalty?): String = when {
    penalty == null -> "—"
    penalty.kind == "none" -> "仅处理内容"
    else -> penaltyText(if (penalty.hours != null) "${penalty.kind}:${penalty.hours}" else penalty.kind)
}

internal fun pointKindLabel(kind: String, fallback: String?) = fallback?.takeIf { it.isNotBlank() } ?: when (kind) {
    "vote_agreed" -> "判断与结论一致"
    "vote_disagreed" -> "参与了已定案的评审"
    "quality_correct" -> "质控题答对"
    "quality_wrong" -> "质控题答错"
    "daily_first" -> "今日首票"
    "streak_7" -> "连续参与 7 天"
    "opinion_liked" -> "观点获得认可"
    "exam_passed" -> "通过入门考试"
    "overturn_adjust" -> "案件改判后调整"
    "panel_agreed" -> "合议与结论一致"
    "panel_disagreed" -> "参与了已定案的合议"
    "review_vote" -> "参与复审"
    else -> "风纪值变动"
}

internal fun signedPoints(points: Int) = if (points > 0) "+$points" else if (points < 0) "−${-points}" else "0"
