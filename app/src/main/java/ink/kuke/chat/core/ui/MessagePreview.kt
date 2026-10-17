package ink.kuke.chat.core.ui

import ink.kuke.chat.core.model.Message
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive

/** 一条消息在会话列表、通知里的一行预览（同 KukeChat-Client 的 lastMessagePreview） */
fun messagePreview(message: Message): String {
    if (message.isRecalled) return "撤回了一条消息"
    val meta = message.metadata
    return when (message.type) {
        "image" -> if (meta?.get("sticker")?.jsonPrimitive?.booleanOrNull == true || message.content.contains("/stickers/")) "[表情]" else "[图片]"
        "voice" -> "[语音]"
        "poll" -> "[投票]"
        "red_packet" -> {
            val packet = meta?.get("red_packet") as? JsonObject
            val chain = (packet?.get("chain_text") as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content?.isNotBlank() == true
            val exclusive = (packet?.get("target_user_id") as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull() != null
            when {
                exclusive && chain -> "[专属接龙红包]"
                exclusive -> "[专属红包]"
                chain -> "[接龙红包]"
                else -> "[红包]"
            }
        }
        "gift" -> message.content.ifBlank { "[礼物]" }
        "forward_bundle" -> "[聊天记录]"
        else -> when {
            meta?.get("share_card") is JsonObject -> "[名片] " + message.content.take(40)
            message.content.isBlank() && meta?.get("images") != null -> "[图片]"
            else -> stripMarkdown(message.content).take(120)
        }
    }
}

private val headingMark = Regex("""(?m)^\s{0,3}#{1,6}\s+""")
private val quoteMark = Regex("""(?m)^\s{0,3}>\s?""")
private val listMark = Regex("""(?m)^\s*(?:[-*+]|\d+\.)\s+""")
private val emphasis = Regex("""(\*\*|__|~~|`{1,3})""")
private val link = Regex("""\[([^\]]*)]\(([^)]*)\)""")

/** 预览里不显示 Markdown 符号（机器人消息常带 # 标题、**加粗**、链接） */
fun stripMarkdown(text: String): String = text
    // 机器人正文里的 <markdown> 包裹去掉、<at id="12"/> 换成 @（预览里没有成员名可查）
    .replace(Regex("""<\s*/?\s*(markdown|md)\b[^<>]*>""", RegexOption.IGNORE_CASE), "")
    .replace(Regex("""<\s*(at_all|mention_all)\b[^<>]*>""", RegexOption.IGNORE_CASE), "@全体成员")
    .replace(Regex("""<\s*(at|mention)\b([^<>]*?)/?\s*>""", RegexOption.IGNORE_CASE)) { m ->
        Regex("""name\s*=\s*["']([^"']+)["']""").find(m.groupValues[2])?.let { "@" + it.groupValues[1] } ?: "@"
    }
    .replace(link, "$1")
    .replace(headingMark, "")
    .replace(quoteMark, "")
    .replace(listMark, "")
    .replace(emphasis, "")
    .replace(Regex("""\s+"""), " ")
    .trim()
