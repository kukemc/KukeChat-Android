package ink.kuke.chat.feature.settings

import ink.kuke.chat.core.data.CacheSlot

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ink.kuke.chat.core.net.userMessage
import ink.kuke.chat.ui.components.pressScale
import ink.kuke.chat.ui.components.rememberInteraction
import ink.kuke.chat.ui.icons.KcIcon
import ink.kuke.chat.ui.icons.KcIcons
import ink.kuke.chat.ui.theme.KcTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * 设置、商店、等级等页面共用的小零件。放在 settings 目录里是因为这几个目录是同一个人维护的，
 * 不去动共用的 ui/components。
 */

// ------------------------------------------------------------------ 加载状态

sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Failed(val message: String) : Load<Nothing>
    data class Ready<T>(val data: T) : Load<T>
}

/**
 * 一次网络读取：加载中 / 失败（带重试）/ 成功。已经有数据时下拉刷新失败不会把页面换成错误页，只保留旧数据。
 * 给了 [cache] 就先拿上次的数据直接进「成功」，进页面那次刷新静默进行（不转圈），拉回来再覆盖并写回缓存。
 */
class Loader<T>(private val scope: CoroutineScope, private val cache: CacheSlot<T>? = null, private val fetch: suspend () -> T) {
    var state by mutableStateOf<Load<T>>(cache?.get()?.let { Load.Ready(it) } ?: Load.Loading)
        private set
    var refreshing by mutableStateOf(false)
        private set
    var lastError by mutableStateOf<String?>(null)
        private set
    private var job: Job? = null

    val data: T? get() = (state as? Load.Ready<T>)?.data

    /** silent：已有数据时不显示下拉转圈（进页面时的后台刷新） */
    fun load(silent: Boolean = false) {
        job?.cancel()
        job = scope.launch {
            val hasData = state is Load.Ready
            if (hasData) { if (!silent) refreshing = true } else state = Load.Loading
            runCatching { fetch() }
                .onSuccess { state = Load.Ready(it); lastError = null; cache?.put(it) }
                .onFailure {
                    if (it is kotlinx.coroutines.CancellationException) throw it
                    lastError = it.userMessage()
                    if (!hasData) state = Load.Failed(it.userMessage())
                }
            refreshing = false
        }
    }

    /** 本地直接改数据（比如点赞后立刻更新，不等重新拉） */
    fun mutate(change: (T) -> T) {
        val current = data ?: return
        val next = change(current)
        state = Load.Ready(next)
        cache?.put(next)
    }
}

/** [cache] 见 [Loader]：传 `graph.screenCache.slot("…")`，key 要把 [keys] 里会变的东西带上 */
@Composable
fun <T> rememberLoader(vararg keys: Any?, cache: CacheSlot<T>? = null, fetch: suspend () -> T): Loader<T> {
    val scope = rememberCoroutineScope()
    val latest = rememberUpdatedState(fetch)
    val loader = remember(*keys) { Loader(scope, cache) { latest.value() } }
    LaunchedEffect(loader) { loader.load(silent = true) }
    return loader
}

/** 带下拉刷新的页面主体：加载中 / 失败 / 内容 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> LoadBox(loader: Loader<T>, modifier: Modifier = Modifier.fillMaxSize(), content: @Composable (T) -> Unit) {
    PullToRefreshBox(isRefreshing = loader.refreshing, onRefresh = { loader.load() }, modifier = modifier) {
        when (val state = loader.state) {
            is Load.Loading -> CenterText("正在加载…")
            is Load.Failed -> Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text(state.message, style = KcTheme.type.secondary, color = KcTheme.colors.muted, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 32.dp))
                Spacer(Modifier.height(12.dp))
                SecondaryButton("重试", onClick = { loader.load() })
            }
            is Load.Ready -> content(state.data)
        }
    }
}

@Composable
fun CenterText(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, style = KcTheme.type.secondary, color = KcTheme.colors.muted, textAlign = TextAlign.Center, modifier = Modifier.padding(32.dp))
    }
}

// ------------------------------------------------------------------ 提示（轻量 toast）

class ToastState {
    var message by mutableStateOf<String?>(null)
        private set
    private var token = 0

    fun show(text: String, scope: CoroutineScope) {
        message = text
        val mine = ++token
        scope.launch {
            delay(2200)
            if (mine == token) message = null
        }
    }
}

@Composable
fun rememberToast(): ToastState = remember { ToastState() }

/** 放在页面最外层 Box 里，底部居中浮出一条胶囊提示 */
@Composable
fun BoxScope.ToastHost(state: ToastState) {
    val text = state.message ?: return
    Box(
        Modifier
            .align(Alignment.TopCenter)
            .statusBarsPadding()
            .padding(top = 8.dp, start = 24.dp, end = 24.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(KcTheme.colors.text.copy(alpha = 0.88f))
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(text, color = KcTheme.colors.panel, style = KcTheme.type.secondary)
    }
}

// ------------------------------------------------------------------ 分组与行

@Composable
fun GroupLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, style = KcTheme.type.group, color = KcTheme.colors.muted, modifier = modifier.padding(start = 20.dp, top = 18.dp, bottom = 6.dp))
}

/** 圆角 12 的白面，内部的行之间自己放 RowDivider */
@Composable
fun GroupCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(KcTheme.colors.panel),
        content = content,
    )
}

@Composable
fun RowDivider(inset: Int = 52) {
    Box(Modifier.padding(start = inset.dp).fillMaxWidth().height(1.dp).background(KcTheme.colors.border))
}

/**
 * 设置行：52dp 起，左图标、标题（+副标题）、行尾当前值、右箭头。
 */
@Composable
fun SettingsRow(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    iconTint: Color = KcTheme.colors.accent,
    /** 图标放进 36dp 的淡色圆角块（设计稿 P28 的设置首页） */
    tile: Boolean = false,
    subtitle: String? = null,
    value: String? = null,
    valueColor: Color = KcTheme.colors.muted,
    danger: Boolean = false,
    chevron: Boolean = true,
    dot: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val interaction = rememberInteraction()
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .let { if (onClick != null) it.clickable(interactionSource = interaction, indication = null, onClick = onClick) else it }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            val tint = if (danger) colors.danger else iconTint
            if (tile) {
                Box(Modifier.size(36.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp)).background(tint.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                    KcIcon(icon, tint = tint, size = 20.dp)
                }
            } else {
                KcIcon(icon, tint = tint, size = 20.dp)
            }
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = type.rowTitle, color = if (danger) colors.danger else colors.text)
            if (subtitle != null) Text(subtitle, style = type.caption, color = colors.muted, modifier = Modifier.padding(top = 2.dp))
        }
        if (value != null) {
            Text(value, style = type.secondary, color = valueColor, modifier = Modifier.padding(start = 8.dp), maxLines = 1)
        }
        if (dot) Box(Modifier.padding(start = 8.dp).size(8.dp).background(colors.unread, CircleShape))
        trailing?.invoke()
        if (chevron && onClick != null && trailing == null) {
            KcIcon(KcIcons.chevron, tint = colors.subtle, size = 16.dp, modifier = Modifier.padding(start = 4.dp))
        }
    }
}

@Composable
fun KcSwitch(checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    val colors = KcTheme.colors
    val track by animateColorAsState(if (checked) colors.fill else colors.borderStrong, label = "track")
    val offset by animateDpAsState(if (checked) 20.dp else 0.dp, spring(dampingRatio = 0.6f, stiffness = 700f), label = "thumb")
    Box(
        Modifier
            .size(width = 46.dp, height = 28.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(track.copy(alpha = if (enabled) 1f else 0.5f))
            .clickable(enabled = enabled, interactionSource = remember { MutableInteractionSource() }, indication = null) { onChange(!checked) }
            .padding(3.dp),
    ) {
        Box(Modifier.offset(x = offset).size(22.dp).background(Color.White, CircleShape))
    }
}

@Composable
fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit, subtitle: String? = null, icon: ImageVector? = null, enabled: Boolean = true) {
    SettingsRow(
        title = title,
        subtitle = subtitle,
        icon = icon,
        onClick = if (enabled) ({ onChange(!checked) }) else null,
        chevron = false,
        trailing = { KcSwitch(checked, onChange, enabled) },
    )
}

/** 分段选择：选中项是一枚白色小键帽，同底栏的语言 */
@Composable
fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val colors = KcTheme.colors
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(colors.panelMuted).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEachIndexed { index, label ->
            val on = index == selected
            Box(
                Modifier
                    .weight(1f)
                    .height(34.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (on) colors.raised else Color.Transparent)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onSelect(index) },
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = KcTheme.type.secondary, color = if (on) colors.text else colors.muted, fontWeight = if (on) FontWeight.W600 else FontWeight.W400, maxLines = 1)
            }
        }
    }
}

// ------------------------------------------------------------------ 按钮与输入

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, loading: Boolean = false, danger: Boolean = false, container: Color? = null, content: Color? = null) {
    val colors = KcTheme.colors
    val interaction = rememberInteraction()
    val usable = enabled && !loading
    val base = container ?: if (danger) colors.danger else colors.fill
    Box(
        modifier
            .heightIn(min = 44.dp)
            .pressScale(interaction)
            .clip(RoundedCornerShape(12.dp))
            .background(if (usable) base else base.copy(alpha = 0.4f))
            .clickable(enabled = usable, interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(if (loading) "请稍候…" else text, style = KcTheme.type.rowTitle, color = content ?: colors.onFill, fontWeight = FontWeight.W600, maxLines = 1)
    }
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, danger: Boolean = false) {
    val colors = KcTheme.colors
    val interaction = rememberInteraction()
    Box(
        modifier
            .heightIn(min = 40.dp)
            .pressScale(interaction)
            .clip(RoundedCornerShape(10.dp))
            .background(colors.panelMuted)
            .clickable(enabled = enabled, interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        val tint = if (danger) colors.danger else colors.text
        Text(text, style = KcTheme.type.secondary, color = if (enabled) tint else tint.copy(alpha = 0.4f), fontWeight = FontWeight.W500, maxLines = 1)
    }
}

@Composable
fun KcTextField(
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    hint: String = "",
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLength: Int = Int.MAX_VALUE,
    numeric: Boolean = false,
    enabled: Boolean = true,
) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    BasicTextField(
        value = value,
        onValueChange = { if (it.length <= maxLength) onChange(it) },
        enabled = enabled,
        singleLine = singleLine,
        minLines = minLines,
        textStyle = TextStyle(color = colors.text, fontSize = type.body.fontSize, lineHeight = type.body.lineHeight),
        cursorBrush = SolidColor(colors.accent),
        keyboardOptions = if (numeric) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
        modifier = modifier,
        decorationBox = { inner ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(colors.panelMuted)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                contentAlignment = if (singleLine) Alignment.CenterStart else Alignment.TopStart,
            ) {
                if (value.isEmpty()) Text(hint, style = type.body, color = colors.subtle)
                inner()
            }
        },
    )
}

@Composable
fun FieldLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, style = KcTheme.type.caption, color = KcTheme.colors.muted, modifier = modifier.padding(bottom = 6.dp))
}

/** 确认弹窗（退出登录、解绑、卸下……） */
@Composable
fun ConfirmDialog(title: String, text: String, confirmText: String, onConfirm: () -> Unit, onDismiss: () -> Unit, danger: Boolean = false) {
    val colors = KcTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.panel,
        title = { Text(title, style = KcTheme.type.title, color = colors.text) },
        text = { Text(text, style = KcTheme.type.secondary, color = colors.muted) },
        confirmButton = {
            TextButton(onClick = { onDismiss(); onConfirm() }) {
                Text(confirmText, color = if (danger) colors.danger else colors.accent, fontWeight = FontWeight.W600)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消", color = colors.muted) } },
    )
}

/** 小标签（等级、状态） */
@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier, filled: Boolean = false) {
    Box(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (filled) color else color.copy(alpha = 0.14f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(text, color = if (filled) Color.White else color, fontSize = 11.sp, fontWeight = FontWeight.W600, maxLines = 1)
    }
}

@Composable
fun PageBody(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.fillMaxSize().background(KcTheme.colors.chat), content = content)
}
