package ink.kuke.chat.feature.teamup

import ink.kuke.chat.core.data.slot

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ink.kuke.chat.core.AppGraph
import ink.kuke.chat.core.net.Api
import ink.kuke.chat.core.net.KcJson
import ink.kuke.chat.core.net.userMessage
import ink.kuke.chat.core.ui.parseInstant
import ink.kuke.chat.feature.feed.decodeItems
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import java.time.Instant

private const val PAGE = 40
private const val DISCOVERY = 12

/** 接口：KukeChat-Client/src/api/teamup.ts */
internal class TeamupApi(private val api: Api) {
    suspend fun event(): TeamupEvent? = (api.get("/teamup/event/current") as? JsonObject)?.let { runCatching { KcJson.decodeFromJsonElement<TeamupEvent>(it) }.getOrNull() }
    suspend fun skills(): List<SkillStat> = api.get("/teamup/skills").decodeItems()
    suspend fun stats(): TeamupStats? = (api.get("/teamup/stats") as? JsonObject)?.let { runCatching { KcJson.decodeFromJsonElement<TeamupStats>(it) }.getOrNull() }

    suspend fun profiles(scope: String = "square", skill: String? = null, sort: String = "hot", beforeId: Long? = null, limit: Int = PAGE): List<TeamupProfile> {
        val query = buildString {
            append("?scope=").append(scope)
            if (skill != null) append("&skill=").append(skill)
            append("&sort=").append(sort)
            if (beforeId != null) append("&before_id=").append(beforeId)
            append("&limit=").append(limit)
        }
        return api.get("/teamup/profiles$query").decodeItems()
    }

    suspend fun mine(): TeamupProfile? = (api.get("/teamup/profiles/me") as? JsonObject)?.let { runCatching { KcJson.decodeFromJsonElement<TeamupProfile>(it) }.getOrNull() }
    suspend fun profile(id: Long): TeamupProfile = KcJson.decodeFromJsonElement(api.get("/teamup/profiles/$id"))
    suspend fun close(): TeamupProfile = KcJson.decodeFromJsonElement(api.post("/teamup/profiles/me/close"))
    suspend fun reopen(): TeamupProfile = KcJson.decodeFromJsonElement(api.post("/teamup/profiles/me/reopen"))
    suspend fun delete() { api.delete("/teamup/profiles/me") }
    suspend fun comments(profileId: Long): List<TeamupComment> = api.get("/teamup/profiles/$profileId/comments").decodeItems()
}

/** 组队中心的状态（赛季横幅、技能统计、推荐、名片流、我的名片） */
class TeamupViewModel(private val graph: AppGraph) : ViewModel() {
    private val api = TeamupApi(graph.api)

    var event by mutableStateOf<TeamupEvent?>(null)
        private set
    var stats by mutableStateOf<TeamupStats?>(null)
        private set
    val skillCounts = mutableStateListOf<SkillStat>()
    val profiles = mutableStateListOf<TeamupProfile>()
    val pool = mutableStateListOf<TeamupProfile>()
    var mine by mutableStateOf<TeamupProfile?>(null)
        private set
    var mineLoaded by mutableStateOf(false)
        private set

    var skill by mutableStateOf<String?>(null)
        private set
    var sort by mutableStateOf("hot")
        private set
    var seed by mutableStateOf((Math.random() * 100000).toInt())
        private set

    var loading by mutableStateOf(true)
        private set
    var loadingMore by mutableStateOf(false)
        private set
    var refreshing by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var notice by mutableStateOf<String?>(null)
    var hasMore = false
        private set
    var acting by mutableStateOf(false)
        private set

    private var debounce: Job? = null

    /** 上次看到的组队大厅（默认筛选下），只在内存：再进来先摆出来，再静默刷新 */
    private class Snapshot(
        val event: TeamupEvent?, val stats: TeamupStats?, val skills: List<SkillStat>,
        val profiles: List<TeamupProfile>, val hasMore: Boolean, val pool: List<TeamupProfile>,
        val mine: TeamupProfile?, val mineLoaded: Boolean,
    )
    private val cache = graph.screenCache.slot<Snapshot>("teamup")

    private fun saveSnapshot() {
        val previous = cache.get()
        val defaultFilter = skill == null && sort == "hot"
        cache.put(Snapshot(
            event, stats, skillCounts.toList(),
            if (defaultFilter) profiles.toList() else previous?.profiles.orEmpty(),
            if (defaultFilter) hasMore else previous?.hasMore ?: false,
            pool.toList(), mine, mineLoaded,
        ))
    }

    init {
        val cached = cache.get()
        if (cached != null) {
            event = cached.event; stats = cached.stats
            skillCounts.addAll(cached.skills); profiles.addAll(cached.profiles); hasMore = cached.hasMore
            pool.addAll(cached.pool); mine = cached.mine; mineLoaded = cached.mineLoaded
            loading = false
        }
        loadAll(silent = cached != null)
        viewModelScope.launch {
            graph.realtime.events.collect { event ->
                if (event.type.startsWith("teamup.")) {
                    debounce?.cancel()
                    debounce = viewModelScope.launch { delay(500); loadAll(silent = true) }
                }
            }
        }
    }

    fun loadAll(pull: Boolean = false, silent: Boolean = false) {
        if (!silent) { if (pull) refreshing = true else loading = profiles.isEmpty() }
        error = null
        viewModelScope.launch {
            runCatching { api.profiles(skill = skill, sort = sort) }
                .onSuccess { page ->
                    profiles.clear()
                    profiles.addAll(page)
                    hasMore = page.size >= PAGE
                    saveSnapshot()
                }
                .onFailure { if (profiles.isEmpty()) error = it.userMessage() else if (!silent) notice = it.userMessage() }
            loading = false
            refreshing = false
        }
        // 下面这些是点缀，失败了不影响名片流
        viewModelScope.launch { runCatching { api.event() }.onSuccess { event = it; saveSnapshot() } }
        viewModelScope.launch { runCatching { api.skills() }.onSuccess { skillCounts.clear(); skillCounts.addAll(it); saveSnapshot() } }
        viewModelScope.launch { runCatching { api.stats() }.onSuccess { stats = it; saveSnapshot() } }
        viewModelScope.launch { runCatching { api.mine() }.onSuccess { mine = it; mineLoaded = true; saveSnapshot() } }
        viewModelScope.launch {
            runCatching { api.profiles(sort = "latest", limit = DISCOVERY) to api.profiles(sort = "hot", limit = DISCOVERY) }
                .onSuccess { (latest, hot) ->
                    pool.clear()
                    pool.addAll((latest + hot).distinctBy { it.id })
                    saveSnapshot()
                }
        }
    }

    fun selectSkill(next: String?) {
        if (skill == next) return
        skill = next
        loadAll()
    }

    fun selectSort(next: String) {
        if (sort == next) return
        sort = next
        loadAll()
    }

    fun loadMore() {
        if (!hasMore || loadingMore || loading || profiles.isEmpty()) return
        loadingMore = true
        viewModelScope.launch {
            runCatching { api.profiles(skill = skill, sort = sort, beforeId = profiles.last().id) }
                .onSuccess { page ->
                    profiles.addAll(page.filter { incoming -> profiles.none { it.id == incoming.id } })
                    hasMore = page.size >= PAGE
                }
                .onFailure { notice = it.userMessage() }
            loadingMore = false
        }
    }

    fun shuffle() { seed = (Math.random() * 100000).toInt() }

    /** 「此刻值得认识的人」：按和我名片的互补程度打分，取前 6；换一批用 seed 打乱同分的顺序 */
    fun recommended(): List<Pair<TeamupProfile, String>> {
        val now = Instant.now()
        val candidates = pool.filter { !it.isSelf && it.recruiting }
        if (candidates.isEmpty()) return emptyList()
        val scored = candidates.sortedByDescending { matchScore(it, mine, now) + ((it.id * 9301 + seed) % 233280) / 233280.0 * 30 }.take(6)
        val myWanted = mine?.lookingFor.orEmpty().toSet()
        return scored.mapIndexed { index, profile ->
            val complementary = profile.skills.any { it.skill in myWanted }
            val tag = when {
                complementary && index == 0 -> "最佳匹配"
                complementary -> "高潜搭档"
                index == 0 && mine == null -> "高潜搭档"
                else -> "动态推荐"
            }
            profile to tag
        }
    }

    fun closeMine() = act { api.close().also { mine = it } }
    fun reopenMine() = act { api.reopen().also { mine = it } }
    fun deleteMine() = act { api.delete(); mine = null }

    private fun act(block: suspend () -> Unit) {
        if (acting) return
        acting = true
        viewModelScope.launch {
            runCatching { block() }
                .onSuccess { loadAll(silent = true) }
                .onFailure { notice = it.userMessage() }
            acting = false
        }
    }

    fun onSaved(profile: TeamupProfile) {
        mine = profile
        loadAll(silent = true)
    }

    /** 结束时间：优先用 ends_at 算（本地时钟走动），没有再退回 seconds_remaining */
    fun remainingSeconds(now: Instant): Long? {
        val e = event ?: return null
        parseInstant(e.endsAt)?.let { return (it.epochSecond - now.epochSecond).coerceAtLeast(0) }
        return e.secondsRemaining
    }
}

internal fun JsonElement?.isObject(): Boolean = this is JsonObject
