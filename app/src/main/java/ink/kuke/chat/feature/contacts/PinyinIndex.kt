package ink.kuke.chat.feature.contacts

import java.text.Collator
import java.util.Locale

/**
 * 联系人分组用的「拼音首字母」。
 * 没有引入拼音库：中文字用系统的中文排序规则（拼音序）和 23 个声母边界字比较，落进 A–Z 的桶里；
 * 英文字母取大写；数字、符号、emoji 一律归 #。中文排在一起，同一个桶里再按拼音排序。
 */
object PinyinIndex {
    /** 每个声母桶的第一个汉字（拼音序），I、U、V 没有字 */
    private val boundaries = listOf(
        'A' to '阿', 'B' to '八', 'C' to '嚓', 'D' to '哒', 'E' to '妸', 'F' to '发', 'G' to '旮', 'H' to '哈',
        'J' to '讥', 'K' to '咔', 'L' to '垃', 'M' to '痳', 'N' to '拏', 'O' to '噢', 'P' to '妑', 'Q' to '七',
        'R' to '呥', 'S' to '扨', 'T' to '它', 'W' to '穵', 'X' to '夕', 'Y' to '丫', 'Z' to '帀',
    )

    private val collator: Collator by lazy { Collator.getInstance(Locale.CHINA).apply { strength = Collator.PRIMARY } }

    /** 一个名字的分组字母：A–Z 或 # */
    fun letterOf(name: String): Char {
        val first = name.trim().firstOrNull() ?: return '#'
        return when {
            first in 'a'..'z' -> first.uppercaseChar()
            first in 'A'..'Z' -> first
            first in '一'..'龥' -> chineseLetter(first)
            else -> '#'
        }
    }

    private fun chineseLetter(ch: Char): Char {
        val text = ch.toString()
        var result = '#'
        for ((letter, boundary) in boundaries) {
            if (collator.compare(text, boundary.toString()) >= 0) result = letter else break
        }
        return result
    }

    /** 同一个桶里的排序 */
    fun compare(a: String, b: String): Int = collator.compare(a, b)

    /** 右侧快速索引显示的字母（固定 A–Z + #，不在这个集合里的不画） */
    val indexLetters: List<Char> = ('A'..'Z').toList() + '#'
}
