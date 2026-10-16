package ink.kuke.chat.core.session

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import ink.kuke.chat.core.model.User
import ink.kuke.chat.core.net.KcJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

private val Context.sessionStore by preferencesDataStore(name = "session")

data class Session(val token: String, val user: User)

/**
 * 登录状态：令牌和当前用户，存在 DataStore 里，启动时读回来。
 * 令牌 7 天有效、没有刷新接口（服务端 app/core/config.py），过期了就是重新登录。
 * 后台服务也要读令牌，所以另外同步一份到 SharedPreferences（见 snapshot）。
 */
class SessionStore(private val context: Context) {
    private val tokenKey = stringPreferencesKey("token")
    private val userKey = stringPreferencesKey("user")
    private val _session = MutableStateFlow<Session?>(null)
    val session: StateFlow<Session?> = _session.asStateFlow()

    /** 启动时读一次。返回读到的登录状态 */
    suspend fun load(): Session? {
        val prefs = context.sessionStore.data.first()
        val token = prefs[tokenKey]
        val user = prefs[userKey]?.let { runCatching { KcJson.decodeFromString(User.serializer(), it) }.getOrNull() }
        val loaded = if (!token.isNullOrBlank() && user != null) Session(token, user) else null
        _session.value = loaded
        writeSnapshot(loaded)
        return loaded
    }

    suspend fun save(session: Session) {
        _session.value = session
        context.sessionStore.edit {
            it[tokenKey] = session.token
            it[userKey] = KcJson.encodeToString(User.serializer(), session.user)
        }
        writeSnapshot(session)
    }

    suspend fun updateUser(user: User) {
        val current = _session.value ?: return
        save(current.copy(user = user))
    }

    suspend fun clear() {
        _session.value = null
        context.sessionStore.edit { it.clear() }
        writeSnapshot(null)
    }

    val token: String? get() = _session.value?.token

    private fun writeSnapshot(session: Session?) {
        context.getSharedPreferences(SNAPSHOT, Context.MODE_PRIVATE).edit()
            .putString("token", session?.token)
            .putLong("user_id", session?.user?.id ?: 0L)
            .apply()
    }

    companion object {
        /** 后台服务读的那一份（服务进程里不方便等 DataStore） */
        const val SNAPSHOT = "session_snapshot"

        fun snapshotToken(context: Context): String? = context.getSharedPreferences(SNAPSHOT, Context.MODE_PRIVATE).getString("token", null)
        fun snapshotUserId(context: Context): Long = context.getSharedPreferences(SNAPSHOT, Context.MODE_PRIVATE).getLong("user_id", 0L)
    }
}
