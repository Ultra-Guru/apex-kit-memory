package com.apex.memkit.core.model

import kotlinx.serialization.Serializable

/**
 * 知识图谱实体 —— 对齐官方 `@modelcontextprotocol/server-memory` 语义
 * （本库的 graph 子系统直供宿主的 memory MCP 服务器）。
 */
@Serializable
data class GraphEntity(
    val name: String,
    val entityType: String,
    val observations: List<String> = emptyList(),
)

/** 知识图谱三元组（from/to 为实体名）。 */
@Serializable
data class GraphRelation(
    val from: String,
    val to: String,
    val relationType: String,
)

/** 图谱搜索命中子图（search_nodes 输出视图）。 */
data class GraphSearchResult(
    val entities: List<GraphEntity>,
    val relations: List<GraphRelation>,
)

/** 摄取防护裁决。 */
enum class GuardVerdict { PASS, NODE_QUARANTINE, FRAME_BLOCKED }

/**
 * 隔离条目 —— 可疑节点指纹（键与指纹体系统一，修复旧系统裸字符串拼接
 * 键的碰撞风险）+ 首见时间（TTL 过期自动放行重评估）。
 */
@Serializable
data class QuarantineEntry(
    val fingerprint: String,
    val firstSeenAt: Long,
    val reason: String,
)

/** 隔离名单持久化文档。 */
@Serializable
data class QuarantineDoc(
    val entries: List<QuarantineEntry> = emptyList(),
)
