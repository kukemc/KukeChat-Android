package ink.kuke.chat.feature.chat

/**
 * 表情回应面板的表情数据（精选常用，按分类排）。
 *
 * 电脑版有一份一模一样的列表：KukeChat-Client/src/components/chat/desktop/reactions/reactionEmojis.ts
 * 两边必须同步改 —— 增删、调顺序、改分类名都一起改，保证两端看到的面板一致。
 *
 * 只收 Emoji 12 及更早的字符（Unicode 13 起的 🥲 🤌 🧋 之类在老安卓和 Windows 10 上是方框）。
 * 后端 validate_reaction_emoji 接受任意单个 emoji（含肤色、ZWJ、国旗、keycap），这里不用迁就后端。
 * 每个分类一行行写，空格分开，读的时候拆开。
 */
data class ReactionCategory(val id: String, val title: String, val icon: String, val emojis: List<String>)

/** 没有最近使用记录时「常用」页显示的默认表情（两端原来的快捷回应合在一起）。 */
val DefaultFrequentReactions: List<String> = "👍 ❤️ 😂 🎉 🤔 😭 😘 🙏 🔥 😮 😢 👏 😍 🤣 👀 💯 😎 🥳 😅 🤝 💪 😡 ❔ ✨".split(' ')

/** 最近使用最多记几个。 */
const val MaxRecentReactions = 16

private fun split(source: String): List<String> = source.trim().split(Regex("\\s+"))

val ReactionCategories: List<ReactionCategory> = listOf(
    ReactionCategory(
        "smileys", "笑脸与情绪", "😀",
        split(
            """
            😀 😃 😄 😁 😆 😅 🤣 😂 🙂 🙃 😉 😊 😇 🥰 😍 🤩 😘 😚 😋 😛 😜 🤪 😝 🤑
            🤗 🤭 🤫 🤔 🤐 🤨 😐 😑 😶 😏 😒 🙄 😬 😌 😔 😪 🤤 😴 😷 🤒 🤕 🤢 🤮 🤧
            🥵 🥶 🥴 😵 🤯 🤠 🥳 😎 🤓 🧐 😕 😟 🙁 😮 😯 😲 😳 🥺 😦 😨 😰 😥 😢 😭
            😱 😖 😣 😞 😓 😩 😫 🥱 😤 😡 😠 🤬 😈 💀 💩 🤡 👻 👽 🤖 😺 😹 😻 🙈 🙊
            """,
        ),
    ),
    ReactionCategory(
        "people", "手势与人物", "👋",
        split(
            """
            👋 🤚 🖐️ ✋ 👌 🤏 ✌️ 🤞 🤟 🤘 🤙 👈 👉 👆 👇 ☝️ 👍 👎 ✊ 👊 🤛 🤜 👏 🙌
            👐 🤲 🤝 🙏 ✍️ 💅 🤳 💪 🧠 👀 👅 👄 💋 👶 👦 👧 🧑 👨 👩 👴 👵 🙋 🙅 🙆
            💁 🤷 🤦 🙇 💃 🕺 🏃 🧘 👫 👼 🎅 🦸 🧙 🧚
            """,
        ),
    ),
    ReactionCategory(
        "symbols", "爱心与符号", "❤️",
        split(
            """
            ❤️ 🧡 💛 💚 💙 💜 🤎 🖤 🤍 💔 ❣️ 💕 💞 💓 💗 💖 💘 💝 💟 💌 💯 💢 💥 💫
            💦 💨 💬 💭 💤 ✨ ⭐ 🌟 ⚡ 🔥 ✅ ✔️ ❌ ⭕ 🚫 ⛔ ❓ ❔ ❗ ‼️ ⁉️ ⚠️ ♻️ 🆗
            🆒 🆕 🆘 🔝 🔴 🟡 🟢 🔵 ➕ ➖ 🎵 🎶 🔔 1️⃣ 2️⃣ 3️⃣ 6️⃣ 🔟 ㊗️ 🉐
            """,
        ),
    ),
    ReactionCategory(
        "nature", "动物与自然", "🐱",
        split(
            """
            🐶 🐱 🐭 🐹 🐰 🦊 🐻 🐼 🐨 🐯 🦁 🐮 🐷 🐸 🐵 🐔 🐧 🐤 🦆 🦉 🐺 🦄 🐝 🦋
            🐌 🐞 🐢 🐍 🐙 🦀 🐠 🐬 🐳 🦈 🐘 🦒 🐑 🦌 🐾 🌵 🎄 🌳 🌱 🍀 🍁 🍂 🍄 🌷
            🌹 🥀 🌸 🌻 🌞 🌝 🌚 🌙 🌎 🪐 ☀️ ⛅ ☁️ 🌧️ ⛈️ ❄️ ☃️ 🌈 🌊 💧 ☔ 🌪️
            """,
        ),
    ),
    ReactionCategory(
        "food", "食物", "🍉",
        split(
            """
            🍎 🍊 🍋 🍌 🍉 🍇 🍓 🍒 🍑 🥭 🍍 🥝 🍅 🥑 🍆 🌽 🌶️ 🥦 🍞 🥐 🧀 🍳 🍗 🍖
            🌭 🍔 🍟 🍕 🌮 🍝 🍜 🍲 🍛 🍣 🍱 🥟 🍤 🍙 🍚 🍢 🍡 🍧 🍦 🧁 🍰 🎂 🍮 🍭
            🍬 🍫 🍿 🍩 🍪 🥛 ☕ 🍵 🧃 🥤 🍺 🍻 🥂 🍷 🍸 🍹 🍾 🥢
            """,
        ),
    ),
    ReactionCategory(
        "activity", "活动与庆祝", "🎉",
        split(
            """
            🎉 🎊 🎈 🎁 🎀 🎆 🎇 🧨 🎃 🏮 🧧 🏆 🥇 🥈 🥉 🏅 ⚽ 🏀 🏈 ⚾ 🎾 🏐 🏓 🏸
            🥊 🎳 🎯 🎱 🎮 🕹️ 🎲 🧩 🀄 🃏 🎨 🎭 🎤 🎧 🎹 🥁 🎷 🎸 🎻 🎬 🏊 🚴 🏂 🏋️
            """,
        ),
    ),
    ReactionCategory(
        "objects", "物品", "💡",
        split(
            """
            📱 💻 ⌨️ 🖥️ 📷 📹 📺 ⏰ ⌛ 🔋 💡 🕯️ 💰 💵 💎 🔧 🔨 ⚙️ 💣 🔮 💊 💉 🧸 🛒
            🎒 👓 🕶️ 👑 🎩 👗 👟 💍 💄 ☂️ 📦 ✉️ 📝 📖 📚 📌 📎 ✂️ 🔒 🔑 🚪 🛁 🚗 🚲
            ✈️ 🚀 🛸 🚢 🏠 🏫 🏰 🗼 ⛺ 🌃 🎪 🧭
            """,
        ),
    ),
    ReactionCategory(
        "flags", "国旗", "🚩",
        split(
            """
            🇨🇳 🇭🇰 🇲🇴 🇯🇵 🇰🇷 🇸🇬 🇺🇸 🇬🇧 🇫🇷 🇩🇪 🇮🇹 🇷🇺 🏁 🚩 🏳️ 🏴
            """,
        ),
    ),
)
