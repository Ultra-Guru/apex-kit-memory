package com.apex.memkit.core.chat

import com.apex.memkit.core.ChatModel
import com.apex.memkit.core.KitClock
import com.apex.memkit.core.KitLogger
import com.apex.memkit.core.MemKitConfig
import com.apex.memkit.core.model.ChatFact
import com.apex.memkit.core.model.ChatTurn
import com.apex.memkit.core.store.MemoryStore
import com.apex.memkit.core.store.addOrBumpFact
import com.apex.memkit.core.store.allFacts
import com.apex.memkit.core.store.dropQueue
import com.apex.memkit.core.store.peekQueue
import com.apex.memkit.core.store.removeFact
import com.apex.memkit.core.store.upsertFactFromDistill
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 聊天蒸馏器 —— 对话窗口 → 长期事实的 LLM 提取协议。
 *
 * 协议完整继承旧 MemoryDistiller 的生产纪律（这是 B 套件最有价值的资产）：
 * - **批量窗口**：一次调用提取整个待蒸馏窗口（多轮上下文）；
 * - **写入门禁**：负面清单（常识 / 一次性任务 / 未来计划 / 助手发言），
 *   无长期价值信号输出 `{"skip":true}` 而非硬凑；
 * - **更新优先**：账本 Top-12 事实注入提示词作参照，同主题演进输出
 *   update（old 逐字复制 + content 完整替换）而非 new；new 限额 3 条；
 * - **防御式解析**：截取首个 `{` 到最后一个 `}`，逐字段形状校验，任何
 *   异常折叠为 null（蒸馏失败只是少记一次，队列保留重试，绝无副作用）。
 *
 * 平台差异：模型调用经 [ChatModel] 端口注入（旧实现直连宿主
 * ModelRuntime —— 库化的唯一接缝变化）。
 */
class ChatDistiller(
    private val store: MemoryStore,
    private val config: MemKitConfig,
    private val clock: KitClock,
    private val logger: KitLogger,
) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 结构化提取结果。 */
    data class DistillOutcome(
        val newFacts: List<String>,
        val updates: List<MemoryUpdate>,
        val skipped: Boolean,
    ) {
        companion object {
            internal val SKIP = DistillOutcome(emptyList(), emptyList(), skipped = true)
        }
    }

    /** 一条事实更新：old = 账本原文（精确），content = 替换后完整内容。 */
    data class MemoryUpdate(val oldContent: String, val newContent: String)

    /**
     * 执行一次批量蒸馏。
     *
     * 成功（含 skip）→ 队列消费 + 落账 + 容量治理；失败（模型不可用 /
     * 解析异常）→ null，队列保留待重试。
     */
    suspend fun distillOnce(model: ChatModel): DistillOutcome? {
        val window = store.peekQueue(config.chat.distillBatchTurns)
        if (window.size < config.chat.minDistillTurns) return DistillOutcome.SKIP

        val existing = store.allFacts()
            .filter { it.kind == ChatFact.Kind.PROFILE }
            .sortedByDescending { scoreFact(it, now()) }
            .take(MAX_EXISTING_FACTS)
        val outcome = runCatching {
            val prompt = buildPrompt(window, existing.map { it.content })
            val response = model.complete(SYSTEM_PROMPT, prompt) ?: return null
            parseOutcome(response)
        }.getOrNull() ?: return null

        applyOutcome(outcome, existing)
        store.dropQueue(window)
        return outcome
    }

    /** 落账：new → 幂等新增；update → old 逐字命中后整条替换。 */
    private suspend fun applyOutcome(outcome: DistillOutcome, existing: List<ChatFact>) {
        val now = now()
        for (content in outcome.newFacts) {
            store.addOrBumpFact(
                content = content,
                kind = ChatFact.Kind.PROFILE,
                source = ChatFact.Source.DISTILLED,
                importance = DISTILL_IMPORTANCE,
            )
        }
        for (u in outcome.updates) {
            val hit = existing.firstOrNull { it.content == u.oldContent }
                ?: store.allFacts().firstOrNull { it.content == u.oldContent }
            if (hit == null) {
                // old 未逐字命中 → 按新增处理（内容仍有价值，不丢）
                store.addOrBumpFact(u.newContent, ChatFact.Kind.PROFILE, ChatFact.Source.DISTILLED, DISTILL_IMPORTANCE)
            } else {
                store.removeFact(hit.id)
                store.upsertFactFromDistill(
                    hit.copy(
                        content = u.newContent.trim(),
                        source = ChatFact.Source.DISTILLED,
                        lastAccessedAt = now,
                    ),
                )
            }
        }
    }

    /** 组装批量提取提示词（写入门禁 + 参照事实 + 对话窗口）。 */
    internal fun buildPrompt(window: List<ChatTurn>, existingFacts: List<String>): String = buildString {
        appendLine("从下面整个对话窗口中提取「值得长期记住的用户事实」。")
        appendLine()
        appendLine("【写入门槛 —— 先过筛，再提取】")
        appendLine("- 只记录用户特异且可复用的稳定信息：身份、职业、偏好、长期目标、约束、常用工具、重要的人际关系；")
        appendLine("- 不记录：常识或公开定义（比如某技术是什么）、一次性任务细节、未来计划或 TODO、助手说过的话；")
        appendLine("- 窗口里没有长期价值信号时，输出 {\"skip\": true}，不要硬凑。")
        appendLine()
        appendLine("【更新优先于新增】")
        appendLine("- 「已有记忆」列表是当前账本：新事实若与某条同主题且信息有演进，输出 update（old 给出该条原文，content 给出替换后的完整内容），不要 new；")
        appendLine("- 只有账本里完全没有的全新概念才输出 new，最多 ${config.chat.maxFactsPerDistill} 条，每条 ≤ 40 字，第三人称（以「用户」开头）；")
        appendLine("- old 必须逐字复制「已有记忆」里的某一条，不允许改写。")
        appendLine()
        appendLine("【输出格式 —— 严格 JSON，不要任何其他文字或代码块标记】")
        appendLine("{\"skip\": true}")
        appendLine("或")
        appendLine("{\"new\": [\"…\", \"…\"], \"update\": [{\"old\": \"已有记忆原文\", \"content\": \"替换后的完整内容\"}]}")
        if (existingFacts.isNotEmpty()) {
            appendLine()
            appendLine("【已有记忆】")
            existingFacts.take(MAX_EXISTING_FACTS).forEach { fact ->
                appendLine("- ${fact.take(MAX_FACT_DISPLAY_LENGTH)}")
            }
        }
        appendLine()
        appendLine("【对话窗口（共 ${window.size} 轮）】")
        window.forEachIndexed { index, turn ->
            appendLine("#${index + 1} 用户：${turn.userText.take(config.chat.distillTurnTextCap)}")
            appendLine("   助手：${turn.assistantText.take(config.chat.distillTurnTextCap)}")
        }
    }

    /**
     * 防御式解析：截取花括号包围区段内的 JSON 对象，逐字段形状校验。
     * skip=true 或无有效内容 → SKIP；解析失败 → null。
     */
    internal fun parseOutcome(raw: String): DistillOutcome? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching {
            val obj = json.parseToJsonElement(raw.substring(start, end + 1)).jsonObject
            if (obj["skip"]?.jsonPrimitive?.content == "true") return DistillOutcome.SKIP
            val newFacts = obj["new"]?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.content.takeIf { c -> c.isNotBlank() } }
                ?.take(config.chat.maxFactsPerDistill)
                .orEmpty()
            val updates = obj["update"]?.jsonArray?.mapNotNull { el ->
                val o = el.jsonObject
                val old = o["old"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val content = o["content"]?.jsonPrimitive?.content ?: return@mapNotNull null
                if (old.isBlank() || content.isBlank()) null else MemoryUpdate(old, content)
            }.orEmpty()
            if (newFacts.isEmpty() && updates.isEmpty()) DistillOutcome.SKIP
            else DistillOutcome(newFacts, updates, skipped = false)
        }.getOrNull()
    }

    private fun now(): Long = clock.now()

    companion object {
        internal const val SYSTEM_PROMPT = "你是对话事实提取器，只输出合法 JSON。"
        internal const val MAX_EXISTING_FACTS = 12
        internal const val MAX_FACT_DISPLAY_LENGTH = 80
        internal const val DISTILL_IMPORTANCE = 0.6f
    }
}
