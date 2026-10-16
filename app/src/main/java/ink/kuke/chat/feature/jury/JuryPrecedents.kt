package ink.kuke.chat.feature.jury

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ink.kuke.chat.core.net.ApiException
import ink.kuke.chat.core.net.userMessage
import ink.kuke.chat.feature.tasks.Card
import ink.kuke.chat.feature.tasks.CenterText
import ink.kuke.chat.feature.tasks.ConfirmDialog
import ink.kuke.chat.feature.tasks.DialogCard
import ink.kuke.chat.feature.tasks.ErrorBlock
import ink.kuke.chat.feature.tasks.PlainField
import ink.kuke.chat.feature.tasks.PrimaryButton
import ink.kuke.chat.feature.tasks.SoftButton
import ink.kuke.chat.feature.tasks.Tag
import ink.kuke.chat.ui.components.Haptics
import ink.kuke.chat.ui.components.KcPage
import ink.kuke.chat.ui.theme.KcTheme
import kotlinx.coroutines.launch

private const val TITLE_MAX = 60
private const val SUMMARY_MAX = 1000
private const val TITLE_MIN = 4
private const val SUMMARY_MIN = 20

private fun precedentBy(by: String) = if (by == "admin") "管理员标注" else "资深委员标注"

@Composable
private fun OutcomeTag(outcome: String, level: Int?) {
    val colors = KcTheme.colors
    Tag(outcomeLabel(outcome) + (level?.let { " · ${levelName(it)}" } ?: ""), if (outcome == "violation") colors.danger else colors.ok, filled = true)
}

/** 判例库：被标为判例的已定案件和裁判要旨，所有登录用户可读 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun JuryPrecedentsPage(ctx: JuryCtx) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val scope = rememberCoroutineScope()
    var category by remember { mutableStateOf("") }
    var rows by remember { mutableStateOf(ctx.graph.screenCache.get<PrecedentPage>("jury.precedents")?.items ?: emptyList()) }
    var total by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(rows.isEmpty()) }
    var refreshing by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableIntStateOf(0) }

    LaunchedEffect(category, tick) {
        runCatching { ctx.api.precedents(0, category) }
            .onSuccess { rows = it.items; total = it.total; error = null; if (category.isEmpty()) ctx.graph.screenCache.put("jury.precedents", it) }
            .onFailure { error = it.userMessage() }
        loading = false
        refreshing = false
    }

    KcPage("判例库", subtitle = "同类情况该怎么判", onBack = { ctx.back() }) {
        Column(Modifier.fillMaxSize()) {
            val categories = ctx.config.categories
            if (categories.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (listOf("" to "全部") + categories.map { it.key to it.label }).forEach { (key, label) ->
                        val on = key == category
                        Box(
                            Modifier.clip(RoundedCornerShape(16.dp)).background(if (on) colors.accentSoft else colors.panelMuted)
                                .clickable { category = key; loading = true }.padding(horizontal = 12.dp, vertical = 6.dp),
                        ) { Text(label, style = type.secondary, color = if (on) colors.accent else colors.muted, fontWeight = if (on) FontWeight.W600 else FontWeight.W400) }
                    }
                }
            }
            PullToRefreshBox(isRefreshing = refreshing, onRefresh = { refreshing = true; tick++ }, modifier = Modifier.weight(1f)) {
                when {
                    loading -> CenterText("正在加载…")
                    error != null && rows.isEmpty() -> ErrorBlock(error!!, { loading = true; tick++ })
                    rows.isEmpty() -> CenterText(
                        if (category.isNotEmpty()) "这个类别下还没有判例"
                        else if (ctx.status?.isSenior == true) "还没有判例\n你可以在「我的评审」里打开一个自己投过票、已经定案的案件，把它标为判例。"
                        else "还没有判例\n资深委员和管理员标出第一个判例后，会出现在这里。",
                    )
                    else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        item("lead") {
                            Text(
                                "判例从已经定案的案件里挑出来，用一段裁判要旨说明一类情况该怎么判，给之后的评审对尺度用。",
                                style = type.caption, color = colors.muted, modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                            )
                        }
                        items(rows, key = { it.id }) { item ->
                            Column(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(colors.panel)
                                    .clickable { ctx.go(JuryPage.Precedent(item.id)) }.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(5.dp),
                            ) {
                                Text(item.title, style = type.rowTitle, color = colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(item.summary, style = type.secondary, color = colors.muted, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    OutcomeTag(item.outcome, item.level)
                                    item.categoryLabel?.let { Tag(it, colors.muted) }
                                    if (item.mine) Tag("我标注的", colors.accent)
                                }
                                Text("${kindLabel(item.kind)} · ${publicNo(item.caseId)} · ${precedentBy(item.by)} · ${dateTimeText(item.createdAt)}", style = type.caption, color = colors.subtle)
                            }
                        }
                        if (rows.size < total) item("more") {
                            Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                                SoftButton(if (loadingMore) "正在加载…" else "加载更多", {
                                    if (loadingMore) return@SoftButton
                                    loadingMore = true
                                    scope.launch {
                                        runCatching { ctx.api.precedents(rows.size, category) }.onSuccess { rows = rows + it.items; total = it.total }
                                        loadingMore = false
                                    }
                                })
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 判例详情：裁判要旨（全站可读）+ 案件内容（在任委员看匿名快照，其他人按公示栏规则） */
@Composable
internal fun JuryPrecedentPage(ctx: JuryCtx, precedentId: Long) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    var detail by remember(precedentId) { mutableStateOf<PrecedentDetail?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var missing by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf(false) }
    var actionError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(precedentId, tick) {
        runCatching { ctx.api.precedent(precedentId) }
            .onSuccess { detail = it; error = null }
            .onFailure { missing = (it as? ApiException)?.status == 404; error = it.userMessage() }
    }

    KcPage("判例 #$precedentId", subtitle = "规则手册 · 判例库", onBack = { ctx.back() }) {
        val current = detail
        when {
            current == null && missing -> CenterText("这个判例不存在\n它可能已经被撤销了。")
            current == null && error != null -> ErrorBlock(error!!, { tick++ })
            current == null -> CenterText("正在加载…")
            else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("裁判要旨", style = type.caption, color = colors.accent, fontWeight = FontWeight.W600)
                        Text(current.title, style = type.title, color = colors.text)
                        Text(current.summary, style = type.body, color = colors.text)
                    }
                }
                Card {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutcomeTag(current.outcome, current.level)
                            current.categoryLabel?.let { Tag(it, colors.muted) }
                        }
                        Text("${kindLabel(current.kind)} · ${publicNo(current.caseId)} · ${precedentBy(current.by)} · ${dateTimeText(current.createdAt)}", style = type.caption, color = colors.muted)
                        if (ctx.config.has("public_cases") && current.contentHiddenReason != "unpublished") {
                            SoftButton("在公示栏查看这个案件", { ctx.go(JuryPage.BulletinCase(current.caseId)) }, Modifier.padding(top = 4.dp))
                        }
                    }
                }

                Text("案件内容", style = type.group, color = colors.muted)
                val snapshot = current.case
                val content = current.content
                when {
                    snapshot != null -> {
                        Text("已匿名 · 只有在任委员能看到这一块", style = type.caption, color = colors.subtle)
                        CaseEvidence(current.kind, snapshot.messages, snapshot.post, ctx.config.rules.contextNear)
                    }
                    content != null -> Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(colors.panel).padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(content.text ?: "这篇动态没有文字", style = type.body, color = if (content.text == null) colors.subtle else colors.text)
                        if (content.imageCount > 0) Text("原文带 ${content.imageCount} 张图片，图片一律不公示。", style = type.caption, color = colors.muted)
                    }
                    else -> {
                        val (title, text) = when (current.contentHiddenReason) {
                            "unpublished" -> "案件还没有公示，暂时不展示内容" to "案件定案满 ${ctx.config.rules.publishDelayHours} 小时、且没有待处理的申诉之后才进公示栏。"
                            "clean" -> "认定不违规，不公示内容" to "把原文挂出来等于公开「此人被举报过」。"
                            "sensitive" -> "涉及隐私，不公示内容" to "内容本身可能包含他人的个人信息。"
                            "overturned" -> "结论已被改判，内容已撤下" to ""
                            else -> "群聊消息不公示内容" to "为了保护群聊隐私，只公示结论，不展示消息原文。"
                        }
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(colors.panelMuted).padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(title, style = type.rowTitle, color = colors.text)
                            Text((text + " 判例的裁判要旨不受影响，照常可读。").trim(), style = type.secondary, color = colors.muted)
                        }
                    }
                }

                if (current.mine) {
                    Text("这是你标注的判例", style = type.group, color = colors.muted)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SoftButton("修改", { editing = true }, Modifier.weight(1f))
                        SoftButton("撤销判例", { confirming = true }, Modifier.weight(1f), danger = true)
                    }
                    actionError?.let { Text(it, style = type.secondary, color = colors.danger) }
                }
            }
        }
    }

    if (editing) detail?.let { current ->
        PrecedentEditorDialog(ctx, caseId = null, editing = current, onDismiss = { editing = false }) { editing = false; tick++ }
    }
    if (confirming) detail?.let { current ->
        ConfirmDialog("撤销这个判例？", "撤销后它会从判例库和练习场的题库里拿掉，案件本身的结论不受影响。", "撤销", onConfirm = {
            confirming = false
            scope.launch {
                runCatching { ctx.api.withdrawPrecedent(current.id) }
                    .onSuccess { Haptics.confirm(view); ctx.graph.screenCache.remove("jury.precedents"); ctx.back() }
                    .onFailure { Haptics.reject(view); actionError = it.userMessage() }
            }
        }, onDismiss = { confirming = false })
    }
}

/** 标为判例 / 修改判例。caseId 是评审编号（新建时用），editing 是已有判例（修改时用） */
@Composable
internal fun PrecedentEditorDialog(ctx: JuryCtx, caseId: Long?, editing: PrecedentDetail?, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val colors = KcTheme.colors
    val type = KcTheme.type
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    var title by remember { mutableStateOf(editing?.title.orEmpty()) }
    var summary by remember { mutableStateOf(editing?.summary.orEmpty()) }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val valid = title.trim().length >= TITLE_MIN && summary.trim().length >= SUMMARY_MIN &&
        (editing == null || title.trim() != editing.title || summary.trim() != editing.summary)

    DialogCard(onDismiss, if (editing == null) "标为判例" else "修改判例") {
        Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("标题 · ${title.length}/$TITLE_MAX", style = type.group, color = colors.muted)
            PlainField(title, { title = it.take(TITLE_MAX) }, "一句话说清这个案件确立的尺度")
            Text("裁判要旨 · ${summary.length}/$SUMMARY_MAX", style = type.group, color = colors.muted)
            PlainField(summary, { summary = it.take(SUMMARY_MAX) }, "这类情况怎么判、看的是哪几点、什么时候该加重或从轻", singleLine = false, minHeight = 160.dp)
            Text("标题至少 $TITLE_MIN 个字，要旨至少 $SUMMARY_MIN 个字。不要写当事人是谁，也不要照抄违规原文。结论被改判的案件不能作为判例。", style = type.caption, color = colors.subtle)
            error?.let { Text(it, style = type.secondary, color = colors.danger) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SoftButton("取消", onDismiss, Modifier.weight(1f))
            PrimaryButton(if (sending) "保存中…" else "保存", {
                sending = true
                error = null
                scope.launch {
                    runCatching {
                        if (editing != null) ctx.api.updatePrecedent(editing.id, title.trim(), summary.trim())
                        else ctx.api.createPrecedent(caseId ?: throw IllegalStateException("缺少案件"), title.trim(), summary.trim())
                    }.onSuccess { Haptics.confirm(view); ctx.graph.screenCache.remove("jury.precedents"); onSaved() }
                        .onFailure { Haptics.reject(view); error = it.userMessage() }
                    sending = false
                }
            }, Modifier.weight(1f), enabled = valid && !sending)
        }
    }
}
