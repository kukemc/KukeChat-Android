package ink.kuke.chat.feature.auth

import androidx.compose.ui.graphics.Color

/*
 * 登录页的 Scratch 积木：数据层（有哪些积木、每块由哪些零件组成）。不含绘制 / 动画。
 * 对应设计稿 KukeChat-Client/dev/login-concepts/live/template.html 的 SB_DEFS / OL_CODE。
 *
 * 跟着表单变的积木都是 [AuthVars] 的函数：
 *   登录 = 当绿旗被点击 / 说「你好，<用户名>!」/ 将 [密码] 设为 [●●●] / 将 KukeChat 窗口 [打开] / 推荐添加好友 (1) 附加消息 [一起聊天吧!]
 *   注册 = 当绿旗被点击 / 说「欢迎加入，<用户名>!」/（填了昵称才有）将 [昵称] 设为 […] / 如果 <●●● = ●●●> 那么 · 创建 [<用户名>] 账号
 * 密码只显示成圆点（一个字符一个点），不会把真实密码画出来。
 * KukeChat 扩展积木的文案取自 src/extension/extension.ts 的 getInfo（zh-cn），配色 color1/2/3 = #27272a / #18181b / #171717。
 */

/** Scratch 3 的分类色：[fill] 主色、[stroke] 描边（tertiary）。扩展积木的下拉框另有底色，文本槽不描边。 */
internal class Cat(
    val fill: Color,
    val stroke: Color,
    val dropFill: Color = fill,
    val dropStroke: Color = stroke,
    /** 文本 / 数字参数槽的描边；null = 不描边（扩展积木） */
    val inputStroke: Color? = stroke,
    val ext: Boolean = false,
)

internal object Cats {
    val Motion = Cat(Color(0xFF4C97FF), Color(0xFF3373CC))
    val Looks = Cat(Color(0xFF9966FF), Color(0xFF774DCB))
    val Events = Cat(Color(0xFFFFBF00), Color(0xFFCC9900))
    val Control = Cat(Color(0xFFFFAB19), Color(0xFFCF8B17))
    val Operators = Cat(Color(0xFF59C059), Color(0xFF389438))
    val Variables = Cat(Color(0xFFFF8C1A), Color(0xFFDB6E00))
    val Custom = Cat(Color(0xFFFF6680), Color(0xFFFF3355))
    val Pen = Cat(Color(0xFF0FBD8C), Color(0xFF0B8E69))
    val Kuke = Cat(
        fill = Color(0xFF27272A),
        stroke = Color(0xFF171717),
        dropFill = Color(0xFF18181B),
        dropStroke = Color(0xFF171717),
        inputStroke = null,
        ext = true,
    )
}

/** 两次密码不一致时「如果」里那块布尔积木的灰色（设计稿 setBool） */
internal val BoolOffFill = Color(0xFFC3C7D0)
internal val BoolOffStroke = Color(0xFFA3A8B4)

internal enum class BlockShape { Hat, Stack, Cap, CBlock, CBlockFinal, Reporter, Boolean }

internal sealed interface Part {
    class Label(val text: String) : Part
    /** 绿旗 */
    data object Flag : Part
    /** 扩展图标 + 竖分隔线（scratchblocks 的 penBlock 图标位） */
    data object ExtIcon : Part
    /** 文本 / 数字参数槽（白色胶囊） */
    class Str(val text: String) : Part
    /** 下拉参数（圆角 4 的方块 + 白三角） */
    class Drop(val text: String) : Part
    /** 空的布尔槽（六边形） */
    data object BoolSlot : Part
    /** 嵌进来的布尔积木；[tinted] 为真时颜色由「两次密码是否一致」决定 */
    class Bool(val cat: Cat, val parts: List<Part>, val tinted: Boolean = false) : Part
}

/** 一块积木。C 形积木的嘴里放 [inner]（这里最多一块，够用） */
internal class Spec(
    val shape: BlockShape,
    val cat: Cat,
    val parts: List<Part>,
    val inner: List<Spec> = emptyList(),
) {
    /** 内容指纹：文字变了才需要重新排版 / 变形 */
    val code: String by lazy { buildString { appendCode(this@Spec) } }

    private fun StringBuilder.appendCode(s: Spec) {
        append(s.shape.name).append('{')
        s.parts.forEach { appendPart(it) }
        s.inner.forEach { appendCode(it) }
        append('}')
    }

    private fun StringBuilder.appendPart(p: Part) {
        when (p) {
            is Part.Label -> append("L:").append(p.text)
            Part.Flag -> append("F")
            Part.ExtIcon -> append("I")
            is Part.Str -> append("S:").append(p.text)
            is Part.Drop -> append("D:").append(p.text)
            Part.BoolSlot -> append("B")
            is Part.Bool -> { append("<"); p.parts.forEach { appendPart(it) }; append(">") }
        }
        append('|')
    }
}

/** 表单里和积木有关的值。名字 / 昵称已按设计稿 clean() 处理过 */
internal data class AuthVars(
    val register: Boolean = false,
    val name: String = "",
    val nick: String = "",
    val pw: String = "",
    val conf: String = "",
) {
    /** 「如果」里的布尔积木：两次密码都填了且一致 → 绿色 */
    val passwordsMatch get() = pw.isNotEmpty() && pw == conf

    companion object {
        /** 去掉 scratchblocks 语法字符，最多 22 个字（同设计稿 clean） */
        fun clean(s: String): String = s.replace(Regex("""[\[\]()<>{}:\\\n]"""), "").take(22)
    }
}

internal object AuthBlocks {
    const val BRAND = "KukeChat"
    val LoginIds = listOf("hat", "say", "pw", "open", "chat")
    val RegisterIds = listOf("hat", "say", "nick", "ifc")

    private fun dots(s: String) = "•".repeat(minOf(s.length, 14))

    fun sayText(v: AuthVars): String {
        val n = v.name.ifEmpty { "Scratcher" }
        return if (v.register) "欢迎加入，$n!" else "你好，$n!"
    }

    /** 当前应该在主串里的积木（注册页的「将 [昵称] 设为」只在昵称有字时接上） */
    fun wantIds(v: AuthVars): List<String> =
        if (v.register) RegisterIds.filter { it != "nick" || v.nick.isNotEmpty() } else LoginIds

    fun spec(id: String, v: AuthVars): Spec = when (id) {
        "hat" -> Spec(BlockShape.Hat, Cats.Events, listOf(Part.Label("当"), Part.Flag, Part.Label("被点击")))
        "say" -> Spec(BlockShape.Stack, Cats.Looks, listOf(Part.Label("说"), Part.Str(sayText(v))))
        "pw" -> Spec(
            BlockShape.Stack, Cats.Variables,
            listOf(Part.Label("将"), Part.Drop("密码"), Part.Label("设为"), Part.Str(dots(v.pw))),
        )
        "open" -> Spec(
            BlockShape.Stack, Cats.Kuke,
            listOf(Part.ExtIcon, Part.Label("将"), Part.Label(BRAND), Part.Label("窗口"), Part.Drop("打开")),
        )
        "chat" -> Spec(
            BlockShape.Stack, Cats.Kuke,
            listOf(Part.ExtIcon, Part.Label("推荐添加好友"), Part.Str("1"), Part.Label("附加消息"), Part.Str("一起聊天吧!")),
        )
        "nick" -> Spec(
            BlockShape.Stack, Cats.Variables,
            listOf(Part.Label("将"), Part.Drop("昵称"), Part.Label("设为"), Part.Str(v.nick)),
        )
        "ifc" -> Spec(
            BlockShape.CBlock, Cats.Control,
            listOf(
                Part.Label("如果"),
                Part.Bool(Cats.Operators, listOf(Part.Str(dots(v.pw)), Part.Label("="), Part.Str(dots(v.conf))), tinted = true),
                Part.Label("那么"),
            ),
            inner = listOf(
                Spec(
                    BlockShape.Stack, Cats.Custom,
                    listOf(Part.Label("创建"), Part.Str(v.name.ifEmpty { "新的 $BRAND" }), Part.Label("账号")),
                ),
            ),
        )
        else -> error("unknown block $id")
    }
}

/**
 * 背景里的「虚拟积木描边」：只取外形（文字、图标去掉），1px 线、很淡、慢慢漂。
 * 形状 = 设计稿 OL_CODE（英文 scratchblocks 渲染）；摆放 = OL_LAYOUT.phone：
 * [形状, x, y, 缩放, 角度, 透明度]，坐标是工作区 dp（以 40dp 状态栏的设计稿为准）。
 */
internal object OutlineBlocks {
    val shapes: Map<String, Spec> = mapOf(
        "ifc" to Spec(BlockShape.CBlock, Cats.Control, listOf(Part.Label("if"), Part.BoolSlot, Part.Label("then"))),
        "rep" to Spec(BlockShape.Reporter, Cats.Motion, listOf(Part.Label("x"), Part.Label("position"))),
        "hat" to Spec(BlockShape.Hat, Cats.Events, listOf(Part.Label("when"), Part.Flag, Part.Label("clicked"))),
        "say" to Spec(
            BlockShape.Stack, Cats.Looks,
            listOf(Part.Label("say"), Part.Str("Hello!"), Part.Label("for"), Part.Str("2"), Part.Label("seconds")),
        ),
        "forever" to Spec(
            BlockShape.CBlockFinal, Cats.Control, listOf(Part.Label("forever")),
            inner = listOf(Spec(BlockShape.Stack, Cats.Motion, listOf(Part.Label("move"), Part.Str("10"), Part.Label("steps")))),
        ),
        "stop" to Spec(BlockShape.Cap, Cats.Control, listOf(Part.Label("stop"), Part.Drop("all"))),
    )

    class Placement(val shape: String, val x: Float, val y: Float, val scale: Float, val rotate: Float, val alpha: Float)

    val phone = listOf(
        Placement("ifc", 286f, -28f, 0.9f, 8f, 0.11f),
        Placement("rep", 302f, 56f, 0.6f, 5f, 0.17f),
        Placement("hat", 262f, 108f, 0.78f, -6f, 0.15f),
        Placement("say", -70f, 300f, 0.9f, 3f, 0.12f),
        Placement("forever", 300f, 286f, 0.82f, -6f, 0.12f),
        Placement("stop", 186f, 318f, 0.62f, 7f, 0.16f),
    )
}
