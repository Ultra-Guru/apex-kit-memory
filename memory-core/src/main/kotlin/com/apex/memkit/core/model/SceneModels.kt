package com.apex.memkit.core.model

import kotlinx.serialization.Serializable

/**
 * 场景（Scene）—— 一次 UI 捕获经语义降维后的产物，是记忆的最小观测单位。
 *
 * 旧系统的 `MemoryGraph` 混合了「一帧快照」与「图查询逻辑」且携带两个
 * 零调用的图遍历方法；本模型回归纯数据：节点字典 + 空间边 + 场景指纹，
 * 查询逻辑一律住在 [com.apex.memkit.core.store.MemoryStore]。
 */
@Serializable
data class Scene(
    /** 场景指纹：全部节点指纹排序后的内容哈希（同一屏面稳定复现）。 */
    val fingerprint: String,
    val nodes: List<SceneNode>,
    /** 空间拓扑边（父子包含 + 兄弟邻接），由降维器确定性生成。 */
    val edges: List<SceneEdge>,
    val appPackage: String? = null,
    val appVersion: String? = null,
    val activityLabel: String? = null,
    val capturedAt: Long,
) {
    /** 全部节点指纹集合（匹配 / 差分 / 锚点判定的输入）。 */
    fun fingerprints(): Set<String> = nodes.mapTo(mutableSetOf()) { it.fingerprint }
}

/**
 * 语义节点 —— 降维后保留下来的可交互 / 有语义的 UI 元素。
 *
 * @param fingerprint 内容指纹（含父上下文）：`SHA-256(cls|res|text|role|parent)`
 *   全 hex —— 与旧系统公式一致以保迁移兼容
 * @param role 交互角色分类
 * @param text 文本提示（text / contentDescription 归一后的首个非空值）
 */
@Serializable
data class SceneNode(
    val fingerprint: String,
    val role: NodeRole,
    val text: String? = null,
    val resourceId: String? = null,
    val className: String? = null,
    val bounds: Bounds = Bounds.ZERO,
    val depth: Int = 0,
    val interactive: Boolean = false,
) {
    /** 节点显示名（搜索 / 召回工具的输出主体）。 */
    fun displayText(): String = text ?: resourceId ?: className?.substringAfterLast('.') ?: role.name
}

/**
 * 节点交互角色。旧枚举含从未被赋值的 `LIST_ITEM`（死枚举值）—— 已删；
 * 分类规则见 [com.apex.memkit.core.ingest.SceneReducer.classifyRole]。
 */
@Serializable
enum class NodeRole {
    BUTTON, INPUT, SCROLLABLE, TEXT, TOGGLE, IMAGE, NAVIGATION, DIALOG, UNKNOWN;

    companion object {
        /** 持久化兼容解析：未知名称折叠为 [UNKNOWN]（坏文档不击穿加载）。 */
        fun safe(name: String?): NodeRole =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: UNKNOWN
    }
}

/** 屏幕坐标（像素）。 */
@Serializable
data class Bounds(
    val x1: Int, val y1: Int, val x2: Int, val y2: Int,
) {
    val width: Int get() = x2 - x1
    val height: Int get() = y2 - y1
    val centerX: Int get() = (x1 + x2) / 2
    val centerY: Int get() = (y1 + y2) / 2
    fun area(): Long = width.toLong() * height.toLong()

    companion object {
        val ZERO = Bounds(0, 0, 0, 0)

        /**
         * 解析 `"[x1,y1][x2,y2]"` 文本形式（宿主无障碍节点原生 bounds 格式）。
         * 形状不合（空 / 负宽高）返回 null —— 摄取时直接丢弃该节点。
         */
        fun parseBounds(raw: String?): Bounds? {
            if (raw.isNullOrBlank()) return null
            val m = Regex("\\[(-?\\d+),(-?\\d+)\\]\\[(-?\\d+),(-?\\d+)\\]").find(raw) ?: return null
            val (x1, y1, x2, y2) = m.destructured
            val b = Bounds(x1.toInt(), y1.toInt(), x2.toInt(), y2.toInt())
            return if (b.width > 0 && b.height > 0) b else null
        }
    }
}

/** 空间拓扑边类型：父包含 / 兄弟邻接。旧 CAUSAL/SEMANTIC 从未生产，已删。 */
@Serializable
enum class EdgeKind { CONTAINS, ADJACENT }

/**
 * 空间边 —— 内容哈希稳定 ID（同一条边在任意两帧同 ID，差分语义才正确；
 * 旧系统 v2→v3 迁移事故的根因修复以库内建形式固化）。
 */
@Serializable
data class SceneEdge(
    val id: String,
    val sourceFp: String,
    val targetFp: String,
    val kind: EdgeKind,
)

/**
 * 场景差分 —— 两次捕获之间的拓扑变化（记忆的增量存储单位）。
 *
 * @param moved 平移节点（同指纹、位置变化超阈值）
 * @param transitionAction 差分期间执行的动作（若已知，用于因果归档）
 */
@Serializable
data class SceneDelta(
    val addedNodes: List<SceneNode>,
    val removedFingerprints: List<String>,
    val moved: List<MovedNode>,
    val addedEdges: List<SceneEdge>,
    val removedEdgeIds: List<String>,
    val fromFingerprint: String,
    val toFingerprint: String,
    val transitionAction: String? = null,
) {
    /** 是否为页面级跳转（变化率超 [ratio] 阈值时逐节点位移判定无意义）。 */
    fun isPageTransition(ratio: Float): Boolean {
        val total = addedNodes.size + removedFingerprints.size
        return total >= 3 && total.toFloat() / (total.toFloat() + moved.size) >= ratio
    }
}

@Serializable
data class MovedNode(
    val fingerprint: String,
    val from: Bounds,
    val to: Bounds,
)
