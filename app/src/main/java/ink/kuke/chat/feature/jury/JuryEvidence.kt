package ink.kuke.chat.feature.jury

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import ink.kuke.chat.core.net.Endpoints
import ink.kuke.chat.ui.theme.KcTheme
import kotlin.math.abs

/**
 * 匿名证据：被举报消息 + 上下文。发言人只有「被举报人」「成员A」这类代称。
 * 默认显示离目标消息近的几条，其余折叠（rules.context_near）。
 */
@Composable
internal fun CaseEvidence(
    kind: String,
    messages: List<CaseMessage>,
    post: CasePost?,
    contextNear: Int,
    modifier: Modifier = Modifier,
) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    var expanded by remember(messages.size, post) { mutableStateOf(false) }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (kind == "post" || (post != null && messages.isEmpty())) {
            post?.let { p ->
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(colors.panel)
                        .border(1.5.dp, colors.danger.copy(alpha = 0.5f), RoundedCornerShape(14.dp)).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("被举报人 · 公开动态", style = type.caption, color = colors.danger, fontWeight = FontWeight.W600)
                    Text(p.content ?: "（内容已不可见）", style = type.body, color = colors.text)
                    p.imageUrls.forEach { url -> EvidenceImage(url) }
                }
            }
            return@Column
        }

        val hiddenCount = messages.count { it.offset != null && abs(it.offset) > contextNear }
        val shown = if (expanded || hiddenCount == 0) messages else messages.filter { it.offset == null || abs(it.offset) <= contextNear }
        if (hiddenCount > 0 && !expanded) {
            Text(
                "展开更多上下文（${hiddenCount} 条）", style = type.secondary, color = colors.accent,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { expanded = true }.padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
        shown.forEach { message ->
            val target = message.isTarget
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                    .background(if (target) colors.danger.copy(alpha = 0.08f) else colors.panel)
                    .then(if (target) Modifier.border(1.5.dp, colors.danger.copy(alpha = 0.5f), RoundedCornerShape(14.dp)) else Modifier)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        message.speaker + if (target) " · 被举报" else "", style = type.caption,
                        color = if (target) colors.danger else colors.muted, fontWeight = FontWeight.W600,
                    )
                }
                Text(
                    message.content ?: "（这条消息已被撤回）", style = type.body,
                    color = if (message.content == null) colors.subtle else colors.text,
                )
                message.images.forEach { url -> EvidenceImage(url) }
            }
        }
    }
}

@Composable
private fun EvidenceImage(url: String) {
    val colors = KcTheme.colors
    val absolute = Endpoints.absolute(url)
    if (absolute == null) {
        Box(Modifier.size(width = 160.dp, height = 80.dp).clip(RoundedCornerShape(10.dp)).background(colors.panelMuted), contentAlignment = Alignment.Center) {
            Text("图片", style = KcTheme.type.caption, color = colors.subtle)
        }
        return
    }
    AsyncImage(
        model = absolute, contentDescription = "证据图片", contentScale = ContentScale.Fit,
        modifier = Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(10.dp)).background(colors.panelMuted),
    )
}

/** 小标题 + 右侧说明 */
@Composable
internal fun BlockTitle(title: String, aside: String? = null, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = KcTheme.type.group, color = KcTheme.colors.muted, modifier = Modifier.weight(1f))
        if (aside != null) Text(aside, style = KcTheme.type.caption, color = KcTheme.colors.subtle)
    }
}
