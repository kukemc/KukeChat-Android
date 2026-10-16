package ink.kuke.chat.feature.tasks

import androidx.compose.ui.graphics.Color
import ink.kuke.chat.core.model.User
import ink.kuke.chat.core.net.Api
import ink.kuke.chat.core.net.KcJson
import ink.kuke.chat.core.ui.parseInstant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** 任务接口（KukeChat-Server app/api/v1/task.py）。只声明界面用得到的字段。 */
@Serializable
data class TaskItem(
    val id: Long,
    @SerialName("conversation_id") val conversationId: Long = 0,
    @SerialName("conversation_title") val conversationTitle: String? = null,
    @SerialName("creator_id") val creatorId: Long = 0,
    val creator: User? = null,
    @SerialName("group_id") val groupId: Long? = null,
    @SerialName("group_name") val groupName: String? = null,
    @SerialName("parent_id") val parentId: Long? = null,
    val title: String = "",
    val description: String = "",
    val status: String = "pending",
    val priority: String = "normal",
    @SerialName("start_at") val startAt: String? = null,
    @SerialName("due_at") val dueAt: String? = null,
    @SerialName("remind_at") val remindAt: String? = null,
    @SerialName("completed_at") val completedAt: String? = null,
    val assignees: List<User> = emptyList(),
    val watchers: List<User> = emptyList(),
    @SerialName("subtask_total") val subtaskTotal: Int = 0,
    @SerialName("subtask_done") val subtaskDone: Int = 0,
    @SerialName("comment_count") val commentCount: Int = 0,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
    @SerialName("can_edit") val canEdit: Boolean = false,
) {
    val completed: Boolean get() = status == "completed"
    val closed: Boolean get() = status == "completed" || status == "cancelled"

    /** 任务编号显示成 t000123（和电脑版一致） */
    val label: String get() = taskLabel(id)
}

fun taskLabel(id: Long): String = "t" + id.toString().padStart(6, '0')

/** 路由里的 taskId 是字符串（形如 t000123），请求用的是数字 */
fun parseTaskId(raw: String): Long? = raw.trim().removePrefix("t").removePrefix("T").toLongOrNull()

@Serializable
data class TaskActivity(
    val id: Long,
    @SerialName("task_id") val taskId: Long = 0,
    @SerialName("task_title") val taskTitle: String? = null,
    val actor: User? = null,
    val type: String = "",
    val payload: JsonObject? = null,
    @SerialName("created_at") val createdAt: String = "",
)

@Serializable
data class TaskComment(
    val id: Long,
    @SerialName("task_id") val taskId: Long = 0,
    val author: User? = null,
    val content: String = "",
    @SerialName("created_at") val createdAt: String = "",
)

@Serializable
data class TaskDashboard(
    val total: Int = 0,
    val pending: Int = 0,
    @SerialName("in_progress") val inProgress: Int = 0,
    val completed: Int = 0,
    val overdue: Int = 0,
    @SerialName("due_today") val dueToday: Int = 0,
    @SerialName("due_soon") val dueSoon: Int = 0,
)

@Serializable
data class TaskGroupDef(val id: Long, val name: String = "")

/** 任务接口的封装：路径、参数名照电脑版 src/api/tasks.ts */
internal class TaskApi(private val api: Api) {
    private fun <T> list(element: JsonElement, serializer: kotlinx.serialization.KSerializer<T>): List<T> =
        KcJson.decodeFromJsonElement(ListSerializer(serializer), element.asItems())

    suspend fun tasks(scope: String, includeCompleted: Boolean, conversationId: Long? = null, limit: Int = 200): List<TaskItem> {
        val query = buildString {
            append("?scope=").append(scope)
            if (conversationId != null) append("&conversation_id=").append(conversationId)
            if (includeCompleted) append("&include_completed=true")
            append("&limit=").append(limit)
        }
        return list(api.get("/tasks$query"), TaskItem.serializer())
    }

    suspend fun task(id: Long): TaskItem = KcJson.decodeFromJsonElement(TaskItem.serializer(), api.get("/tasks/$id"))

    suspend fun activities(limit: Int = 80): List<TaskActivity> = list(api.get("/tasks/activities?limit=$limit"), TaskActivity.serializer())
    suspend fun taskActivities(id: Long): List<TaskActivity> = list(api.get("/tasks/$id/activities"), TaskActivity.serializer())
    suspend fun comments(id: Long): List<TaskComment> = list(api.get("/tasks/$id/comments"), TaskComment.serializer())
    suspend fun addComment(id: Long, content: String) { api.post("/tasks/$id/comments", buildJsonObject { put("content", content) }) }
    suspend fun dashboard(): TaskDashboard = KcJson.decodeFromJsonElement(TaskDashboard.serializer(), api.get("/tasks/dashboard"))
    suspend fun groups(): List<TaskGroupDef> = list(api.get("/tasks/groups"), TaskGroupDef.serializer())
    suspend fun createGroup(name: String): TaskGroupDef =
        KcJson.decodeFromJsonElement(TaskGroupDef.serializer(), api.post("/tasks/groups", buildJsonObject { put("name", name) }))

    suspend fun renameGroup(id: Long, name: String): TaskGroupDef =
        KcJson.decodeFromJsonElement(TaskGroupDef.serializer(), api.put("/tasks/groups/$id", buildJsonObject { put("name", name) }))

    /** 删除分组：组里的任务回到默认分组，任务本身不删 */
    suspend fun deleteGroup(id: Long) { api.delete("/tasks/groups/$id") }

    suspend fun complete(id: Long, completed: Boolean): TaskItem =
        KcJson.decodeFromJsonElement(TaskItem.serializer(), api.post("/tasks/$id/complete", buildJsonObject { put("completed", completed) }))

    suspend fun delete(id: Long) { api.delete("/tasks/$id") }

    suspend fun update(id: Long, body: JsonObject): TaskItem = KcJson.decodeFromJsonElement(TaskItem.serializer(), api.put("/tasks/$id", body))

    suspend fun moveToGroup(id: Long, groupId: Long?) {
        api.post("/tasks/$id/group", buildJsonObject { put("group_id", groupId?.let { JsonPrimitive(it) } ?: JsonNull) })
    }

    suspend fun create(body: JsonObject): TaskItem = KcJson.decodeFromJsonElement(TaskItem.serializer(), api.post("/tasks", body))

    /** 任务所在会话的成员：负责人 / 关注人从这里选。成员条目里的 user 才是用户 */
    suspend fun members(conversationId: Long): List<User> {
        val array = api.get("/conversations/$conversationId/members").asItems()
        return array.mapNotNull { item ->
            val user = (item as? JsonObject)?.get("user") as? JsonObject ?: return@mapNotNull null
            runCatching { KcJson.decodeFromJsonElement(User.serializer(), user) }.getOrNull()
        }.distinctBy { it.id }
    }
}

/* ───────── 优先级 / 状态 ───────── */

internal data class PriorityMeta(val value: String, val code: String, val label: String, val color: Color)

/** 设计稿固定色：P0 #EF4444 · P1 #F97316 · P2 #0A84FF · P3 #94A3B8 */
internal val Priorities = listOf(
    PriorityMeta("urgent", "P0", "紧急", Color(0xFFEF4444)),
    PriorityMeta("high", "P1", "重要", Color(0xFFF97316)),
    PriorityMeta("normal", "P2", "普通", Color(0xFF0A84FF)),
    PriorityMeta("low", "P3", "较低", Color(0xFF94A3B8)),
)

internal fun priorityOf(value: String): PriorityMeta = Priorities.firstOrNull { it.value == value } ?: Priorities[2]

internal val DoneGreen = Color(0xFF22C55E)

internal val RemindOptions: List<Pair<Int?, String>> = listOf(
    null to "不提醒",
    0 to "截止时",
    30 to "提前 30 分钟",
    60 to "提前 1 小时",
    1440 to "提前 1 天",
)

/* ───────── 时间 ───────── */

private val zone: ZoneId get() = ZoneId.systemDefault()

internal fun localDateTimeOf(iso: String?): LocalDateTime? = parseInstant(iso)?.atZone(zone)?.toLocalDateTime()

private fun pad(value: Int) = value.toString().padStart(2, '0')

/** 截止时间的短写法：今天 18:00 · 明天 · 昨天 · 10月18日（不带具体时间的记成当天 0 点） */
internal fun dueLabel(iso: String?): String {
    val time = localDateTimeOf(iso) ?: return ""
    val days = ChronoUnit.DAYS.between(LocalDate.now(zone), time.toLocalDate()).toInt()
    val day = when (days) {
        0 -> "今天"
        1 -> "明天"
        -1 -> "昨天"
        else -> if (time.year == LocalDate.now(zone).year) "${time.monthValue}月${time.dayOfMonth}日" else "${time.year}年${time.monthValue}月${time.dayOfMonth}日"
    }
    val hasTime = !(time.hour == 0 && time.minute == 0)
    return if (hasTime) "$day ${pad(time.hour)}:${pad(time.minute)}" else day
}

internal fun fullDateLabel(iso: String?): String {
    val time = localDateTimeOf(iso) ?: return ""
    val sameYear = time.year == LocalDate.now(zone).year
    val date = if (sameYear) "${time.monthValue}月${time.dayOfMonth}日" else "${time.year}年${time.monthValue}月${time.dayOfMonth}日"
    return "$date ${pad(time.hour)}:${pad(time.minute)}"
}

internal fun clockOf(iso: String?): String = localDateTimeOf(iso)?.let { "${pad(it.hour)}:${pad(it.minute)}" }.orEmpty()

internal fun isOverdue(task: TaskItem): Boolean {
    if (task.closed) return false
    val due = parseInstant(task.dueAt) ?: return false
    return due.isBefore(Instant.now())
}

/** 提醒提前了多少分钟；没设截止或提醒时是 null */
internal fun remindOffsetOf(task: TaskItem): Int? {
    val due = parseInstant(task.dueAt) ?: return null
    val remind = parseInstant(task.remindAt) ?: return null
    return ChronoUnit.MINUTES.between(remind, due).toInt()
}

internal fun remindLabel(offset: Int?): String = RemindOptions.firstOrNull { it.first == offset }?.second ?: "提前 $offset 分钟"

/* ───────── 分桶（已逾期 · 今天 · 未来 7 天 · 更晚 · 无截止时间） ───────── */

internal data class DueBucket(val key: String, val name: String, val order: Int)

private val BucketOverdue = DueBucket("overdue", "已逾期", 0)
private val BucketToday = DueBucket("today", "今天", 1)
private val BucketWeek = DueBucket("week", "未来 7 天", 2)
private val BucketLater = DueBucket("later", "更晚", 3)
private val BucketNone = DueBucket("none", "无截止时间", 4)

internal fun bucketOf(task: TaskItem): DueBucket {
    val due = localDateTimeOf(task.dueAt) ?: return BucketNone
    val days = ChronoUnit.DAYS.between(LocalDate.now(zone), due.toLocalDate()).toInt()
    return when {
        due.isBefore(LocalDateTime.now(zone)) && days < 0 -> BucketOverdue
        days <= 0 -> BucketToday
        days <= 7 -> BucketWeek
        else -> BucketLater
    }
}

/** 活动动态里一行的文案，同电脑版 activityText */
internal fun activityText(type: String, actor: String, payload: JsonObject?): String {
    val prefix = if (actor.isNotBlank()) "$actor " else ""
    val due = (payload?.get("due_at") as? JsonPrimitive)?.contentOrNull?.let { dueLabel(it) } ?: "空"
    return when (type) {
        "created" -> "${prefix}创建了任务"
        "completed" -> "${prefix}完成了任务"
        "reopened" -> "${prefix}重新开启了任务"
        "subtask_completed" -> "${prefix}完成了子任务"
        "subtask_reopened" -> "${prefix}重新开启了子任务"
        "status_changed" -> "${prefix}更新了任务状态"
        "assignee_added" -> "${prefix}添加了任务负责人"
        "assignee_removed" -> "${prefix}移除了任务负责人"
        "due_changed" -> "${prefix}将任务截止时间设置为 $due"
        "updated" -> "${prefix}修改了任务"
        else -> "${prefix}更新了任务"
    }
}

/** 详情时间线只显示这些里程碑，其余去全局「动态」 */
internal val MilestoneTypes = setOf("created", "completed", "reopened", "subtask_completed", "subtask_reopened", "assignee_added", "assignee_removed")

/** 把本地时间转成接口用的 ISO（UTC，带 Z） */
internal fun toApiTime(time: LocalDateTime): String = time.atZone(zone).toInstant().toString()

internal fun putNullable(builder: kotlinx.serialization.json.JsonObjectBuilder, key: String, value: String?) {
    if (value == null) builder.put(key, JsonNull) else builder.put(key, value)
}
