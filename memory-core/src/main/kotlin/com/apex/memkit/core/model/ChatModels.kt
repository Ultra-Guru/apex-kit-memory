package com.apex.memkit.core.model

import kotlinx.serialization.Serializable

/**
 * 聊天事实（ChatFact）—— 从对话中沉淀的长期用户记忆。
 *
 * 模型沿用旧 MemoryLedgerStore 的三分类（画像 / 近况 / 里程碑）与评分
 * 字段；「内容稳定哈希为 id」的幂等键设计一并保留 —— 重复披露自动强化
 * 而非重复堆积。
 */
@Serializable
data class ChatFact(
    val id: String,
    val content: String,
    val kind: Kind,
    val source: Source,
    val importance: Float,
    val credibility: Float = 1.0f,
    val createdAt: Long,
    val lastAccessedAt: Long = 0,
    val accessCount: Int = 0,
) {
    @Serializable
    enum class Kind { PROFILE, STATE, MILESTONE;

        companion object {
            /** 展示契约顺序（画像 → 近况 → 里程碑）。 */
            val DISPLAY_ORDER: List<Kind> = listOf(PROFILE, STATE, MILESTONE)

            fun safe(name: String?): Kind =
                entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: PROFILE
        }
    }

    @Serializable
    enum class Source { HEURISTIC, DISTILLED, MANUAL, MIGRATED;

        companion object {
            fun safe(name: String?): Source =
                entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: HEURISTIC
        }
    }
}

/** 待蒸馏对话轮（持久化队列元素 —— 进程被杀不丢轮次）。 */
@Serializable
data class ChatTurn(
    val userText: String,
    val assistantText: String,
    val at: Long,
)

/** 蒸馏窗口（批量送入 LLM 的输入视图，文本已按配置截断）。 */
data class DistillWindow(
    val turns: List<ChatTurn>,
) {
    fun render(cap: Int): String = turns.joinToString("\n\n") { t ->
        "User: ${t.userText.take(cap)}\nAssistant: ${t.assistantText.take(cap)}"
    }
}

/** 情绪信号（基调滚动窗的元素；NONE 不计入窗口）。 */
enum class ChatEmotion { POSITIVE, NEUTRAL, SAD, ANXIOUS, ANGRY, EXCITED, NONE }

/**
 * 事实账本摘要（记忆页 / 统计消费的轻量视图）。
 */
data class ChatLedgerStats(
    val profileCount: Int,
    val stateCount: Int,
    val milestoneCount: Int,
    val pendingDistillTurns: Int,
)
