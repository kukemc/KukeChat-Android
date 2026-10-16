package ink.kuke.chat.feature.jury

import ink.kuke.chat.core.model.User
import ink.kuke.chat.core.net.Api
import ink.kuke.chat.core.net.ApiException
import ink.kuke.chat.core.net.KcJson
import ink.kuke.chat.feature.tasks.asItems
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/*
 * 风纪接口的数据。契约在 KukeChat-Server docs/jury-v2.md 与 docs/moderation-v2.md。
 * 隐私：这里的案件内容都是服务端匿名化后的快照（发言人只有「被举报人」「成员A」这类代称），
 * 客户端不提供任何别的消息浏览入口。
 */

@Serializable
data class JuryStats(
    @SerialName("votes_total") val votesTotal: Int = 0,
    @SerialName("votes_settled") val votesSettled: Int = 0,
    @SerialName("votes_agreed") val votesAgreed: Int = 0,
    @SerialName("agreement_rate") val agreementRate: Double? = null,
    @SerialName("golden_total") val goldenTotal: Int = 0,
    @SerialName("golden_correct") val goldenCorrect: Int = 0,
)

@Serializable
data class JuryRequirement(val key: String = "", val ok: Boolean = false, val text: String = "", val detail: String? = null)

@Serializable
data class JuryRestriction(val reason: String = "", val until: String? = null, val message: String = "")

@Serializable
data class JuryWarning(val code: String = "", val message: String = "")

@Serializable
data class JuryExamSummary(
    val available: Boolean = false,
    val passed: Boolean = false,
    @SerialName("attempts_used") val attemptsUsed: Int = 0,
    @SerialName("attempts_max") val attemptsMax: Int = 0,
    @SerialName("next_attempt_at") val nextAttemptAt: String? = null,
    @SerialName("last_score") val lastScore: Int? = null,
    @SerialName("pass_score") val passScore: Int = 0,
    val total: Int = 0,
)

@Serializable
data class TierRequirement(val key: String = "", val label: String = "", val current: Double? = null, val target: Double = 0.0, val ok: Boolean = false, val note: String? = null)

@Serializable
data class TierProgress(@SerialName("next_tier") val nextTier: String? = null, val requirements: List<TierRequirement> = emptyList())

@Serializable
data class SeniorQueue(val panel: Int = 0, val appeals: Int = 0)

/** GET /jury/me */
@Serializable
data class JuryStatus(
    val status: String = "none",
    val eligible: Boolean = false,
    val requirements: List<JuryRequirement> = emptyList(),
    @SerialName("term_ends_at") val termEndsAt: String? = null,
    @SerialName("review_note") val reviewNote: String? = null,
    val stats: JuryStats? = null,
    @SerialName("daily_limit") val dailyLimit: Int = 0,
    @SerialName("voted_today") val votedToday: Int = 0,
    @SerialName("daily_resets_at") val dailyResetsAt: String? = null,
    @SerialName("recuse_resets_at") val recuseResetsAt: String? = null,
    val tier: String? = null,
    @SerialName("available_cases") val availableCases: Int? = null,
    val restriction: JuryRestriction? = null,
    val senior: SeniorQueue? = null,
    val exam: JuryExamSummary? = null,
    @SerialName("exam_required") val examRequired: Boolean = false,
    val points: Int = 0,
    @SerialName("tier_progress") val tierProgress: TierProgress? = null,
    @SerialName("badge_visible") val badgeVisible: Boolean = false,
    val warnings: List<JuryWarning> = emptyList(),
    @SerialName("cooldown_until") val cooldownUntil: String? = null,
    @SerialName("recuse_left_today") val recuseLeftToday: Int? = null,
) {
    val isJuror: Boolean get() = status == "active"
    val everJuror: Boolean get() = status in setOf("active", "expired", "suspended", "retired") || (stats?.votesTotal ?: 0) > 0
    val canApply: Boolean get() = status in setOf("none", "rejected", "expired", "retired")
    val isSenior: Boolean get() = isJuror && tier == "senior"
}

@Serializable
data class VoteOption(val value: String = "", val kind: String = "", val label: String = "", val hint: String? = null, val level: Int? = null)

@Serializable
data class JuryRules(
    @SerialName("seats_per_case") val seatsPerCase: Int = 5,
    @SerialName("quick_consensus_votes") val quickConsensusVotes: Int = 3,
    @SerialName("full_consensus_votes") val fullConsensusVotes: Int = 4,
    @SerialName("timeout_hours") val timeoutHours: Int = 72,
    @SerialName("assignment_ttl_hours") val assignmentTtlHours: Int = 24,
    @SerialName("daily_votes") val dailyVotes: Int = 30,
    @SerialName("term_days") val termDays: Int = 30,
    @SerialName("context_near") val contextNear: Int = 3,
    @SerialName("content_retention_days") val contentRetentionDays: Int = 30,
    @SerialName("publish_delay_hours") val publishDelayHours: Int = 72,
    @SerialName("max_ban_hours") val maxBanHours: Int? = null,
)

@Serializable
data class OpinionRules(
    @SerialName("max_length") val maxLength: Int = 500,
    @SerialName("report_reasons") val reportReasons: List<String> = emptyList(),
)

@Serializable
data class JuryCategory(val key: String = "", val label: String = "", @SerialName("default_level") val defaultLevel: Int = 0)

@Serializable
data class DailyLimit(@SerialName("daily_limit") val dailyLimit: Int = 0)

/** GET /jury/config */
@Serializable
data class JuryConfig(
    val capabilities: List<String> = emptyList(),
    val votes: List<VoteOption> = emptyList(),
    val rules: JuryRules = JuryRules(),
    val opinions: OpinionRules = OpinionRules(),
    val categories: List<JuryCategory> = emptyList(),
    val recuse: DailyLimit? = null,
    val practice: DailyLimit? = null,
    /** 管理员开关：appeals 等；未下发视为关 */
    val switches: Map<String, Boolean> = emptyMap(),
    val panel: SeniorRules? = null,
    val appeals: SeniorRules? = null,
    val hall: HallRules? = null,
) {
    fun has(capability: String) = capability in capabilities
}

@Serializable
data class JurorCounts(val total: Int = 0, val trainee: Int = 0, val regular: Int = 0, val senior: Int = 0)

@Serializable
data class Backlog(val jury: Int = 0, val panel: Int = 0)

@Serializable
data class Settled(val d7: Int = 0, val d30: Int = 0, val total: Int = 0)

@Serializable
data class JuryOverview(
    val jurors: JurorCounts = JurorCounts(),
    val backlog: Backlog = Backlog(),
    val settled: Settled = Settled(),
    @SerialName("window_days") val windowDays: Int = 30,
    val sample: Int = 0,
    @SerialName("sample_enough") val sampleEnough: Boolean = false,
    @SerialName("median_hours") val medianHours: Double? = null,
    @SerialName("consensus_rate") val consensusRate: Double? = null,
    @SerialName("violation_rate") val violationRate: Double? = null,
    @SerialName("overturn_rate") val overturnRate: Double? = null,
)

@Serializable
data class ReasonCount(val reason: String = "", val count: Int = 0)

@Serializable
data class CaseMessage(
    val speaker: String = "",
    @SerialName("is_target") val isTarget: Boolean = false,
    val type: String = "text",
    val content: String? = null,
    val images: List<String> = emptyList(),
    @SerialName("created_at") val createdAt: String = "",
    val offset: Int? = null,
)

@Serializable
data class CasePost(
    val content: String? = null,
    @SerialName("image_urls") val imageUrls: List<String> = emptyList(),
    @SerialName("created_at") val createdAt: String = "",
)

@Serializable
data class MyVote(
    val vote: String = "",
    val note: String? = null,
    @SerialName("voted_at") val votedAt: String = "",
    val alias: String? = null,
    val agreed: Boolean? = null,
    val points: Int? = null,
    val category: String? = null,
)

@Serializable
data class Verdict(
    val outcome: String = "",
    val source: String = "",
    val category: String? = null,
    @SerialName("category_label") val categoryLabel: String? = null,
    val level: Int? = null,
    @SerialName("closed_at") val closedAt: String? = null,
    val hours: Double? = null,
    val overturned: Boolean = false,
    @SerialName("overturn_note") val overturnNote: String? = null,
    val explanation: String? = null,
)

@Serializable
data class RewardItem(val kind: String = "", val points: Int = 0, val label: String = "")

@Serializable
data class Reward(val points: Int = 0, val total: Int = 0, val items: List<RewardItem> = emptyList())

/** 评审台发来的案件，和投后视图（GET /jury/cases/{id}）共用一个类，没有的字段是默认值 */
@Serializable
data class JuryCase(
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
    // —— 投后视图 ——
    @SerialName("content_available") val contentAvailable: Boolean = true,
    @SerialName("my_vote") val myVote: MyVote? = null,
    val stage: String? = null,
    @SerialName("votes_cast") val votesCast: Int = 0,
    val tally: JsonObject? = null,
    val verdict: Verdict? = null,
    @SerialName("is_quality_check") val isQualityCheck: Boolean = false,
    @SerialName("opinions_enabled") val opinionsEnabled: Boolean = true,
    @SerialName("opinion_count") val opinionCount: Int = 0,
    @SerialName("public_case_id") val publicCaseId: Long? = null,
    /** 三期：已是判例时指向它 */
    val precedent: CasePrecedentRef? = null,
    @SerialName("can_mark_precedent") val canMarkPrecedent: Boolean = false,
)

@Serializable
data class CasePrecedentRef(val id: Long = 0, val title: String = "", val mine: Boolean = false)

@Serializable
data class VoteResult(
    @SerialName("case_id") val caseId: Long = 0,
    val settled: Boolean = false,
    @SerialName("case") val detail: JuryCase? = null,
    val reward: Reward? = null,
)

@Serializable
data class VoteItem(
    @SerialName("case_id") val caseId: Long = 0,
    val kind: String = "message",
    @SerialName("primary_reason") val primaryReason: String? = null,
    val vote: String = "",
    @SerialName("voted_at") val votedAt: String = "",
    val alias: String? = null,
    val stage: String? = null,
    val outcome: String? = null,
    @SerialName("verdict_source") val verdictSource: String? = null,
    @SerialName("verdict_level") val verdictLevel: Int? = null,
    @SerialName("closed_at") val closedAt: String? = null,
    val agreed: Boolean? = null,
    val overturned: Boolean = false,
    @SerialName("is_quality_check") val isQualityCheck: Boolean = false,
    @SerialName("opinion_count") val opinionCount: Int = 0,
    @SerialName("can_view") val canView: Boolean = true,
)

@Serializable
data class VotesPage(val items: List<VoteItem> = emptyList(), val total: Int = 0)

@Serializable
data class MyStats(
    @SerialName("votes_total") val votesTotal: Int = 0,
    @SerialName("votes_settled") val votesSettled: Int = 0,
    @SerialName("votes_agreed") val votesAgreed: Int = 0,
    @SerialName("agreement_rate") val agreementRate: Double? = null,
    @SerialName("golden_total") val goldenTotal: Int = 0,
    @SerialName("golden_correct") val goldenCorrect: Int = 0,
    @SerialName("golden_rate") val goldenRate: Double? = null,
    @SerialName("abstain_total") val abstainTotal: Int = 0,
    @SerialName("abstain_rate") val abstainRate: Double? = null,
    @SerialName("opinions_total") val opinionsTotal: Int = 0,
    @SerialName("likes_received") val likesReceived: Int = 0,
)

@Serializable
data class Opinion(
    val id: Long = 0,
    @SerialName("parent_id") val parentId: Long? = null,
    val alias: String = "",
    val tier: String? = null,
    val vote: String? = null,
    val content: String? = null,
    val state: String = "visible",
    @SerialName("is_note") val isNote: Boolean = false,
    val mine: Boolean = false,
    @SerialName("like_count") val likeCount: Int = 0,
    val liked: Boolean = false,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("reply_to_alias") val replyToAlias: String? = null,
    val replies: List<Opinion> = emptyList(),
)

@Serializable
data class PublicCase(
    @SerialName("case_id") val caseId: Long = 0,
    val kind: String = "message",
    val reasons: List<ReasonCount> = emptyList(),
    @SerialName("report_count") val reportCount: Int = 0,
    val outcome: String = "",
    val category: String? = null,
    @SerialName("category_label") val categoryLabel: String? = null,
    val level: Int? = null,
    val source: String = "",
    val tally: JsonObject? = null,
    val votes: Int = 0,
    @SerialName("closed_at") val closedAt: String? = null,
    val hours: Double? = null,
    val overturned: Boolean = false,
    @SerialName("has_content") val hasContent: Boolean = false,
    val content: PublicContent? = null,
    @SerialName("content_hidden_reason") val contentHiddenReason: String? = null,
    @SerialName("overturn_note") val overturnNote: String? = null,
    @SerialName("case_origin") val caseOrigin: String? = null,
    /** 是判例时指向它 */
    @SerialName("precedent_id") val precedentId: Long? = null,
)

@Serializable
data class PublicContent(val text: String? = null, @SerialName("image_count") val imageCount: Int = 0, @SerialName("created_at") val createdAt: String? = null)

@Serializable
data class PublicPage(val items: List<PublicCase> = emptyList(), val total: Int = 0)

@Serializable
data class LeaderboardItem(
    val rank: Int = 0,
    val points: Int = 0,
    val tier: String? = null,
    val votes: Int? = null,
    val me: Boolean = false,
    val user: User? = null,
)

@Serializable
data class Leaderboard(val period: String = "week", val items: List<LeaderboardItem> = emptyList(), val me: LeaderboardItem? = null)

@Serializable
data class StandardAnswer(
    val outcome: String = "",
    val level: Int? = null,
    @SerialName("category_label") val categoryLabel: String? = null,
    val explanation: String? = null,
)

@Serializable
data class PracticeQuestion(
    @SerialName("question_id") val questionId: Long = 0,
    val kind: String = "message",
    val reasons: List<ReasonCount> = emptyList(),
    val messages: List<CaseMessage> = emptyList(),
    val post: CasePost? = null,
    val verified: Boolean = false,
    @SerialName("answered_before") val answeredBefore: Boolean = false,
)

@Serializable
data class PracticeStats(val answered: Int = 0, val correct: Int = 0)

@Serializable
data class PracticeRound(val total: Int = 0, val done: Int = 0)

@Serializable
data class PracticeNext(
    val question: PracticeQuestion? = null,
    @SerialName("left_today") val leftToday: Int = 0,
    @SerialName("daily_limit") val dailyLimit: Int = 0,
    @SerialName("resets_at") val resetsAt: String? = null,
    val stats: PracticeStats? = null,
    val round: PracticeRound? = null,
)

@Serializable
data class PracticeAnswer(
    val correct: Boolean = false,
    val answer: StandardAnswer = StandardAnswer(),
    @SerialName("left_today") val leftToday: Int = 0,
    val stats: PracticeStats? = null,
)

@Serializable
data class ExamQuestion(
    val question: Int = 0,
    val kind: String = "message",
    val reasons: List<ReasonCount> = emptyList(),
    val messages: List<CaseMessage> = emptyList(),
    val post: CasePost? = null,
)

@Serializable
data class ExamAttempt(
    @SerialName("attempt_id") val attemptId: Long = 0,
    @SerialName("expires_at") val expiresAt: String? = null,
    val questions: List<ExamQuestion> = emptyList(),
)

@Serializable
data class ExamLastResult(val score: Int = 0, val passed: Boolean = false)

@Serializable
data class ExamState(
    val available: Boolean = false,
    val eligible: Boolean = false,
    val passed: Boolean = false,
    val total: Int = 0,
    @SerialName("pass_score") val passScore: Int = 0,
    @SerialName("time_limit_minutes") val timeLimitMinutes: Int = 0,
    @SerialName("attempts_used") val attemptsUsed: Int = 0,
    @SerialName("attempts_max") val attemptsMax: Int = 0,
    @SerialName("next_attempt_at") val nextAttemptAt: String? = null,
    @SerialName("last_result") val lastResult: ExamLastResult? = null,
    val active: ExamAttempt? = null,
)

@Serializable
data class ExamQuestionResult(
    val question: Int = 0,
    val correct: Boolean = false,
    @SerialName("your_vote") val yourVote: String? = null,
    val answer: StandardAnswer = StandardAnswer(),
)

@Serializable
data class ExamResult(
    val score: Int = 0,
    val total: Int = 0,
    @SerialName("pass_score") val passScore: Int = 0,
    val passed: Boolean = false,
    val results: List<ExamQuestionResult> = emptyList(),
    @SerialName("attempts_left") val attemptsLeft: Int = 0,
    @SerialName("next_attempt_at") val nextAttemptAt: String? = null,
)

@Serializable
data class VoteState(
    val yes: Int = 0,
    val no: Int = 0,
    @SerialName("ends_at") val endsAt: String? = null,
    @SerialName("my_vote") val myVote: String? = null,
    @SerialName("can_vote") val canVote: Boolean = false,
    val quorum: Int = 0,
)

@Serializable
data class HallPost(
    val id: Long = 0,
    val kind: String = "discussion",
    val title: String = "",
    val content: String? = null,
    val status: String = "open",
    val pinned: Boolean = false,
    val author: User? = null,
    @SerialName("author_tier") val authorTier: String? = null,
    val vote: VoteState? = null,
    @SerialName("admin_note") val adminNote: String? = null,
    @SerialName("comment_count") val commentCount: Int = 0,
    @SerialName("can_comment") val canComment: Boolean = false,
    @SerialName("created_at") val createdAt: String = "",
    val state: String = "visible",
    @SerialName("content_truncated") val contentTruncated: Boolean = false,
    val mine: Boolean = false,
    @SerialName("can_edit") val canEdit: Boolean = false,
    @SerialName("can_delete") val canDelete: Boolean = false,
    val reported: Boolean = false,
)

@Serializable
data class HallComment(
    val id: Long = 0,
    val content: String? = null,
    val author: User? = null,
    @SerialName("author_tier") val authorTier: String? = null,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("parent_id") val parentId: Long? = null,
    val state: String = "visible",
    val mine: Boolean = false,
    val reported: Boolean = false,
    @SerialName("reply_to") val replyTo: User? = null,
)

@Serializable
data class ModerationRules(
    @SerialName("point_window_days") val pointWindowDays: Int = 30,
    val categories: List<RuleCategory> = emptyList(),
    val levels: List<RuleLevel> = emptyList(),
    val ladder: List<RuleStep> = emptyList(),
)

@Serializable
data class RuleCategory(val key: String = "", val label: String = "", val level: Int = 0)

@Serializable
data class RuleLevel(val level: Int = 0, val points: Int = 0, val penalty: String = "")

@Serializable
data class RuleStep(val threshold: Int = 0, val penalty: String = "")

/** 风纪接口封装。路径照电脑版 src/api/jury*.ts */
internal class JuryApi(private val api: Api) {
    private fun <T> list(element: JsonElement, serializer: KSerializer<T>): List<T> =
        KcJson.decodeFromJsonElement(ListSerializer(serializer), element.asItems())

    private fun <T> obj(element: JsonElement, serializer: KSerializer<T>): T = KcJson.decodeFromJsonElement(serializer, element)

    suspend fun me() = obj(api.get("/jury/me"), JuryStatus.serializer())
    suspend fun config() = obj(api.get("/jury/config"), JuryConfig.serializer())
    suspend fun overview() = obj(api.get("/jury/overview"), JuryOverview.serializer())
    suspend fun apply(statement: String?) = obj(
        api.post("/jury/apply", buildJsonObject { if (!statement.isNullOrBlank()) put("statement", statement) }),
        JuryStatus.serializer(),
    )

    /** 下一件待审案件；没有时 null。案件里没有任何当事人身份 */
    suspend fun nextCase(): JuryCase? {
        val result = api.get("/jury/cases/next") as? JsonObject ?: return null
        val case = result["case"] ?: return null
        if (case !is JsonObject) return null
        return obj(case, JuryCase.serializer())
    }

    suspend fun vote(caseId: Long, vote: String, note: String?, category: String?): VoteResult = obj(
        api.post("/jury/cases/$caseId/vote", buildJsonObject {
            put("vote", vote)
            if (!note.isNullOrBlank()) put("note", note)
            if (!category.isNullOrBlank()) put("category", category)
        }),
        VoteResult.serializer(),
    )

    suspend fun caseDetail(caseId: Long) = obj(api.get("/jury/cases/$caseId"), JuryCase.serializer())
    suspend fun opinions(caseId: Long) = list(api.get("/jury/cases/$caseId/opinions"), Opinion.serializer())
    suspend fun postOpinion(caseId: Long, content: String, parentId: Long?) {
        api.post("/jury/cases/$caseId/opinions", buildJsonObject {
            put("content", content)
            if (parentId != null) put("parent_id", parentId)
        })
    }
    suspend fun likeOpinion(id: Long, liked: Boolean) { if (liked) api.post("/jury/opinions/$id/like") else api.delete("/jury/opinions/$id/like") }
    suspend fun deleteOpinion(id: Long) { api.delete("/jury/opinions/$id") }
    suspend fun reportOpinion(id: Long, reason: String, details: String?) {
        api.post("/jury/opinions/$id/report", buildJsonObject {
            put("reason", reason)
            if (!details.isNullOrBlank()) put("details", details)
        })
    }

    suspend fun votes(offset: Int, limit: Int = 20, state: String? = null): VotesPage {
        val query = buildString {
            append("?limit=$limit&offset=$offset")
            if (state != null) append("&state=$state")
        }
        return obj(api.get("/jury/votes$query"), VotesPage.serializer())
    }

    suspend fun myStats() = obj(api.get("/jury/me/stats"), MyStats.serializer())
    suspend fun publicCases(offset: Int, limit: Int = 20, kind: String? = null, outcome: String? = null): PublicPage {
        val query = buildString {
            append("?limit=$limit&offset=$offset")
            if (kind != null) append("&kind=$kind")
            if (outcome != null) append("&outcome=$outcome")
        }
        return obj(api.get("/jury/public/cases$query"), PublicPage.serializer())
    }
    suspend fun publicCase(id: Long) = obj(api.get("/jury/public/cases/$id"), PublicCase.serializer())
    suspend fun leaderboard(period: String) = obj(api.get("/jury/leaderboard?period=$period&limit=50"), Leaderboard.serializer())
    suspend fun practiceNext() = obj(api.get("/jury/practice/next"), PracticeNext.serializer())
    suspend fun practiceAnswer(questionId: Long, vote: String) =
        obj(api.post("/jury/practice/$questionId/answer", buildJsonObject { put("vote", vote) }), PracticeAnswer.serializer())
    suspend fun exam() = obj(api.get("/jury/exam"), ExamState.serializer())
    suspend fun examStart() = obj(api.post("/jury/exam/start", buildJsonObject { }), ExamAttempt.serializer())
    suspend fun examSubmit(attemptId: Long, answers: Map<Int, String>): ExamResult = obj(
        api.post("/jury/exam/$attemptId/submit", buildJsonObject {
            put("answers", kotlinx.serialization.json.JsonArray(answers.map { (q, v) ->
                buildJsonObject { put("question", q); put("vote", v) }
            }))
        }),
        ExamResult.serializer(),
    )
    suspend fun rules() = obj(api.get("/moderation/rules"), ModerationRules.serializer())
    suspend fun hallPosts(offset: Int, limit: Int = 20) = list(api.get("/jury/hall/posts?limit=$limit&offset=$offset"), HallPost.serializer())
    suspend fun hallPost(id: Long) = obj(api.get("/jury/hall/posts/$id"), HallPost.serializer())
    suspend fun hallComments(id: Long) = list(api.get("/jury/hall/posts/$id/comments?limit=50&offset=0"), HallComment.serializer())
    suspend fun hallVote(id: Long, vote: String) = obj(api.post("/jury/hall/posts/$id/vote", buildJsonObject { put("vote", vote) }), HallPost.serializer())
    suspend fun hallComment(id: Long, content: String) { api.post("/jury/hall/posts/$id/comments", buildJsonObject { put("content", content) }) }
    suspend fun setBadge(visible: Boolean) = obj(api.put("/jury/me/settings", buildJsonObject { put("badge_visible", visible) }), JuryStatus.serializer())

    // —— 议事厅：发帖 / 改帖 / 删帖、回复、删评论、举报 ——
    suspend fun hallCreate(kind: String, title: String, content: String) = obj(
        api.post("/jury/hall/posts", buildJsonObject { put("kind", kind); put("title", title); put("content", content) }), HallPost.serializer(),
    )
    suspend fun hallUpdate(id: Long, title: String, content: String) = obj(
        api.put("/jury/hall/posts/$id", buildJsonObject { put("title", title); put("content", content) }), HallPost.serializer(),
    )
    suspend fun hallDelete(id: Long) { api.delete("/jury/hall/posts/$id") }
    suspend fun hallReply(id: Long, content: String, parentId: Long?) {
        api.post("/jury/hall/posts/$id/comments", buildJsonObject { put("content", content); if (parentId != null) put("parent_id", parentId) })
    }
    suspend fun hallDeleteComment(commentId: Long) { api.delete("/jury/hall/comments/$commentId") }
    suspend fun hallReport(targetType: String, targetId: Long, reason: String, details: String?) {
        api.post("/jury/hall/reports", buildJsonObject {
            put("target_type", targetType)
            put("target_id", targetId)
            put("reason", reason)
            if (!details.isNullOrBlank()) put("details", details)
        })
    }

    // —— 资深工作台：合议、申诉复审 ——
    /** 下一件合议案件；没有时 null */
    suspend fun panelNext(): PanelCase? {
        val case = (api.get("/jury/panel/next") as? JsonObject)?.get("case") as? JsonObject ?: return null
        return obj(case, PanelCase.serializer())
    }
    suspend fun panelVote(caseId: Long, vote: String, note: String?, category: String?): VoteResult = obj(
        api.post("/jury/panel/$caseId/vote", buildJsonObject {
            put("vote", vote)
            if (!note.isNullOrBlank()) put("note", note)
            if (!category.isNullOrBlank()) put("category", category)
        }),
        VoteResult.serializer(),
    )
    /** 下一件复审；没有时 null */
    suspend fun appealNext(): AppealReview? {
        val review = (api.get("/jury/appeals/next") as? JsonObject)?.get("review") as? JsonObject ?: return null
        return obj(review, AppealReview.serializer())
    }
    suspend fun appealVote(reviewId: Long, vote: String, note: String?, level: Int?, category: String?): AppealVoteResult = obj(
        api.post("/jury/appeals/$reviewId/vote", buildJsonObject {
            put("vote", vote)
            if (!note.isNullOrBlank()) put("note", note)
            if (level != null) put("level", level)
            if (!category.isNullOrBlank()) put("category", category)
        }),
        AppealVoteResult.serializer(),
    )
    suspend fun appealsMine(offset: Int, limit: Int = 20) = obj(api.get("/jury/appeals/mine?limit=$limit&offset=$offset"), AppealMinePage.serializer())

    // —— 成长：风纪值明细、成就、评审足迹 ——
    suspend fun points(offset: Int, limit: Int = 20) = obj(api.get("/jury/me/points?limit=$limit&offset=$offset"), PointsPage.serializer())
    suspend fun achievements() = list(api.get("/jury/me/achievements"), Achievement.serializer())
    suspend fun history() = list(api.get("/jury/history"), HistoryItem.serializer())

    // —— 判例 ——
    suspend fun precedents(offset: Int, category: String?, limit: Int = 20): PrecedentPage {
        val query = buildString {
            append("?limit=$limit&offset=$offset")
            if (!category.isNullOrBlank()) append("&category=$category")
        }
        return obj(api.get("/jury/precedents$query"), PrecedentPage.serializer())
    }
    suspend fun precedent(id: Long) = obj(api.get("/jury/precedents/$id"), PrecedentDetail.serializer())
    /** caseId 是评审编号（我投过票的那个），不是公开编号 */
    suspend fun createPrecedent(caseId: Long, title: String, summary: String) = obj(
        api.post("/jury/precedents", buildJsonObject { put("case_id", caseId); put("title", title); put("summary", summary) }), Precedent.serializer(),
    )
    suspend fun updatePrecedent(id: Long, title: String, summary: String) = obj(
        api.put("/jury/precedents/$id", buildJsonObject { put("title", title); put("summary", summary) }), Precedent.serializer(),
    )
    suspend fun withdrawPrecedent(id: Long) { api.delete("/jury/precedents/$id") }
}

/** 错误码：服务端 detail.code，没有则 null。风纪里很多状态（受限、额度用完、冷却）要靠它区分 */
internal fun Throwable.apiCode(): String? = (this as? ApiException)?.code

/** 错误里的附加字段，比如 resets_at / until */
internal fun Throwable.apiField(name: String): String? =
    ((this as? ApiException)?.detail as? JsonObject)?.get(name)?.let { (it as? JsonPrimitive)?.contentOrNull }

/** 票数分布里取某一项 */
internal fun JsonObject?.count(key: String): Int = (this?.get(key) as? JsonPrimitive)?.intOrNull ?: 0

