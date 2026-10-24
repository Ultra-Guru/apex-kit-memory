package com.apex.memkit.core.chat

import com.apex.memkit.core.MemKitConfig
import com.apex.memkit.core.model.ChatEmotion
import com.apex.memkit.core.model.ChatFact

/**
 * 聊天信号 —— 零成本同步启发式层（自我披露 / 情绪基调 / 里程碑）。
 *
 * 三套判定均继承旧 ChatMemoryPipeline 的生产口径（含「宁可漏判不可误判」
 * 的护栏哲学），词表从硬编码提升为 [MemKitConfig.Chat] 可配项；
 * 情绪词表与宿主引擎的 ChatSignalDetector 保持同源（库无法依赖宿主
 * 类型，词表口径以配置默认值对齐）。
 */
class ChatSignals(private val config: MemKitConfig) {

    /** 日期 × 事件双命中的里程碑候选句（已过创作护栏）。 */
    fun captureMilestones(userText: String): List<String> {
        val cfg = config.chat
        val sentences = splitSentences(userText)
        return sentences
            .filterNot { s -> MILESTONE_GUARDS.any { s.contains(it) } }
            .filter { s -> DATE_PATTERN.containsMatchIn(s) && EVENT_MARKERS.any { s.contains(it) } }
            .take(cfg.maxMilestonesPerTurn)
    }

    /** 自我披露句（含标记词且含「我」）。 */
    fun captureSelfDisclosure(userText: String): List<String> {
        val cfg = config.chat
        val sentences = splitSentences(userText)
        return sentences
            .filter { it.length in 2..cfg.heuristicSentenceMaxLen && ('我' in it || 'i' in it.lowercase()) }
            .filter { s -> cfg.selfDisclosureMarkers.any { s.contains(it) } }
            .take(cfg.maxHeuristicPerTurn)
    }

    /**
     * 情绪基调滚动窗汇总。
     *
     * @param window 历史情绪序列（新→旧或旧→新均可，只影响计数）
     * @return 主导情绪 + 命中轮数的汇总句（null = 窗口空）
     */
    fun summarizeTone(window: List<ChatEmotion>): String? {
        val valid = window.filter { it != ChatEmotion.NONE }
        if (valid.isEmpty()) return null
        val counts = valid.groupingBy { it }.eachCount()
        // 平票负向优先（低落 > 焦虑 > 愤怒 > 兴奋 > 积极 > 中性）
        val dominant = counts.entries
            .sortedWith(
                compareByDescending<Map.Entry<ChatEmotion, Int>> { it.value }
                    .thenBy { TONE_PRIORITY.indexOf(it.key) },
            )
            .first()
        val label = TONE_LABELS[dominant.key] ?: dominant.key.name
        return config.chat.stateTemplate
            .replace("{emo}", label)
            .replace("{n}", dominant.value.toString())
    }

    /** 句切分（中英标点通吃）。 */
    private fun splitSentences(text: String): List<String> =
        text.split(Regex("[，。！？；\\n,.!?;]+")).map { it.trim() }.filter { it.length >= 2 }

    companion object {
        /** 情绪基调标签（检测枚举 → 用户可读中文）。 */
        val TONE_LABELS = mapOf(
            ChatEmotion.SAD to "低落",
            ChatEmotion.ANXIOUS to "焦虑",
            ChatEmotion.ANGRY to "烦躁易怒",
            ChatEmotion.EXCITED to "兴奋",
            ChatEmotion.POSITIVE to "积极",
            ChatEmotion.NEUTRAL to "平静",
        )

        private val TONE_PRIORITY = listOf(
            ChatEmotion.SAD, ChatEmotion.ANXIOUS, ChatEmotion.ANGRY,
            ChatEmotion.EXCITED, ChatEmotion.POSITIVE, ChatEmotion.NEUTRAL,
        )

        /** 日期模式（具体日期/星期/相对日/节日）。 */
        val DATE_PATTERN = Regex(
            "\\d{1,2}月\\d{1,2}[日号]|周[一二三四五六日天]|明天|后天|下周|" +
                "这周末|周末|月底|年底|元旦|春节|除夕|元宵|清明|端午|中秋|" +
                "国庆|五一|情人节|母亲节|父亲节|高考|考研",
        )

        /** 人生事件标记。 */
        val EVENT_MARKERS = listOf(
            "生日", "纪念日", "领证", "结婚", "婚礼", "订婚", "求婚", "相亲",
            "面试", "笔试", "考试", "考研", "考公", "开学", "毕业", "入职",
            "离职", "搬家", "体检", "手术", "复诊", "答辩", "开题", "交房",
            "提车", "摇号", "出发", "返程", "预产期", "放榜", "出成绩",
        )

        /** 创作护栏：这些句式里的日子是素材不是用户的日子。 */
        val MILESTONE_GUARDS = listOf(
            "帮我写", "写一篇", "写个", "写一段", "文案", "帮我翻译", "翻译成",
            "生成", "润色", "仿写", "续写", "例句", "示例", "假设",
        )
    }
}

/** 情绪词表（滚动窗入口；由宿主按需喂入 —— 库不监听对话流）。 */
object EmotionLexicon {
    private val SAD = listOf(
        "难过", "伤心", "心碎", "失落", "沮丧", "低落", "想哭", "泪目", "哭死",
        "绝望", "委屈", "心累", "好累", "累死", "累坏了", "崩溃", "崩了",
        "我emo", "emo了", "emo中", "抑郁",
    )

    private val ANXIOUS = listOf(
        "焦虑", "压力大", "压力好大", "压力太大", "紧张", "心慌", "好慌", "慌死",
        "失眠", "睡不着", "睡不好", "着急", "焦头烂额", "来不及", "提心吊胆",
        "忐忑", "发愁", "愁死", "担心死",
    )

    private val ANGRY = listOf(
        "生气", "气死", "气人", "气疯", "气炸", "愤怒", "恼火", "火大",
        "烦死了", "烦死", "受够了", "受够", "暴怒", "无语死",
    )

    private val EXCITED = listOf(
        "太好了", "开心死", "高兴坏了", "激动", "太棒了", "兴奋", "超开心",
        "好开心", "乐开花", "爽翻了", "nb了", "666", "绝了",
    )

    private val POSITIVE = listOf(
        "开心", "高兴", "快乐", "满意", "幸福", "顺利", "舒服", "舒心",
        "满意极了", "很好", "不错", "lovely", "great",
    )

    /** 任务指令护栏：命中即跳过情绪判定（内容创作不是情绪表达）。 */
    private val TASK_GUARDS = listOf(
        "帮我写", "写一篇", "写个", "翻译成", "生成", "润色", "仿写", "续写",
    )

    /**
     * 判定情绪（宁可漏判不可误判：超长文本 / 任务护栏 / 多类冲突取
     * 命中最多者，平票负向优先）。
     */
    fun detect(text: String): ChatEmotion {
        if (text.length > 200) return ChatEmotion.NONE
        if (TASK_GUARDS.any { text.contains(it) }) return ChatEmotion.NONE
        val counts = linkedMapOf(
            ChatEmotion.SAD to SAD.count { text.contains(it) },
            ChatEmotion.ANXIOUS to ANXIOUS.count { text.contains(it) },
            ChatEmotion.ANGRY to ANGRY.count { text.contains(it) },
            ChatEmotion.EXCITED to EXCITED.count { text.contains(it) },
            ChatEmotion.POSITIVE to POSITIVE.count { text.contains(it) },
        )
        val best = counts.entries
            .filter { it.value > 0 }
            .sortedWith(
                compareByDescending<Map.Entry<ChatEmotion, Int>> { it.value }
                    .thenBy { TONE_ORDER.indexOf(it.key) },
            )
            .firstOrNull() ?: return ChatEmotion.NONE
        return best.key
    }

    private val TONE_ORDER = listOf(
        ChatEmotion.SAD, ChatEmotion.ANXIOUS, ChatEmotion.ANGRY,
        ChatEmotion.EXCITED, ChatEmotion.POSITIVE,
    )
}

/**
 * 账本评分 —— 召回排序与容量淘汰的统一度量。
 *
 * `score = importance × (0.55 + 0.45 × 2^(−ageDays/14)) × (1 + 0.12 × ln(1+access))`
 * （14 天半衰 + 使用频率增益，口径沿用旧 MemoryLedgerStore）。
 */
fun scoreFact(fact: ChatFact, now: Long): Float {
    val ageDays = ((now - fact.createdAt).coerceAtLeast(0)) / 86_400_000.0
    val recency = (0.55 + 0.45 * Math.pow(2.0, -ageDays / 14.0)).toFloat()
    val frequency = (1 + 0.12 * kotlin.math.ln1p(fact.accessCount.toDouble())).toFloat()
    return fact.importance * recency * frequency
}

/** 词元 Jaccard（近重复合并判定）。 */
fun jaccard(a: String, b: String): Float {
    val sa = a.split(Regex("[\\s，。,.!?！？;；]+")).filter { it.isNotBlank() }.toSet()
    val sb = b.split(Regex("[\\s，。,.!?！？;；]+")).filter { it.isNotBlank() }.toSet()
    if (sa.isEmpty() || sb.isEmpty()) return 0f
    return sa.intersect(sb).size.toFloat() / sa.union(sb).size
}
