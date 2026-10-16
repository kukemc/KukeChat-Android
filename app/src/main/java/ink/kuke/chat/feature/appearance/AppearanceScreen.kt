package ink.kuke.chat.feature.appearance

import androidx.compose.ui.draw.shadow
import ink.kuke.chat.feature.settings.RowDivider
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ink.kuke.chat.core.AppGraph
import ink.kuke.chat.feature.settings.GroupCard
import ink.kuke.chat.feature.settings.GroupLabel
import ink.kuke.chat.feature.settings.Segmented
import ink.kuke.chat.ui.components.KcPage
import ink.kuke.chat.ui.icons.KcIcon
import ink.kuke.chat.ui.icons.KcIcons
import ink.kuke.chat.ui.shell.LocalAppNav
import ink.kuke.chat.ui.theme.KcTheme

/**
 * 外观（设计稿 P29）：顶部是实时预览（导航 · 列表 · 气泡 · 输入卡），下面是分组设置，每个改动立刻反映在预览上。
 * 预览用的是 [Appearance.resolve] 算出来的一套颜色，而不是 KcTheme —— 所以即使外壳还没把外观接进主题，预览也是对的。
 */
@Composable
fun AppearanceScreen(graph: AppGraph, store: AppearanceStore) {
    val nav = LocalAppNav.current
    AppearanceContent(store = store, onBack = { nav.back() })
}

@OptIn(ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun AppearanceContent(store: AppearanceStore, onBack: () -> Unit) {
    val appearance by store.state.collectAsStateWithLifecycle()
    val systemDark = isSystemInDarkTheme()
    val resolved = remember(appearance, systemDark) { appearance.resolve(systemDark) }
    val colors = KcTheme.colors

    KcPage(
        title = "外观",
        onBack = onBack,
        actions = {
            Text(
                "恢复默认",
                style = KcTheme.type.secondary,
                color = if (appearance.isDefault) colors.subtle else colors.accent,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = !appearance.isDefault) { store.reset() }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            )
        },
    ) {
        // 设计稿 P29：整页一起滚动；顶部预览 → 明暗三卡 → 主题横滑 → 「细节」一张卡，左标签右控件
        var accentOpen by remember { mutableStateOf(false) }
        Column(Modifier.fillMaxSize().background(colors.list).verticalScroll(rememberScrollState()).navigationBarsPadding().padding(top = 12.dp, bottom = 24.dp)) {
            Box(Modifier.padding(horizontal = 12.dp)) { ThemePreview(resolved) }

            GroupLabel("明暗模式")
            Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val icons = listOf(KcIcons.device, KcIcons.sun, KcIcons.moon)
                DarkMode.entries.forEachIndexed { index, mode ->
                    val on = appearance.mode.ordinal == index
                    val shape = RoundedCornerShape(12.dp)
                    Column(
                        Modifier.weight(1f).clip(shape).background(colors.panel)
                            .then(if (on) Modifier.border(BorderStroke(2.dp, colors.accent), shape) else Modifier)
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { store.update { it.copy(mode = mode) } }
                            .padding(vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        KcIcon(icons[index], tint = if (on) colors.accent else colors.muted, size = 22.dp)
                        Text(mode.label, style = KcTheme.type.caption.copy(fontSize = KcTheme.type.caption.fontSize * (13f / 12f)), color = if (on) colors.accent else colors.text, fontWeight = if (on) FontWeight.W700 else FontWeight.W400)
                    }
                }
            }

            GroupLabel("主题")
            LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(ThemePresets, key = { it.id }) { preset ->
                    PresetChip(
                        preset = preset,
                        selected = preset.id == appearance.presetId,
                        dark = resolved.dark,
                        onClick = { store.update { it.copy(presetId = preset.id, accent = null, bubbleKind = null, bubbleColor = null) } },
                    )
                }
            }

            GroupLabel("细节")
            GroupCard {
                // 强调色：右侧五个小圆点预览，点开展开完整色板
                DetailRow("强调色", onClick = { accentOpen = !accentOpen }) {
                    val current = appearance.accentFill
                    AccentSwatches.take(5).forEach { swatch ->
                        val on = current == swatch
                        Box(Modifier.padding(start = 6.dp).size(if (on) 20.dp else 18.dp).background(swatch, CircleShape).then(if (on) Modifier.border(BorderStroke(2.dp, colors.panel), CircleShape).border(BorderStroke(3.5.dp, swatch), CircleShape) else Modifier))
                    }
                    KcIcon(if (accentOpen) KcIcons.chevronUp else KcIcons.chevron, tint = colors.subtle, size = 18.dp, modifier = Modifier.padding(start = 6.dp))
                }
                if (accentOpen) {
                    FlowRow(Modifier.padding(horizontal = 14.dp).padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        val current = appearance.accentFill
                        AccentSwatches.forEach { swatch ->
                            Swatch(swatch, selected = current == swatch) { store.update { it.copy(accent = swatch.toStoredLong()) } }
                        }
                    }
                }
                RowDivider()
                DetailRow("我的气泡") {
                    MiniSegmented(
                        BubbleKind.entries.map { it.label },
                        appearance.effectiveBubble.ordinal,
                    ) { i ->
                        val kind = BubbleKind.entries[i]
                        store.update { it.copy(bubbleKind = kind, bubbleColor = if (kind == BubbleKind.Custom) (it.bubbleColor ?: it.accentFill.toStoredLong()) else it.bubbleColor) }
                    }
                }
                if (appearance.effectiveBubble == BubbleKind.Custom) {
                    FlowRow(Modifier.padding(horizontal = 14.dp).padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        val current = appearance.bubbleColor?.let { Color(it.toInt()) }
                        BubbleSwatches.forEach { swatch ->
                            Swatch(swatch, selected = current == swatch) { store.update { it.copy(bubbleColor = swatch.toStoredLong()) } }
                        }
                    }
                }
                RowDivider()
                DetailRow("气泡圆角", sub = "$RadiusMin–${RadiusMax}dp，当前 ${appearance.bubbleRadius}") {
                    Slider(
                        value = appearance.bubbleRadius.toFloat(),
                        onValueChange = { v -> store.update { it.copy(bubbleRadius = v.toInt().coerceIn(RadiusMin, RadiusMax)) } },
                        valueRange = RadiusMin.toFloat()..RadiusMax.toFloat(),
                        steps = RadiusMax - RadiusMin - 1,
                        modifier = Modifier.width(130.dp),
                        // 设计稿：4dp 细轨 + 20dp 白色圆形滑块带轻影，不画刻度点
                        colors = SliderDefaults.colors(
                            thumbColor = Color.White, activeTrackColor = colors.fill, inactiveTrackColor = colors.text.copy(alpha = 0.12f),
                            activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent,
                        ),
                        thumb = { Box(Modifier.size(20.dp).shadow(2.dp, CircleShape).background(Color.White, CircleShape)) },
                        track = { state ->
                            val fraction = (state.value - state.valueRange.start) / (state.valueRange.endInclusive - state.valueRange.start)
                            Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(colors.text.copy(alpha = 0.12f))) {
                                Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(colors.fill))
                            }
                        },
                    )
                }
                RowDivider()
                DetailRow("界面密度") {
                    MiniSegmented(Density.entries.map { it.label }, appearance.density.ordinal) { i -> store.update { it.copy(density = Density.entries[i]) } }
                }
                RowDivider()
                DetailRow("头像形状") {
                    MiniSegmented(AvatarShape.entries.map { it.label }, appearance.avatarShape.ordinal) { i -> store.update { it.copy(avatarShape = AvatarShape.entries[i]) } }
                }
                RowDivider()
                DetailRow("字体大小") {
                    Text("A", style = KcTheme.type.caption, color = colors.muted, modifier = Modifier.padding(end = 4.dp))
                    MiniSegmented(listOf("小", "较小", "标准", "稍大", "大"), appearance.fontIndex, compact = true) { i -> store.update { it.copy(fontIndex = i) } }
                    Text("A", color = colors.muted, fontSize = 17.sp, modifier = Modifier.padding(start = 4.dp))
                }
            }
        }
    }
}

/** 「细节」卡里的一行：左标签（可带小字说明），右侧放控件，最小高 52 */
@Composable
private fun DetailRow(label: String, sub: String? = null, onClick: (() -> Unit)? = null, trailing: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    val colors = KcTheme.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = KcTheme.type.rowTitle, color = colors.text)
            if (sub != null) Text(sub, style = KcTheme.type.caption, color = colors.muted)
        }
        trailing()
    }
}

/** 设计稿 .seg：右侧的小分段控件，底是文字色 6%，选中是面色块 */
@Composable
private fun MiniSegmented(options: List<String>, selected: Int, compact: Boolean = false, onSelect: (Int) -> Unit) {
    val colors = KcTheme.colors
    Row(
        Modifier.clip(RoundedCornerShape(9.dp)).background(colors.text.copy(alpha = 0.06f)).padding(if (compact) 2.dp else 3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEachIndexed { index, label ->
            val on = index == selected
            Box(
                Modifier.height(if (compact) 24.dp else 30.dp).clip(RoundedCornerShape(7.dp))
                    .background(if (on) colors.raised else Color.Transparent)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onSelect(index) }
                    .padding(horizontal = if (compact) 7.dp else 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = KcTheme.type.caption.copy(fontSize = KcTheme.type.caption.fontSize * (13f / 12f)), color = if (on) colors.text else colors.muted, fontWeight = if (on) FontWeight.W700 else FontWeight.W500, maxLines = 1)
            }
        }
    }
}

@Composable
private fun Swatch(color: Color, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(color)
            .then(if (selected) Modifier.border(BorderStroke(2.dp, KcTheme.colors.panel), CircleShape) else Modifier)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) KcIcon(KcIcons.check, tint = Color.White, size = 18.dp)
    }
}

@Composable
private fun PresetChip(preset: ThemePreset, selected: Boolean, dark: Boolean, onClick: () -> Unit) {
    val colors = KcTheme.colors
    val swatch = if (dark) preset.accentDark else preset.accentLight
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier.clip(RoundedCornerShape(12.dp)).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // 选中时外面套两圈（列表底色 + 主题色），和设计稿的 box-shadow 一致
        Box(
            Modifier.size(48.dp)
                .then(if (selected) Modifier.border(BorderStroke(4.dp, swatch), shape).padding(2.dp) else Modifier)
                .clip(shape).background(swatch),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) KcIcon(KcIcons.check, tint = Color.White, size = 20.dp)
        }
        Text(preset.name, style = KcTheme.type.caption.copy(fontSize = KcTheme.type.caption.fontSize * (11.5f / 12f)), color = colors.text, fontWeight = if (selected) FontWeight.W700 else FontWeight.W400, maxLines = 1)
    }
}

// ------------------------------------------------------------------ 预览

/** 迷你的「导航 · 列表 · 气泡 · 输入卡」。所有颜色取自 [ResolvedTheme]，不碰 KcTheme */
@Composable
fun ThemePreview(theme: ResolvedTheme, modifier: Modifier = Modifier) {
    val c = theme.colors
    val scale = theme.fontScale
    val avatarShape: Shape = if (theme.roundedAvatars) RoundedCornerShape(8.dp) else CircleShape
    val gap = if (theme.compact) 5.dp else 9.dp

    Row(
        modifier
            .fillMaxWidth()
            .height(214.dp)
            .clip(RoundedCornerShape(14.dp))
            .border(BorderStroke(1.dp, c.border), RoundedCornerShape(14.dp)),
    ) {
        // 导航轨
        Column(Modifier.width(30.dp).fillMaxHeight().background(c.rail).padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(16.dp).clip(RoundedCornerShape(5.dp)).background(c.fill))
            repeat(3) { Box(Modifier.size(14.dp).clip(RoundedCornerShape(5.dp)).background(c.borderStrong)) }
        }
        // 列表
        Column(Modifier.width(96.dp).fillMaxHeight().background(c.list).padding(8.dp), verticalArrangement = Arrangement.spacedBy(gap)) {
            Text("聊天", color = c.text, fontSize = (13 * scale).sp, fontWeight = FontWeight.W800)
            PreviewListRow(c.text, c.muted, c.accent, scale, avatarShape, selected = true, c.active)
            PreviewListRow(c.text, c.muted, c.accent, scale, avatarShape, selected = false, c.active)
            PreviewListRow(c.text, c.muted, c.accent, scale, avatarShape, selected = false, c.active)
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(c.border))
        // 聊天
        Column(Modifier.weight(1f).fillMaxHeight().background(c.chat).padding(8.dp), verticalArrangement = Arrangement.spacedBy(gap)) {
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.Top) {
                Box(Modifier.size(20.dp).clip(avatarShape).background(c.accent.copy(alpha = 0.55f)))
                Box(
                    Modifier
                        .clip(RoundedCornerShape(theme.bubbleRadius))
                        .background(c.bubbleIn)
                        .border(BorderStroke(1.dp, c.border), RoundedCornerShape(theme.bubbleRadius))
                        .padding(horizontal = 9.dp, vertical = if (theme.compact) 5.dp else 8.dp),
                ) { Text("新主题怎么样？", color = c.text, fontSize = (11.5f * scale).sp) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.Top) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(theme.bubbleRadius))
                        .background(c.bubbleOut)
                        .padding(horizontal = 9.dp, vertical = if (theme.compact) 5.dp else 8.dp),
                ) { Text("好看！", color = theme.bubbleOutText, fontSize = (11.5f * scale).sp) }
                Spacer(Modifier.width(5.dp))
                Box(Modifier.size(20.dp).clip(avatarShape).background(c.fill))
            }
            Spacer(Modifier.weight(1f))
            // 输入卡
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.panel).border(BorderStroke(1.dp, c.border), RoundedCornerShape(10.dp)).padding(horizontal = 9.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("说点什么…", color = c.subtle, fontSize = (11f * scale).sp, modifier = Modifier.weight(1f))
                Box(Modifier.size(18.dp).clip(CircleShape).background(c.fill), contentAlignment = Alignment.Center) {
                    KcIcon(KcIcons.send, tint = c.onFill, size = 10.dp)
                }
            }
        }
    }
}

@Composable
private fun PreviewListRow(text: Color, muted: Color, accent: Color, scale: Float, shape: Shape, selected: Boolean, activeColor: Color) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (selected) activeColor else Color.Transparent).padding(vertical = 3.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(22.dp).clip(shape).background(accent.copy(alpha = if (selected) 0.9f else 0.4f)))
        Column(Modifier.padding(start = 6.dp)) {
            Text("星野", color = text, fontSize = (10.5f * scale).sp, fontWeight = FontWeight.W600, maxLines = 1)
            Text("在吗？", color = muted, fontSize = (9.5f * scale).sp, maxLines = 1)
        }
    }
}
