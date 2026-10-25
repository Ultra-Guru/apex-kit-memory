package com.apex.memkit.graph.graph

import com.apex.memkit.core.model.ChatFact
import com.apex.memkit.core.model.EpisodeRecord
import com.apex.memkit.core.model.GraphEntity
import com.apex.memkit.core.model.GraphRelation
import com.apex.memkit.core.model.MacroSkill
import com.apex.memkit.core.model.FingerprintAlias
import com.apex.memkit.core.store.NodeDictEntry

/**
 * 记忆图谱节点类型 —— 认知记忆的五轨 + 一个聚合容器。
 *
 * 六类节点覆盖库的全部记忆资产：
 * - [EPISODE]    情景记忆：一次 Agent 任务的完整容器（目标 / 轨迹 / 派生宏）；
 * - [MACRO]      程序性记忆：可回放的宏技能（入口指纹集合 → 类型化动作序列）；
 * - [CHAT_FACT]  语义记忆：从对话沉淀的长期事实（画像 / 近况 / 里程碑）；
 * - [ENTITY]     知识图谱实体（MCP memory 对齐的 name/type/observations）;
 * - [SCENE_NODE] UI 场景节点（记忆的最小观测单位，可选层 —— 数量可达数千）；
 * - [APP_CLUSTER] 应用簇：同包名记忆的聚合容器（节点 / 情景 / 宏的归属地）。
 */
enum class MemoryNodeKind {
    EPISODE, MACRO, CHAT_FACT, ENTITY, SCENE_NODE, APP_CLUSTER;

    /** 图例 / 过滤条的稳定次序（认知层次：情景 → 程序 → 语义 → 知识 → 底层 → 聚合）。 */
    companion object {
        val DISPLAY_ORDER: List<MemoryNodeKind> =
            listOf(EPISODE, MACRO, CHAT_FACT, ENTITY, SCENE_NODE, APP_CLUSTER)
    }
}

/**
 * 记忆图谱关系边类型 —— 「连线显示记忆节点的关系」的六类语义。
 *
 * - [DERIVED_MACRO]   情景 → 宏：任务成功后被蒸馏产出（`episode.macroIds`）；
 * - [MACRO_CHAIN]     宏 → 宏：出口指纹 ∈ 对方入口集合 —— 邻接宏链（可接力回放）；
 * - [OBSERVED_IN]     情景 → 场景节点/应用簇：任务期间观察到的 UI 元素 / 发生的应用；
 * - [APP_SCOPE]       宏/场景节点 → 应用簇：包名归属（同应用记忆聚拢的锚）；
 * - [GRAPH_RELATION]  实体 → 实体：知识图谱三元组（边标签 = relationType）；
 * - [ALIAS_BRIDGE]    场景节点 → 场景节点：跨版本指纹别名桥（App 升级后的漂移线）。
 */
enum class MemoryEdgeKind {
    DERIVED_MACRO, MACRO_CHAIN, OBSERVED_IN, APP_SCOPE, GRAPH_RELATION, ALIAS_BRIDGE
}

/**
 * 图谱节点 —— 渲染与详情的完整单元。
 *
 * @param id 全局唯一稳定 id（`kind:业务id` 复合形式）
 * @param kind 节点类型（着色 / 形状 / 详情面板的判别依据）
 * @param label 主标签（画布缩放达标时显示）
 * @param subtitle 次标签（详情头部 / 搜索匹配的语料）
 * @param weight 归一化权重 0..1（节点半径 = 基础 + 权重 × 跨度）
 * @param clusterKey 聚类键（力导向布局的同簇引力：类型 × 应用 / 事实类别）
 * @param badgeText 徽标文本（如 "48 步"、"已晶化"），null = 无徽标
 * @param payload 原始记忆数据的类型化包装（详情面板直读）
 */
data class MemoryNode(
    val id: String,
    val kind: MemoryNodeKind,
    val label: String,
    val subtitle: String? = null,
    val weight: Float = 0.5f,
    val clusterKey: String,
    val badgeText: String? = null,
    val payload: MemoryNodePayload,
) {
    /** 搜索语料（label + subtitle，大小写不敏感包含匹配）。 */
    fun matchesQuery(query: String): Boolean {
        if (query.isBlank()) return true
        val q = query.trim()
        return label.contains(q, ignoreCase = true) ||
            subtitle?.contains(q, ignoreCase = true) == true
    }
}

/**
 * 图谱关系边（无重语义：同源同类型的重复关系在构建期合并，计数进 [strength]）。
 *
 * @param sourceId / targetId 节点 id
 * @param kind 关系类型（线型 / 颜色 / 图例的判别依据）
 * @param label 具体关联标签（GRAPH_RELATION 为 relationType；其余为空 ——
 *   由渲染层经 [EdgeLabels.labelOf] 补上类型语义名）
 * @param strength 合并强度（并行边合并计数；线宽 = 1 + ln(strength)）
 */
data class MemoryEdge(
    val sourceId: String,
    val targetId: String,
    val kind: MemoryEdgeKind,
    val label: String? = null,
    val strength: Int = 1,
)

/**
 * 边显示标签解析器 —— 「连线显示具体关联」的唯一口径。
 *
 * 六类边全部带可读标签：知识关系边用具体 relationType（如 works_at），
 * 其余五类用类型语义名（构建器不硬编码文案 —— 宿主经资源解析后以
 * [kindNames] 传入，保持库内 i18n 纪律）；合并强度 >1 时追加 ×N。
 *
 * 纯 Kotlin（零 Compose 依赖）—— 单测直接覆盖渲染前的标签决策。
 */
object EdgeLabels {

    /**
     * 解析边的具体关联标签。
     *
     * @param edge 目标边
     * @param kindNames 六类边的类型语义名（GRAPH_RELATION 仅作 relationType
     *   空白时的兜底；宿主应传全部六类）
     * @return null = 无法解析（不渲染标签）；否则为「语义名 ×N」或 relationType
     */
    fun labelOf(edge: MemoryEdge, kindNames: Map<MemoryEdgeKind, String>): String? {
        val base = edge.label?.takeIf { it.isNotBlank() } ?: kindNames[edge.kind] ?: return null
        return if (edge.strength > 1) "$base ×${edge.strength}" else base
    }
}

/**
 * 节点负载 —— 原始记忆数据的类型化包装。
 *
 * 详情面板按载荷分派；每类载荷都预解析好「点击跳转」所需的相邻节点 id
 * （宏的溯源情景 / 情景的派生宏 / 实体的关联实体），避免面板回查图索引。
 */
sealed class MemoryNodePayload {

    /** 情景记忆：观察节点的显示文本预解析（指纹 → 节点字典直查）。 */
    data class Episode(
        val record: EpisodeRecord,
        val observedNodeTexts: List<String>,
        val observedNodeIds: List<String>,
        val quarantinedCount: Int,
    ) : MemoryNodePayload()

    /** 程序性记忆（宏技能）。 */
    data class Macro(val macro: MacroSkill) : MemoryNodePayload()

    /** 语义记忆（聊天事实）。字段类型全限定 —— 同名包装类会遮蔽简单名解析。 */
    data class ChatFact(val fact: com.apex.memkit.core.model.ChatFact) : MemoryNodePayload()

    /** 知识图谱实体 + 与之相关的全部关系（入边 + 出边）。 */
    data class Entity(
        val entity: GraphEntity,
        val incoming: List<GraphRelation>,
        val outgoing: List<GraphRelation>,
    ) : MemoryNodePayload()

    /** UI 场景节点。 */
    data class SceneNode(val node: NodeDictEntry) : MemoryNodePayload()

    /** 应用簇：包内成员统计 + 样本（详情面板的「簇里有什么」）。 */
    data class AppCluster(
        val appPackage: String?,
        val nodeCount: Int,
        val episodeCount: Int,
        val macroCount: Int,
        val topNodes: List<NodeDictEntry>,
        val episodeGoals: List<String>,
    ) : MemoryNodePayload()
}

/**
 * 图谱构建输入 —— [MemoryGraphSource] 端口的快照契约（五轨记忆 + 别名）。
 *
 * 纯数据（全部来自 memory-core 公开模型）；构建器对其做确定性变换，
 * 同输入必然产出同图谱（布局 seeded 初始位置的复现前提）。
 */
data class GraphData(
    val episodes: List<EpisodeRecord> = emptyList(),
    val macros: List<MacroSkill> = emptyList(),
    val facts: List<ChatFact> = emptyList(),
    val entities: List<GraphEntity> = emptyList(),
    val relations: List<GraphRelation> = emptyList(),
    val sceneNodes: List<NodeDictEntry> = emptyList(),
    val aliases: List<FingerprintAlias> = emptyList(),
)

/**
 * 图谱统计（状态栏 chips / 空态判据）。
 */
data class GraphStats(
    val episodes: Int = 0,
    val macros: Int = 0,
    val facts: Int = 0,
    val entities: Int = 0,
    val sceneNodes: Int = 0,
    val appClusters: Int = 0,
    val edges: Int = 0,
) {
    val nodes: Int get() = episodes + macros + facts + entities + sceneNodes + appClusters
    val isEmpty: Boolean get() = nodes == 0
}

/**
 * 构建完成的记忆图谱 —— 不可变快照（过滤在内存视图层做，不重建索引）。
 *
 * @param nodesById / edgesById 稳定索引（选择 / 详情跳转的 O(1) 回查）
 * @param adjacency 邻接表（节点 id → （边 id, 对端 id）列表；聚焦高亮的依据）
 */
class MemoryGraph(
    val nodes: List<MemoryNode>,
    val edges: List<MemoryEdge>,
    val stats: GraphStats,
) {
    val nodesById: Map<String, MemoryNode> = nodes.associateBy { it.id }
    val edgesById: Map<Long, MemoryEdge> = edges.mapIndexed { i, e -> i.toLong() to e }.toMap()
    val adjacency: Map<String, List<Pair<Long, String>>> = buildAdjacency()

    private fun buildAdjacency(): Map<String, List<Pair<Long, String>>> {
        val adj = HashMap<String, MutableList<Pair<Long, String>>>()
        edges.forEachIndexed { i, e ->
            adj.getOrPut(e.sourceId) { mutableListOf() }.add(i.toLong() to e.targetId)
            adj.getOrPut(e.targetId) { mutableListOf() }.add(i.toLong() to e.sourceId)
        }
        return adj
    }

    /** 节点的邻接节点 id 集合（聚焦态的完全亮集）。 */
    fun neighborsOf(nodeId: String): Set<String> =
        adjacency[nodeId]?.mapTo(mutableSetOf()) { it.second } ?: emptySet()

    /**
     * 节点的关联边列表（详情面板「关联关系」区的数据源）。
     *
     * 返回顺序 = 构建顺序（确定性）；每条边带上「另一端节点 id」——
     * 面板渲染 `对端 —具体关联→ 本节点 / 本节点 —具体关联→ 对端` 的
     * 漫游行，具体关联标签经 [EdgeLabels.labelOf] 解析。
     */
    fun edgesOf(nodeId: String): List<Pair<MemoryEdge, String>> =
        adjacency[nodeId]?.mapNotNull { (edgeIndex, otherId) ->
            edgesById[edgeIndex]?.let { it to otherId }
        } ?: emptyList()

    /**
     * 子图过滤（类型开关）：保留启用类型的节点与两端皆存活的边。
     * 邻接索引重建一次（子图节点数 ≤ 全图，成本可接受）。
     */
    fun filtered(kinds: Set<MemoryNodeKind>): MemoryGraph {
        val kept = nodes.filter { it.kind in kinds }
        val keptIds = kept.mapTo(HashSet()) { it.id }
        val keptEdges = edges.filter { it.sourceId in keptIds && it.targetId in keptIds }
        return MemoryGraph(kept, keptEdges, stats.copy(
            episodes = kept.count { it.kind == MemoryNodeKind.EPISODE },
            macros = kept.count { it.kind == MemoryNodeKind.MACRO },
            facts = kept.count { it.kind == MemoryNodeKind.CHAT_FACT },
            entities = kept.count { it.kind == MemoryNodeKind.ENTITY },
            sceneNodes = kept.count { it.kind == MemoryNodeKind.SCENE_NODE },
            appClusters = kept.count { it.kind == MemoryNodeKind.APP_CLUSTER },
            edges = keptEdges.size,
        ))
    }
}
