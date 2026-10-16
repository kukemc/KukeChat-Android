package ink.kuke.chat.feature.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import ink.kuke.chat.ui.components.pressScale
import ink.kuke.chat.ui.components.rememberInteraction
import ink.kuke.chat.ui.theme.KcTheme
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * 任务 / 机器人 / 风纪三块界面共用的小部件。放在这里是因为三个目录只能各自新增文件、不能动 ui/components；
 * 都是 internal，不对外暴露。
 */

/** 列表接口有的直接返回数组，有的包一层 { items: [...] }，两种都认 */
internal fun JsonElement.asItems(): JsonArray = when (this) {
    is JsonArray -> this
    is JsonObject -> (this["items"] as? JsonArray) ?: JsonArray(emptyList())
    else -> JsonArray(emptyList())
}

/** 整页中间的一行字：加载中、空状态 */
@Composable
internal fun CenterText(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, style = KcTheme.type.secondary, color = KcTheme.colors.muted, textAlign = TextAlign.Center, modifier = Modifier.padding(32.dp))
    }
}

/** 失败状态：一句原因 + 重试按钮 */
@Composable
internal fun ErrorBlock(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(message, style = KcTheme.type.secondary, color = KcTheme.colors.muted, textAlign = TextAlign.Center)
        SoftButton("重试", onClick = onRetry, modifier = Modifier.padding(top = 14.dp))
    }
}

/** 主按钮：强调色底白字，按下 0.96 倍 */
@Composable
internal fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, color: Color = KcTheme.colors.fill) {
    val interaction = rememberInteraction()
    Box(
        modifier
            .heightIn(min = 44.dp)
            .pressScale(interaction)
            .clip(RoundedCornerShape(12.dp))
            .background(if (enabled) color else KcTheme.colors.subtle.copy(alpha = 0.5f))
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.W600) }
}

/** 次按钮：灰底 */
@Composable
internal fun SoftButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, danger: Boolean = false) {
    val colors = KcTheme.colors
    val interaction = rememberInteraction()
    Box(
        modifier
            .heightIn(min = 44.dp)
            .pressScale(interaction)
            .clip(RoundedCornerShape(12.dp))
            .background(colors.panelMuted)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = if (danger) colors.danger else if (enabled) colors.text else colors.subtle, fontSize = 15.sp, fontWeight = FontWeight.W500) }
}

/** 小标签（分类、状态） */
@Composable
internal fun Tag(text: String, color: Color, modifier: Modifier = Modifier, filled: Boolean = false) {
    Box(
        modifier
            .clip(RoundedCornerShape(5.dp))
            .background(if (filled) color else color.copy(alpha = 0.14f))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    ) { Text(text, color = if (filled) Color.White else color, fontSize = 11.sp, fontWeight = FontWeight.W600, maxLines = 1) }
}

/** 白色圆角分组卡片 */
@Composable
internal fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(KcTheme.colors.panel)
            .border(1.dp, KcTheme.colors.border, RoundedCornerShape(14.dp)),
    ) { content() }
}

@Composable
internal fun SectionLabel(text: String, modifier: Modifier = Modifier, trailing: String? = null) {
    Row(modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = KcTheme.type.group, color = KcTheme.colors.muted, modifier = Modifier.weight(1f))
        if (trailing != null) Text(trailing, style = KcTheme.type.caption, color = KcTheme.colors.subtle)
    }
}

/** 文本框：灰底圆角，带占位 */
@Composable
internal fun PlainField(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    minHeight: Dp = 44.dp,
    numeric: Boolean = false,
    enabled: Boolean = true,
) {
    val colors = KcTheme.colors
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .clip(RoundedCornerShape(10.dp))
            .background(colors.panelMuted)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        contentAlignment = if (singleLine) Alignment.CenterStart else Alignment.TopStart,
    ) {
        BasicTextField(
            value = value,
            onValueChange = onChange,
            enabled = enabled,
            singleLine = singleLine,
            textStyle = KcTheme.type.body.copy(color = colors.text),
            cursorBrush = SolidColor(colors.accent),
            keyboardOptions = if (numeric) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                if (value.isEmpty()) Text(placeholder, style = KcTheme.type.body, color = colors.subtle)
                inner()
            },
        )
    }
}

/** 居中的对话框外壳：面板色圆角卡片 */
@Composable
internal fun DialogCard(onDismiss: () -> Unit, title: String? = null, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(KcTheme.colors.panel)
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (title != null) Text(title, style = KcTheme.type.title, color = KcTheme.colors.text)
            content()
        }
    }
}

/** 二次确认：删除、重置 Key 之类不可逆操作 */
@Composable
internal fun ConfirmDialog(title: String, message: String, confirmText: String, onConfirm: () -> Unit, onDismiss: () -> Unit, danger: Boolean = true) {
    DialogCard(onDismiss, title) {
        Text(message, style = KcTheme.type.secondary, color = KcTheme.colors.muted)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SoftButton("取消", onDismiss, Modifier.weight(1f))
            PrimaryButton(confirmText, onConfirm, Modifier.weight(1f), color = if (danger) KcTheme.colors.danger else KcTheme.colors.fill)
        }
    }
}

/** 单选列表对话框 */
@Composable
internal fun <T> ChoiceDialog(title: String, options: List<Pair<T, String>>, selected: T?, onPick: (T) -> Unit, onDismiss: () -> Unit) {
    val colors = KcTheme.colors
    DialogCard(onDismiss, title) {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
            options.forEach { (value, label) ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(10.dp)).clickable { onPick(value) }.padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(label, style = KcTheme.type.body, color = if (value == selected) colors.accent else colors.text, fontWeight = if (value == selected) FontWeight.W600 else FontWeight.W400, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (value == selected) Box(Modifier.size(8.dp).clip(CircleShape).background(colors.accent))
                }
            }
        }
    }
}
