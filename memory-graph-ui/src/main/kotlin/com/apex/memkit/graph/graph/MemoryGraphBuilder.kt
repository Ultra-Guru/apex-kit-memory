package com.apex.memkit.graph.graph

import com.apex.memkit.core.model.ChatFact
import com.apex.memkit.core.model.EpisodeStatus
import com.apex.memkit.core.store.NodeDictEntry
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * 记忆图谱构建器 —— [GraphData] → [MemoryGraph] 的确定性变换。
 *
 * 六类节点的成形规则：
 * - 情景 / 宏 / 聊天事实 / 实体：一比一成形（cap 截取最活跃子集）；
 * - 应用簇：同包名聚合（`null` 包名折入「未知应用」簇）；
 * - 场景节点：可选层（默认关 —— 数量可达数千，开启时按出现频次截取头部）。
 *
 * 六类边的推导（全部来自存储的真实关系，零推测边）：
 * 1. 派生宏：`episode.macroIds` 逐条连线；
 * 2. 宏链：A.exitFingerprint ∈ B.entryFingerprints（方向 A→B，自环剔除）；
 * 3. 观察于：情景 → 场景节点（observedFingerprints ∩ 节点字典）/ 情景 → 应用簇；
 * 4. 应用归属：宏 / 场景节点 → 同包名应用簇；
 * 5. 知识关系：graph relations 三元组直译（悬空端自动剔除）；
 * 6. 别名桥：oldFingerprint → newFingerprint（仅场景节点层开启时成形）。
 *
 * 确定性纪律：同输入同输出（集合遍历前排序、cap 按「最活跃 + id 字典序」
 * 决胜）—— 力导向布局的 seeded 初始位置因此可复现，回归测试可断言。
 */
class MemoryGraphBuilder(private val options: Options = Options()) {

    /**
     * 构建选项 —— 大图防护（默认值按中端手机画布一次绘制 ≤ ~900 节点校准）。
     *
     * @param includeSceneNodes 场景节点层开关（默认关：UI 元素级观测是大头）
     * @param appClustering 应用簇开关（关 = 不生成簇节点与归属/发生边）
     */
    data class Options(
        val includeSceneNodes: Boolean = false,
        val appClustering: Boolean = true,
        val episodeCap: Int = 120,
        val macroCap: Int = 150,
        val factCap: Int = 300,
        val entityCap: Int = 200,
        val sceneNodeCap: Int = 250,
    )

    // ══ 内部累积态（build 期间私有）══════════════════════════════

    private val nodes = mutableListOf<MemoryNode>()
    private val edgeAcc = HashMap<String, MutableEdge>()
    private var episodeNodeIdsByMacroId = HashMap<String, String>()
    private var sceneNodeIdByFingerprint = HashMap<String, String>()
    private var clusterNodeIdByPackage = HashMap<String?, String>()

    /** 合并语义的边累积器（同源同向同类 → strength+1 / 首个 label 胜出）。 */
    private class MutableEdge(
        val sourceId: String,
        val targetId: String,
        val kind: MemoryEdgeKind,
        var label: String? = null,
        var strength: Int = 0,
    )

    private fun addEdge(sourceId: String, targetId: String, kind: MemoryEdgeKind, label: String? = null) {
        if (sourceId == targetId) return  // 自环无语义（宏链的 A→A 剔除）
        val key = "$sourceId|$targetId|${kind.name}"
        val e = edgeAcc.getOrPut(key) {
            MutableEdge(sourceId, targetId, kind).also { it.label = label }
        }
        e.strength++
        if (label != null && e.label == null) e.label = label
    }

    // ══ 主入口 ═══════════════════════════════════════════════════

    fun build(data: GraphData): MemoryGraph {
        // 阶段 0：cap 截取（活跃度降序 + id 字典序决胜 —— 确定性）
        val episodes = data.episodes.sortedWith(
            compareByDescending<com.apex.memkit.core.model.EpisodeRecord> { it.startedAt }
                .thenBy { it.episodeId },
        ).take(options.episodeCap)

        val macros = data.macros.sortedWith(
            compareByDescending<com.apex.memkit.core.model.MacroSkill> { it.stats.energy }
                .thenBy { it.skillId },
        ).take(options.macroCap)

        val facts = data.facts.sortedWith(
            compareByDescending<ChatFact> { it.importance }.thenBy { it.id },
        ).take(options.factCap)

        val entities = data.entities.sortedBy { it.name }.take(options.entityCap)

        val sceneNodes = if (options.includeSceneNodes) {
            data.sceneNodes.sortedWith(
                compareByDescending<NodeDictEntry> { it.occurrences }.thenBy { it.fingerprint },
            ).take(options.sceneNodeCap)
        } else {
            emptyList()
        }

        // 场景节点指纹 → 图节点 id 的解析表（观察边 / 别名桥的端点解析）
        sceneNodes.forEach { sceneNodeIdByFingerprint[it.fingerprint] = nodeId(MemoryNodeKind.SCENE_NODE, it.fingerprint) }

        // 阶段 1：应用簇（先建 —— 后续三类节点向簇连边）
        if (options.appClustering) buildAppClusters(episodes, macros, sceneNodes, data.sceneNodes)

        // 阶段 2：六类主体节点（含可选的场景节点层）
        buildSceneNodes(sceneNodes)
        buildEpisodeNodes(episodes, data.sceneNodes.associateBy { it.fingerprint })
        buildMacroNodes(macros)
        buildFactNodes(facts)
        buildEntityNodes(entities, data.relations)

        // 阶段 3：边推导（派生宏 / 宏链 / 别名桥 / 知识关系 —— 主体节点就位后才能解析端点）
        buildDerivedMacroEdges(episodes)
        buildMacroChains(macros)
        if (options.includeSceneNodes) buildAliasBridges(data.aliases)
        buildEntityRelations(data.relations)

        val edges = edgeAcc.values
            .sortedWith(compareBy({ it.sourceId }, { it.targetId }, { it.kind.name }))
            .map { MemoryEdge(it.sourceId, it.targetId, it.kind, it.label, it.strength) }

        return MemoryGraph(nodes.toList(), edges, collectStats())
    }

    // ══ 应用簇 ═══════════════════════════════════════════════════

    private fun buildAppClusters(
        cappedEpisodes: List<com.apex.memkit.core.model.EpisodeRecord>,
        cappedMacros: List<com.apex.memkit.core.model.MacroSkill>,
        cappedSceneNodes: List<NodeDictEntry>,
        allSceneNodes: List<NodeDictEntry>,
    ) {
        // 簇成员统计用全量节点字典（簇是聚合视图 —— cap 只作用于簇内样本与连线端点）
        val packages = buildSet {
            cappedEpisodes.forEach { add(it.appPackage) }
            cappedMacros.forEach { add(it.appPackage) }
            if (options.includeSceneNodes) cappedSceneNodes.forEach { add(it.appPackage) }
        }.filterNotNull().sorted()

        val perPackageNodes = allSceneNodes.groupBy { it.appPackage }

        for (pkg in packages) {
            val id = nodeId(MemoryNodeKind.APP_CLUSTER, pkg)
            clusterNodeIdByPackage[pkg] = id
            val members = perPackageNodes[pkg].orEmpty()
            val clusterEpisodes = cappedEpisodes.filter { it.appPackage == pkg }
            val clusterMacros = cappedMacros.filter { it.appPackage == pkg }
            val topNodes = members.sortedWith(
                compareByDescending<NodeDictEntry> { it.occurrences }.thenBy { it.fingerprint },
            ).take(CLUSTER_SAMPLE_CAP)
            nodes += MemoryNode(
                id = id,
                kind = MemoryNodeKind.APP_CLUSTER,
                label = pkg.substringAfterLast('.'),
                subtitle = pkg,
                weight = normalize(
                    (members.size + clusterEpisodes.size + clusterMacros.size).toFloat(),
                    0f, 120f,
                ),
                clusterKey = "app:$pkg",
                badgeText = "${members.size + clusterEpisodes.size + clusterMacros.size} 项",
                payload = MemoryNodePayload.AppCluster(
                    appPackage = pkg,
                    nodeCount = members.size,
                    episodeCount = clusterEpisodes.size,
                    macroCount = clusterMacros.size,
                    topNodes = topNodes,
                    episodeGoals = clusterEpisodes.map { it.goal }.take(CLUSTER_SAMPLE_CAP),
                ),
            )
        }

        // 未知应用簇：包名缺失的记忆（旧数据 / 采集端口缺席期）折入一个显式簇
        val orphans = buildList {
            addAll(cappedEpisodes.filter { it.appPackage == null })
            addAll(cappedMacros.filter { it.appPackage == null })
            if (options.includeSceneNodes) addAll(cappedSceneNodes.filter { it.appPackage == null })
        }
        if (orphans.isNotEmpty()) {
            val id = nodeId(MemoryNodeKind.APP_CLUSTER, UNKNOWN_APP)
            clusterNodeIdByPackage[null] = id
            nodes += MemoryNode(
                id = id,
                kind = MemoryNodeKind.APP_CLUSTER,
                label = "未知应用",
                subtitle = null,
                weight = normalize(orphans.size.toFloat(), 0f, 60f),
                clusterKey = "app:$UNKNOWN_APP",
                badgeText = "${orphans.size} 项",
                payload = MemoryNodePayload.AppCluster(
                    appPackage = null,
                    nodeCount = 0,
                    episodeCount = orphans.count { it is com.apex.memkit.core.model.EpisodeRecord },
                    macroCount = orphans.count { it is com.apex.memkit.core.model.MacroSkill },
                    topNodes = emptyList(),
                    episodeGoals = orphans.filterIsInstance<com.apex.memkit.core.model.EpisodeRecord>()
                        .map { it.goal }.take(CLUSTER_SAMPLE_CAP),
                ),
            )
        }
    }

    // ══ 场景节点层（可选：记忆的最小观测单位）════════════════════

    private fun buildSceneNodes(sceneNodes: List<NodeDictEntry>) {
        for (n in sceneNodes) {
            val id = sceneNodeIdByFingerprint.getValue(n.fingerprint)
            nodes += MemoryNode(
                id = id,
                kind = MemoryNodeKind.SCENE_NODE,
                label = n.displayText(),
                subtitle = n.role.name,
                weight = normalize(n.occurrences.toFloat(), 1f, 30f),
                clusterKey = clusterKeyOf(n.appPackage),
                badgeText = "${n.occurrences} 次",
                payload = MemoryNodePayload.SceneNode(n),
            )
            // 归属边：场景节点 → 同包名应用簇
            if (options.appClustering) {
                clusterNodeIdByPackage[n.appPackage]?.let { target ->
                    addEdge(id, target, MemoryEdgeKind.APP_SCOPE)
                }
            }
        }
    }

    // ══ 情景节点 + 观察边 ═════════════════════════════════════════

    private fun buildEpisodeNodes(
        episodes: List<com.apex.memkit.core.model.EpisodeRecord>,
        nodeDictByFingerprint: Map<String, NodeDictEntry>,
    ) {
        for (e in episodes) {
            val id = nodeId(MemoryNodeKind.EPISODE, e.episodeId)
            episodeNodeIdsByMacroId.putAll(e.macroIds.map { it to id })
            // 观察节点的显示文本与图节点 id 预解析（详情面板 + 观察边共用）
            val observed = e.observedFingerprints
                .mapNotNull { fp -> nodeDictByFingerprint[fp]?.let { fp to it } }
                .sortedWith(compareByDescending<Pair<String, NodeDictEntry>> { it.second.occurrences }.thenBy { it.first })
            nodes += MemoryNode(
                id = id,
                kind = MemoryNodeKind.EPISODE,
                label = e.goal.ifBlank { "（无目标）" },
                subtitle = e.appPackage ?: "未知应用",
                weight = normalize(e.actionCount.toFloat(), 0f, 40f),
                clusterKey = clusterKeyOf(e.appPackage),
                badgeText = statusBadge(e.status),
                payload = MemoryNodePayload.Episode(
                    record = e,
                    observedNodeTexts = observed.take(EPISODE_OBSERVED_SAMPLE_CAP).map { it.second.displayText() },
                    observedNodeIds = observed.take(EPISODE_OBSERVED_SAMPLE_CAP)
                        .map { sceneNodeIdByFingerprint[it.first] }
                        .filterNotNull(),
                    quarantinedCount = 0,
                ),
            )
            // 观察边：情景 → 场景节点（场景层开启且指纹在截取集内才成形）
            observed.forEach { (fp, _) ->
                sceneNodeIdByFingerprint[fp]?.let { target ->
                    addEdge(id, target, MemoryEdgeKind.OBSERVED_IN)
                }
            }
            // 发生边：情景 → 应用簇
            if (options.appClustering) {
                clusterNodeIdByPackage[e.appPackage]?.let { target ->
                    addEdge(id, target, MemoryEdgeKind.OBSERVED_IN)
                }
            }
        }
    }

    // ══ 宏节点 + 归属边 ═══════════════════════════════════════════

    private fun buildMacroNodes(macros: List<com.apex.memkit.core.model.MacroSkill>) {
        for (m in macros) {
            val id = nodeId(MemoryNodeKind.MACRO, m.skillId)
            nodes += MemoryNode(
                id = id,
                kind = MemoryNodeKind.MACRO,
                label = m.name,
                subtitle = m.appPackage ?: "未知应用",
                weight = normalize(m.stats.energy, 0f, 10f),
                clusterKey = clusterKeyOf(m.appPackage),
                badgeText = if (m.stats.crystallized) "已晶化" else "${m.steps.size} 步",
                payload = MemoryNodePayload.Macro(m),
            )
            if (options.appClustering) {
                clusterNodeIdByPackage[m.appPackage]?.let { target ->
                    addEdge(id, target, MemoryEdgeKind.APP_SCOPE)
                }
            }
        }
    }

    // ══ 聊天事实节点（语义记忆，按类别聚类）═══════════════════════

    private fun buildFactNodes(facts: List<ChatFact>) {
        for (f in facts) {
            nodes += MemoryNode(
                id = nodeId(MemoryNodeKind.CHAT_FACT, f.id),
                kind = MemoryNodeKind.CHAT_FACT,
                label = f.content,
                subtitle = factKindLabel(f.kind),
                weight = f.importance.coerceIn(0f, 1f),
                clusterKey = "fact:${f.kind.name}",
                badgeText = null,
                payload = MemoryNodePayload.ChatFact(f),
            )
        }
    }

    // ══ 知识实体节点 ═════════════════════════════════════════════

    private fun buildEntityNodes(
        entities: List<com.apex.memkit.core.model.GraphEntity>,
        relations: List<com.apex.memkit.core.model.GraphRelation>,
    ) {
        val byName = entities.associateBy { it.name }
        for (entity in entities) {
            val id = nodeId(MemoryNodeKind.ENTITY, entity.name)
            val incoming = relations.filter { it.to == entity.name && byName.containsKey(it.from) }
            val outgoing = relations.filter { it.from == entity.name && byName.containsKey(it.to) }
            nodes += MemoryNode(
                id = id,
                kind = MemoryNodeKind.ENTITY,
                label = entity.name,
                subtitle = entity.entityType,
                weight = normalize(entity.observations.size.toFloat(), 0f, 12f),
                clusterKey = "entity",
                badgeText = if (entity.observations.isEmpty()) null else "${entity.observations.size} 条",
                payload = MemoryNodePayload.Entity(entity, incoming, outgoing),
            )
        }
    }

    // ══ 关系边（主体节点就位后的第二阶段推导）════════════════════

    /** 派生宏边：情景 → macroIds 引用的宏（目标不在截取集内的悬空引用剔除）。 */
    private fun buildDerivedMacroEdges(episodes: List<com.apex.memkit.core.model.EpisodeRecord>) {
        val macroNodeIds = nodes.asSequence()
            .filter { it.kind == MemoryNodeKind.MACRO }
            .mapTo(mutableSetOf()) { it.id }
        for (e in episodes) {
            val episodeId = nodeId(MemoryNodeKind.EPISODE, e.episodeId)
            for (macroId in e.macroIds) {
                val target = nodeId(MemoryNodeKind.MACRO, macroId)
                if (target in macroNodeIds) {
                    addEdge(episodeId, target, MemoryEdgeKind.DERIVED_MACRO)
                }
            }
        }
    }

    private fun buildMacroChains(macros: List<com.apex.memkit.core.model.MacroSkill>) {
        // 出口指纹 → 持有宏 id（多个宏可共享出口 —— 全连）
        val macrosByExit = HashMap<String, MutableList<String>>()
        macros.forEach { m ->
            macrosByExit.getOrPut(m.exitFingerprint) { mutableListOf() }.add(m.skillId)
        }
        // 方向语义：A.exit ∈ B.entryFingerprints → 边 A→B（B 的入口锚点 = A 的出口锚点）
        // 遍历 B（入口持有方），反查出口命中者作为源 —— 与文档「出口指纹 → 入口指纹」一致
        for (b in macros) {
            val sources = b.entryFingerprints
                .flatMap { fp -> macrosByExit[fp].orEmpty() }
                .filter { it != b.skillId }
                .distinct()
            sources.forEach { sourceId ->
                addEdge(
                    nodeId(MemoryNodeKind.MACRO, sourceId),
                    nodeId(MemoryNodeKind.MACRO, b.skillId),
                    MemoryEdgeKind.MACRO_CHAIN,
                )
            }
        }
    }

    private fun buildAliasBridges(aliases: List<com.apex.memkit.core.model.FingerprintAlias>) {
        for (a in aliases) {
            val source = sceneNodeIdByFingerprint[a.oldFingerprint] ?: continue
            val target = sceneNodeIdByFingerprint[a.newFingerprint] ?: continue
            addEdge(source, target, MemoryEdgeKind.ALIAS_BRIDGE)
        }
    }

    private fun buildEntityRelations(relations: List<com.apex.memkit.core.model.GraphRelation>) {
        // 实体节点 id = "entity:" + 实体名 —— 悬空端点（不在截取集内的实体）直接剔除
        val entityIds = nodes.asSequence()
            .filter { it.kind == MemoryNodeKind.ENTITY }
            .mapTo(mutableSetOf()) { it.id }
        for (r in relations) {
            val source = nodeId(MemoryNodeKind.ENTITY, r.from)
            val target = nodeId(MemoryNodeKind.ENTITY, r.to)
            if (source !in entityIds || target !in entityIds) continue
            addEdge(source, target, MemoryEdgeKind.GRAPH_RELATION, r.relationType)
        }
    }

    // ══ 工具 ═════════════════════════════════════════════════════

    private fun collectStats(): GraphStats {
        var episodes = 0; var macros = 0; var facts = 0
        var entities = 0; var scene = 0; var clusters = 0
        nodes.forEach {
            when (it.kind) {
                MemoryNodeKind.EPISODE -> episodes++
                MemoryNodeKind.MACRO -> macros++
                MemoryNodeKind.CHAT_FACT -> facts++
                MemoryNodeKind.ENTITY -> entities++
                MemoryNodeKind.SCENE_NODE -> scene++
                MemoryNodeKind.APP_CLUSTER -> clusters++
            }
        }
        return GraphStats(
            episodes = episodes, macros = macros, facts = facts,
            entities = entities, sceneNodes = scene, appClusters = clusters,
            edges = edgeAcc.size,
        )
    }

    private fun statusBadge(status: EpisodeStatus): String = when (status) {
        EpisodeStatus.RUNNING -> "进行中"
        EpisodeStatus.SUCCEEDED -> "成功"
        EpisodeStatus.FAILED -> "失败"
        EpisodeStatus.ABANDONED -> "放弃"
    }

    private fun factKindLabel(kind: ChatFact.Kind): String = when (kind) {
        ChatFact.Kind.PROFILE -> "画像"
        ChatFact.Kind.STATE -> "近况"
        ChatFact.Kind.MILESTONE -> "里程碑"
    }

    private fun clusterKeyOf(appPackage: String?): String =
        "app:${appPackage ?: UNKNOWN_APP}"

    private fun normalize(v: Float, min: Float, max: Float): Float =
        ((v - min) / max(0f, max - min)).coerceIn(0.08f, 1f)

    private companion object {
        const val UNKNOWN_APP = "__unknown__"
        const val CLUSTER_SAMPLE_CAP = 12
        const val EPISODE_OBSERVED_SAMPLE_CAP = 40

        /** 节点 id 前缀（kind:业务id 复合 —— 全局唯一 + 详情跳转 O(1) 回查）。 */
        const val EPISODE_PREFIX = "episode:"
        const val MACRO_PREFIX = "macro:"
        const val FACT_PREFIX = "fact:"
        const val ENTITY_PREFIX = "entity:"
        const val SCENE_PREFIX = "scene:"
        const val CLUSTER_PREFIX = "cluster:"

        fun nodeId(kind: MemoryNodeKind, businessId: String): String =
            when (kind) {
                MemoryNodeKind.EPISODE -> EPISODE_PREFIX + businessId
                MemoryNodeKind.MACRO -> MACRO_PREFIX + businessId
                MemoryNodeKind.CHAT_FACT -> FACT_PREFIX + businessId
                MemoryNodeKind.ENTITY -> ENTITY_PREFIX + businessId
                MemoryNodeKind.SCENE_NODE -> SCENE_PREFIX + businessId
                MemoryNodeKind.APP_CLUSTER -> CLUSTER_PREFIX + businessId
            }
    }

    /** 供状态机 / 测试复用的 id 成形规则（与内部一致）。 */
    object Ids {
        fun episode(id: String) = EPISODE_PREFIX + id
        fun macro(id: String) = MACRO_PREFIX + id
        fun fact(id: String) = FACT_PREFIX + id
        fun entity(name: String) = ENTITY_PREFIX + name
        fun scene(fingerprint: String) = SCENE_PREFIX + fingerprint
        fun cluster(appPackage: String?) = CLUSTER_PREFIX + (appPackage ?: UNKNOWN_APP)
    }

    /** 边线宽计算（渲染层消费：strength 合并 → 对数感知线宽）。 */
    fun edgeWidth(strength: Int): Float = 1f + ln(max(1, strength).toFloat()).coerceAtMost(3f) * 0.8f
}
