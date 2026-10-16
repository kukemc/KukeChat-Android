package ink.kuke.chat.ui.components

import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import ink.kuke.chat.core.net.Endpoints
import ink.kuke.chat.feature.shop.frameDecor
import ink.kuke.chat.ui.theme.KcTheme
import kotlin.math.absoluteValue

/** 设计稿的两条曲线：位移和出现用「平滑」，选中和按下回弹用「弹簧」 */
val EaseSmooth = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)
val EaseSpring = CubicBezierEasing(0.19f, 1.18f, 0.22f, 1f)

/** 占位头像的底色：按 id 取，和电脑版一样同一个人永远同一个颜色 */
private val AvatarTones = listOf(
    Color(0xFF1F6BFF), Color(0xFF7950F2), Color(0xFF12B886), Color(0xFFF76707),
    Color(0xFFF06595), Color(0xFF0C8599), Color(0xFF2B8A3E), Color(0xFFF59F00),
)

/**
 * 头像。[frame] 是装备的头像框（rememberAvatarFrame 取），默认不画：只在消息行、资料页、名片、联系人、私聊会话这些地方传。
 * 框画在头像边界之外、不占布局，外层别裁切。
 */
@Composable
fun Avatar(url: String?, name: String, tone: Long, size: Dp = 48.dp, modifier: Modifier = Modifier, plusTier: String? = null, frame: ink.kuke.chat.feature.shop.AvatarFrame? = null) {
    // 群升级徽记：只有 MAX 群在头像右下角画墨色小圆 + 王冠（同电脑版；Pro / Pro+ 只靠群名后的标签）
    if (plusTier == "max") {
        Box(modifier) {
            Avatar(url, name, tone, size, frame = frame)
            ink.kuke.chat.feature.groupplus.PlusAvatarBadge(plusTier, size, Modifier.align(Alignment.BottomEnd))
        }
        return
    }
    val rounded = ink.kuke.chat.feature.appearance.LocalAppearance.current.avatarShape == ink.kuke.chat.feature.appearance.AvatarShape.Rounded
    if (frame != null) {
        Box(modifier.size(size).frameDecor(frame, rounded)) {
            Avatar(url, name, tone, size)
        }
        return
    }
    val shape = if (rounded) RoundedCornerShape((size.value * 0.28f).dp) else CircleShape
    val fallback = @Composable {
        Box(Modifier.size(size).clip(shape).background(AvatarTones[(tone.absoluteValue % AvatarTones.size).toInt()]), contentAlignment = Alignment.Center) {
            Text(name.trim().take(1).ifBlank { "?" }, color = Color.White, fontSize = (size.value * 0.4f).sp, fontWeight = FontWeight.W700)
        }
    }
    val absolute = Endpoints.absolute(url)
    if (absolute == null) {
        Box(modifier) { fallback() }
        return
    }
    SubcomposeAsyncImage(
        model = absolute,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier.size(size).clip(shape),
        loading = { fallback() },
        error = { fallback() },
    )
}

/** 未读角标：一律红底白字，数字等宽，99+ 封顶；免打扰的会话是灰色 */
@Composable
fun UnreadBadge(count: Int, quiet: Boolean = false, modifier: Modifier = Modifier) {
    if (count <= 0) return
    val colors = KcTheme.colors
    Box(
        modifier
            .defaultMinSize(minWidth = 18.dp, minHeight = 18.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(if (quiet) colors.subtle else colors.unread)
            .padding(horizontal = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        // 数字滚动（设计稿「角标数字滚动」）：变大向上滚、变小向下滚
        androidx.compose.animation.AnimatedContent(
            targetState = count,
            transitionSpec = {
                val up = targetState > initialState
                (androidx.compose.animation.slideInVertically { if (up) it else -it } + androidx.compose.animation.fadeIn())
                    .togetherWith(androidx.compose.animation.slideOutVertically { if (up) -it else it } + androidx.compose.animation.fadeOut())
            },
            label = "badge",
        ) { value ->
            Text(if (value > 99) "99+" else value.toString(), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.W600, lineHeight = 18.sp)
        }
    }
}

/** 所有可点元素按下 0.96 倍（图标按钮 0.9），松手弹簧回弹 */
@Composable
fun Modifier.pressScale(interaction: MutableInteractionSource, pressed: Float = 0.96f): Modifier {
    val isPressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (isPressed) pressed else 1f, spring(dampingRatio = 0.55f, stiffness = 900f), label = "press")
    return this.graphicsLayer { scaleX = scale; scaleY = scale }
}

@Composable
fun rememberInteraction() = remember { MutableInteractionSource() }

/** 系统关了动画（开发者选项「动画时长缩放」= 0）：循环动效停下、庆祝改静态 */
@Composable
fun rememberReducedMotion(): Boolean {
    val context = androidx.compose.ui.platform.LocalContext.current
    return remember {
        runCatching {
            android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
    }
}
