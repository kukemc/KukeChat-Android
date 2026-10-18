package ink.kuke.chat.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ink.kuke.chat.core.AppGraph
import ink.kuke.chat.core.model.User
import ink.kuke.chat.core.net.KcJson
import ink.kuke.chat.core.net.userMessage
import ink.kuke.chat.ui.theme.KcTheme
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** GET /users/poke-options：可选的动作、默认动作、后缀字数上限 */
@Serializable
private data class PokeOptions(
    val actions: List<String> = emptyList(),
    @SerialName("default_action") val defaultAction: String = "戳",
    @SerialName("suffix_max_length") val suffixMaxLength: Int = 16,
)

/** 菜单和设置里的叫法：「拍」→「拍一拍」 */
private fun pokeActionLabel(action: String): String = action.trim().ifEmpty { "戳" }.let { "${it}一$it" }

/** 聊天记录里的写法：「拍」→「拍了拍」 */
private fun pokeVerb(action: String): String = action.trim().ifEmpty { "戳" }.let { "${it}了$it" }

/** 服务端按 Unicode 码点数字数（Python len），这里也一样，表情不会被算成两个字 */
private fun String.textLength(): Int = codePointCount(0, length)

/**
 * 设置 › 我的资料 › 戳一戳：别人戳我时，聊天里显示「某某 拍了拍 你 的小脑袋」。同电脑版 PokeSetting。
 * 这一项自己保存（PATCH /users/me poke_action / poke_suffix），不跟资料的「保存资料」走 ——
 * 后缀要过文本审核，被拒时服务端的提示要落在这张卡片里。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PokeCard(graph: AppGraph, profile: MyProfile, onSaved: (MyProfile) -> Unit) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val scope = rememberCoroutineScope()
    val options = rememberLoader(Unit) { KcJson.decodeFromJsonElement(PokeOptions.serializer(), graph.api.get("/users/poke-options")) }
    val data = options.data
    val actions = data?.actions.orEmpty()
    val defaultAction = data?.defaultAction ?: "戳"
    val maxLength = data?.suffixMaxLength ?: 16

    // 预设调整后，资料里存的旧动作可能已经不在表里，服务端按默认动作处理，这里也一样
    val savedAction = profile.pokeAction?.takeIf { it in actions } ?: defaultAction
    val savedSuffix = profile.pokeSuffix.orEmpty()
    // null = 没动过，跟着已保存的值走（别处改了资料、动作列表晚到都不用同步）
    var draftAction by remember { mutableStateOf<String?>(null) }
    var draftSuffix by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var justSaved by remember { mutableStateOf(false) }

    val action = draftAction ?: savedAction
    val suffixInput = draftSuffix ?: savedSuffix
    val suffix = suffixInput.trim()
    val dirty = action != savedAction || suffix != savedSuffix
    val isDefault = action == defaultAction && suffix.isEmpty()

    fun edit(newAction: String = action, newSuffix: String = suffixInput) {
        draftAction = newAction
        draftSuffix = newSuffix
        error = null
        justSaved = false
    }

    fun save() {
        if (!dirty || saving || data == null) return
        scope.launch {
            saving = true
            error = null
            runCatching { graph.api.patch("/users/me", buildJsonObject { put("poke_action", action); put("poke_suffix", suffix) }) }
                .onSuccess { element ->
                    runCatching { onSaved(KcJson.decodeFromJsonElement(MyProfile.serializer(), element)) }
                    runCatching { graph.session.updateUser(KcJson.decodeFromJsonElement(User.serializer(), element)) }
                    draftAction = null
                    draftSuffix = null
                    justSaved = true
                }
                // 后缀没过审核（poke_suffix_rejected）、动作不在预设里等，服务端给的是写好的中文，原样显示
                .onFailure { error = it.userMessage() }
            saving = false
        }
    }

    GroupCard {
        Column(Modifier.padding(14.dp)) {
            Text("别人戳你时，聊天里会留下一行字。用哪个动作、名字后面接什么话，由你来定。", style = type.caption, color = colors.muted)
            Spacer(Modifier.height(14.dp))
            when {
                options.state is Load.Failed -> {
                    Text("动作列表暂时加载不出来，稍后再试。", style = type.secondary, color = colors.muted)
                    Spacer(Modifier.height(10.dp))
                    SecondaryButton("重试", onClick = { options.load() })
                    return@Column
                }
                data == null -> {
                    Text("正在加载…", style = type.secondary, color = colors.muted)
                    return@Column
                }
            }

            FieldLabel("动作")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                actions.forEach { option ->
                    val on = option == action
                    Box(
                        Modifier
                            .heightIn(min = 34.dp)
                            .clip(RoundedCornerShape(99.dp))
                            .background(if (on) colors.fill else colors.panelMuted)
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, enabled = !saving) { edit(newAction = option) }
                            .padding(horizontal = 14.dp, vertical = 7.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            pokeActionLabel(option), style = type.secondary,
                            color = if (on) colors.onFill else colors.text,
                            fontWeight = if (on) FontWeight.W600 else FontWeight.W400, maxLines = 1,
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            FieldLabel("名字后面接的话")
            KcTextField(
                suffixInput,
                { if (it.textLength() <= maxLength) edit(newSuffix = it) },
                hint = "例如：的小脑袋",
                enabled = !saving,
            )
            Text(
                "${suffixInput.textLength()}/$maxLength，留空就只显示动作。保存时会过一遍文本审核。",
                style = type.caption, color = colors.subtle, modifier = Modifier.padding(top = 6.dp),
            )

            Spacer(Modifier.height(14.dp))
            FieldLabel("预览")
            Text(
                "某某 ${pokeVerb(action)} 你" + if (suffix.isNotEmpty()) " $suffix" else "",
                style = type.caption, color = colors.muted, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(colors.panelMuted).padding(horizontal = 12.dp, vertical = 12.dp),
            )

            error?.let {
                Text(it, style = type.secondary, color = colors.danger, modifier = Modifier.padding(top = 12.dp))
            }

            Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (justSaved && !dirty) "已保存" else "", style = type.caption, color = colors.muted, modifier = Modifier.weight(1f))
                if (!isDefault) SecondaryButton("恢复默认", enabled = !saving, onClick = { edit(newAction = defaultAction, newSuffix = "") })
                PrimaryButton(if (saving) "保存中…" else "保存", onClick = ::save, enabled = dirty, loading = saving)
            }
        }
    }
}
