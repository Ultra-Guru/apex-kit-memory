package com.apex.memkit.core.store

import com.apex.memkit.core.KitClock
import com.apex.memkit.core.KitLogger
import com.apex.memkit.core.MemKitConfig
import com.apex.memkit.core.model.ChatFact
import com.apex.memkit.core.model.ChatTurn
import com.apex.memkit.core.model.EpisodeRecord
import com.apex.memkit.core.model.EpisodeSummary
import com.apex.memkit.core.model.FingerprintAlias
import com.apex.memkit.core.model.GraphEntity
import com.apex.memkit.core.model.GraphRelation
import com.apex.memkit.core.model.MacroSkill
import com.apex.memkit.core.model.NodeRole
import com.apex.memkit.core.model.QuarantineDoc
import com.apex.memkit.core.model.QuarantineEntry
import com.apex.memkit.core.model.SceneNode
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 文档名 → 内容清单（持久化面的全集）。 */
internal val DOC_META = "meta.json"
internal val DOC_EPISODES = "episodes.json"
internal val DOC_NODES = "nodes.json"
internal val DOC_MACROS = "macros.json"
internal val DOC_ALIASES = "aliases.json"
internal val DOC_CHAT = "chat.json"
internal val DOC_GRAPH = "graph.json"
internal val DOC_QUARANTINE = "quarantine.json"
internal val DOC_NAMES = listOf(
    DOC_META, DOC_EPISODES, DOC_NODES, DOC_MACROS, DOC_ALIASES, DOC_CHAT, DOC_GRAPH, DOC_QUARANTINE,
)

/** 当前文档 schema 版本（未来字段演进在此处挂迁移钩子）。 */
internal const val DOC_SCHEMA_VERSION = 1

/**
 * 记忆库内存态 + 持久化 —— 唯一事实源（single source of truth）。
 *
 * 设计纪律（针对旧 Room 存储层的三处结构性修复）：
 * 1. **无 N+1**：全部集合常驻内存（数万节点量级），读写为纯内存操作，
 *   落盘按文档粒度合并批量写（旧 store 每帧对每个已存在节点发一条 UPDATE）；
 * 2. **单写者纪律**：全部公开操作经 [mutex] 串行 —— 无锁竞态面；
 * 3. **诚实统计**：只有能被真实写入的字段（[EpisodeRecord.actionCount] 等）。
 *
 * 持久化为命名 JSON 文档（原子写 / 损坏备份 / 按 dirty 合并落盘）：
 * meta / episodes / nodes / macros / aliases / chat / graph / quarantine。
 *
 * 容量治理：各集合超 [MemKitConfig.limits] 时按「能量低者先出、同能量取最旧」
 * 淘汰（晶化宏豁免）—— 记忆库永不无界增长。
 */
class MemoryStore internal constructor(
    internal val config: MemKitConfig,
    private val backend: StorageBackend,
    internal val clock: KitClock,
    internal val logger: KitLogger,
) {

    internal val mutex = Mutex()

    // ── 内存态（全部经 mutex 访问）──────────────────────────────────
    internal val episodes = LinkedHashMap<String, EpisodeRecord>()
    internal val nodes = HashMap<String, NodeDictEntry>()
    internal val macros = HashMap<String, MacroSkill>()
    /** 旧指纹 → 别名列表（同旧指纹可能漂移出多个新指纹，按 score 取最优）。 */
    internal val aliases = HashMap<String, MutableList<FingerprintAlias>>()
    internal val macroIndexByPackage = HashMap<String?, MutableList<MacroSkill>>()
    internal val metaFlags = HashMap<String, String>()

    // 聊天 / 图谱 / 隔离三组扩展状态（逻辑住在 ChatGraphOps.kt，状态与序列化收编于此）
    internal val chatFacts = ArrayList<ChatFact>()
    internal val chatQueue = ArrayList<ChatTurn>()
    internal val graphEntities = LinkedHashMap<String, GraphEntity>()
    internal val graphRelations = ArrayList<GraphRelation>()
    internal val quarantine = LinkedHashMap<String, QuarantineEntry>()

    internal val dirty = HashSet<String>()

    internal val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private inline fun <reified T> decode(text: String): List<T> =
        runCatching { json.decodeFromString(ListSerializer(kotlinx.serialization.serializer<T>()), text) }
            .getOrElse { emptyList() }

    // ═══ 生命周期：加载 / 落盘 ═════════════════════════════════════

    /** 启动加载：逐文档防御式读取（坏文档 → 备份留档 + 空集继续）。 */
    suspend fun load() = mutex.withLock {
        for (name in DOC_NAMES) runCatching { loadDocLocked(name) }
            .onFailure { logger.log(KitLogger.Level.WARN, TAG, "文档加载失败（按空处理）：$name — ${it.message}") }
        rebuildMacroIndexLocked()
    }

    private suspend fun loadDocLocked(name: String) {
        when (name) {
            DOC_META -> backend.read(name)?.let { text ->
                runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()?.let { obj ->
                    (obj[KEY_FLAGS] as? JsonObject)?.forEach { (k, v) ->
                        (v as? kotlinx.serialization.json.JsonPrimitive)?.content?.let { metaFlags[k] = it }
                    }
                }
            }

            DOC_EPISODES -> decode<EpisodeRecord>(backend.read(name) ?: return).forEach { episodes[it.episodeId] = it }
            DOC_NODES -> decode<NodeDictEntry>(backend.read(name) ?: return).forEach { nodes[it.fingerprint] = it }
            DOC_MACROS -> decode<MacroSkill>(backend.read(name) ?: return).forEach { macros[it.skillId] = it }
            DOC_ALIASES -> decode<FingerprintAlias>(backend.read(name) ?: return).forEach { addAliasIndexLocked(it) }
            DOC_CHAT -> backend.read(name)?.let { text ->
                runCatching { json.decodeFromString(ChatDoc.serializer(), text) }.getOrNull()?.let { doc ->
                    chatFacts.addAll(doc.facts)
                    chatQueue.addAll(doc.queue)
                }
            }

            DOC_GRAPH -> backend.read(name)?.let { text ->
                runCatching { json.decodeFromString(GraphDoc.serializer(), text) }.getOrNull()?.let { doc ->
                    doc.entities.forEach { graphEntities[it.name] = it }
                    graphRelations.addAll(doc.relations)
                }
            }

            DOC_QUARANTINE -> backend.read(name)?.let { text ->
                runCatching { json.decodeFromString(QuarantineDoc.serializer(), text) }.getOrNull()?.let { doc ->
                    doc.entries.forEach { quarantine[it.fingerprint] = it }
                }
            }
        }
    }

    /**
     * 落盘全部 dirty 文档（写入完成后清 dirty）。返回写入的文档数。
     *
     * 调用方：写入管道（节拍/批量触发）与维护周期；单文档损坏不阻断其余文档。
     */
    suspend fun flush(): Int = mutex.withLock { flushLocked() }

    private suspend fun flushLocked(): Int {
        var written = 0
        for (name in dirty.toList()) {
            runCatching {
                when (name) {
                    DOC_META -> backend.write(name, json.encodeToString(metaDocLocked()))
                    DOC_EPISODES -> backend.write(name, json.encodeToString(ListSerializer(EpisodeRecord.serializer()), episodes.values.toList()))
                    DOC_NODES -> backend.write(name, json.encodeToString(ListSerializer(NodeDictEntry.serializer()), nodes.values.toList()))
                    DOC_MACROS -> backend.write(name, json.encodeToString(ListSerializer(MacroSkill.serializer()), macros.values.toList()))
                    DOC_ALIASES -> backend.write(name, json.encodeToString(ListSerializer(FingerprintAlias.serializer()), aliases.values.flatten()))
                    DOC_CHAT -> backend.write(name, json.encodeToString(ChatDoc.serializer(), ChatDoc(chatFacts.toList(), chatQueue.toList())))
                    DOC_GRAPH -> backend.write(name, json.encodeToString(GraphDoc.serializer(), GraphDoc(graphEntities.values.toList(), graphRelations.toList())))
                    DOC_QUARANTINE -> backend.write(name, json.encodeToString(QuarantineDoc.serializer(), QuarantineDoc(quarantine.values.toList())))
                }
                written++
            }.onFailure {
                logger.log(KitLogger.Level.ERROR, TAG, "文档写入失败：$name — ${it.message}")
            }
        }
        dirty.clear()
        return written
    }

    internal fun markDirty(name: String) {
        dirty.add(name)
    }

    // ═══ meta 标志位 ═════════════════════════════════════════════

    suspend fun metaFlag(name: String): String? = mutex.withLock { metaFlags[name] }

    suspend fun setMetaFlag(name: String, value: String) = mutex.withLock {
        metaFlags[name] = value
        dirty.add(DOC_META)
    }

    private fun metaDocLocked(): JsonObject = buildJsonObject {
        put("schemaVersion", DOC_SCHEMA_VERSION)
        put(KEY_FLAGS, buildJsonObject { metaFlags.forEach { (k, v) -> put(k, v) } })
    }

    // ═══ Episode ═════════════════════════════════════════════════

    suspend fun addEpisode(record: EpisodeRecord) = mutex.withLock {
        episodes[record.episodeId] = record
        markDirty(DOC_EPISODES)
    }

    /** 原子更新（不存在返回 null）。 */
    suspend fun updateEpisode(
        id: String,
        transform: (EpisodeRecord) -> EpisodeRecord,
    ): EpisodeRecord? = mutex.withLock {
        val cur = episodes[id] ?: return null
        val next = transform(cur)
        episodes[id] = next
        markDirty(DOC_EPISODES)
        next
    }

    suspend fun removeEpisode(id: String): Boolean = mutex.withLock {
        val removed = episodes.remove(id) != null
        if (removed) markDirty(DOC_EPISODES)
        removed
    }

    suspend fun episodeById(id: String): EpisodeRecord? = mutex.withLock { episodes[id] }

    /** 最近情景（新→旧）。 */
    suspend fun recentEpisodes(limit: Int): List<EpisodeSummary> = mutex.withLock {
        episodes.values
            .sortedByDescending { it.startedAt }
            .take(limit)
            .map { it.toSummary() }
    }

    suspend fun episodeCount(): Int = mutex.withLock { episodes.size }

    private fun EpisodeRecord.toSummary() = EpisodeSummary(
        episodeId = episodeId,
        goal = goal,
        appPackage = appPackage,
        status = status,
        startedAt = startedAt,
        finishedAt = finishedAt,
        actionCount = actionCount,
        macroIds = macroIds,
        manual = manual,
    )

    // ═══ 节点字典（全局去重 + 出现计数 + 能量）══════════════════════

    /**
     * 批量摄取节点（一帧降维产物）。已存在节点按「出现计数 +1 / lastSeen 刷新 /
     * 能量增能」更新；新节点插入。返回 (新增数, 见过数)。
     *
     * **批量合并写**取代旧 store 的逐节点 UPDATE —— N+1 的根治点。
     */
    suspend fun upsertNodes(
        nodesIn: List<SceneNode>,
        appPackage: String?,
        appVersion: String?,
    ): Pair<Int, Int> = mutex.withLock {
        val now = clock.now()
        var added = 0
        var seen = 0
        for (n in nodesIn) {
            val cur = nodes[n.fingerprint]
            if (cur == null) {
                nodes[n.fingerprint] = NodeDictEntry(
                    fingerprint = n.fingerprint,
                    role = n.role,
                    text = n.text,
                    resourceId = n.resourceId,
                    className = n.className,
                    appPackage = appPackage,
                    appVersion = appVersion,
                    firstSeenAt = now,
                    lastSeenAt = now,
                    occurrences = 1,
                    energy = 1.0f,
                )
                added++
            } else {
                nodes[n.fingerprint] = cur.copy(
                    occurrences = cur.occurrences + 1,
                    lastSeenAt = now,
                    energy = (cur.energy + 0.1f).coerceAtMost(10f),
                    appVersion = appVersion ?: cur.appVersion,
                )
                seen++
            }
        }
        if (added + seen > 0) markDirty(DOC_NODES)
        added to seen
    }

    /** 节点搜索（text / resourceId / className 不区分大小写包含匹配）。 */
    suspend fun searchNodes(query: String, limit: Int): List<NodeDictEntry> = mutex.withLock {
        if (query.isBlank()) return emptyList()
        val q = query.trim().lowercase()
        nodes.values
            .filter {
                it.text?.lowercase()?.contains(q) == true ||
                    it.resourceId?.lowercase()?.contains(q) == true ||
                    it.className?.lowercase()?.contains(q) == true
            }
            .sortedByDescending { it.occurrences }
            .take(limit)
            .toList()
    }

    suspend fun nodeCount(): Int = mutex.withLock { nodes.size }

    /** 全部节点快照（别名发现 / 迁移导入用）。 */
    suspend fun allNodes(): List<NodeDictEntry> = mutex.withLock { nodes.values.toList() }

    /** 维护周期入口：节点变换（返回值 null = 删除该节点），返回被删数。 */
    suspend fun mutateNodes(transform: (NodeDictEntry) -> NodeDictEntry?): Int = mutex.withLock {
        val drop = ArrayList<String>()
        for ((fp, entry) in nodes) if (transform(entry) == null) drop.add(fp)
        drop.forEach { nodes.remove(it) }
        markDirty(DOC_NODES)
        drop.size
    }

    // ═══ 宏技能 ══════════════════════════════════════════════════

    suspend fun saveMacro(m: MacroSkill) = mutex.withLock {
        macros[m.skillId] = m
        indexMacroLocked(m)
        markDirty(DOC_MACROS)
    }

    private fun indexMacroLocked(m: MacroSkill) {
        macroIndexByPackage[m.appPackage]?.removeAll { it.skillId == m.skillId }
        macroIndexByPackage.getOrPut(m.appPackage) { mutableListOf() }.add(m)
    }

    private fun rebuildMacroIndexLocked() {
        macroIndexByPackage.clear()
        macros.values.forEach { indexMacroLocked(it) }
    }

    suspend fun macroById(id: String): MacroSkill? = mutex.withLock { macros[id] }

    /** 指定包的全部宏（入口匹配的候选集；null 包名单独分组）。 */
    suspend fun macrosForPackage(appPackage: String?): List<MacroSkill> = mutex.withLock {
        macroIndexByPackage[appPackage]?.toList() ?: emptyList()
    }

    /** 全量宏快照（别名发现 / 迁移导入用）。 */
    suspend fun allMacros(): List<MacroSkill> = mutex.withLock { macros.values.toList() }

    /** Top 宏（成功率 × 能量降序，晶化优先）。 */
    suspend fun topMacros(limit: Int): List<MacroSkill> = mutex.withLock {
        macros.values
            .sortedWith(
                compareByDescending<MacroSkill> { it.stats.crystallized }
                    .thenByDescending { it.stats.energy }
                    .thenByDescending { it.stats.successRate() },
            )
            .take(limit)
            .toList()
    }

    suspend fun macroCount(): Int = mutex.withLock { macros.size }

    /** 原子更新单宏（不存在返回 null）。 */
    suspend fun updateMacro(
        id: String,
        transform: (MacroSkill) -> MacroSkill,
    ): MacroSkill? = mutex.withLock {
        val cur = macros[id] ?: return null
        val next = transform(cur)
        macros[id] = next
        indexMacroLocked(next)
        markDirty(DOC_MACROS)
        next
    }

    /** 维护周期入口：全量宏变换（null = 删除），返回被删数。 */
    suspend fun mutateMacros(transform: (MacroSkill) -> MacroSkill?): Int = mutex.withLock {
        val drop = ArrayList<String>()
        for ((id, m) in macros) {
            val next = transform(m)
            if (next == null) drop.add(id) else macros[id] = next
        }
        if (drop.isNotEmpty()) {
            drop.forEach { macros.remove(it) }
            rebuildMacroIndexLocked()
        }
        markDirty(DOC_MACROS)
        drop.size
    }

    // ═══ 指纹别名 ════════════════════════════════════════════════

    suspend fun addAlias(alias: FingerprintAlias) = mutex.withLock {
        addAliasIndexLocked(alias)
        markDirty(DOC_ALIASES)
    }

    private fun addAliasIndexLocked(alias: FingerprintAlias) {
        val list = aliases.getOrPut(alias.oldFingerprint) { mutableListOf() }
        // 同 old→new 去重（保留最高分）
        val existing = list.indexOfFirst { it.newFingerprint == alias.newFingerprint }
        if (existing >= 0) {
            if (list[existing].score < alias.score) list[existing] = alias
        } else {
            list.add(alias)
        }
    }

    /** 旧指纹的全部候选别名（分值降序）。 */
    suspend fun aliasesFrom(oldFingerprint: String): List<FingerprintAlias> = mutex.withLock {
        aliases[oldFingerprint]?.sortedByDescending { it.score } ?: emptyList()
    }

    suspend fun aliasCount(): Int = mutex.withLock { aliases.values.sumOf { it.size } }

    // ═══ 容量治理 ════════════════════════════════════════════════

    /**
     * 容量治理：各集合超限淘汰。
     *
     * - 情景：最旧优先（上限 [MemKitConfig.Limits.maxEpisodes]）；
     * - 节点：能量低 → 最旧（上限 [MemKitConfig.Limits.maxNodes]）；
     * - 宏：晶化豁免，其余能量低 → 最旧（上限 [MemKitConfig.Limits.maxMacros]）。
     */
    suspend fun enforceLimits(): Triple<Int, Int, Int> = mutex.withLock {
        var droppedEpisodes = 0
        var droppedNodes = 0
        var droppedMacros = 0

        if (episodes.size > config.limits.maxEpisodes) {
            val keep = episodes.values
                .sortedByDescending { it.startedAt }
                .take(config.limits.maxEpisodes)
                .mapTo(mutableSetOf()) { it.episodeId }
            droppedEpisodes = episodes.size - keep.size
            episodes.keys.retainAll(keep)
            if (droppedEpisodes > 0) markDirty(DOC_EPISODES)
        }

        if (nodes.size > config.limits.maxNodes) {
            val dropCount = nodes.size - config.limits.maxNodes
            nodes.values
                .sortedWith(compareBy<NodeDictEntry> { it.energy }.thenBy { it.lastSeenAt })
                .take(dropCount)
                .forEach { nodes.remove(it.fingerprint) }
            droppedNodes = dropCount
            markDirty(DOC_NODES)
        }

        if (macros.size > config.limits.maxMacros) {
            val ranked = macros.values
                .sortedWith(compareBy<MacroSkill> { it.stats.crystallized }.thenByDescending { it.stats.energy })
            var toDrop = macros.size - config.limits.maxMacros
            for (m in ranked) {
                if (toDrop <= 0) break
                if (m.stats.crystallized) continue
                macros.remove(m.skillId)
                toDrop--
                droppedMacros++
            }
            if (droppedMacros > 0) {
                rebuildMacroIndexLocked()
                markDirty(DOC_MACROS)
            }
        }
        Triple(droppedEpisodes, droppedNodes, droppedMacros)
    }

    companion object {
        private const val TAG = "MemoryStore"
        private const val KEY_FLAGS = "flags"
    }
}

/**
 * 节点字典条目 —— 跨情景共享的全局语义节点（搜索 / 别名发现的数据面）。
 *
 * 相比旧 NodeEntity：剔除从未被读取的 `bounds_json / interactive_flags`
 *（展示与指纹计算不需要持久化它们）。
 */
@Serializable
data class NodeDictEntry(
    val fingerprint: String,
    val role: NodeRole = NodeRole.UNKNOWN,
    val text: String? = null,
    val resourceId: String? = null,
    val className: String? = null,
    val appPackage: String? = null,
    val appVersion: String? = null,
    val firstSeenAt: Long = 0,
    val lastSeenAt: Long = 0,
    val occurrences: Int = 1,
    val energy: Float = 1.0f,
) {
    /** 召回工具 / 记忆页的节点视图。 */
    fun displayText(): String = text ?: resourceId ?: className?.substringAfterLast('.') ?: role.name
}
