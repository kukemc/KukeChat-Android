package ink.kuke.chat.feature.tasks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.unit.dp
import ink.kuke.chat.ui.icons.KcIcon
import ink.kuke.chat.ui.icons.KcIcons
import ink.kuke.chat.ui.theme.KcTheme
import kotlinx.coroutines.launch

private const val GROUP_NAME_MAX = 30

/** 看板列头的「⋯」：重命名 / 删除自定义分组（默认分组没有） */
@Composable
internal fun GroupMenu(onRename: () -> Unit, onDelete: () -> Unit) {
    val colors = KcTheme.colors
    var open by remember { mutableStateOf(false) }
    Box {
        Box(Modifier.size(32.dp).clip(CircleShape).clickable { open = true }, contentAlignment = Alignment.Center) {
            KcIcon(KcIcons.more, tint = colors.muted, size = 18.dp, contentDescription = "分组操作")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("重命名") }, onClick = { open = false; onRename() })
            DropdownMenuItem(text = { Text("删除分组", color = colors.danger) }, onClick = { open = false; onDelete() })
        }
    }
}

/** 分组名输入框：保存失败时 onSave 返回错误文字，成功返回 null（由调用方关掉对话框） */
@Composable
internal fun GroupNameDialog(title: String, initial: String, onDismiss: () -> Unit, onSave: suspend (String) -> String?) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(initial) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val clean = name.trim()
    DialogCard(onDismiss, title) {
        PlainField(name, { name = it.take(GROUP_NAME_MAX) }, "分组名称，比如「本周」「设计」")
        error?.let { Text(it, style = KcTheme.type.secondary, color = KcTheme.colors.danger) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SoftButton("取消", onDismiss, Modifier.weight(1f))
            PrimaryButton(if (saving) "保存中…" else "保存", {
                saving = true
                error = null
                scope.launch {
                    error = onSave(clean)
                    saving = false
                }
            }, Modifier.weight(1f), enabled = !saving && clean.isNotEmpty() && clean != initial.trim())
        }
    }
}
