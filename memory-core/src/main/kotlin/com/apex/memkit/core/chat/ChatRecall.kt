package com.apex.memkit.core.chat

import com.apex.memkit.core.KitClock
import com.apex.memkit.core.KitLogger
import com.apex.memkit.core.MemKitConfig
import com.apex.memkit.core.model.ChatFact
import com.apex.memkit.core.model.GraphEntity
import com.apex.memkit.core.store.MemoryStore
import com.apex.memkit.core.store.allFacts
import com.apex.memkit.core.store.enforceFactLimits
import com.apex.memkit.core.store.readGraph
import com.apex.memkit.core.store.replaceAllFacts
import com.apex.memkit.core.store.touchFacts

/**
 * 聊天召回 —— 用户文本 → 长期记忆注入段。
 *
 * 组合口径完整继承旧 ChatMemoryPipeline.recall（B 套件生产验证的形态）：
 * 画像评分 Top-10 + 关键词直接命中 ≤4（去重合并）+ 近况最新 1 条 +
 * 里程碑最近 5 条 + 图谱相关实体 ≤3（各 ≤3 条观察），总长钳制
 * [MemKitConfig.Chat.recallMaxChars]；被召回条目触达（越用越牢）。
 *
 * 返回体**不含**「## Remembered About You」标题 —— 引擎侧提示词模板
 * 负责包裹（与旧契约一致）。
 */
class ChatRecall(
    private val store: MemoryStore,
    private val config: MemKitConfig,
    private val clock: KitClock,
    @Suppress("unused") private val logger: KitLogger,
) {

    /** 召回产物：渲染文本 + 触达的账本条目 id（持久化触达由调用方提交）。 */
    data class RecallResult(val rendered: String, val touchedFactIds: Set<String>)

    /** 召回主入口。无可召回内容返回 null（提示词段落省略）。 */
    suspend fun recall(userText: String): RecallResult? = runCatching {
        val now = clock.now()
        val facts = store.allFacts()
        val cfg = config.chat

        val profile = facts.filter { it.kind == ChatFact.Kind.PROFILE }
        val byScore = profile.sortedByDescending { scoreFact(it, now) }
        val top = byScore.take(cfg.recallTopProfile).mapTo(mutableListOf()) { it }

        // 关键词直接命中补充（去重合并，≤ recallKeywordHits）
        val keywords = userText.split(Regex("[\\s，。,.！？!?；;]+"))
            .map { it.trim() }
            .filter { it.length >= 2 }
            .toSet()
        if (keywords.isNotEmpty()) {
            for (fact in byScore) {
                if (top.size >= cfg.recallTopProfile + cfg.recallKeywordHits) break
                if (fact in top) continue
                if (keywords.any { fact.content.contains(it) }) top.add(fact)
            }
        }

        val state = facts.filter { it.kind == ChatFact.Kind.STATE }
            .maxByOrNull { it.createdAt }
        val milestones = facts.filter { it.kind == ChatFact.Kind.MILESTONE }
            .sortedByDescending { it.createdAt }
            .take(cfg.recallRecentMilestones)

        val related = relatedGraphEntities(userText)

        val touched = (top.map { it.id } + listOfNotNull(state?.id) + milestones.map { it.id }).toSet()
        val rendered = render(top, state, milestones, related)
        if (rendered == null || touched.isEmpty()) null else RecallResult(rendered, touched)
    }.getOrNull()

    /** 召回触达提交（评分的频率增益输入；失败忽略）。 */
    suspend fun commitTouch(ids: Set<String>) {
        runCatching { store.touchFacts(ids) }
    }

    private suspend fun relatedGraphEntities(userText: String): List<GraphEntity> {
        val (entities, _) = store.readGraph()
        if (entities.isEmpty()) return emptyList()
        val lowered = userText.lowercase()
        return entities
            .map { entity ->
                var hits = 0
                if (entity.name.isNotBlank() && lowered.contains(entity.name.lowercase())) hits++
                for (obs in entity.observations) {
                    if (obs.isNotBlank() && lowered.contains(obs.lowercase())) hits++
                }
                entity to hits
            }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(config.chat.recallGraphEntities)
            .map { it.first }
    }

    private fun render(
        top: List<ChatFact>,
        state: ChatFact?,
        milestones: List<ChatFact>,
        related: List<GraphEntity>,
    ): String? = buildString {
        if (top.isNotEmpty()) {
            appendLine("### 用户画像")
            top.forEach { appendLine("- ${it.content}") }
        }
        if (state != null) {
            if (isNotEmpty()) appendLine()
            appendLine("### 用户近况")
            appendLine("- ${state.content}")
        }
        if (milestones.isNotEmpty()) {
            if (isNotEmpty()) appendLine()
            appendLine("### 重要时刻")
            milestones.forEach { appendLine("- ${it.content}") }
        }
        if (related.isNotEmpty()) {
            if (isNotEmpty()) appendLine()
            appendLine("### 相关记忆")
            related.forEach { entity ->
                entity.observations.take(3).forEach { appendLine("- [${entity.name}] $it") }
            }
        }
    }.trim().takeIf { it.isNotEmpty() }?.take(config.chat.recallMaxChars)
}

/**
 * 账本治理 —— 近重复合并 + 容量淘汰（蒸馏批落账后调用）。
 *
 * 合并口径沿用旧 MemoryLedgerStore.consolidate：词元 Jaccard ≥ 0.75 的
 * 条目合并（importance 取 max + 增益、accessCount 求和、createdAt 取早）；
 * 容量超限按评分升序淘汰。
 */
object ChatConsolidator {

    /** @return (合并数, 淘汰数) */
    suspend fun consolidate(store: MemoryStore, config: MemKitConfig, clock: KitClock): Pair<Int, Int> {
        val now = clock.now()
        val facts = store.allFacts()
        var merged = 0

        // 近重复合并（同类别内比较，PROFILE 为主战场）：保留高分者，吸收低分孪生
        val byScore = facts.sortedByDescending { scoreFact(it, now) }
        val keep = ArrayList<ChatFact>(facts.size)
        for (fact in byScore) {
            val twin = keep.firstOrNull {
                it.kind == fact.kind && jaccard(it.content, fact.content) >= 0.75f
            }
            if (twin == null) {
                keep.add(fact)
            } else {
                keep[keep.indexOf(twin)] = twin.copy(
                    importance = (maxOf(twin.importance, fact.importance) + 0.05f).coerceAtMost(1f),
                    accessCount = twin.accessCount + fact.accessCount,
                    createdAt = minOf(twin.createdAt, fact.createdAt),
                )
                merged++
            }
        }
        if (merged > 0) store.replaceAllFacts(keep)

        val caps = mapOf(
            ChatFact.Kind.PROFILE to config.limits.maxChatFactsProfile,
            ChatFact.Kind.MILESTONE to config.limits.maxChatFactsMilestone,
        )
        val evicted = store.enforceFactLimits(caps) { scoreFact(it, now) }
        return merged to evicted
    }
}
