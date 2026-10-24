package com.apex.memkit.core.store

import com.apex.memkit.core.KitLogger
import com.apex.memkit.core.model.ChatFact
import com.apex.memkit.core.model.ChatTurn
import com.apex.memkit.core.model.GraphEntity
import com.apex.memkit.core.model.GraphRelation
import com.apex.memkit.core.model.GraphSearchResult
import com.apex.memkit.core.model.QuarantineEntry
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import java.security.MessageDigest

/**
 * 聊天账本 + 知识图谱 + 隔离名单的操作面（[MemoryStore] 的扩展文档配套）。
 *
 * 三组状态本体住在 [MemoryStore]（与 episodes/nodes/macros 同一条单写者
 * mutex 纪律），本文件只承载业务操作与持久化文档形状。
 */

/** chat.json 文档形状。 */
@Serializable
internal data class ChatDoc(
    val facts: List<ChatFact> = emptyList(),
    val queue: List<ChatTurn> = emptyList(),
)

/** graph.json 文档形状（对齐官方 memory MCP 的图谱语义）。 */
@Serializable
internal data class GraphDoc(
    val entities: List<GraphEntity> = emptyList(),
    val relations: List<GraphRelation> = emptyList(),
)

// ═══ 聊天事实账本 ═════════════════════════════════════════════════

/** 幂等添加结果：added = 新增；bumped = 同 id 已存在（重要性抬升 / 访问刷新）。 */
data class FactAddResult(val added: Boolean, val bumped: Boolean)

/** 内容规范化（幂等键成分）：压缩空白 + 去句尾标点。 */
internal fun normalizeContent(content: String): String =
    content.trim().replace(Regex("\\s+"), " ")

/** 事实幂等键：kind + 规范化内容的稳定哈希（同义重复披露自动归并）。 */
internal fun factId(kind: ChatFact.Kind, content: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest((kind.name + "|" + normalizeContent(content)).toByteArray())
    return digest.joinToString("") { "%02x".format(it) }.take(16)
}

/**
 * 幂等添加事实：同 id 条目 → importance 取 max、createdAt 取最早、
 * accessCount 累加（重复披露 = 强化信号）。
 */
suspend fun MemoryStore.addOrBumpFact(
    content: String,
    kind: ChatFact.Kind,
    source: ChatFact.Source,
    importance: Float,
): FactAddResult = mutex.withLock {
    val id = factId(kind, content)
    val now = clock.now()
    val existing = chatFacts.indexOfFirst { it.id == id }
    if (existing >= 0) {
        val cur = chatFacts[existing]
        chatFacts[existing] = cur.copy(
            importance = maxOf(cur.importance, importance),
            accessCount = cur.accessCount + 1,
            lastAccessedAt = now,
        )
        markDirty(DOC_CHAT)
        return@withLock FactAddResult(added = false, bumped = true)
    }
    chatFacts.add(
        ChatFact(
            id = id,
            content = normalizeContent(content),
            kind = kind,
            source = source,
            importance = importance,
            createdAt = now,
            lastAccessedAt = now,
            accessCount = 1,  // 创建即首次触达
        ),
    )
    markDirty(DOC_CHAT)
    FactAddResult(added = true, bumped = false)
}

/** 近况基调：单条有界替换（STATE 类容量 = 1 的语义化表达）。 */
suspend fun MemoryStore.replaceStateFact(summary: String, at: Long) = mutex.withLock {
    chatFacts.removeAll { it.kind == ChatFact.Kind.STATE }
    chatFacts.add(
        ChatFact(
            id = factId(ChatFact.Kind.STATE, summary),
            content = normalizeContent(summary),
            kind = ChatFact.Kind.STATE,
            source = ChatFact.Source.HEURISTIC,
            importance = 0.5f,
            createdAt = at,
            lastAccessedAt = at,
        ),
    )
    markDirty(DOC_CHAT)
}

/** 账本全量快照（展示 / 迁移用）。 */
suspend fun MemoryStore.allFacts(): List<ChatFact> = mutex.withLock { chatFacts.toList() }

/** 蒸馏 upsert：按 id 替换（update 语义），无则新增。 */
suspend fun MemoryStore.upsertFactFromDistill(fact: ChatFact): Boolean = mutex.withLock {
    val idx = chatFacts.indexOfFirst { it.id == fact.id }
    if (idx >= 0) chatFacts[idx] = fact else chatFacts.add(fact)
    markDirty(DOC_CHAT)
    idx < 0
}

suspend fun MemoryStore.removeFact(id: String): Boolean = mutex.withLock {
    val removed = chatFacts.removeAll { it.id == id }
    if (removed) markDirty(DOC_CHAT)
    removed
}

/** 原子替换全量事实（治理 / 迁移专用；队列不受影响）。 */
suspend fun MemoryStore.replaceAllFacts(facts: List<ChatFact>) = mutex.withLock {
    chatFacts.clear()
    chatFacts.addAll(facts)
    markDirty(DOC_CHAT)
}

/** 清空指定类别（返回清除数）。 */
suspend fun MemoryStore.clearFacts(kinds: Set<ChatFact.Kind>): Int = mutex.withLock {
    val before = chatFacts.size
    chatFacts.removeAll { it.kind in kinds }
    val removed = before - chatFacts.size
    if (removed > 0) markDirty(DOC_CHAT)
    removed
}

/** 召回触达（访问计数 + 时间刷新 —— 越用越牢的评分输入）。 */
suspend fun MemoryStore.touchFacts(ids: Collection<String>) = mutex.withLock {
    if (ids.isEmpty()) return@withLock
    val now = clock.now()
    for (i in chatFacts.indices) {
        val f = chatFacts[i]
        if (f.id in ids) chatFacts[i] = f.copy(accessCount = f.accessCount + 1, lastAccessedAt = now)
    }
    markDirty(DOC_CHAT)
}

/** 类别容量治理：PROFILE/MILESTONE 超限按评分升序淘汰；STATE 由 replace 语义天然有界。 */
suspend fun MemoryStore.enforceFactLimits(
    caps: Map<ChatFact.Kind, Int>,
    scorer: (ChatFact) -> Float,
): Int = mutex.withLock {
    var removed = 0
    for ((kind, cap) in caps) {
        val ofKind = chatFacts.filter { it.kind == kind }
        if (ofKind.size > cap) {
            val dropIds = ofKind.sortedBy(scorer).take(ofKind.size - cap).mapTo(mutableSetOf()) { it.id }
            chatFacts.removeAll { it.id in dropIds }
            removed += dropIds.size
        }
    }
    if (removed > 0) markDirty(DOC_CHAT)
    removed
}

// ═══ 蒸馏队列（持久化：进程被杀不丢轮次）════════════════════════════

suspend fun MemoryStore.enqueueTurn(turn: ChatTurn) = mutex.withLock {
    chatQueue.add(turn)
    while (chatQueue.size > config.chat.distillQueueCapacity) chatQueue.removeAt(0)
    markDirty(DOC_CHAT)
}

suspend fun MemoryStore.pendingTurnCount(): Int = mutex.withLock { chatQueue.size }

/** 取走待蒸馏窗口（不清队；成功后 [dropQueue] 提交、失败保留重试）。 */
suspend fun MemoryStore.peekQueue(max: Int): List<ChatTurn> = mutex.withLock {
    chatQueue.take(max)
}

/** 蒸馏成功：移除已消费轮（按对象等值匹配）。 */
suspend fun MemoryStore.dropQueue(consumed: List<ChatTurn>) = mutex.withLock {
    chatQueue.removeAll(consumed.toSet())
    markDirty(DOC_CHAT)
}

// ═══ 知识图谱（memory MCP 语义）════════════════════════════════════

/** 建实体（幂等：同名实体合并 observations，去重）。返回实际新增的实体名。 */
suspend fun MemoryStore.upsertGraphEntities(
    entities: List<GraphEntity>,
): List<String> = mutex.withLock {
    val added = mutableListOf<String>()
    for (e in entities) {
        val cur = graphEntities[e.name]
        if (cur == null) {
            graphEntities[e.name] = e
            added.add(e.name)
        } else if (cur.entityType != e.entityType || cur.observations != e.observations) {
            graphEntities[e.name] = cur.copy(
                entityType = e.entityType,
                observations = (cur.observations + e.observations).distinct(),
            )
        }
    }
    if (added.isNotEmpty()) markDirty(DOC_GRAPH)
    added
}

/** 建关系（三元组整体去重）。返回实际新增数。 */
suspend fun MemoryStore.addGraphRelations(relations: List<GraphRelation>): Int = mutex.withLock {
    var added = 0
    for (r in relations) {
        if (graphRelations.none { it == r }) {
            graphRelations.add(r)
            added++
        }
    }
    if (added > 0) markDirty(DOC_GRAPH)
    added
}

/** 追加观察（实体不存在 → 返回 false，MCP 侧转错误消息）。 */
suspend fun MemoryStore.addObservations(entityName: String, contents: List<String>): Boolean =
    mutex.withLock {
        val cur = graphEntities[entityName] ?: return@withLock false
        val merged = (cur.observations + contents).distinct()
        if (merged.size != cur.observations.size) {
            graphEntities[entityName] = cur.copy(observations = merged)
            markDirty(DOC_GRAPH)
        }
        true
    }

/** 删观察（幂等，返回实际删除数）。 */
suspend fun MemoryStore.deleteObservations(entityName: String, contents: List<String>): Int =
    mutex.withLock {
        val cur = graphEntities[entityName] ?: return@withLock 0
        val remaining = cur.observations.filterNot { it in contents.toSet() }
        val removed = cur.observations.size - remaining.size
        if (removed > 0) {
            graphEntities[entityName] = cur.copy(observations = remaining)
            markDirty(DOC_GRAPH)
        }
        removed
    }

/** 删实体（级联删边；返回是否删到）。 */
suspend fun MemoryStore.deleteGraphEntities(names: List<String>): Boolean = mutex.withLock {
    val nameSet = names.toSet()
    val before = graphEntities.size
    graphEntities.keys.retainAll { it !in nameSet }
    val edgesBefore = graphRelations.size
    graphRelations.removeAll { it.from in nameSet || it.to in nameSet }
    val changed = before != graphEntities.size || edgesBefore != graphRelations.size
    if (changed) markDirty(DOC_GRAPH)
    changed
}

suspend fun MemoryStore.readGraph(): Pair<List<GraphEntity>, List<GraphRelation>> = mutex.withLock {
    graphEntities.values.toList() to graphRelations.toList()
}

/** 图谱搜索（名称 / 类型 / 观察不区分大小写包含；边保留两端都命中的）。 */
suspend fun MemoryStore.searchGraph(query: String): GraphSearchResult = mutex.withLock {
    if (query.isBlank()) return@withLock GraphSearchResult(emptyList(), emptyList())
    val q = query.lowercase()
    val hit = graphEntities.values.filter {
        it.name.lowercase().contains(q) ||
            it.entityType.lowercase().contains(q) ||
            it.observations.any { o -> o.lowercase().contains(q) }
    }.mapTo(mutableSetOf()) { it.name }
    GraphSearchResult(
        entities = hit.map { graphEntities[it]!! },
        relations = graphRelations.filter { it.from in hit && it.to in hit },
    )
}

/** 实体按名取（召回的相关记忆段用）。 */
suspend fun MemoryStore.graphEntitiesByName(names: List<String>): List<GraphEntity> =
    mutex.withLock { names.mapNotNull { graphEntities[it] } }

// ═══ 隔离名单（摄取防护的持久化）════════════════════════════════════

suspend fun MemoryStore.quarantineFingerprint(fingerprint: String, reason: String) =
    mutex.withLock {
        quarantine.putIfAbsent(
            fingerprint,
            QuarantineEntry(fingerprint = fingerprint, firstSeenAt = clock.now(), reason = reason),
        )
        markDirty(DOC_QUARANTINE)
    }

suspend fun MemoryStore.isQuarantined(fingerprint: String): Boolean =
    mutex.withLock { fingerprint in quarantine }

/** 批量查询隔离命中（单次加锁，避免逐指纹锁风暴）。 */
suspend fun MemoryStore.quarantinedAmong(fingerprints: List<String>): Set<String> =
    mutex.withLock { fingerprints.filterTo(mutableSetOf()) { it in quarantine } }

suspend fun MemoryStore.quarantineCount(): Int = mutex.withLock { quarantine.size }

suspend fun MemoryStore.clearQuarantine(): Int = mutex.withLock {
    val n = quarantine.size
    quarantine.clear()
    if (n > 0) markDirty(DOC_QUARANTINE)
    n
}

/** TTL 过期放行（重新评估机会）；返回放行数。 */
suspend fun MemoryStore.expireQuarantine(ttlMs: Long): Int = mutex.withLock {
    val now = clock.now()
    val expired = quarantine.values.filter { now - it.firstSeenAt > ttlMs }
    expired.forEach { quarantine.remove(it.fingerprint) }
    if (expired.isNotEmpty()) markDirty(DOC_QUARANTINE)
    expired.size
}
